package com.bitsycore.codegen

import com.bitsycore.ast.*
import com.bitsycore.types.KtcType

/**
 * ── Type Inference ──────────────────────────────────────────────────────
 *
 * Recursively determines the Kotlin type string for any AST expression.
 * Used by both the scanning passes (to discover generic instantiations)
 * and the codegen passes (to pick the right C type, null check strategy, etc.).
 *
 * Type strings follow the internal convention described in AGENTS.md:
 *   - "Int", "String", "Vec2" for plain types
 *   - "Int?" for nullable values (uses Optional struct)
 *   - "Vec2*" for @Ptr-annotated pointer types
 *   - "IntArray" for Array<Int>, "Vec2Array" for Array<Vec2>
 *   - "Pair_Int_String", "Triple_A_B_C", "Tuple_A_B_..."
 *   - "Fun(P1,P2)->R" for function types
 *
 * ## Main entry points:
 *
 *   [inferExprType]          — infer type of any expression (main dispatcher)
 *   [inferCallType]           — infer return type of a function/constructor call
 *   [inferMethodReturnType]   — infer return type of obj.method()
 *   [inferDotType]            — infer type of obj.field
 *   [inferIndexType]          — infer type of obj[index]
 *
 *   (helpers) [inferBlockType], [inferIfExprType], [inferWhenExprType]
 *
 * ## State accessed (read-only):
 *   classes, interfaces, enums, objects, funSigs, extensionFuns, genericFunDecls,
 *   genericFunConcreteReturn, classInterfaces, classArrayTypes,
 *   pairTypeComponents, tripleTypeComponents, tupleTypeComponents,
 *   scopes (lookupVar), lambdaParamTypes, typeSubst, currentClass, currentExtRecvType,
 *   allGenericTypeParamNames
 *
 * ## State mutated:
 *   genericInstantiations (via recordGenericInstantiation on inferred generic class usage)
 *
 * ## Dependencies:
 *   Calls into [CCodeGenCTypes] (resolveTypeName, resolveIfaceName, resolveMethodReturnType, ...)
 *   Called from everywhere (scan passes, emitter, statements, expressions)
 */

// ═══════════════════════════ Type inference ═══════════════════════

internal fun CCodeGen.inferExprType(e: Expr?): String? = when (e) {
    null        -> null
    is IntLit   -> "Int"
    is LongLit  -> "Long"
    is UIntLit  -> "UInt"
    is ULongLit -> "ULong"
    is DoubleLit -> "Double"
    is FloatLit -> "Float"
    is BoolLit  -> "Boolean"
    is CharLit  -> "Char"
    is StrLit, is StrTemplateExpr -> "String"
    is NullLit  -> null
    is ThisExpr -> lambdaParamTypes["\$this"] ?: lookupVar("\$self") ?: currentExtRecvType ?: currentClass
    is NameExpr -> lambdaParamTypes[e.name] ?: lookupVar(e.name) ?: run {
        if (enums.containsKey(e.name)) e.name
        else if (objects.containsKey(e.name)) e.name
        else {
            // Parent object field inside nested class
            val parentObj = currentClass?.substringBefore('$')
            if (parentObj != null && currentObject == null) {
                val oi = objects[parentObj]
                if (oi?.props?.any { it.first == e.name } == true)
                    resolveTypeName(oi.props.find { it.first == e.name }!!.second).toInternalStr
                else null
            } else null
        }
    }
    is BinExpr  -> {
        if (e.op in setOf("==", "!=", "<", ">", "<=", ">=", "&&", "||", "in", "!in")) "Boolean"
        else if (e.op == "..") "IntRange"
        else if (e.op == "to") {
            val a = inferExprType(e.left) ?: "Int"
            val b = inferExprType(e.right) ?: "Int"
            // Register in pairTypeComponents so matchTypeParam can decompose
            // Pair types during early scanning (before codegen populates it)
            pairTypeComponents["Pair_${a}_${b}"] = Pair(a, b)
            "Pair_${a}_${b}"
        }
        else {
            /* User-defined infix operator: look up in inlineExtFunDecls, resolve return type with type substitution. */
            val vInfixDecl = inlineExtFunDecls[e.op] // infix function declaration, or null if not user-defined infix
            if (vInfixDecl != null && vInfixDecl.returnType != null) {
                val vRecvType = inferExprType(e.left)  // receiver type (e.g. "Int")
                val vArgType  = inferExprType(e.right) // argument type (e.g. "String")
                val vSavedSubst = typeSubst            // save outer substitution
                if (vInfixDecl.typeParams.isNotEmpty()) {
                    typeSubst = inferInlineFunSubst(vInfixDecl, vRecvType, listOf(vArgType))
                }
                val vResult = resolveTypeName(vInfixDecl.returnType).toInternalStr // concrete return type string
                typeSubst = vSavedSubst
                vResult
            } else {
                inferExprType(e.left)  // arithmetic inherits left type
            }
        }
    }
    is PrefixExpr -> if (e.op == "!") "Boolean" else inferExprType(e.expr)
    is PostfixExpr -> inferExprType(e.expr)
    is CallExpr -> inferCallType(e)
    is DotExpr  -> inferDotType(e)
    is SafeDotExpr -> inferDotTypeSafe(e)
    is IndexExpr -> inferIndexType(e)
    is IfExpr   -> inferIfExprType(e)
    is WhenExpr -> inferWhenExprType(e)
    is NotNullExpr -> inferExprType(e.expr)?.removeSuffix("?")
    is ElvisExpr -> (inferExprType(e.left) ?: inferExprType(e.right))?.removeSuffix("?")
    is IsCheckExpr -> "Boolean"
    is CastExpr -> if (e.safe) e.type.name + "?" else e.type.name
    is FunRefExpr -> {
        // Look up the function signature and build a Fun(...)->R type string
        val sig = funSigs[e.name]
        if (sig != null) {
            val params = sig.params.joinToString(",") { resolveTypeName(it.type).toInternalStr }
            val ret = if (sig.returnType != null) resolveTypeName(sig.returnType).toInternalStr else "Unit"
            "Fun($params)->$ret"
        } else null
    }
    is LambdaExpr -> null
}

internal fun CCodeGen.inferCallType(e: CallExpr): String? {
    // Nested class constructor: Outer.Inner(...) or A.B.C(...) → flat name
    if (e.callee is DotExpr) {
        fun flattenDotCallee(callee: Expr): String? {
            if (callee is NameExpr) return callee.name
            if (callee is DotExpr && callee.obj is NameExpr)
                return "${callee.obj.name}\$${callee.name}"
            if (callee is DotExpr) {
                val left = flattenDotCallee(callee.obj)
                if (left != null) return "$left\$${callee.name}"
            }
            return null
        }
        val flatCallee = flattenDotCallee(e.callee)
        if (flatCallee != null) {
            if (classes.containsKey(flatCallee)) return flatCallee
            if (genericClassDecls.containsKey(flatCallee)) {
                val resolvedArgs = e.typeArgs.map { substituteTypeParams(it) }.map { it.name }
                return mangledGenericName(flatCallee, resolvedArgs)
            }
        }
    }
    val name = (e.callee as? NameExpr)?.name
    if (name != null) {
        // StringBuffer constructor (intrinsic — only when no user-defined class named StringBuffer)
        if (name == "StringBuffer" && !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")) {
            return "ktc_StrBuf"
        }
        // Pair constructor (intrinsic — only when no user-defined class named Pair)
        if (name == "Pair" && !classes.containsKey("Pair") && !genericClassDecls.containsKey("Pair")) {
            val a = if (e.typeArgs.size == 2) resolveTypeName(e.typeArgs[0]).toInternalStr else inferExprType(e.args.getOrNull(0)?.expr) ?: "Int"
            val b = if (e.typeArgs.size == 2) resolveTypeName(e.typeArgs[1]).toInternalStr else inferExprType(e.args.getOrNull(1)?.expr) ?: "Int"
            return "Pair_${a}_${b}"
        }
        // Triple constructor (intrinsic)
        if (name == "Triple" && !classes.containsKey("Triple") && !genericClassDecls.containsKey("Triple")) {
            val a = if (e.typeArgs.size == 3) resolveTypeName(e.typeArgs[0]).toInternalStr else inferExprType(e.args.getOrNull(0)?.expr) ?: "Int"
            val b = if (e.typeArgs.size == 3) resolveTypeName(e.typeArgs[1]).toInternalStr else inferExprType(e.args.getOrNull(1)?.expr) ?: "Int"
            val c = if (e.typeArgs.size == 3) resolveTypeName(e.typeArgs[2]).toInternalStr else inferExprType(e.args.getOrNull(2)?.expr) ?: "Int"
            return "Triple_${a}_${b}_${c}"
        }
        // Generic class constructor: MyList<Int>(8) → "MyList_Int"
        // Apply typeSubst so type params resolve inside generic function bodies
        if (classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
            val resolvedArgs = e.typeArgs.map { substituteTypeParams(it) }.map { it.name }
            return mangledGenericName(name, resolvedArgs)
        }
        if (classes.containsKey(name) && classes[name]!!.isGeneric && e.args.isNotEmpty()) {
            // Infer type args from constructor arguments (e.g. Wrapper("hello") → Wrapper_String)
            val inferredArgs = e.args.map { inferExprType(it.expr) ?: "Int" }
            recordGenericInstantiation(name, inferredArgs)
            return mangledGenericName(name, inferredArgs)
        }
        if (classes.containsKey(name)) return name
        if (name == "HeapAlloc" || name == "HeapArrayZero" || name == "HeapArrayResize" || name == "heapArrayOf") {
            if (e.typeArgs.isNotEmpty()) {
                val ta = e.typeArgs[0]
                // HeapAlloc<Array<Int>>(n) → Int* (element type pointer)
                if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                    return "${elemName}*"
                }
                // HeapAlloc<RawArray<T>>(n) → T* (raw pointer, no $len)
                if (ta.name == "RawArray" && ta.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                    return "${elemName}*"
                }
                // HeapAlloc<MyList<Int>>(...) → MyList_Int* (generic class heap pointer)
                if (ta.typeArgs.isNotEmpty() && classes.containsKey(ta.name) && classes[ta.name]!!.isGeneric) {
                    return "${mangledGenericName(ta.name, ta.typeArgs.map { it.name })}*"
                }
                val resolvedName = typeSubst[ta.name] ?: ta.name
                return "${resolvedName}*"
            }
            if (heapAllocTargetType != null) {
                val tt = heapAllocTargetType!!
                if (tt.name == "Array" && tt.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[tt.typeArgs[0].name] ?: tt.typeArgs[0].name
                    return "${elemName}*"
                }
                if (tt.name == "RawArray" && tt.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[tt.typeArgs[0].name] ?: tt.typeArgs[0].name
                    return "${elemName}*"
                }
                if (tt.typeArgs.isNotEmpty() && classes.containsKey(tt.name) && classes[tt.name]!!.isGeneric) {
                    return "${mangledGenericName(tt.name, tt.typeArgs.map { it.name })}*"
                }
                val resolvedName = typeSubst[tt.name] ?: tt.name
                return "${resolvedName}*"
            }
            return "void*"
        }
        if (name == "byteArrayOf" || name == "ByteArray") return "ByteArray"
        if (name == "shortArrayOf" || name == "ShortArray") return "ShortArray"
        if (name == "intArrayOf" || name == "IntArray") return "IntArray"
        if (name == "longArrayOf" || name == "LongArray") return "LongArray"
        if (name == "floatArrayOf" || name == "FloatArray") return "FloatArray"
        if (name == "doubleArrayOf" || name == "DoubleArray") return "DoubleArray"
        if (name == "booleanArrayOf" || name == "BooleanArray") return "BooleanArray"
        if (name == "charArrayOf" || name == "CharArray") return "CharArray"
        if (name == "ubyteArrayOf" || name == "UByteArray") return "UByteArray"
        if (name == "ushortArrayOf" || name == "UShortArray") return "UShortArray"
        if (name == "uintArrayOf" || name == "UIntArray") return "UIntArray"
        if (name == "ulongArrayOf" || name == "ULongArray") return "ULongArray"
        if (name == "arrayOf") {
            // Prefer explicit type argument (arrayOf<T?> or arrayOf<T>)
            if (e.typeArgs.isNotEmpty()) {
                val vTypeArg = e.typeArgs[0]
                val vElemName = typeSubst[vTypeArg.name] ?: vTypeArg.name
                if (vTypeArg.nullable) {
                    return when (vElemName) {
                        "Byte" -> "ByteOptArray"; "Short" -> "ShortOptArray"
                        "Int" -> "IntOptArray";   "Long" -> "LongOptArray"
                        "Float" -> "FloatOptArray"; "Double" -> "DoubleOptArray"
                        "Boolean" -> "BooleanOptArray"; "Char" -> "CharOptArray"
                        "UByte" -> "UByteOptArray"; "UShort" -> "UShortOptArray"
                        "UInt" -> "UIntOptArray"; "ULong" -> "ULongOptArray"
                        "String" -> "StringOptArray"
                        else -> { classArrayTypes.add(vElemName); "${vElemName}OptArray" }
                    }
                }
                return when (vElemName) {
                    "Byte" -> "ByteArray"; "Short" -> "ShortArray"
                    "Int" -> "IntArray"; "Long" -> "LongArray"
                    "Float" -> "FloatArray"; "Double" -> "DoubleArray"
                    "Boolean" -> "BooleanArray"; "Char" -> "CharArray"
                    "UByte" -> "UByteArray"; "UShort" -> "UShortArray"
                    "UInt" -> "UIntArray"; "ULong" -> "ULongArray"
                    "String" -> "StringArray"
                    else -> { classArrayTypes.add(vElemName); "${vElemName}Array" }
                }
            }
            // Fall back to inference from first argument
            val elemType = if (e.args.isNotEmpty()) inferExprType(e.args[0].expr) ?: "Int" else "Int"
            return when (elemType) {
                "Byte" -> "ByteArray"; "Short" -> "ShortArray"
                "Int" -> "IntArray"; "Long" -> "LongArray"
                "Float" -> "FloatArray"; "Double" -> "DoubleArray"
                "Boolean" -> "BooleanArray"; "Char" -> "CharArray"
                "UByte" -> "UByteArray"; "UShort" -> "UShortArray"
                "UInt" -> "UIntArray"; "ULong" -> "ULongArray"
                "String" -> "StringArray"
                else -> { classArrayTypes.add(elemType); "${elemType}Array" }
            }
        }
        if (name == "arrayOfNulls") {
            if (e.typeArgs.isNotEmpty()) {
                val vTypeArg = e.typeArgs[0]
                val vElemName = typeSubst[vTypeArg.name] ?: vTypeArg.name
                return when (vElemName) {
                    "Byte" -> "ByteOptArray"; "Short" -> "ShortOptArray"
                    "Int" -> "IntOptArray";   "Long" -> "LongOptArray"
                    "Float" -> "FloatOptArray"; "Double" -> "DoubleOptArray"
                    "Boolean" -> "BooleanOptArray"; "Char" -> "CharOptArray"
                    "UByte" -> "UByteOptArray"; "UShort" -> "UShortOptArray"
                    "UInt" -> "UIntOptArray"; "ULong" -> "ULongOptArray"
                    "String" -> "StringOptArray"
                    else -> { classArrayTypes.add(vElemName); "${vElemName}OptArray" }
                }
            }
            return "IntOptArray"
        }
        if (name == "enumValues") {
            if (e.typeArgs.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                val resolved = typeSubst[enumName] ?: enumName
                return "${resolved}Array"
            }
            return "IntArray"
        }
        if (name == "enumValueOf") {
            if (e.typeArgs.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                return typeSubst[enumName] ?: enumName
            }
            return "Int"
        }
        // Generic Array<T>(size) constructor
        if (name == "Array" && e.typeArgs.isNotEmpty()) {
            val elemName = resolveTypeName(e.typeArgs[0]).toInternalStr
            return "${elemName}Array"
        }
        // Generic function call: newArray<Int>(5) → resolve return type with type substitution
        // Also handles implicit type args inferred from arguments
        val genFun = genericFunDecls.find { it.name == name }
        if (genFun != null && genFun.returnType != null) {
            val typeArgNames = if (e.typeArgs.isNotEmpty()) {
                e.typeArgs.map { resolveTypeName(it).toInternalStr }
            } else {
                // Infer type args from argument types
                val inferredSubst = mutableMapOf<String, String>()
                for ((i, param) in genFun.params.withIndex()) {
                    if (i >= e.args.size) break
                    val argType = inferExprType(e.args[i].expr) ?: continue
                    matchTypeParam(param.type, argType, genFun.typeParams.toSet(), inferredSubst)
                }
                if (inferredSubst.size == genFun.typeParams.size) genFun.typeParams.map { inferredSubst[it]!! } else null
            }
            if (typeArgNames != null) {
                // Check if this instantiation has a known concrete return type
                val mangledName = "${name}_${typeArgNames.joinToString("_")}"
                val concreteRet = genericFunConcreteReturn[mangledName]
                if (concreteRet != null) return concreteRet
                val subst = genFun.typeParams.zip(typeArgNames).toMap()
                val saved = typeSubst
                typeSubst = subst
                val result = resolveTypeName(genFun.returnType).toInternalStr
                typeSubst = saved
                return if (genFun.returnType.nullable && !result.endsWith("?")) "${result}?" else result
            }
        }
        funSigs[name]?.returnType?.let {
            val base = resolveTypeName(it).toInternalStr
            return if (it.nullable && !base.endsWith("?")) "${base}?" else base
        }
    }
    if (e.callee is DotExpr) return inferMethodReturnType(e.callee, e.args)
    if (e.callee is SafeDotExpr) {
        val retType = inferMethodReturnType(DotExpr(e.callee.obj, e.callee.name), e.args) ?: return null
        if (retType == "Unit") return retType
        return if (retType.endsWith("?")) retType else "${retType}?"
    }
    return null
}

/** Resolve a method return type, applying generic bindings if the class is a concrete generic instantiation. */
internal fun CCodeGen.resolveMethodReturnType(className: String, returnType: TypeRef?): String {
    if (returnType == null) return "Unit"
    val bindings = genericTypeBindings[className]
    val base = if (bindings != null) {
        val saved = typeSubst
        typeSubst = bindings
        val result = resolveTypeName(returnType).toInternalStr
        typeSubst = saved
        result
    } else {
        resolveTypeName(returnType).toInternalStr
    }
    return if (returnType.nullable && !base.endsWith("?")) "${base}?" else base
}

internal fun CCodeGen.inferMethodReturnType(dot: DotExpr, args: List<Arg>): String? {
    // C package: can't infer return type of C functions
    if (dot.obj is NameExpr && dot.obj.name == "c" && lookupVar(dot.obj.name) == null) return null
    // Companion object method return type
    val vDotObjName = (dot.obj as? NameExpr)?.name
    val vCompanionName = vDotObjName?.let { classCompanions[it] }
    if (vCompanionName != null) {
        val vMethod = objects[vCompanionName]?.methods?.find { it.name == dot.name }
        if (vMethod != null && vMethod.returnType != null) {
            val base = resolveTypeName(vMethod.returnType).toInternalStr
            return if (vMethod.returnType.nullable && !base.endsWith("?")) "${base}?" else base
        }
        return null
    }
    val recvType = inferExprType(dot.obj) ?: return null
    val method = dot.name
    if (method == "toString") return "String"
    if (method == "trimIndent") return "String"
    if (method == "trimMargin") return "String"
    if (method == "runeAt") return "Rune"
    if (method == "toInt") return "Int"
    if (method == "toLong") return "Long"
    if (method == "toFloat") return "Float"
    if (method == "toDouble") return "Double"
    if (method == "toIntOrNull") return "Int?"
    if (method == "toLongOrNull") return "Long?"
    if (method == "toFloatOrNull") return "Float?"
    if (method == "toDoubleOrNull") return "Double?"
    if (method == "hashCode") return "Int"
    if (method == "inv") return recvType  // bitwise NOT returns same type
    // Array methods (including pointer-to-array)
    val isArrayPtr = recvType.endsWith("Array") ||
        (recvType.removeSuffix("?").endsWith("*") && isArrayType(recvType))
    if (isArrayPtr) {
        return when (method) {
            "size" -> "Int"
            "get" -> {
                val base = recvType.removeSuffix("?")
                // IntArray*? → strip Array → Int; Int*? → strip * → Int
                if (base.endsWith("Array*")) base.removeSuffix("Array*") else base.removeSuffix("*")
            }
            "ptr", "toHeap" -> {
                // For XxxArray* types, return element-type pointer (e.g. IntArray* → Int*)
                val base = recvType.removeSuffix("?")
                if (base.endsWith("Array*")) {
                    val elem = base.removeSuffix("Array*")
                    val internal = if (elem.endsWith("Opt")) "${elem.removeSuffix("Opt")}?" else elem
                    "${internal}*"
                } else if (recvType.endsWith("Array")) {
                    val elem = recvType.removeSuffix("Array")
                    val internal = if (elem.endsWith("Opt")) "${elem.removeSuffix("Opt")}?" else elem
                    "${internal}*"
                } else recvType
            }
            "set" -> "Unit"
            else -> null
        }
    }
    // String methods
    if (recvType == "String") {
        return when (method) {
            "substring" -> "String"
            "startsWith", "endsWith", "contains", "isEmpty", "isNotEmpty" -> "Boolean"
            "indexOf" -> "Int"
            else -> null
        }
    }
    // @Ptr/@Heap/@Value pointer methods (type return inference)
    val pointerBase = pointerClassName(recvType)
    if (pointerBase != null) {
        val classMethod = classes[pointerBase]?.methods?.find { it.name == method }
        if (classMethod != null) {
            return resolveMethodReturnType(pointerBase, classMethod.returnType)
        }
        val extFun = extensionFuns[pointerBase]?.find { it.name == method }
        if (extFun != null) return if (extFun.returnType != null) resolveTypeName(extFun.returnType).toInternalStr else "Unit"
        return when (method) {
            "value" -> pointerBase
            "deref" -> pointerBase
            "set" -> "Unit"
            "copy" -> pointerBase
            "toHeap", "ptr" -> "${pointerBase}*"
            else -> null
        }
    }
    // Stack class methods
    val baseClass = recvType.removeSuffix("?")
    if (classes.containsKey(baseClass)) {
        if (method == "copy") return baseClass
        if (method == "toHeap" || method == "ptr") return "${baseClass}*"
    }
    // Interface method
    val iface = interfaces[recvType]
    if (iface != null) {
        val m = iface.methods.find { it.name == method }
        if (m != null && m.returnType != null) {
            return resolveMethodReturnType(recvType, m.returnType)
        }
    }
    // Object method
    if (objects.containsKey(baseClass)) {
        val m = objects[baseClass]!!.methods.find { it.name == method }
        if (m != null) return resolveMethodReturnType(baseClass, m.returnType)
    }
    // Class method
    val ci = classes[recvType]
    if (ci != null) {
        val m = ci.methods.find { it.name == method }
        if (m != null) return resolveMethodReturnType(recvType, m.returnType)
    }
    // Extension function on non-class type
    val extFun = extensionFuns[recvType]?.find { it.name == method }
    if (extFun != null) {
        /* Generic extension: infer concrete return type by substituting type params from receiver + args. */
        if (extFun.typeParams.isNotEmpty() && extFun.returnType != null) {
            val vArgTypes    = args.map { inferExprType(it.expr) } // concrete argument types
            val vSavedSubst  = typeSubst                           // save outer substitution
            typeSubst        = inferInlineFunSubst(extFun, recvType, vArgTypes)
            val vResult      = resolveTypeName(extFun.returnType).toInternalStr
            typeSubst        = vSavedSubst
            return vResult
        }
        return if (extFun.returnType != null) resolveTypeName(extFun.returnType).toInternalStr else "Unit"
    }
    // Enum static methods
    if (recvType in enums) {
        when (method) {
            "values" -> return "${recvType}Array"
            "valueOf" -> return recvType
        }
    }
    return null
}

internal fun CCodeGen.inferDotType(e: DotExpr): String? {
    // C package: can't infer type of C constants/macros
    if (e.obj is NameExpr && e.obj.name == "c" && lookupVar("c") == null) return null
    if (e.obj is NameExpr && enums.containsKey(e.obj.name)) return e.obj.name
    if (e.obj is NameExpr && objects.containsKey(e.obj.name)) {
        val prop = objects[e.obj.name]?.props?.find { it.first == e.name }
        return if (prop != null) resolveTypeName(prop.second).toInternalStr else null
    }
    // Companion object property: Foo.bar → look up in companion's ObjInfo
    if (e.obj is NameExpr && classCompanions.containsKey(e.obj.name)) {
        val vCompanionName = classCompanions[e.obj.name]!!
        val vProp = objects[vCompanionName]?.props?.find { it.first == e.name }
        return if (vProp != null) resolveTypeName(vProp.second).toInternalStr else null
    }
    val recvType = inferExprType(e.obj) ?: return null
    if (recvType.startsWith("Pair_")) {
        val components = pairTypeComponents[recvType]
        if (components != null) {
            return when (e.name) {
                "first" -> components.first
                "second" -> components.second
                else -> null
            }
        }
    }
    if (recvType.startsWith("Triple_")) {
        val components = tripleTypeComponents[recvType]
        if (components != null) {
            return when (e.name) {
                "first" -> components.first
                "second" -> components.second
                "third" -> components.third
                else -> null
            }
        }
    }
    // StringBuffer field types
    if (recvType == "ktc_StrBuf" || recvType == "StringBuffer") {
        return when (e.name) {
            "buffer" -> "CharArray*?"  // nullable pointer to char array
            "len" -> "Int"
            else -> null
        }
    }
    if (e.name == "size" && recvType.endsWith("Array")) return "Int"
    if (e.name == "size" && recvType.removeSuffix("?").endsWith("*") && isArrayType(recvType)) return "Int"
    if (e.name == "ptr") {
        if (recvType.endsWith("Array")) {
            val elem = recvType.removeSuffix("Array")
            val internal = if (elem.endsWith("Opt")) "${elem.removeSuffix("Opt")}?" else elem
            return "${internal}*"
        }
        if (recvType.removeSuffix("?").endsWith("*") && isArrayType(recvType)) return recvType
        return if (recvType.endsWith("*")) recvType else "${recvType}*"
    }
    if (e.name == "toHeap" && recvType.endsWith("Array")) {
        val elem = recvType.removeSuffix("Array")
        val internal = if (elem.endsWith("Opt")) "${elem.removeSuffix("Opt")}?" else elem
        return "${internal}*"
    }
    if (e.name == "length" && recvType == "String") return "Int"
    if (e.name == "runeLen" && recvType == "String") return "Int"
    // Enum value .name / .ordinal
    if (e.name == "name" && recvType in enums) return "String"
    if (e.name == "ordinal" && recvType in enums) return "Int"
    // Heap/Ptr/Value pointer field access → look up in base class
    val indirectBase = anyIndirectClassName(recvType)
    if (indirectBase != null) {
        val ci = classes[indirectBase] ?: return null
        val prop = ci.props.find { it.first == e.name }
        return if (prop != null) resolveTypeName(prop.second).toInternalStr else null
    }
    val ci = classes[recvType] ?: return null
    val prop = ci.props.find { it.first == e.name }
    return if (prop != null) resolveTypeName(prop.second).toInternalStr else null
}

internal fun CCodeGen.inferDotTypeSafe(e: SafeDotExpr): String? {
    val base = inferDotType(DotExpr(e.obj, e.name)) ?: return null
    return if (base.endsWith("?")) base else "${base}?"
}
internal fun CCodeGen.inferIndexType(e: IndexExpr): String? {
    val tRaw = inferExprType(e.obj) ?: return null
    // Strip nullability: indexing a nullable array is valid after a null guard
    val t = tRaw.removeSuffix("?")
    // String indexing: str[i] → Char
    if (t == "String") return "Char"
    // Class with operator get() method → return type of get
    if (classes.containsKey(t)) {
        val methodDecl = classes[t]?.methods?.find { it.name == "get" && it.isOperator }
        if (methodDecl?.returnType != null) {
            return resolveMethodReturnType(t, methodDecl.returnType)
        }
    }
    // Heap<T>/Ptr<T>/Value<T> wrapping a class with operator get()
    val indirectBase = anyIndirectClassName(t)
    if (indirectBase != null && classes.containsKey(indirectBase)) {
        val methodDecl = classes[indirectBase]?.methods?.find { it.name == "get" && it.isOperator }
        if (methodDecl?.returnType != null) {
            return resolveMethodReturnType(indirectBase, methodDecl.returnType)
        }
    }
    // Interface with operator get() in vtable
    if (interfaces.containsKey(t)) {
        val ifaceInfo = interfaces[t]
        val ifaceMethod = ifaceInfo?.methods?.find { it.name == "get" && it.isOperator }
            ?: collectAllIfaceMethods(ifaceInfo!!).find { it.name == "get" && it.isOperator }
        if (ifaceMethod?.returnType != null) {
            return resolveMethodReturnType(t, ifaceMethod.returnType)
        }
    }
    // Typed pointer: "Int*" → "Int"; "IntArray*" → "Int" (array element)
    if (t.endsWith("*")) {
        val base = t.dropLast(1)
        return if (isArrayType(base)) arrayElementKtType(base) else base
    }
    return arrayElementKtType(t)
}

// ══ Phase 4.4 — KtcType inference entry points ══════════════════════

/*
Infer the KtcType of an expression.
For NameExpr uses lookupVarKtc directly to avoid string round-trip.
All other branches delegate to inferExprType and convert via stringToKtc.
Callers should migrate to this function progressively (Phase 5 dispatch).
*/
internal fun CCodeGen.inferExprTypeKtc(inExpr: Expr?): KtcType?
	{
	if (inExpr == null) return null
	if (inExpr is NameExpr)
		{
		/* Use KtcType scope directly — avoids toInternalStr → stringToKtc round-trip. */
		val vKtc = lookupVarKtc(inExpr.name)
		if (vKtc != null) return vKtc
		/* Fall through to string-based for objects/enums/parent-object lookup. */
		}
	val vStr = inferExprType(inExpr) ?: return null  // string-based fallback
	return parseResolvedTypeName(vStr)
	}

// ── Phase 4.5 — KtcType dot / method-return inference ───────────────

/* Infer the KtcType of a dot expression (field access). */
internal fun CCodeGen.inferDotTypeKtc(inExpr: DotExpr): KtcType?
	{
	val vStr = inferDotType(inExpr) ?: return null  // delegate to string version
	return parseResolvedTypeName(vStr)
	}

/* Infer the KtcType of a method call return. */
internal fun CCodeGen.inferMethodReturnTypeKtc(inDot: DotExpr, inArgs: List<Arg>): KtcType?
	{
	val vStr = inferMethodReturnType(inDot, inArgs) ?: return null
	return parseResolvedTypeName(vStr)
	}

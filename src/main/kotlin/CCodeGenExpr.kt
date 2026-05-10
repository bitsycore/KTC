package com.bitsycore

/**
 * ── Expression Codegen ──────────────────────────────────────────────────
 *
 * Translates Kotlin expressions into C expressions. This is the largest
 * and most complex section of the transpiler. The central function is
 * [genExpr] which dispatches on the expression AST node type.
 *
 * ## Main entry points:
 *
 *   [genExpr]         — central expression dispatcher (see below for full list)
 *   [genName]         — resolve variable/field/this names with smart-cast unwrap
 *   [genBin]           — binary ops with special handling for String, Pair, null, in/!in
 *   [genCall]          — function/constructor call, built-ins (HeapAlloc, arrayOf...)
 *   [genMethodCall]    — method dispatch: built-in, class method, interface vtable, extension
 *   [genDot]           — field access, enum values, object properties, pointer deref
 *   [genSafeDot]       — nullable-safe field access (?.)
 *   [genNotNull]       — not-null assertion (!!)
 *   [genIfExpr]        — if-expression → C ternary or pre-stmt temp
 *   [genWhenExpr]      — when-expression → nested ternary or pre-stmt temp
 *   [genStrTemplate]   — string template → StrBuf-based concatenation
 *   [genToString]      — toString() dispatch (primitive, data class, default hash)
 *   [genSbAppend]      — StrBuf append for all types
 *   [genArrayOfExpr]   — array literal (arrayOf/byteArrayOf/etc.)
 *   [genNewArray]      — Array<T>(size) constructor → alloca
 *   [genLValue]         — l-value for assignment target
 *
 * ## genExpr dispatch table:
 *   IntLit, LongLit, DoubleLit, FloatLit, BoolLit, CharLit, StrLit, NullLit,
 *   ThisExpr, NameExpr, BinExpr, PrefixExpr, PostfixExpr, CallExpr, DotExpr,
 *   SafeDotExpr, IndexExpr, IfExpr, WhenExpr, NotNullExpr, ElvisExpr,
 *   StrTemplateExpr, IsCheckExpr, CastExpr, FunRefExpr, LambdaExpr
 *
 * ## State accessed:
 *   classes, interfaces, enums, objects, funSigs, extensionFuns, classInterfaces,
 *   scopes (lookupVar), lambdaParamSubst, currentClass, currentObject, selfIsPointer,
 *   currentStmtLine, currentInd, typeSubst, inlineFunDecls, inlineExtFunDecls,
 *   activeLambdas, pairTypes, pairTypeComponents, tripleTypeComponents,
 *   classArrayTypes, classCompanions, preStmts, hdr, impl
 *
 * ## Dependencies:
 *   Calls into [CCodeGenStmts] (emitStmt, emitInlineCall, emitBlock)
 *   Calls into [CCodeGenInfer] (inferExprType, inferMethodReturnType, ...)
 *   Calls into [CCodeGenCTypes] (cType, cTypeStr, resolveTypeName, optNone, optSome, ...)
 */

// ═══════════════════════════ Expression codegen ═══════════════════

/** Generate an expression for use as a C function argument.
 *  String literals are emitted as raw C strings (not ktc_str wrapped). */
internal fun CCodeGen.genCArg(e: Expr): String = when (e) {
    is StrLit -> "\"${escapeStr(e.value)}\""
    else -> genExpr(e)
}

fun CCodeGen.genExpr(e: Expr): String = when (e) {
    is IntLit    -> "${e.value}"
    is LongLit   -> "${e.value}LL"
    is DoubleLit -> "${e.value}"
    is FloatLit  -> "${e.value}f"
    is BoolLit   -> if (e.value) "true" else "false"
    is CharLit   -> "'${escapeC(e.value)}'"
    is StrLit    -> "ktc_str(\"${escapeStr(e.value)}\")"
    is NullLit   -> "NULL"
    is ThisExpr  -> {
        val inlineThis = lambdaParamSubst["\$this"]
        if (inlineThis != null) return inlineThis
        val selfType = lookupVar("\$self")
        if (selfType != null && isOptional("\$self") && !selfType.endsWith("?")) {
            "\$self.value"
        } else if (selfIsPointer) "(*\$self)" else "\$self"
    }
    is NameExpr  -> genName(e)
    is BinExpr   -> genBin(e)
    is PrefixExpr  -> "(${e.op}${genExpr(e.expr)})"
    is PostfixExpr -> "(${genExpr(e.expr)}${e.op})"
    is CallExpr    -> genCall(e)
    is DotExpr     -> genDot(e)
    is SafeDotExpr -> genSafeDot(e)
    is IndexExpr   -> {
        val objType = inferExprType(e.obj)
        if (objType == "String") {
            // String indexing: str[i] → str.ptr[i] (returns char)
            "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
        } else if (objType != null && classes.containsKey(objType)) {
            // Class with operator get() method → operator[] dispatch
            val methodDecl = classes[objType]?.methods?.find { it.name == "get" && it.isOperator }
            if (methodDecl != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                if (methodDecl.returnType?.nullable == true) {
                    genNullableMethodCall(objType, "${pfx(objType)}_get", "&$recv, $idx", methodDecl)
                } else {
                    "${pfx(objType)}_get(&$recv, $idx)"
                }
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        } else if (objType != null && anyIndirectClassName(objType)?.let { classes.containsKey(it) } == true) {
            // Heap<T>/Ptr<T>/Value<T> with operator get() → pointer-based dispatch
            val baseClass = anyIndirectClassName(objType)!!
            val methodDecl = classes[baseClass]?.methods?.find { it.name == "get" && it.isOperator }
            if (methodDecl != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                if (methodDecl.returnType?.nullable == true) {
                    genNullableMethodCall(baseClass, "${pfx(baseClass)}_get", "$recv, $idx", methodDecl)
                } else {
                    "${pfx(baseClass)}_get($recv, $idx)"
                }
            } else {
                "${genExpr(e.obj)}[${genExpr(e.index)}]"
            }
        } else if (objType != null && interfaces.containsKey(objType)) {
            // Interface with operator get() in vtable → operator[] dispatch
            val ifaceInfo = interfaces[objType]
            val ifaceMethod = ifaceInfo?.methods?.find { it.name == "get" && it.isOperator }
                ?: collectAllIfaceMethods(ifaceInfo!!).find { it.name == "get" && it.isOperator }
            if (ifaceMethod != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                if (ifaceMethod.returnType?.nullable == true) {
                    val retBase = resolveMethodReturnType(objType, ifaceMethod.returnType).removeSuffix("?")
                    val optType = optCTypeName("${retBase}?")
                    val t = tmp()
                    preStmts += "$optType $t = $recv.vt->get((void*)&$recv, $idx);"
                    markOptional(t)
                    defineVar(t, "${retBase}?")
                    t
                } else {
                    "$recv.vt->get((void*)&$recv, $idx)"
                }
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        } else if (objType != null && (objType.endsWith("*") || isArrayType(objType))) {
            // Typed pointer or array: direct indexing
            "${genExpr(e.obj)}[${genExpr(e.index)}]"
        } else {
            "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
        }
    }
    is IfExpr      -> genIfExpr(e)
    is WhenExpr    -> genWhenExpr(e)
    is NotNullExpr -> genNotNull(e)
    is ElvisExpr   -> {
        val lt = inferExprType(e.left)
        val l = genExpr(e.left)
        val rt = inferExprType(e.right)
        // If right side returns Nothing (e.g., error("msg")), emit non-null assertion
        if (rt != null && (rt == "Nothing" || rt.removeSuffix("?") == "Nothing")) {
            val baseType = lt?.removeSuffix("?") ?: "void*"
            val ct = cTypeStr(baseType)
            val t = tmp()
            preStmts += "$ct $t = $l;"
            val r = genExpr(e.right)
            preStmts += "if (!$t) { $r; }"
            return t
        }
        val r = genExpr(e.right)
        if (lt != null && isValueNullableType(lt)) {
            "($l.tag == ktc_SOME ? $l.value : $r)"
        } else if (lt != null && lt.endsWith("*?")) {
            "($l != NULL ? $l : $r)"
        } else {
            "($l != NULL ? $l : $r)"
        }
    }
    is StrTemplateExpr -> genStrTemplate(e)
    is IsCheckExpr -> {
        val target = resolveTypeName(e.type)
        val inner = genExpr(e.expr)
        val exprType = inferExprType(e.expr)
        val memOp = if (exprType != null && (exprType.endsWith("*") || exprType.endsWith("*?"))) "->" else "."
        val check = if (classes.containsKey(target)) {
            "${inner}${memOp}__type_id == ${pfx(target)}_TYPE_ID"
        } else if (interfaces.containsKey(target)) {
            val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
            if (impls.isEmpty()) "false"
            else impls.joinToString(" || ") { "${inner}${memOp}__type_id == ${pfx(it)}_TYPE_ID" }
        } else if (isArrayType(target)) {
            val exprBase = exprType?.removeSuffix("?")
            if (exprBase != null && isArrayType(exprBase)) {
                if (exprBase == target) "true" else "false"
            } else {
                val arrayId = getTypeId(target)
                "(${inner}${memOp}__array_type_id == $arrayId)"
            }
        } else if (isBuiltinType(target)) {
            val exprBase = exprType?.removeSuffix("?")
            if (exprBase != null && exprBase != "Any" && !exprBase.endsWith("*")) {
                if (exprBase == target) "true" else "false"
            } else {
                val typeId = getTypeId(target)
                "(${inner}${memOp}__type_id == $typeId)"
            }
        } else {
            "/* is-check: unknown type '${target}' */ true"
        }
        if (e.negated) "!($check)" else "($check)"
    }
    is CastExpr    -> {
        val target = resolveTypeName(e.type)
        val inner = genExpr(e.expr)
        val srcType = inferExprType(e.expr)?.removeSuffix("?")
        val isPtr = srcType?.endsWith("*") == true
        if (e.safe) {
            val optCType = optCTypeName("$target?")
            val memOp = if (isPtr) "->" else "."
            val check = if (classes.containsKey(target)) {
                "${inner}${memOp}__type_id == ${pfx(target)}_TYPE_ID"
            } else if (interfaces.containsKey(target)) {
                val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
                if (impls.isEmpty()) "false"
                else impls.joinToString(" || ") { "${inner}${memOp}__type_id == ${pfx(it)}_TYPE_ID" }
            } else if (isBuiltinType(target)) {
                val typeId = getTypeId(target)
                "${inner}${memOp}__type_id == $typeId"
            } else {
                "true"
            }
            val castVal = if (interfaces.containsKey(target)) {
                "${pfx(target)}_as_$target(&($inner))"
            } else if (srcType == "Any" || srcType == "Any*") {
                "(*(${cTypeStr(target)}*)(${inner}${memOp}data))"
            } else {
                "(${cTypeStr(target)})($inner)"
            }
            "($check) ? ${optSome(optCType, castVal)} : ${optNone(optCType)}"
        } else if (interfaces.containsKey(target)) {
            "${pfx(target)}_as_$target(&($inner))"
        } else if (srcType == "Any" || srcType == "Any*") {
            val memOp = if (isPtr) "->" else "."
            "(*(${cTypeStr(target)}*)(${inner}${memOp}data))"
        } else {
            "(${cType(e.type)})($inner)"
        }
    }
    is FunRefExpr  -> pfx(e.name)    // ::functionName → C function pointer
    is LambdaExpr  -> error("Lambda can only be passed to an inline function, not used as a standalone expression")
}

// ── names (may resolve to enum, object field, self->field) ───────

internal fun CCodeGen.genName(e: NameExpr): String {
    val subst = lambdaParamSubst[e.name]
    if (subst != null) return subst
    val curType = lookupVar(e.name)
    // Check if it's a known variable in scope
    if (curType != null) {
        if (currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
            val ci = classes[currentClass]!!
            val fieldName = if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
            val fieldRef = if (selfIsPointer) "\$self->${fieldName}" else "\$self.${fieldName}"
            // If field is stored as Optional but accessed after smart-cast (non-nullable context), unwrap
            val fieldType = ci.props.find { it.first == e.name }?.second
            if (fieldType?.nullable == true && !curType.endsWith("?")) {
                return "$fieldRef.value"
            }
            return fieldRef
        }
        val vCurObj = currentObject
        if (vCurObj != null && objects[vCurObj]?.props?.any { it.first == e.name } == true) {
            return "${pfx(vCurObj)}.${e.name}"
        }
        // Trampolined array param: redirect to local stack copy
        if (e.name in trampolinedParams) return "local\$${e.name}"
        // Any trampoline smart-cast: narrowed from Any, dereference .data
        if (curType != "Any" && isAnySmartCastVar(e.name)) {
            val ct = cTypeStr(curType)
            return "(*(($ct*)(${e.name}.data)))"
        }
        // Optional var smart-casted to non-nullable: unwrap to .value
        if (isOptional(e.name) && !curType.endsWith("?")) {
            return "${e.name}.value"
        }
        return e.name
    }
    // Top-level property: apply package prefix
    if (e.name in topProps) return pfx(e.name)
    return e.name
}

// ── binary ───────────────────────────────────────────────────────

internal fun CCodeGen.genBin(e: BinExpr): String {
    val lt = inferExprType(e.left)
    // `to` infix → Pair compound literal
    if (e.op == "to") {
        val aType = inferExprType(e.left) ?: "Int"
        val bType = inferExprType(e.right) ?: "Int"
        pairTypes.add(Pair(aType, bType))
        pairTypeComponents["Pair_${aType}_${bType}"] = Pair(aType, bType)
        ensurePairType(aType, bType)
        val pairCType = "ktc_Pair_${aType}_${bType}"
        return "($pairCType){${genExpr(e.left)}, ${genExpr(e.right)}}"
    }
    // null comparison
    if ((e.op == "==" || e.op == "!=") && (e.left is NullLit || e.right is NullLit)) {
        val nonNull = if (e.left is NullLit) e.right else e.left
        // this == null / this != null inside nullable-receiver extension
        if (nonNull is ThisExpr) {
            val thisType = inferExprType(nonNull)
            if (thisType != null && thisType.endsWith("?")) {
                if (isValueNullableType(thisType)) {
                    return if (e.op == "==") "\$self.tag == ktc_NONE" else "\$self.tag == ktc_SOME"
                }
                return if (e.op == "==") "!\$self\$has" else "\$self\$has"
            }
        }
        val varName = (nonNull as? NameExpr)?.name
        val varType = if (varName != null) lookupVar(varName) else null
        if (varType != null) {
            // @Ptr T? → compare pointer to NULL
            if (varType.endsWith("*?")) {
                return if (e.op == "==") "$varName == NULL" else "$varName != NULL"
            }
            // Any? nullable → compare data pointer to NULL
            if (varType == "Any?") {
                return if (e.op == "==") "$varName.data == NULL" else "$varName.data != NULL"
            }
            // Value nullable → use Optional tag
            if (varType.endsWith("?") && isValueNullableType(varType)) {
                return if (e.op == "==") "$varName.tag == ktc_NONE" else "$varName.tag == ktc_SOME"
            }
            // Trampolined array param: null is data == NULL — use local copy for consistency
            if (varName in trampolinedParams) {
                return if (e.op == "==") "local\$$varName == NULL" else "local\$$varName != NULL"
            }
            // Fallback for other nullable
            if (varType.endsWith("?")) {
                return if (e.op == "==") "$varName == NULL" else "$varName != NULL"
            }
        }
    }
    // data class == → ClassName_equals
    if (e.op == "==" && lt != null && classes[lt]?.isData == true) {
        return "${pfx(lt)}_equals(${genExpr(e.left)}, ${genExpr(e.right)})"
    }
    // String == → ktc_string_eq
    if (e.op == "==" && lt == "String") {
        return "ktc_string_eq(${genExpr(e.left)}, ${genExpr(e.right)})"
    }
    if (e.op == "!=" && lt == "String") {
        return "!ktc_string_eq(${genExpr(e.left)}, ${genExpr(e.right)})"
    }
    // String <, >, <=, >= → ktc_string_cmp
    if (lt == "String" && e.op in listOf("<", ">", "<=", ">=")) {
        return "(ktc_string_cmp(${genExpr(e.left)}, ${genExpr(e.right)}) ${e.op} 0)"
    }
    // String + → ktc_string_cat
    if (e.op == "+" && (lt == "String" || inferExprType(e.right) == "String")) {
        return genStringConcat(e)
    }
    // in / !in → operator contains() dispatch on class or interface
    if (e.op == "in" || e.op == "!in") {
        val rt = inferExprType(e.right)
        val negated = e.op == "!in"
        if (rt != null && classes.containsKey(rt)) {
            val containsMethod = classes[rt]?.methods?.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "${pfx(rt)}_${containsMethod.name}(&$recv, $elem)"
                return if (negated) "!$call" else call
            }
        }
        if (rt != null && anyIndirectClassName(rt)?.let { classes.containsKey(it) } == true) {
            val baseClass = anyIndirectClassName(rt)!!
            val containsMethod = classes[baseClass]?.methods?.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "${pfx(baseClass)}_${containsMethod.name}($recv, $elem)"
                return if (negated) "!$call" else call
            }
        }
        if (rt != null && interfaces.containsKey(rt)) {
            val ifaceInfo = interfaces[rt]
            val allMethods = collectAllIfaceMethods(ifaceInfo!!)
            val containsMethod = allMethods.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "$recv.vt->${containsMethod.name}((void*)&$recv, $elem)"
                return if (negated) "!$call" else call
            }
        }
        // Fallback: range-based `in` for IntRange, etc.
        if (rt != null && (rt == "IntRange" || rt.endsWith("Range"))) {
            val lo = genExpr((e.right as? BinExpr)?.left ?: e.right)
            val hi = genExpr((e.right as? BinExpr)?.right ?: e.right)
            val v = genExpr(e.left)
            return if (negated) "($v < $lo || $v > $hi)" else "($v >= $lo && $v <= $hi)"
        }
    }
    return "(${genExpr(e.left)} ${e.op} ${genExpr(e.right)})"
}

internal fun CCodeGen.genStringConcat(e: BinExpr): String {
    val buf = tmp()
    preStmts += "char ${buf}[512];"
    return "ktc_string_cat($buf, sizeof($buf), ${genExpr(e.left)}, ${genExpr(e.right)})"
}

// ── function / constructor call ──────────────────────────────────

internal fun CCodeGen.genCall(e: CallExpr): String {
    // Method call: DotExpr(receiver, method)(args)
    if (e.callee is DotExpr) {
        // Inline extension function call in value position
        val inlineExt = inlineExtFunDecls[e.callee.name]
        if (inlineExt != null) {
            val recvExpr = genExpr(e.callee.obj)
            val recvKtType = inferExprType(e.callee.obj)?.removeSuffix("?")
            val retType = inlineExt.returnType
            if (retType == null) {
                emitInlineCall(inlineExt, e.args, currentInd, false, receiverExpr = recvExpr, receiverType = recvKtType)
                return ""
            }
            val resultName = "\$ir${inlineCounter++}"
            impl.appendLine("$currentInd${cType(retType)} $resultName;")
            emitInlineCall(inlineExt, e.args, currentInd, false, receiverExpr = recvExpr, receiverType = recvKtType, resultVar = resultName)
            return resultName
        }
        // C package passthrough: c.printf(...) → printf(...)
        // String literals are emitted as raw C strings (not ktc_str wrapped)
        if (e.callee.obj is NameExpr && (e.callee.obj as NameExpr).name == "c" && lookupVar((e.callee.obj as NameExpr).name) == null) {
            val cFnName = e.callee.name
            val argStr = e.args.joinToString(", ") { genCArg(it.expr) }
            return "$cFnName($argStr)"
        }
        // Reject non-safe call on nullable receiver (unless the extension accepts nullable receiver,
        // or the nullable is a Ptr/Heap/Value<Array<T>> where deref() etc. are valid on nullable)
        val recvType = inferExprType(e.callee.obj)
        if (recvType != null && recvType.endsWith("?")) {
            val baseType = recvType.removeSuffix("?")
            val isIndirectArray = baseType.endsWith("*") && isArrayType(baseType)
            if (!hasNullableReceiverExt(baseType, e.callee.name) && !isIndirectArray && !isArrayType(baseType)) {
                val recvSrc = (e.callee.obj as? NameExpr)?.name ?: e.callee.obj.toString()
                codegenError("Only safe (?.) calls are allowed on a nullable receiver of type '$recvType': $recvSrc.${e.callee.name}()")
            }
        }
        return genMethodCall(e.callee, e.args)
    }
    if (e.callee is SafeDotExpr) return genSafeMethodCall(e.callee, e.args)

    val name = (e.callee as? NameExpr)?.name ?: return "${genExpr(e.callee)}(${e.args.joinToString(", ") { genExpr(it.expr) }})"
    val args = e.args

    // Inline function call in value position — emit body as C block, capture return via result var
    val inlineDecl = inlineFunDecls[name]
    if (inlineDecl != null) {
        val retType = inlineDecl.returnType
        if (retType == null) {
            emitInlineCall(inlineDecl, e.args, currentInd, false)
            return ""
        }
        val resultName = "\$ir${inlineCounter++}"
        impl.appendLine("$currentInd${cType(retType)} $resultName;")
        emitInlineCall(inlineDecl, e.args, currentInd, false, resultVar = resultName)
        return resultName
    }

    // Active lambda call in value position (inside inline body expansion)
    val activeLambda = activeLambdas[name]
    if (activeLambda != null) {
        val savedSubst = lambdaParamSubst.toMap()
        val savedTypes = lambdaParamTypes.toMap()
        activeLambda.expr.params.forEachIndexed { i, pName ->
            val arg = e.args.getOrNull(i)
            if (arg != null) {
                lambdaParamSubst[pName] = genExpr(arg.expr)
                val t = (if (arg.expr is ThisExpr) lambdaParamTypes["\$this"] else null)
                    ?: inferExprType(arg.expr)
                    ?: activeLambda.paramTypes.getOrElse(i) { "" }
                if (t.isNotEmpty()) lambdaParamTypes[pName] = t
            }
        }
        val body = activeLambda.expr.body
        val allButLast = if (body.size > 1) body.dropLast(1) else emptyList()
        for (stmt in allButLast) emitStmt(stmt, currentInd)
        val result = when (val last = body.lastOrNull()) {
            is ExprStmt -> genExpr(last.expr)
            is ReturnStmt -> if (last.value != null) genExpr(last.value) else ""
            null -> ""
            else -> { emitStmt(last, currentInd); "" }
        }
        lambdaParamSubst.clear(); lambdaParamSubst.putAll(savedSubst)
        lambdaParamTypes.clear(); lambdaParamTypes.putAll(savedTypes)
        return result
    }

    // Built-in functions
    when (name) {
        "println" -> return genPrintln(args)
        "print"   -> return genPrint(args)
        "HeapAlloc"  -> {
            if (e.typeArgs.isNotEmpty()) {
                val ta = e.typeArgs[0]
                // HeapAlloc<RawArray<T>>(n) → raw typed pointer allocation (no $len)
                if (ta.name == "RawArray" && ta.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                    val elemC = cTypeStr(elemName)
                    val sizeExpr = genExpr(args[0].expr)
                    val t = tmp()
                    preStmts += "$elemC* $t = ($elemC*)${tMalloc("sizeof($elemC) * (size_t)($sizeExpr)")};"
                    return t
                }
                // HeapAlloc<Array<T>>(n) → typed array allocation: (elemC*)malloc(sizeof(elemC) * (size_t)(n))
                if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                    val elemC = cTypeStr(elemName)
                    val sizeExpr = genExpr(args[0].expr)
                    val t = tmp()
                    preStmts += "$elemC* $t = ($elemC*)${tMalloc("sizeof($elemC) * (size_t)($sizeExpr)")};"
                    preStmts += "const int32_t ${t}\$len = $sizeExpr;"
                    return t
                }
                var typeName = typeSubst[ta.name] ?: ta.name
                // Resolve generic class: HeapAlloc<MyList<Int>>(...) → MyList_Int_new(...)
                if (ta.typeArgs.isNotEmpty() && classes.containsKey(typeName) && classes[typeName]!!.isGeneric) {
                    typeName = mangledGenericName(typeName, ta.typeArgs.map { it.name })
                }
                // Class heap constructor: HeapAlloc<MyClass>(args) → inline malloc + primaryConstructor
                if (classes.containsKey(typeName)) {
                    val cName = pfx(typeName)
                    val argStr = args.joinToString(", ") { genExpr(it.expr) }
                    val t = tmp()
                    preStmts += "$cName* $t = ($cName*)${tMalloc("sizeof($cName)")};"
                    preStmts += "if ($t) *$t = ${cName}_primaryConstructor($argStr);"
                    return t
                }
                // HeapAlloc<T>() with no args → single element: (T*)malloc(sizeof(T))
                val elemC = cTypeStr(typeName)
                if (args.isEmpty()) {
                    return "($elemC*)${tMalloc("sizeof($elemC)")}"
                }
                // HeapAlloc<T>(n) → array allocation: (T*)malloc(sizeof(T) * (size_t)(n))
                return "($elemC*)${tMalloc("sizeof($elemC) * (size_t)(${genExpr(args[0].expr)})")}"
            }
            if (heapAllocTargetType != null) {
                val tt = heapAllocTargetType!!
                if (tt.name == "RawArray" && tt.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[tt.typeArgs[0].name] ?: tt.typeArgs[0].name
                    val elemC = cTypeStr(elemName)
                    val sizeExpr = genExpr(args[0].expr)
                    val t = tmp()
                    preStmts += "$elemC* $t = ($elemC*)${tMalloc("sizeof($elemC) * (size_t)($sizeExpr)")};"
                    return t
                }
                if (tt.name == "Array" && tt.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[tt.typeArgs[0].name] ?: tt.typeArgs[0].name
                    val elemC = cTypeStr(elemName)
                    val sizeExpr = genExpr(args[0].expr)
                    val t = tmp()
                    preStmts += "$elemC* $t = ($elemC*)${tMalloc("sizeof($elemC) * (size_t)($sizeExpr)")};"
                    preStmts += "const int32_t ${t}\$len = $sizeExpr;"
                    return t
                }
                var typeName = typeSubst[tt.name] ?: tt.name
                if (tt.typeArgs.isNotEmpty() && classes.containsKey(typeName) && classes[typeName]!!.isGeneric) {
                    typeName = mangledGenericName(typeName, tt.typeArgs.map { it.name })
                }
                if (classes.containsKey(typeName)) {
                    val cName = pfx(typeName)
                    val argStr = args.joinToString(", ") { genExpr(it.expr) }
                    val t = tmp()
                    preStmts += "$cName* $t = ($cName*)${tMalloc("sizeof($cName)")};"
                    preStmts += "if ($t) *$t = ${cName}_primaryConstructor($argStr);"
                    return t
                }
                val elemC = cTypeStr(typeName)
                if (args.isEmpty()) {
                    return "($elemC*)${tMalloc("sizeof($elemC)")}"
                }
                return "($elemC*)${tMalloc("sizeof($elemC) * (size_t)(${genExpr(args[0].expr)})")}"
            }
            return tMalloc("(size_t)(${genExpr(args[0].expr)})")
        }
        "HeapArrayZero"  -> {
            if (e.typeArgs.isNotEmpty()) {
                val ta = e.typeArgs[0]
                val isArray = ta.name == "Array" && ta.typeArgs.isNotEmpty()
                val isRawArray = ta.name == "RawArray" && ta.typeArgs.isNotEmpty()
                val elemName = if (isArray || isRawArray) {
                    typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                } else {
                    typeSubst[ta.name] ?: ta.name
                }
                val elemC = cTypeStr(elemName)
                val sizeExpr = genExpr(args[0].expr)
                val t = tmp()
                preStmts += "$elemC* $t = ($elemC*)${tCalloc("(size_t)($sizeExpr)", "sizeof($elemC)")};"
                if (isArray) {
                    preStmts += "const int32_t ${t}\$len = $sizeExpr;"
                }
                return t
            }
            if (heapAllocTargetType != null) {
                val tt = heapAllocTargetType!!
                val isArray = tt.name == "Array" && tt.typeArgs.isNotEmpty()
                val isRawArray = tt.name == "RawArray" && tt.typeArgs.isNotEmpty()
                val elemName = if (isArray || isRawArray) {
                    typeSubst[tt.typeArgs[0].name] ?: tt.typeArgs[0].name
                } else {
                    typeSubst[tt.name] ?: tt.name
                }
                val elemC = cTypeStr(elemName)
                val sizeExpr = genExpr(args[0].expr)
                val t = tmp()
                preStmts += "$elemC* $t = ($elemC*)${tCalloc("(size_t)($sizeExpr)", "sizeof($elemC)")};"
                if (isArray) {
                    preStmts += "const int32_t ${t}\$len = $sizeExpr;"
                }
                return t
            }
            return tCalloc("(size_t)(${genExpr(args[0].expr)})", "(size_t)(${genExpr(args[1].expr)})")
        }
        "HeapArrayResize" -> {
            if (e.typeArgs.isNotEmpty()) {
                val ta = e.typeArgs[0]
                val isArray = ta.name == "Array" && ta.typeArgs.isNotEmpty()
                val elemName = if (isArray) {
                    typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                } else {
                    typeSubst[ta.name] ?: ta.name
                }
                val elemC = cTypeStr(elemName)
                val ptrExpr = genExpr(args[0].expr)
                val sizeExpr = genExpr(args[1].expr)
                val t = tmp()
                preStmts += "$elemC* $t = ($elemC*)${tRealloc(ptrExpr, "sizeof($elemC) * (size_t)($sizeExpr)")};"
                if (isArray) {
                    preStmts += "const int32_t ${t}\$len = $sizeExpr;"
                }
                return t
            }
            if (heapAllocTargetType != null) {
                val tt = heapAllocTargetType!!
                val isArray = tt.name == "Array" && tt.typeArgs.isNotEmpty()
                val elemName = if (isArray) {
                    typeSubst[tt.typeArgs[0].name] ?: tt.typeArgs[0].name
                } else {
                    typeSubst[tt.name] ?: tt.name
                }
                val elemC = cTypeStr(elemName)
                val ptrExpr = genExpr(args[0].expr)
                val sizeExpr = genExpr(args[1].expr)
                val t = tmp()
                preStmts += "$elemC* $t = ($elemC*)${tRealloc(ptrExpr, "sizeof($elemC) * (size_t)($sizeExpr)")};"
                if (isArray) {
                    preStmts += "const int32_t ${t}\$len = $sizeExpr;"
                }
                return t
            }
            return tRealloc(genExpr(args[0].expr), "(size_t)(${genExpr(args[1].expr)})")
        }
        "HeapFree"    -> return tFree(genExpr(args[0].expr))
        "byteArrayOf", "shortArrayOf", "intArrayOf", "longArrayOf",
        "floatArrayOf", "doubleArrayOf", "booleanArrayOf", "charArrayOf",
        "ubyteArrayOf", "ushortArrayOf", "uintArrayOf", "ulongArrayOf" -> {
            // handled in emitVarDecl; if used as expr, wrap in compound literal
            return genArrayOfExpr(name, args)
        }
        "arrayOf" -> {
            return genArrayOfExpr(name, args, e.typeArgs.getOrNull(0))
        }
        "heapArrayOf" -> {
            return genHeapArrayOfExpr(args, e.typeArgs.getOrNull(0))
        }
        "arrayOfNulls" -> {
            val typeArg = e.typeArgs.getOrNull(0)
            val elemName = typeSubst[typeArg?.name ?: "Int"] ?: (typeArg?.name ?: "Int")
            val optCType = optCTypeName("${elemName}?")
            return genNewArray(optCType, args)
        }
        "enumValues" -> {
            if (e.typeArgs.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                val resolved = typeSubst[enumName] ?: enumName
                enumValuesCalled.add(resolved)
                return "${pfx(resolved)}_values"
            }
            error("enumValues requires a type argument")
        }
        "enumValueOf" -> {
            if (e.typeArgs.isNotEmpty() && e.args.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                val resolved = typeSubst[enumName] ?: enumName
                enumValuesCalled.add(resolved)
                enumValueOfCalled.add(resolved)
                val nameExpr = genExpr(e.args[0].expr)
                return "${pfx(resolved)}_valueOf($nameExpr)"
            }
            error("enumValueOf requires a type argument and a name")
        }
        "ByteArray"    -> return genNewArray("ktc_Byte", args)
        "ShortArray"   -> return genNewArray("ktc_Short", args)
        "IntArray"     -> return genNewArray("ktc_Int", args)
        "LongArray"    -> return genNewArray("ktc_Long", args)
        "FloatArray"   -> return genNewArray("ktc_Float", args)
        "DoubleArray"  -> return genNewArray("ktc_Double", args)
        "BooleanArray" -> return genNewArray("ktc_Bool", args)
        "CharArray"    -> return genNewArray("ktc_Char", args)
        "UByteArray"   -> return genNewArray("ktc_UByte", args)
        "UShortArray"  -> return genNewArray("ktc_UShort", args)
        "UIntArray"    -> return genNewArray("ktc_UInt", args)
        "ULongArray"   -> return genNewArray("ktc_ULong", args)
        // Generic Array<T>(size) constructor — stack-allocated like IntArray(size)
        "Array" -> {
            if (e.typeArgs.isNotEmpty()) {
                val elemName = resolveTypeName(e.typeArgs[0])
                val elemC = cTypeStr(elemName)
                return genNewArray(elemC, args)
            }
        }
    }

    // Pair constructor (intrinsic — only when no user-defined class named Pair)
    if (name == "Pair" && args.size == 2 && !classes.containsKey("Pair") && !genericClassDecls.containsKey("Pair")) {
        val a = if (e.typeArgs.size == 2) resolveTypeName(e.typeArgs[0]) else inferExprType(args[0].expr) ?: "Int"
        val b = if (e.typeArgs.size == 2) resolveTypeName(e.typeArgs[1]) else inferExprType(args[1].expr) ?: "Int"
        pairTypes.add(Pair(a, b))
        pairTypeComponents["Pair_${a}_${b}"] = Pair(a, b)
        ensurePairType(a, b)
        val pairCType = "ktc_Pair_${a}_${b}"
        return "($pairCType){${genExpr(args[0].expr)}, ${genExpr(args[1].expr)}}"
    }

    // Triple constructor (intrinsic)
    if (name == "Triple" && args.size == 3 && !classes.containsKey("Triple") && !genericClassDecls.containsKey("Triple")) {
        val a = if (e.typeArgs.size == 3) resolveTypeName(e.typeArgs[0]) else inferExprType(args[0].expr) ?: "Int"
        val b = if (e.typeArgs.size == 3) resolveTypeName(e.typeArgs[1]) else inferExprType(args[1].expr) ?: "Int"
        val c = if (e.typeArgs.size == 3) resolveTypeName(e.typeArgs[2]) else inferExprType(args[2].expr) ?: "Int"
        tripleTypeComponents["Triple_${a}_${b}_${c}"] = Triple(a, b, c)
        ensureTripleType(a, b, c)
        return "(ktc_Triple_${a}_${b}_${c}){${genExpr(args[0].expr)}, ${genExpr(args[1].expr)}, ${genExpr(args[2].expr)}}"
    }

    // Tuple constructor (intrinsic, indefinite arity)
    if (name == "Tuple" && args.isNotEmpty() && !classes.containsKey("Tuple") && !genericClassDecls.containsKey("Tuple")) {
        val types = if (e.typeArgs.size == args.size) e.typeArgs.map { resolveTypeName(it) }
            else args.map { inferExprType(it.expr) ?: "Int" }
        val key = "Tuple_${types.joinToString("_")}"
        tupleTypeComponents[key] = types
        ensureTupleType(types)
        val fields = args.map { genExpr(it.expr) }.joinToString(", ")
        return "(ktc_${key}){$fields}"
    }

    // Function pointer call: variable with function type → just call it
    val varType = lookupVar(name)
    if (varType != null && isFuncType(varType)) {
        val argStr = args.joinToString(", ") { genExpr(it.expr) }
        return "$name($argStr)"
    }

    // Constructor call (known class)
    // Handle generic class constructor: MyList<Int>(8) → MyList_Int_primaryConstructor(8)
    if (classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
        // Apply typeSubst so type params (e.g. T) resolve to concrete types (e.g. Int)
        // when inside a generic function body
        val resolvedTypeArgs = e.typeArgs.map { substituteTypeParams(it) }.map { it.name }
        val mangledName = mangledGenericName(name, resolvedTypeArgs)
        val ci = classes[mangledName] ?: error("Generic class '$mangledName' not materialized (typeSubst=$typeSubst)")
        val templateDecl = genericClassDecls[name]
        val allParams = ci.ctorProps + ci.ctorPlainParams
        val ctorParamList = allParams.map { Param(it.first, it.second) }
        val filledArgs = fillDefaults(args, ctorParamList, allParams.associate {
            val cp = templateDecl?.ctorParams?.find { p -> p.name == it.first }
            it.first to cp?.default
        })
        val expandedArgs = expandCallArgs(filledArgs, ctorParamList, isCtorCall = true)
        return "${pfx(mangledName)}_primaryConstructor($expandedArgs)"
    }
    // Generic class constructor without explicit type args: infer from arguments
    if (classes.containsKey(name) && classes[name]!!.isGeneric && e.args.isNotEmpty()) {
        val inferredArgs = e.args.map { inferExprType(it.expr) ?: "Int" }
        val mangledName = recordGenericInstantiation(name, inferredArgs)
        materializeGenericInstantiations()
        val ci = classes[mangledName]
        if (ci != null) {
            val allParams = ci.ctorProps + ci.ctorPlainParams
            val ctorParamList = allParams.map { Param(it.first, it.second) }
            val filledArgs = fillDefaults(args, ctorParamList, allParams.associate {
                it.first to null
            })
            val expandedArgs = expandCallArgs(filledArgs, ctorParamList, isCtorCall = true)
            return "${pfx(mangledName)}_primaryConstructor($expandedArgs)"
        }
    }
    if (classes.containsKey(name)) {
        val ci = classes[name]!!
        // Check secondary constructors by argument count (skip those with same count as primary)
        val declClass = file.decls.filterIsInstance<ClassDecl>().find { c -> c.name == name }
        val primaryParamCount = ci.ctorProps.size + ci.ctorPlainParams.size
        val sctor = declClass?.secondaryCtors?.find { it.params.size == args.size && it.params.size != primaryParamCount }
        if (sctor != null) {
            val types = sctor.params.map { resolveTypeName(it.type).removeSuffix("*") }
            val suffix = if (types.isEmpty()) "emptyConstructor" else "constructorWith${types.joinToString("_")}"
            val argStr = args.joinToString(", ") { genExpr(it.expr) }
            return "${pfx(name)}_$suffix($argStr)"
        }
        val allParams = ci.ctorProps + ci.ctorPlainParams
        val ctorParamList = allParams.map { Param(it.first, it.second) }
        val filledArgs = fillDefaults(args, ctorParamList, allParams.associate {
            // find matching ctor param default
            val cp = (file.decls.filterIsInstance<ClassDecl>().find { c -> c.name == name })
                ?.ctorParams?.find { p -> p.name == it.first }
            it.first to cp?.default
        })
        val expandedArgs = expandCallArgs(filledArgs, ctorParamList, isCtorCall = true)
        return "${pfx(name)}_primaryConstructor($expandedArgs)"
    }

    // Enum access (should be handled as DotExpr, but just in case)

    // Generic function call: sizeOfList(list) → sizeOfList_Int(list)
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null) {
        val typeArgNames = if (e.typeArgs.isNotEmpty()) {
            e.typeArgs.map { it.name }
        } else {
            // Infer type args from argument types
            val subst = mutableMapOf<String, String>()
            for ((i, param) in genFun.params.withIndex()) {
                if (i >= args.size) break
                val argType = inferExprType(args[i].expr) ?: continue
                matchTypeParam(param.type, argType, genFun.typeParams.toSet(), subst)
            }
            if (subst.size == genFun.typeParams.size) genFun.typeParams.map { subst[it]!! } else null
        }
        if (typeArgNames != null) {
            val mangledName = "${name}_${typeArgNames.joinToString("_")}"
            // Record for late emission if not already known
            genericFunInstantiations.getOrPut(name) { mutableSetOf() }.add(typeArgNames)
            // Set typeSubst so expandCallArgs resolves param types correctly (T→Int etc.)
            val prevSubst = typeSubst
            typeSubst = genFun.typeParams.zip(typeArgNames).toMap()
            // Check for @Size array return
            if (genFun.returnType != null && isSizedArrayTypeRef(genFun.returnType)) {
                val retType = resolveTypeName(genFun.returnType)
                val elemCType = arrayElementCType(retType)
                val size = getSizeAnnotation(genFun.returnType)!!
                val t = tmp()
                preStmts += "$elemCType ${t}[$size];"
                preStmts += "const int32_t ${t}\$len = $size;"
                val filledArgs = fillDefaults(args, genFun.params, genFun.params.associate { it.name to it.default })
                val expandedArgs2 = expandCallArgs(filledArgs, genFun.params)
                val allArgs = if (expandedArgs2.isEmpty()) t else "$expandedArgs2, $t"
                preStmts += "${pfx(mangledName)}($allArgs);"
                typeSubst = prevSubst
                defineVar(t, retType)
                return t
            }
            // Fill in default arguments
            val filledArgs = fillDefaults(args, genFun.params, genFun.params.associate { it.name to it.default })
            val expandedArgs2 = expandCallArgs(filledArgs, genFun.params)
            typeSubst = prevSubst
            return "${pfx(mangledName)}($expandedArgs2)"
        }
    }

    // Regular function call with default arg filling
    val sig = funSigs[name]
    val filledArgs = if (sig != null) {
        fillDefaults(args, sig.params, sig.params.associate { it.name to it.default })
    } else args

    val expandedArgs = expandCallArgs(filledArgs, sig?.params)

    // If function returns sized array (@Size(N) Array<T>), allocate temp and pass as out-param
    if (sig?.returnType != null && isSizedArrayTypeRef(sig.returnType)) {
        val retType = resolveTypeName(sig.returnType)
        val elemCType = arrayElementCType(retType)
        val size = getSizeAnnotation(sig.returnType)!!
        val t = tmp()
        preStmts += "$elemCType ${t}[$size];"
        preStmts += "const int32_t ${t}\$len = $size;"
        val allArgs = if (expandedArgs.isEmpty()) t else "$expandedArgs, $t"
        preStmts += "${pfx(name)}($allArgs);"
        defineVar(t, retType)
        return t
    }

    // Value-nullable functions now return Optional directly; no hoisting needed.
    // The variable declaration code handles wrapping for already-Opt values.

    // Inside a class method or extension: bare method call resolves to $self.method()
    if (currentClass != null) {
        val ci = classes[currentClass]
        val hasMethod = ci?.methods?.any { it.name == name } == true
        if (hasMethod) {
            val selfArg = if (selfIsPointer) "\$self" else "&\$self"
            val allArgs = if (expandedArgs.isEmpty()) selfArg else "$selfArg, $expandedArgs"
            return "${pfx(currentClass!!)}_$name($allArgs)"
        }
    }

    return "${pfx(name)}($expandedArgs)"
}

/** Expand call arguments: array → (arg, arg$len); nullable → (arg, arg$has); class→interface wrapping; vararg packing. */
internal fun CCodeGen.expandCallArgs(args: List<Arg>, params: List<Param>?, isCtorCall: Boolean = false): String {
    val parts = mutableListOf<String>()
    if (params == null) {
        for (arg in args) parts += genExpr(arg.expr)
        return parts.joinToString(", ")
    }

    var argIdx = 0
    for (param in params) {
        val paramType = resolveTypeName(param.type)
        if (param.isVararg) {
            // Consume remaining args for vararg
            val remaining = args.subList(argIdx, args.size)
            val elemCType = cTypeStr(paramType)
            if (remaining.size == 1 && remaining[0].isSpread) {
                val spreadExpr = genExpr(remaining[0].expr)
                parts += spreadExpr
                parts += "${spreadExpr}\$len"
            } else if (remaining.isEmpty()) {
                parts += "NULL"
                parts += "0"
            } else {
                val t = tmp()
                val argExprs = remaining.map { genExpr(it.expr) }
                preStmts += "$elemCType ${t}[] = {${argExprs.joinToString(", ")}};"
                parts += t
                parts += "${remaining.size}"
            }
            argIdx = args.size
        } else if (argIdx < args.size) {
            val arg = args[argIdx]
            val expr = genExpr(arg.expr)
            if (paramType.endsWith("*") || paramType.endsWith("*?")) {
                // @Ptr-annotated type — pass raw pointer (NULL for null)
                if (arg.expr is NullLit) {
                    parts += "NULL"
                    if (isArrayType(paramType)) parts += "0"
                } else if (paramType.removeSuffix("?").removeSuffix("*") == "Any") {
                    // @Ptr Any → wrap as ktc_Any fat pointer, pass pointer to it.
                    // For variables, take address of original (allows mutation).
                    // For rvalues, copy to temp first (isolates the callee from the caller's stack).
                    val argType = inferExprType(arg.expr)?.removeSuffix("?") ?: "Int"
                    val typeId = getTypeId(argType)
                    val ct = cTypeStr(argType)
                    val dataRef: String
                    if (arg.expr is NameExpr) {
                        dataRef = "&$expr"
                    } else {
                        val tVal = tmp()
                        preStmts += "$ct $tVal = $expr;"
                        dataRef = "&$tVal"
                    }
                    val tAny = tmp()
                    preStmts += "ktc_Any $tAny = {$typeId, (void*)$dataRef};"
                    parts += "&$tAny"
                } else {
                    parts += expr
                    if (isArrayType(paramType)) parts += "${expr}\$len"
                }
            } else if (isArrayType(paramType)) {
                if (hasSizeAnnotation(param.type)) {
                    // @Size fixed array — passed as raw pointer
                    parts += expr
                } else if (arg.expr is NullLit && !isCtorCall) {
                    // Both nullable and non-nullable use ktc_ArrayTrampoline; NULL is data == NULL
                    parts += "(ktc_ArrayTrampoline){.size = 0, .data = NULL}"
                } else {
                    // Non-null array (nullable or not): pack as ktc_ArrayTrampoline
                    val argName = (arg.expr as? NameExpr)?.name
                    val sizeExpr = if (argName != null && argName in trampolinedParams) "$argName.size" else "${expr}\$len"
                    if (isCtorCall) {
                        parts += expr
                        parts += sizeExpr
                    } else {
                        parts += "(ktc_ArrayTrampoline){.size = $sizeExpr, .data = $expr}"
                    }
                }
            } else if (param.type.nullable && isValueNullableType("${paramType}?")) {
                // Value-nullable param → pass as Optional struct
                val optType = optCTypeName("${paramType}?")
                if (arg.expr is NullLit) {
                    parts += optNone(optType)
                } else {
                    val argVarName = (arg.expr as? NameExpr)?.name
                    val argVarType = if (argVarName != null) lookupVar(argVarName) else null
                    if (argVarType != null && argVarType.endsWith("?") && isValueNullableType(argVarType)
                            && (argVarName != null && isOptional(argVarName))) {
                        // Already an Optional var — pass through (genName returned the Optional var name)
                        parts += expr
                    } else {
                        // Non-nullable value — wrap in Some
                        parts += optSome(optType, expr)
                    }
                }
            } else if (interfaces.containsKey(paramType)) {
                val argType = inferExprType(arg.expr)
                val baseArgType = argType?.trimEnd('*', '?')
                if (baseArgType != null && classes.containsKey(baseArgType) && classInterfaces[baseArgType]?.contains(paramType) == true) {
                    if (argType != null && argType.endsWith("*")) {
                        parts += "${pfx(baseArgType)}_as_$paramType($expr)"
                    } else if (argType != null && argType.endsWith("*")) {
                        parts += "${pfx(baseArgType)}_as_$paramType($expr)"
                    } else {
                        parts += "${pfx(baseArgType)}_as_$paramType(&$expr)"
                    }
                } else {
                    parts += expr
                }
            } else if (paramType == "Any") {
                if (arg.expr is NullLit) {
                    parts += "(ktc_Any){0}"
                } else {
                    val argType = inferExprType(arg.expr)?.removeSuffix("?") ?: "Int"
                    // If already Any/Any?, pass directly (no re-wrap)
                    if (argType == "Any") {
                        parts += expr
                    } else {
                        val typeId = getTypeId(argType)
                        val ct = cTypeStr(argType)
                        val tVal = tmp()
                        preStmts += "$ct $tVal = $expr;"
                        parts += "(ktc_Any){$typeId, (void*)&$tVal}"
                    }
                }
            } else {
                parts += expr
            }
            argIdx++
        }
    }
    // Handle remaining args if more args than params (shouldn't happen normally)
    while (argIdx < args.size) {
        parts += genExpr(args[argIdx].expr)
        argIdx++
    }
    return parts.joinToString(", ")
}


internal fun CCodeGen.genMethodCall(dot: DotExpr, args: List<Arg>): String {
    val rawRecvType = inferExprType(dot.obj)
    val recvType = rawRecvType?.removeSuffix("?")
    val rawRecv = genExpr(dot.obj)
    val method = dot.name
    val hasNullRecv = hasNullableReceiverExt(recvType ?: "", method)
    val isValueNull = rawRecvType != null && rawRecvType.endsWith("?") && isValueNullableType(rawRecvType) && !hasNullRecv
    val recv = if (isValueNull) "$rawRecv.value" else rawRecv
    val argStr = args.joinToString(", ") { genExpr(it.expr) }

    // Built-in methods
    when (method) {
        "toString" -> return genToString(recv, recvType ?: "Int")
        "toInt" -> {
            if (recvType == "String") return "ktc_str_toInt($recv)"
            return "((ktc_Int)($recv))"
        }
        "toLong" -> {
            if (recvType == "String") return "ktc_str_toLong($recv)"
            return "((ktc_Long)($recv))"
        }
        "toFloat" -> {
            if (recvType == "String") return "((ktc_Float)ktc_str_toDouble($recv))"
            return "((ktc_Float)($recv))"
        }
        "toDouble" -> {
            if (recvType == "String") return "ktc_str_toDouble($recv)"
            return "((ktc_Double)($recv))"
        }
        "toByte"   -> return "((ktc_Byte)($recv))"
        "toShort"  -> return "((ktc_Short)($recv))"
        "toUByte"  -> return "((ktc_UByte)($recv))"
        "toUShort" -> return "((ktc_UShort)($recv))"
        "toUInt"   -> return "((ktc_UInt)($recv))"
        "toULong"  -> return "((ktc_ULong)($recv))"
        "toChar"   -> return "((ktc_Char)($recv))"
        // Nullable string-to-number: toIntOrNull, toLongOrNull, toFloatOrNull, toDoubleOrNull
        "toIntOrNull" -> if (recvType == "String") {
            val t = tmp()
            preStmts += "ktc_Int ${t}_val;"
            preStmts += "ktc_Int_Optional $t;"
            preStmts += "$t.tag = ktc_str_toIntOrNull($recv, &${t}_val) ? ktc_SOME : ktc_NONE;"
            preStmts += "$t.value = ${t}_val;"
            markOptional(t)
            t
        }
        "toLongOrNull" -> if (recvType == "String") {
            val t = tmp()
            preStmts += "ktc_Long ${t}_val;"
            preStmts += "ktc_Long_Optional $t;"
            preStmts += "$t.tag = ktc_str_toLongOrNull($recv, &${t}_val) ? ktc_SOME : ktc_NONE;"
            preStmts += "$t.value = ${t}_val;"
            markOptional(t)
            t
        }
        "toDoubleOrNull" -> if (recvType == "String") {
            val t = tmp()
            preStmts += "ktc_Double ${t}_val;"
            preStmts += "ktc_Double_Optional $t;"
            preStmts += "$t.tag = ktc_str_toDoubleOrNull($recv, &${t}_val) ? ktc_SOME : ktc_NONE;"
            preStmts += "$t.value = ${t}_val;"
            markOptional(t)
            t
        }
        "toFloatOrNull" -> if (recvType == "String") {
            val t = tmp()
            preStmts += "ktc_Double ${t}_d;"
            preStmts += "ktc_Float_Optional $t;"
            preStmts += "$t.tag = ktc_str_toDoubleOrNull($recv, &${t}_d) ? ktc_SOME : ktc_NONE;"
            preStmts += "$t.value = (ktc_Float)${t}_d;"
            markOptional(t)
            t
        }
        "substring" -> if (recvType == "String") {
            val from = genExpr(args[0].expr)
            val to = if (args.size >= 2) genExpr(args[1].expr) else "$recv.len"
            return "ktc_string_substring($recv, $from, $to)"
        }
        "startsWith" -> if (recvType == "String") {
            val prefix = genExpr(args[0].expr)
            return "($recv.len >= $prefix.len && memcmp($recv.ptr, $prefix.ptr, (size_t)$prefix.len) == 0)"
        }
        "endsWith" -> if (recvType == "String") {
            val suffix = genExpr(args[0].expr)
            return "($recv.len >= $suffix.len && memcmp($recv.ptr + $recv.len - $suffix.len, $suffix.ptr, (size_t)$suffix.len) == 0)"
        }
        "contains" -> if (recvType == "String") {
            val sub = genExpr(args[0].expr)
            val t = tmp()
            preStmts += "bool $t = false;"
            preStmts += "for (int32_t ${t}_i = 0; ${t}_i <= $recv.len - $sub.len; ${t}_i++) { if (memcmp($recv.ptr + ${t}_i, $sub.ptr, (size_t)$sub.len) == 0) { $t = true; break; } }"
            return t
        }
        "indexOf" -> if (recvType == "String") {
            val sub = genExpr(args[0].expr)
            val t = tmp()
            preStmts += "int32_t $t = -1;"
            preStmts += "for (int32_t ${t}_i = 0; ${t}_i <= $recv.len - $sub.len; ${t}_i++) { if (memcmp($recv.ptr + ${t}_i, $sub.ptr, (size_t)$sub.len) == 0) { $t = ${t}_i; break; } }"
            return t
        }
        "isEmpty" -> if (recvType == "String") {
            return "($recv.len == 0)"
        }
        "isNotEmpty" -> if (recvType == "String") {
            return "($recv.len > 0)"
        }
        "hashCode" -> {
            val rt = recvType ?: "Int"
            return when (rt) {
                "Byte"    -> "ktc_hash_i8($recv)"
                "Short"   -> "ktc_hash_i16($recv)"
                "Int"     -> "ktc_hash_i32($recv)"
                "Long"    -> "ktc_hash_i64($recv)"
                "Float"   -> "ktc_hash_f32($recv)"
                "Double"  -> "ktc_hash_f64($recv)"
                "Boolean" -> "ktc_hash_bool($recv)"
                "Char"    -> "ktc_hash_char($recv)"
                "UByte"   -> "ktc_hash_u8($recv)"
                "UShort"  -> "ktc_hash_u16($recv)"
                "UInt"    -> "ktc_hash_u32($recv)"
                "ULong"   -> "ktc_hash_u64($recv)"
                "String"  -> "ktc_hash_str($recv)"
                else      -> "${pfx(rt)}_hashCode(&($recv))"
            }
        }
    }

    // Array .size → trampolined param uses trampoline size field; others use $len
    if (method == "size" && recvType != null && (isArrayType(recvType) || recvType.removeSuffix("?").endsWith("*"))) {
        val dotName = (dot.obj as? NameExpr)?.name
        return if (dotName != null && dotName in trampolinedParams) "$dotName.size" else "${recv}\$len"
    }
    // Array .ptr() → just the pointer (already a pointer type)
    if (method == "ptr" && recvType != null && isArrayType(recvType)) {
        return recv
    }
    // Array .toHeap() → malloc + memcpy to heap
    if (method == "toHeap" && recvType != null && isArrayType(recvType)) {
        val elemC = arrayElementCType(recvType)
        val lenExpr = when {
            dot.obj is NameExpr && (dot.obj as NameExpr).name in trampolinedParams -> "${(dot.obj as NameExpr).name}.size"
            else -> "${recv}\$len"
        }
        val t = tmp()
        preStmts += "$elemC* $t = ($elemC*)${tMalloc("sizeof($elemC) * (size_t)($lenExpr)")};"
        preStmts += "if ($t) memcpy($t, $recv, sizeof($elemC) * (size_t)($lenExpr));"
        preStmts += "int32_t ${t}\$len = $lenExpr;"
        return t
    }
    // Array pointer .get(i) → recv[i] and .set(i,v) → recv[i] = v
    if ((method == "get" || method == "set") && recvType != null && isArrayType(recvType)) {
        val idx = args.getOrNull(0)?.let { genExpr(it.expr) } ?: "0"
        if (method == "get") return "${recv}[$idx]"
        val valExpr = args.getOrNull(1)?.let { genExpr(it.expr) } ?: "0"
        return "(${recv}[$idx] = $valExpr)"
    }
    // Ptr<Array<T>> .deref() → dereference to get the array
    if (method == "deref" && recvType != null && isArrayType(recvType) &&
        (recvType.endsWith("*") || recvType.endsWith("*?"))) {
        return recv
    }

    // @Ptr/@Heap/@Value-annotated class pointer methods
    val pointerBase = pointerClassName(recvType)
    if (pointerBase != null) {
        // If class defines the method, delegate to it
        val classHasMethod = classes[pointerBase]?.methods?.any { it.name == method } == true
        if (classHasMethod) {
            val methodDecl = classes[pointerBase]?.methods?.find { it.name == method }
            val isExt = methodDecl?.receiver != null
            val recvArg = if (isExt) "(*$recv)" else recv
            val allArgs = if (argStr.isEmpty()) recvArg else "$recvArg, $argStr"
            if (methodDecl?.returnType?.nullable == true) {
                return genNullableMethodCall(pointerBase, "${pfx(pointerBase)}_$method", allArgs, methodDecl)
            }
            return "${pfx(pointerBase)}_$method($allArgs)"
        }
        when (method) {
            "value" -> return "(*$recv)"
            "deref" -> return "(*$recv)"
            "set" -> return "(*$recv = $argStr)"
            "copy" -> if (classes[pointerBase]?.isData == true) {
                return genDataClassCopy(recv, pointerBase, args, heap = true)
            }
            "toHeap", "ptr" -> return recv
        }
        // general class method — pointer passed directly
        val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
        return "${pfx(pointerBase)}_$method($allArgs)"
    }

    // Interface method dispatch → d.vt->method((void*)&d, args)
    if (recvType != null && interfaces.containsKey(recvType)) {
        val allArgs = if (argStr.isEmpty()) "(void*)&$recv" else "(void*)&$recv, $argStr"
        // Check if this interface method returns nullable
        val ifaceInfo = interfaces[recvType]
        val ifaceMethod = ifaceInfo?.methods?.find { it.name == method }
            ?: collectAllIfaceMethods(ifaceInfo!!).find { it.name == method }
        if (ifaceMethod?.returnType?.nullable == true) {
            val retType = resolveTypeName(ifaceMethod.returnType)
            val optType = optCTypeName("${retType}?")
            val t = tmp()
            preStmts += "$optType $t = $recv.vt->$method($allArgs);"
            markOptional(t)
            defineVar(t, "${retType}?")
            return t
        }
        return "$recv.vt->$method($allArgs)"
    }

    // Class method or extension function on class type (stack value)
    if (recvType != null && classes.containsKey(recvType)) {
        // .copy() on data class
        if (method == "copy" && classes[recvType]?.isData == true) {
            return genDataClassCopy(recv, recvType, args, heap = false)
        }
        // .toHeap() → inline malloc + struct copy
        if (method == "toHeap") {
            val cName = pfx(recvType)
            val t = tmp()
            preStmts += "$cName* $t = ($cName*)${tMalloc("sizeof($cName)")};"
            preStmts += "if ($t) *$t = $recv;"
            return t
        }
        // .ptr() → &value
        if (method == "ptr") {
            val t = tmp()
            preStmts += "${pfx(recvType)}* $t = &$recv;"
            return t
        }
        val methodDecl = classes[recvType]?.methods?.find { it.name == method }
        val isExtFun = methodDecl?.receiver != null
        val nullableRecv = hasNullableReceiverExt(recvType, method)
        val selfArg = if (nullableRecv) {
            val recvName = (dot.obj as? NameExpr)?.name
            val recvVarType2 = if (recvName != null) lookupVar(recvName) else null
            val optSelfType = optCTypeName("${recvType}?")
            when {
                dot.obj is ThisExpr -> "\$self"
                recvVarType2 != null && recvVarType2.endsWith("?") && isValueNullableType(recvVarType2)
                    && recvName != null && isOptional(recvName) -> recv
                isExtFun -> optSome(optSelfType, recv)
                else -> optSome(optSelfType, "&$recv")
            }
        } else if (isExtFun) "$recv"
        else "&$recv"
        val allArgs = if (argStr.isEmpty()) selfArg else "$selfArg, $argStr"
        val fnPrefix = if (methodDecl?.isPrivate == true) "PRIV_${method}" else method
        // Nullable return: use out-pointer pattern
        if (methodDecl?.returnType?.nullable == true) {
            return genNullableMethodCall(recvType, "${pfx(recvType)}_$fnPrefix", allArgs, methodDecl)
        }
        return "${pfx(recvType)}_$fnPrefix($allArgs)"
    }
    // Object method
    if (recvType != null && objects.containsKey(recvType)) {
        val vObjMethod = objects[recvType]?.methods?.find { it.name == method }
        val vObjArgs = if (vObjMethod != null)
            fillDefaults(args, vObjMethod.params, vObjMethod.params.associate { it.name to it.default })
            else args
        val vObjArgStr = vObjArgs.joinToString(", ") { genExpr(it.expr) }
        return "${pfx(recvType)}_$method($vObjArgStr)"
    }
    // Companion object method: Foo.bar() where Foo has a companion object
    val vDotObjName = (dot.obj as? NameExpr)?.name
    if (vDotObjName != null && classCompanions.containsKey(vDotObjName)) {
        val vCompanionName = classCompanions[vDotObjName]!!
        val vCompMethod = objects[vCompanionName]?.methods?.find { it.name == method }
        val vCompArgs = if (vCompMethod != null)
            fillDefaults(args, vCompMethod.params, vCompMethod.params.associate { it.name to it.default })
            else args
        val vCompArgStr = vCompArgs.joinToString(", ") { genExpr(it.expr) }
        return "${pfx(vCompanionName)}_$method($vCompArgStr)"
    }
    // Enum → static method/field access
    if (recvType != null && enums.containsKey(recvType)) {
        when (method) {
            "values" -> {
                enumValuesCalled.add(recvType)
                return "${pfx(recvType)}_values"
            }
            "valueOf" -> {
                val argStr = args.joinToString(", ") { genExpr(it.expr) }
                enumValuesCalled.add(recvType)
                enumValueOfCalled.add(recvType)
                return "${pfx(recvType)}_valueOf($argStr)"
            }
            else -> return "${pfx(recvType)}_$method"
        }
    }

    // Extension function on non-class type (Int, String, etc.)
    if (recvType != null) {
        var extFun = extensionFuns[recvType]?.find { it.name == method }
        // Also check implemented interfaces for class receiver types
        if (extFun == null && classes.containsKey(recvType)) {
            val ifaces = classInterfaces[recvType] ?: emptyList()
            for (ifaceName in ifaces) {
                extFun = extensionFuns[ifaceName]?.find { it.name == method }
                if (extFun != null) break
            }
        }
        if (extFun != null) {
            val nullableRecv = extFun.receiver?.nullable == true
            val recvArg = if (nullableRecv) {
                val recvName = (dot.obj as? NameExpr)?.name
                val recvVarType = if (recvName != null) lookupVar(recvName) else null
                val optSelfType = optCTypeName("${recvType}?")
                when {
                    dot.obj is ThisExpr -> "\$self"
                    recvVarType != null && recvVarType.endsWith("?") && isValueNullableType(recvVarType)
                        && recvName != null && isOptional(recvName) -> recv
                    else -> optSome(optSelfType, recv)
                }
            } else recv
            // Use the receiver's actual interface type for the call
            val callRecvType = if (classes.containsKey(recvType) && extFun.receiver != null) {
                // Extension is on an interface — wrap in interface vtable
                val ifaceName = extFun.receiver.name
                if (ifaceName in (classInterfaces[recvType] ?: emptyList())) {
                    // recvType implements this interface — use direct call for now
                    recvType
                } else recvType
            } else recvType
            val allArgs = if (argStr.isEmpty()) recvArg else "$recvArg, $argStr"
            return "${pfx(recvType)}_$method($allArgs)"
        }
        // Implicit dispose — always emitted as no-op
        if (method == "dispose" && (classes.containsKey(recvType) || enums.containsKey(recvType) || objects.containsKey(recvType))) {
            val selfExpr = if (recvType.endsWith("*") || recvType.endsWith("*?")) recv else "&$recv"
            val base = anyIndirectClassName(recvType) ?: recvType
            return "${pfx(base)}_dispose($selfExpr)"
        }
        // Per-class star-projection extension (e.g. fun Map<K,V>.tryDispose())
        if (classes.containsKey(recvType) && starExtFunDecls.any { it.name == method }) {
            val selfExpr = if (recvType.endsWith("*") || recvType.endsWith("*?")) recv else "&$recv"
            val base = anyIndirectClassName(recvType) ?: recvType
            val allArgs = if (argStr.isEmpty()) selfExpr else "$selfExpr, $argStr"
            return "${pfx(base)}_$method($allArgs)"
        }
    }

    return "$recv.$method($argStr)"   // fallback
}

/** Generate a method call that returns nullable via out-pointer. */
internal fun CCodeGen.genNullableMethodCall(className: String, fnExpr: String, allArgs: String, methodDecl: FunDecl): String {
    val retBase = resolveMethodReturnType(className, methodDecl.returnType).removeSuffix("?")
    val optType = optCTypeName("${retBase}?")
    val t = tmp()
    preStmts += "$optType $t = $fnExpr($allArgs);"
    markOptional(t)
    defineVar(t, "${retBase}?")
    return t
}

/** Generate data class copy. `heap` = true when receiver is a heap pointer. */
internal fun CCodeGen.genDataClassCopy(recv: String, className: String, args: List<Arg>, heap: Boolean): String {
    val cName = pfx(className)
    val src = if (heap) "(*$recv)" else recv
    if (args.isEmpty()) {
        // Simple copy — struct value copy
        return src
    }
    // copy(field = val, ...) — hoist to temp, override named fields
    val t = tmp()
    preStmts += "$cName $t = $src;"
    for (arg in args) {
        val fieldName = arg.name ?: continue
        val value = genExpr(arg.expr)
        preStmts += "$t.$fieldName = $value;"
    }
    return t
}

internal fun CCodeGen.genSafeMethodCall(dot: SafeDotExpr, args: List<Arg>): String {
    val recvName = (dot.obj as? NameExpr)?.name
    val recvType = if (recvName != null) lookupVar(recvName) else null
    val isValueNullRecv = recvType != null && recvType.endsWith("?") && isValueNullableType(recvType)
    val dotExpr = DotExpr(dot.obj, dot.name)

    // Handle .ptr() safe-call: guard first, then take address
    if (dot.name == "ptr" && isValueNullRecv && recvName != null) {
        val baseClass = recvType.removeSuffix("?")
        val cName = pfx(baseClass)
        val t = tmp()
        preStmts += "$cName* $t = ($recvName.tag == ktc_SOME ? &${recvName}.value : NULL);"
        defineVar(t, "${baseClass}*?")
        return t
    }
    if (dot.name == "ptr" && recvType != null && recvName != null) {
        val cleanType = recvType.removeSuffix("?")
        if (isArrayType(cleanType)) {
            val t = tmp()
            val guard = if (recvType.endsWith("?")) "${recvName}\$has" else "true"
            val arrCType = cTypeStr(cleanType)
            preStmts += "$arrCType $t = $guard ? $recvName : NULL;"
            preStmts += "int32_t ${t}\$len = $guard ? ${recvName}\$len : 0;"
            defineVar(t, "${cleanType}*?")
            return t
        }
    }

    val call = genMethodCall(dotExpr, args)
    // Determine the null guard expression
    val guard = when {
        recvType != null && recvType.endsWith("*?") ->
            "$recvName != NULL"
        isValueNullRecv -> "$recvName.tag == ktc_SOME"
        else -> "${recvName}\$has"
    }
    // Determine the return type
    val retType = inferMethodReturnType(dotExpr, args)
    if (retType == null || retType == "Unit") {
        return "($guard ? ($call, 0) : 0)"
    }
    // Pointer return (@Ptr): use NULL for null, no Optional wrapping
    if (retType.endsWith("*")) {
        val t = tmp()
        preStmts += "${cTypeStr(retType)} $t = $guard ? $call : NULL;"
        defineVar(t, "${retType}?")
        return t
    }
    // Emit temp as Optional
    val optType = optCTypeName("${retType}?")
    val t = tmp()
    preStmts += "$optType $t = $guard ? ($optType){ktc_SOME, $call} : ${optNone(optType)};"
    markOptional(t)
    defineVar(t, "${retType}?")
    return t
}

// ── dot access (property, enum) ──────────────────────────────────

internal fun CCodeGen.genDot(e: DotExpr): String {
    // C package passthrough: c.EXIT_SUCCESS → EXIT_SUCCESS, c.NULL → NULL
    if (e.obj is NameExpr && e.obj.name == "c" && lookupVar("c") == null) {
        return e.name
    }

    val recvType = inferExprType(e.obj)
    val recv = genExpr(e.obj)

    // Reject non-safe access on nullable receiver (enum/object/companion are never nullable)
    // Allow array types (plain or indirect) where size/index access is safe
    val isEnumOrObj = e.obj is NameExpr && (enums.containsKey(e.obj.name) || objects.containsKey(e.obj.name) || classCompanions.containsKey(e.obj.name))
    if (recvType != null && recvType.endsWith("?") && !isEnumOrObj) {
        val baseType = recvType.removeSuffix("?")
        val isIndirectArray = baseType.endsWith("*") && isArrayType(baseType)
        if (!isIndirectArray && !isArrayType(baseType)) {
            val recvSrc = (e.obj as? NameExpr)?.name ?: e.obj.toString()
            codegenError("Only safe (?.) access is allowed on a nullable receiver of type '$recvType': $recvSrc.${e.name}")
        }
    }

    // Enum entry: Color.RED → game_Color_RED
    if (e.obj is NameExpr && enums.containsKey(e.obj.name)) {
        return "${pfx(e.obj.name)}_${e.name}"
    }
    // Object field: Config.debug → game_Config.debug (with lazy init guard)
    if (e.obj is NameExpr && objects.containsKey(e.obj.name)) {
        preStmts += "${pfx(e.obj.name)}_\$ensure_init();"
        return "${pfx(e.obj.name)}.${e.name}"
    }
    // Companion object field: Foo.bar → game_Foo_Companion.bar (with lazy init guard)
    if (e.obj is NameExpr && classCompanions.containsKey(e.obj.name)) {
        val vCompanionName = classCompanions[e.obj.name]!!
        preStmts += "${pfx(vCompanionName)}_\$ensure_init();"
        return "${pfx(vCompanionName)}.${e.name}"
    }
    // Array .size → trampolined param uses trampoline struct field; others use $len
    if (e.name == "size" && e.obj is NameExpr && e.obj.name in trampolinedParams) return "${e.obj.name}.size"
    if (e.name == "size" && recvType != null && isArrayType(recvType)) return "${recv}\$len"
    if (e.name == "length" && recvType == "String") return "$recv.len"
    // Enum .ordinal → the int value itself
    if (e.name == "ordinal" && recvType != null && recvType in enums) return recv
    // Enum .name → lookup in names array
    if (e.name == "name" && recvType != null && recvType in enums) return "${pfx(recvType)}_names[($recv)]"

    // Heap<T> / Ptr<T> / Value<T>: p->field (auto-deref through pointer)
    if (pointerClassName(recvType) != null) {
        val fieldName = if (currentClass != null && e.obj is ThisExpr) {
            val ci = classes[currentClass]!!
            if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
        } else e.name
        return "$recv->${fieldName}"
    }

    // Interface property access via vtable: list.size → list.vt->size(list.obj)
    if (recvType != null && interfaces.containsKey(recvType)) {
        val iface = interfaces[recvType]!!
        val allProps = collectAllIfaceProperties(iface)
        if (allProps.any { it.name == e.name }) {
            return "$recv.vt->${e.name}((void*)&$recv)"
        }
    }

    val fieldName = if (currentClass != null && e.obj is ThisExpr) {
        val ci = classes[currentClass]!!
        if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
    } else e.name
    return "$recv.${fieldName}"
}

internal fun CCodeGen.genSafeDot(e: SafeDotExpr): String {
    val recvType = inferExprType(e.obj)
    val recv = genExpr(e.obj)
    val recvName = (e.obj as? NameExpr)?.name
    val isThis = e.obj is ThisExpr
    val isValueNullRecv = recvType != null && recvType.endsWith("?") && isValueNullableType(recvType)

    // Determine the null guard expression
    val guard = if (isThis) {
        if (isValueNullRecv) "\$self.tag == ktc_SOME" else "\$self\$has"
    } else if (recvName != null && recvType != null) {
        when {
            recvType.endsWith("*?") ->
                "$recvName != NULL"
            isValueNullRecv -> "$recvName.tag == ktc_SOME"
            recvType.endsWith("?") ->
                "${recvName}\$has"
            else -> "${recv}\$has"
        }
    } else "${recv}\$has"

    // Unwrapped receiver expression for field access (unwrap Optional if needed)
    val recvVal = if (isValueNullRecv) "$recv.value" else recv

    // Determine field access expression (same logic as genDot but without nullable check)
    val fieldAccess = when {
        anyIndirectClassName(recvType) != null -> "$recvVal->${e.name}"
        e.name == "size" && recvType != null && isArrayType(recvType) -> "${recvVal}\$len"
        e.name == "length" && recvType?.removeSuffix("?") == "String" -> "$recvVal.len"
        else -> "$recvVal.${e.name}"
    }

    // Infer field type for proper default and C type
    val fieldType = inferDotType(DotExpr(e.obj, e.name))
    val ct = if (fieldType != null) cTypeStr(fieldType) else "int32_t"
    val defVal = if (fieldType != null) defaultVal(fieldType) else "0"

    // Emit temp as Optional for value-nullable field results
    val t = tmp()
    val isFieldValueNull = fieldType != null && fieldType.endsWith("?") && isValueNullableType(fieldType)
    if (isFieldValueNull) {
        val optType = optCTypeName(fieldType)
        preStmts += "$optType $t = $guard ? $fieldAccess : ${optNone(optType)};"
        markOptional(t)
        defineVar(t, fieldType)
    } else {
        val optType = if (fieldType != null) optCTypeName("${fieldType}?") else "ktc_Int_Optional"
        val fieldCType = if (fieldType != null) cTypeStr(fieldType) else "int32_t"
        preStmts += "$optType $t = $guard ? ($optType){ktc_SOME, $fieldAccess} : ${optNone(optType)};"
        markOptional(t)
        defineVar(t, "${fieldType ?: "Int"}?")
    }
    return t
}

// ── !! (not-null assertion) ─────────────────────────────────────────

internal fun CCodeGen.genNotNull(e: NotNullExpr): String {
    val inner = genExpr(e.expr)
    val innerType = inferExprType(e.expr)
    val loc = "$sourceFileName:$currentStmtLine"

    // Pointer-nullable: type ends with "*", "^", or "&"
    val baseType = innerType?.removeSuffix("?") ?: ""
    val isPtr = baseType.endsWith("*") || isAllocCall(e.expr)

    if (isPtr) {
        val ct = cTypeStr(baseType.ifEmpty { "void*" })
        // Simple name — no temp needed
        if (e.expr is NameExpr) {
            preStmts += "if (!$inner) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
            return inner
        }
        val t = tmp()
        preStmts += "$ct $t = $inner;"
        if (isArrayType(baseType) || isAllocArrayCall(e.expr)) {
            preStmts += "const int32_t ${t}\$len = ${inner}\$len;"
        }
        preStmts += "if (!$t) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
        return t
    }

    // Value-nullable variable: check Optional tag
    if (innerType != null && innerType.endsWith("?") && isValueNullableType(innerType) && e.expr is NameExpr) {
        val name = (e.expr as NameExpr).name
        preStmts += "if ($name.tag == ktc_NONE) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
        // Return the unwrapped value
        return "$name.value"
    }

    // Fallback: no check (non-nullable expression)
    return inner
}

/** Find a common interface implemented by both types, or null. */
internal fun CCodeGen.findCommonInterface(type1: String?, type2: String?): String? {
    if (type1 == null || type2 == null || type1 == type2) return null
    val ifaces1 = classInterfaces[type1]?.toSet() ?: return null
    val ifaces2 = classInterfaces[type2]?.toSet() ?: return null
    val common = ifaces1.intersect(ifaces2)
    return common.firstOrNull()
}

/** Emit block statements into preStmts, wrapping the last expression into an interface struct. */
internal fun CCodeGen.emitBlockIntoTempIface(b: Block, tempVar: String, concreteType: String, ifaceName: String, indent: String) {
    val cConcrete = pfx(concreteType)
    val impls = interfaceImplementors[ifaceName] ?: emptyList()
    val dataName = ifaceDataName(concreteType)
    val fieldPath = when {
        impls.size <= 1 -> ".$dataName"
        else -> ".data.$dataName"
    }
    for ((i, s) in b.stmts.withIndex()) {
        if (i == b.stmts.lastIndex) {
            val expr = when (s) {
                is ExprStmt -> s.expr
                is ReturnStmt -> s.value
                else -> null
            }
            if (expr != null) {
                val valExpr = genExpr(expr)
                preStmts += "$indent$tempVar$fieldPath = $valExpr;"
                preStmts += "$indent$tempVar.vt = &${cConcrete}_${ifaceName}_vt;"
            } else {
                emitStmtToPreStmts(s, indent)
            }
        } else {
            emitStmtToPreStmts(s, indent)
        }
    }
}

// ── if expression (as C ternary or temp) ─────────────────────────

internal fun CCodeGen.genIfExpr(e: IfExpr): String {
    val thenType = inferBlockType(e.then)
    val elseType = if (e.els != null) inferBlockType(e.els) else null
    val commonIface = findCommonInterface(thenType, elseType)

    // Interface coercion: branches return different types sharing a common interface
    if (commonIface != null) {
        val t = tmp()
        val cIface = pfx(commonIface)
        preStmts += "$cIface $t;"
        preStmts += "if (${genExpr(e.cond)}) {"
        emitBlockIntoTempIface(e.then, t, thenType!!, commonIface, "    ")
        if (e.els != null) {
            preStmts += "} else {"
            emitBlockIntoTempIface(e.els, t, elseType!!, commonIface, "    ")
        }
        preStmts += "}"
        return t
    }

    // Simple case: both branches are single expressions → ternary
    val thenExpr = blockAsSingleExpr(e.then)
    val elseExpr = if (e.els != null) blockAsSingleExpr(e.els) else null
    if (thenExpr != null && (e.els == null || elseExpr != null)) {
        val thenStr = genExpr(thenExpr)
        val elseStr = if (elseExpr != null) genExpr(elseExpr) else "0"
        return "(${genExpr(e.cond)} ? $thenStr : $elseStr)"
    }

    // Complex case: multi-statement bodies → hoist to temp
    val t = tmp()
    val retType = inferIfExprType(e) ?: "Int"
    val ct = cTypeStr(retType)
    preStmts += "$ct $t;"
    preStmts += "if (${genExpr(e.cond)}) {"
    emitBlockIntoTemp(e.then, t, "    ")
    if (e.els != null) {
        preStmts += "} else {"
        emitBlockIntoTemp(e.els, t, "    ")
    }
    preStmts += "}"
    return t
}

/** Try to extract a single expression from a block (last stmt as expr). */
internal fun CCodeGen.blockAsSingleExpr(b: Block): Expr? {
    if (b.stmts.size == 1) {
        val s = b.stmts[0]
        if (s is ExprStmt) return s.expr
    }
    return null
}

/** Emit block statements into preStmts, assigning last expression to tempVar. */
internal fun CCodeGen.emitBlockIntoTemp(b: Block, tempVar: String, indent: String) {
    for ((i, s) in b.stmts.withIndex()) {
        if (i == b.stmts.lastIndex) {
            // Last statement: assign its value to temp
            val expr = when (s) {
                is ExprStmt -> s.expr
                is ReturnStmt -> s.value
                else -> null
            }
            if (expr != null) {
                preStmts += "$indent$tempVar = ${genExpr(expr)};"
            } else {
                // Non-expression last statement — just emit it
                emitStmtToPreStmts(s, indent)
            }
        } else {
            emitStmtToPreStmts(s, indent)
        }
    }
}

/** Emit a statement into preStmts (for hoisting into if/when expression bodies). */
internal fun CCodeGen.emitStmtToPreStmts(s: Stmt, indent: String) {
    when (s) {
        is ExprStmt -> {
            val expr = genExpr(s.expr)
            preStmts += "$indent$expr;"
        }
        is VarDeclStmt -> {
            val t = if (s.type != null) resolveTypeName(s.type) else (inferExprType(s.init) ?: "Int")
            val ct = cTypeStr(t)
            val initExpr = if (s.init != null) genExpr(s.init) else defaultVal(t)
            preStmts += "$indent$ct ${s.name} = $initExpr;"
            defineVar(s.name, t)
        }
        else -> preStmts += "$indent/* unsupported stmt in expr block */;"
    }
}

internal fun CCodeGen.inferIfExprType(e: IfExpr): String? {
    val thenType = inferBlockType(e.then)
    val elseType = if (e.els != null) inferBlockType(e.els) else null
    val commonIface = findCommonInterface(thenType, elseType)
    if (commonIface != null) return commonIface
    return thenType ?: elseType
}

internal fun CCodeGen.inferBlockType(b: Block): String? {
    val last = b.stmts.lastOrNull() ?: return null
    return when (last) {
        is ExprStmt -> inferExprType(last.expr)
        is ReturnStmt -> if (last.value != null) inferExprType(last.value) else null
        else -> null
    }
}

// ── when expression (nested ternary or temp) ──────────────────────

internal fun CCodeGen.genWhenExpr(e: WhenExpr): String {
    val subjName = (e.subject as? NameExpr)?.name
    // Check if branches need interface coercion (different types sharing a common interface)
    val branchTypes = e.branches.map { inferBlockType(it.body) }
    val distinctTypes = branchTypes.filterNotNull().distinct()
    val commonIface = if (distinctTypes.size > 1) {
        var common: Set<String>? = null
        for (t in distinctTypes) {
            val ifaces = classInterfaces[t]?.toSet() ?: break
            if (common == null) common = ifaces
            else common = common.intersect(ifaces)
            if (common.isEmpty()) break
        }
        common?.firstOrNull()
    } else null

    if (commonIface != null) {
        // Interface coercion: use temp with interface type, wrap each branch
        val t = tmp()
        val cIface = pfx(commonIface)
        preStmts += "$cIface $t;"
        for ((bi, br) in e.branches.withIndex()) {
            if (br.conds == null) {
                preStmts += if (bi > 0) "} else {" else "{"
            } else {
                val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                val keyword = if (bi == 0) "if" else "} else if"
                preStmts += "$keyword ($condStr) {"
            }
            val narrowedType = narrowSubjectForBranch(br, subjName)
            if (narrowedType != null) {
                preStmts += "    // smart-cast: '$subjName' narrowed to '$narrowedType'"
                pushScope(); defineVar(subjName!!, narrowedType)
            }
            val brType = branchTypes[bi]
            if (brType != null && classes.containsKey(brType)) {
                emitBlockIntoTempIface(br.body, t, brType, commonIface, "    ")
            } else {
                emitBlockIntoTemp(br.body, t, "    ")
            }
            if (narrowedType != null) popScope()
        }
        preStmts += "}"
        return t
    }

    // Check if all branches are single-expression → nested ternary
    val allSimple = e.branches.all { blockAsSingleExpr(it.body) != null }
    if (allSimple) {
        val sb = StringBuilder()
        for (br in e.branches) {
            val narrowedType = narrowSubjectForBranch(br, subjName)
            if (narrowedType != null) { pushScope(); defineVar(subjName!!, narrowedType) }
            val expr = genExpr(blockAsSingleExpr(br.body)!!)
            if (narrowedType != null) popScope()
            if (br.conds == null) {
                sb.append(expr)
            } else {
                val cond = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                sb.append("($cond) ? $expr : ")
            }
        }
        return sb.toString()
    }

    // Complex case: hoist to temp
    val t = tmp()
    val retType = inferWhenExprType(e) ?: "Int"
    val ct = cTypeStr(retType)
    preStmts += "$ct $t;"
    for ((bi, br) in e.branches.withIndex()) {
        if (br.conds == null) {
            preStmts += if (bi > 0) "} else {"
            else "{"
        } else {
            val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
            val keyword = if (bi == 0) "if" else "} else if"
            preStmts += "$keyword ($condStr) {"
        }
        val narrowedType = narrowSubjectForBranch(br, subjName)
        if (narrowedType != null) {
            preStmts += "    // smart-cast: '$subjName' narrowed to '$narrowedType'"
            pushScope(); defineVar(subjName!!, narrowedType)
        }
        emitBlockIntoTemp(br.body, t, "    ")
        if (narrowedType != null) popScope()
    }
    preStmts += "}"
    return t
}

internal fun CCodeGen.narrowSubjectForBranch(br: WhenBranch, subjName: String?): String? {
    if (br.conds == null || subjName == null || isMutable(subjName)) return null
    val isCond = br.conds.find { it is IsCond && !it.negated } as? IsCond ?: return null
    val target = resolveTypeName(isCond.type)
    val current = lookupVar(subjName) ?: return null
    // Don't narrow pointer types (Any* etc.) — they need original type for ->data dereference
    if (current.endsWith("*")) return null
    return if (current != target) target else null
}

internal fun CCodeGen.inferWhenExprType(e: WhenExpr): String? {
    val types = e.branches.mapNotNull { inferBlockType(it.body) }
    if (types.isEmpty()) return null
    if (types.distinct().size > 1) {
        var common: Set<String>? = null
        for (t in types) {
            val ifaces = classInterfaces[t]?.toSet() ?: break
            if (common == null) common = ifaces
            else common = common.intersect(ifaces)
            if (common.isEmpty()) break
        }
        if (common != null && common.isNotEmpty()) return common.first()
    }
    return types.first()
}

// ── println / print (expression context — rare) ──────────────────

internal fun CCodeGen.genPrintln(args: List<Arg>): String {
    if (args.isEmpty()) return "printf(\"\\n\")"
    return genPrintCall(args, newline = true)
}

internal fun CCodeGen.genPrint(args: List<Arg>): String {
    if (args.isEmpty()) return "(void)0"
    return genPrintCall(args, newline = false)
}

internal fun CCodeGen.genPrintCall(args: List<Arg>, newline: Boolean): String {
    val arg = args[0].expr
    val nl = if (newline) "\\n" else ""

    // String template → direct printf
    if (arg is StrTemplateExpr) {
        return genPrintfFromTemplate(arg, nl)
    }

    val t = inferExprType(arg) ?: "Int"
    val expr = genExpr(arg)

    // Nullable → ternary: $has ? printf(value) : printf("null")
    if (t.endsWith("?")) {
        val baseT = t.removeSuffix("?")
        val hasExpr = "${expr}\$has"
        val fmt = printfFmt(baseT) + nl
        val a = printfArg(expr, baseT)
        return "($hasExpr ? printf(\"$fmt\", $a) : printf(\"null$nl\"))"
    }

    // data class → use preStmts for toString buffer
    if (classes.containsKey(t) && classes[t]!!.isData) {
        val buf = tmp()
        val vTmp = tmp()
        preStmts += "char ${buf}[256];"
        preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 256};"
        preStmts += "${cTypeStr(t)} $vTmp = ($expr);"
        preStmts += "${pfx(t)}_toString(&$vTmp, &${buf}_sb);"
        return "printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr)"
    }
    // Non-data class/object/interface → use toString()
    if (classes.containsKey(t) || objects.containsKey(t) || interfaces.containsKey(t)) {
        val str = genToString(expr, t)
        val tmpStr = tmp()
        preStmts += "ktc_String $tmpStr = $str;"
        return "printf(\"%.*s$nl\", (int)${tmpStr}.len, ${tmpStr}.ptr)"
    }
    // Heap/Ptr/Value to data class → pass pointer directly (no dereference)
    val indirectBase = anyIndirectClassName(t)
    if (indirectBase != null && classes[indirectBase]?.isData == true) {
        val buf = tmp()
        preStmts += "char ${buf}[256];"
        preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 256};"
        preStmts += "${pfx(indirectBase)}_toString($expr, &${buf}_sb);"
        return "printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr)"
    }

    val fmt = printfFmt(t) + nl
    val a = printfArg(expr, t)
    return "printf(\"$fmt\", $a)"
}

internal fun CCodeGen.genPrintfFromTemplate(tmpl: StrTemplateExpr, nl: String): String {
    val fmt = StringBuilder()
    val argsList = mutableListOf<String>()
    for (part in tmpl.parts) {
        when (part) {
            is LitPart -> fmt.append(escapeStr(part.text))
            is ExprPart -> {
                val t = inferExprType(part.expr) ?: "Int"
                fmt.append(printfFmt(t))
                argsList += printfArg(genExpr(part.expr), t)
            }
        }
    }
    fmt.append(nl)
    val argsStr = if (argsList.isNotEmpty()) ", " + argsList.joinToString(", ") else ""
    return "printf(\"$fmt\"$argsStr)"
}

// ── string template (returns ktc_String via preStmts) ─────────────

internal fun CCodeGen.genStrTemplate(e: StrTemplateExpr): String {
    val buf = tmp()
    preStmts += "char ${buf}[256];"
    preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 256};"
    for (part in e.parts) {
        when (part) {
            is LitPart -> preStmts += "ktc_sb_append_cstr(&${buf}_sb, \"${escapeStr(part.text)}\");"
            is ExprPart -> {
                val t = inferExprType(part.expr) ?: "Int"
                val expr = genExpr(part.expr)
                preStmts += genSbAppend("&${buf}_sb", expr, t)
            }
        }
    }
    return "ktc_sb_to_string(&${buf}_sb)"
}

// ── toString dispatch ────────────────────────────────────────────

internal fun CCodeGen.genToString(recv: String, type: String): String {
    if (classes.containsKey(type) && classes[type]!!.isData) {
        val buf = tmp()
        val vTmp = tmp()
        preStmts += "char ${buf}[256];"
        preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 256};"
        preStmts += "${cTypeStr(type)} $vTmp = ($recv);"
        preStmts += "${pfx(type)}_toString(&$vTmp, &${buf}_sb);"
        return "ktc_sb_to_string(&${buf}_sb)"
    }
    return when (type) {
        "Byte" -> {
            val buf = tmp()
            preStmts += "char ${buf}[8];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 8};"
            preStmts += "ktc_sb_append_byte(&${buf}_sb, $recv);"
            "ktc_sb_to_string(&${buf}_sb)"
        }
        "Short" -> {
            val buf = tmp()
            preStmts += "char ${buf}[8];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 8};"
            preStmts += "ktc_sb_append_short(&${buf}_sb, $recv);"
            "ktc_sb_to_string(&${buf}_sb)"
        }
        "Int" -> {
            val buf = tmp()
            preStmts += "char ${buf}[32];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 32};"
            preStmts += "ktc_sb_append_int(&${buf}_sb, $recv);"
            "ktc_sb_to_string(&${buf}_sb)"
        }
        "Long" -> {
            val buf = tmp()
            preStmts += "char ${buf}[32];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 32};"
            preStmts += "ktc_sb_append_long(&${buf}_sb, $recv);"
            "ktc_sb_to_string(&${buf}_sb)"
        }
        "UByte" -> {
            val buf = tmp()
            preStmts += "char ${buf}[8];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 8};"
            preStmts += "ktc_sb_append_ubyte(&${buf}_sb, $recv);"
            "ktc_sb_to_string(&${buf}_sb)"
        }
        "UShort" -> {
            val buf = tmp()
            preStmts += "char ${buf}[8];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 8};"
            preStmts += "ktc_sb_append_ushort(&${buf}_sb, $recv);"
            "ktc_sb_to_string(&${buf}_sb)"
        }
        "UInt" -> {
            val buf = tmp()
            preStmts += "char ${buf}[32];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 32};"
            preStmts += "ktc_sb_append_uint(&${buf}_sb, $recv);"
            "ktc_sb_to_string(&${buf}_sb)"
        }
        "ULong" -> {
            val buf = tmp()
            preStmts += "char ${buf}[32];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, 32};"
            preStmts += "ktc_sb_append_ulong(&${buf}_sb, $recv);"
            "ktc_sb_to_string(&${buf}_sb)"
        }
        "Float" -> {
            val buf = tmp()
            preStmts += "char ${buf}[32];"
            preStmts += "snprintf($buf, 32, \"%g\", (double)($recv));"
            "ktc_str($buf)"
        }
        "Double" -> {
            val buf = tmp()
            preStmts += "char ${buf}[32];"
            preStmts += "snprintf($buf, 32, \"%g\", $recv);"
            "ktc_str($buf)"
        }
        "Boolean" -> {
            val buf = tmp()
            preStmts += "char ${buf}[8];"
            preStmts += "snprintf($buf, 8, \"%s\", ($recv) ? \"true\" : \"false\");"
            "ktc_str($buf)"
        }
        "Char" -> {
            val buf = tmp()
            preStmts += "char ${buf}[8];"
            preStmts += "snprintf($buf, 8, \"%c\", (char)($recv));"
            "ktc_str($buf)"
        }
        "String" -> recv
        else -> {
            // Default toString: ClassName@hexHashCode (Java-like)
            val base = type.removeSuffix("*").removeSuffix("?")
            val hasHash = classes.containsKey(base) || objects.containsKey(base)
            val hasIface = interfaces.containsKey(base)
            if (hasHash) {
                val cName = pfx(base)
                val buf = tmp()
                val selfExpr = if (type.endsWith("*") || type.endsWith("*?")) recv else "&$recv"
                preStmts += "char ${buf}[64];"
                preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", ${cName}_hashCode($selfExpr));"
                "ktc_str($buf)"
            } else if (hasIface) {
                val buf = tmp()
                preStmts += "char ${buf}[64];"
                preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", $recv.vt->hashCode((void*)&$recv));"
                "ktc_str($buf)"
            } else {
                "ktc_str(\"<$type>\")"
            }
        }
    }
}

// ── StrBuf append helper ─────────────────────────────────────────

internal fun CCodeGen.genSbAppend(sbRef: String, expr: String, type: String): String {
    // Nullable → conditionally append "null" or the value
    if (type.endsWith("?")) {
        val baseT = type.removeSuffix("?")
        if (isValueNullableType(type)) {
            val inner = genSbAppend(sbRef, "($expr).value", baseT).removeSuffix(";")
            return "if (($expr).tag == ktc_SOME) { $inner; } else { ktc_sb_append_cstr($sbRef, \"null\"); }"
        } else {
            val inner = genSbAppend(sbRef, expr, baseT).removeSuffix(";")
            return "if (${expr}\$has) { $inner; } else { ktc_sb_append_cstr($sbRef, \"null\"); }"
        }
    }
    return when (type) {
        "Byte"    -> "ktc_sb_append_byte($sbRef, $expr);"
        "Short"   -> "ktc_sb_append_short($sbRef, $expr);"
        "Int"     -> "ktc_sb_append_int($sbRef, $expr);"
        "Long"    -> "ktc_sb_append_long($sbRef, $expr);"
        "Float"   -> "ktc_sb_append_double($sbRef, (double)$expr);"
        "Double"  -> "ktc_sb_append_double($sbRef, $expr);"
        "Boolean" -> "ktc_sb_append_bool($sbRef, $expr);"
        "Char"    -> "ktc_sb_append_char($sbRef, $expr);"
        "UByte"   -> "ktc_sb_append_ubyte($sbRef, $expr);"
        "UShort"  -> "ktc_sb_append_ushort($sbRef, $expr);"
        "UInt"    -> "ktc_sb_append_uint($sbRef, $expr);"
        "ULong"   -> "ktc_sb_append_ulong($sbRef, $expr);"
        "String"  -> "ktc_sb_append_str($sbRef, $expr);"
        else -> {
            if (classes.containsKey(type) && classes[type]!!.isData) {
                // Copy to a named temp so we can legally take its address
                // (expr may be an rvalue, e.g. a function call return value)
                val vTmp = tmp()
                "{ ${cTypeStr(type)} $vTmp = ($expr); ${pfx(type)}_toString(&$vTmp, $sbRef); }"
            } else {
                val base = type.removeSuffix("*").removeSuffix("?")
                if (classes.containsKey(base) || objects.containsKey(base)) {
                    val buf = tmp()
                    val cName = pfx(base)
                    val selfExpr = if (type.endsWith("*") || type.endsWith("*?")) expr else "&$expr"
                    preStmts += "char ${buf}[64];"
                    preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", ${cName}_hashCode($selfExpr));"
                    "ktc_sb_append_cstr($sbRef, $buf);"
                } else if (interfaces.containsKey(base)) {
                    val buf = tmp()
                    preStmts += "char ${buf}[64];"
                    preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", $expr.vt->hashCode((void*)&$expr));"
                    "ktc_sb_append_cstr($sbRef, $buf);"
                } else {
                    "ktc_sb_append_cstr($sbRef, \"<$type>\");"
                }
            }
        }
    }
}

// ── arrayOf helpers ──────────────────────────────────────────────

internal fun CCodeGen.genArrayOfExpr(name: String, args: List<Arg>, inTypeArg: TypeRef? = null): String { // inTypeArg — explicit type argument from the call site, e.g. arrayOf<Int?>(...)
    // arrayOf<T?>(v1, null, v2) → nullable element array; each element wrapped in Optional struct
    if (name == "arrayOf" && inTypeArg?.nullable == true) {
        val vElemName = typeSubst[inTypeArg.name] ?: inTypeArg.name
        val vOptCType = optCTypeName("${vElemName}?")
        val vVals = args.joinToString(", ") { vArg ->
            if (vArg.expr is NullLit) "($vOptCType){ktc_NONE}"
            else "($vOptCType){ktc_SOME, ${genExpr(vArg.expr)}}"
        }
        val vTmp = tmp()
        preStmts += "$vOptCType ${vTmp}[] = {$vVals};"
        preStmts += "const int32_t ${vTmp}\$len = ${args.size};"
        return vTmp
    }
    val elemType = when (name) {
        "byteArrayOf" -> "ktc_Byte"; "shortArrayOf" -> "ktc_Short"
        "intArrayOf" -> "ktc_Int"; "longArrayOf" -> "ktc_Long"
        "floatArrayOf" -> "ktc_Float"; "doubleArrayOf" -> "ktc_Double"
        "booleanArrayOf" -> "ktc_Bool"; "charArrayOf" -> "ktc_Char"
        "ubyteArrayOf" -> "ktc_UByte"; "ushortArrayOf" -> "ktc_UShort"
        "uintArrayOf" -> "ktc_UInt"; "ulongArrayOf" -> "ktc_ULong"
        "arrayOf" -> {
            if (inTypeArg != null) cTypeStr(typeSubst[inTypeArg.name] ?: inTypeArg.name)
            else { val elemKt = if (args.isNotEmpty()) inferExprType(args[0].expr) ?: "Int" else "Int"; cTypeStr(elemKt) }
        }
        else -> "int32_t"
    }
    val vals = args.joinToString(", ") { genExpr(it.expr) }
    val n = args.size
    val t = tmp()
    preStmts += "$elemType ${t}[] = {$vals};"
    preStmts += "const int32_t ${t}\$len = $n;"
    return t
}

internal fun CCodeGen.genNewArray(elemCType: String, args: List<Arg>): String {
    val size = if (args.isNotEmpty()) genExpr(args[0].expr) else "0"
    val t = tmp()
    preStmts += "$elemCType* $t = ($elemCType*)ktc_alloca(sizeof($elemCType) * (size_t)($size));"
    preStmts += "const int32_t ${t}\$len = $size;"
    return t
}

internal fun CCodeGen.genHeapArrayOfExpr(args: List<Arg>, inTypeArg: TypeRef? = null): String {
    val elemType = if (inTypeArg != null) cTypeStr(typeSubst[inTypeArg.name] ?: inTypeArg.name)
                   else if (args.isNotEmpty()) {
                       val inferred = inferExprType(args[0].expr) ?: "Int"
                       cTypeStr(inferred)
                   } else "ktc_Int"
    val n = args.size
    val t = tmp()
    preStmts += "$elemType* $t = ($elemType*)${tMalloc("sizeof($elemType) * $n")};"
    val vals = args.mapIndexed { i, arg -> "$t[$i] = ${genExpr(arg.expr)};" }.joinToString(" ")
    if (vals.isNotEmpty()) preStmts += vals
    preStmts += "const int32_t ${t}\$len = $n;"
    return t
}

/** Heap-allocated array via calloc — safe to return from functions. */
internal fun CCodeGen.genHeapArray(elemCType: String, args: List<Arg>): String {
    val size = if (args.isNotEmpty()) genExpr(args[0].expr) else "0"
    val t = tmp()
    preStmts += "$elemCType* $t = ($elemCType*)${tCalloc("(size_t)($size)", "sizeof($elemCType)")};"
    preStmts += "const int32_t ${t}\$len = $size;"
    return t
}


// ── fill default arguments ───────────────────────────────────────

internal fun CCodeGen.fillDefaults(args: List<Arg>, params: List<Param>, defaults: Map<String, Expr?>): List<Arg> {
    if (args.size >= params.size) return args
    // Named args: reorder
    val hasNamed = args.any { it.name != null }
    if (hasNamed) {
        val result = params.mapNotNull { p ->
            if (p.isVararg) return@mapNotNull null  // vararg handled by expandCallArgs
            val explicit = args.find { it.name == p.name }
            explicit ?: Arg(p.name, defaults[p.name] ?: IntLit(0))
        }
        return result
    }
    // Positional: fill missing from defaults (skip vararg params)
    val result = args.toMutableList()
    for (i in args.size until params.size) {
        if (params[i].isVararg) continue  // vararg handled by expandCallArgs
        val def = defaults[params[i].name]
        result += Arg(null, def ?: IntLit(0))
    }
    return result
}

// ── l-value generation (for assignments) ─────────────────────────

internal fun CCodeGen.genLValue(e: Expr, method: Boolean): String {
    return when (e) {
        is NameExpr -> {
            if (method && currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
                val ci = classes[currentClass]!!
                val fieldName = if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
                if (selfIsPointer) "\$self->${fieldName}" else "\$self.${fieldName}"
            }
            else if (currentObject.let { vCO -> vCO != null && objects[vCO]?.props?.any { it.first == e.name } == true })
                "${pfx(currentObject!!)}.${e.name}"
            else e.name
        }
        is DotExpr -> {
            if (e.obj is NameExpr && objects.containsKey(e.obj.name))
                "${pfx(e.obj.name)}.${e.name}"
            else if (e.obj is NameExpr && classCompanions.containsKey(e.obj.name)) {
                val vCompanionName = classCompanions[e.obj.name]!!
                "${pfx(vCompanionName)}.${e.name}"
            }
            else {
                val recvType = inferExprType(e.obj)
                val op = if (anyIndirectClassName(recvType) != null) "->" else "."
                "${genExpr(e.obj)}$op${e.name}"
            }
        }
        is IndexExpr -> {
            val objType = inferExprType(e.obj)
            if (objType != null && (objType.endsWith("*") || isArrayType(objType))) {
                "${genExpr(e.obj)}[${genExpr(e.index)}]"
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        }
        else -> genExpr(e)
    }
}

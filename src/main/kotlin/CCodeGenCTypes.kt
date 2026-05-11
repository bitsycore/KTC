package com.bitsycore

/**
 * ── C Type Mapping & Printf Helpers ─────────────────────────────────────
 *
 * Converts the internal Kotlin type representation to C type strings,
 * parameter lists, and printf format/argument pairs. This module is the
 * "leaf" dependency — used by almost every other module but calls no other
 * module itself (besides the core CCodeGen state).
 *
 * Type resolution follows [resolveTypeName] → [resolveTypeNameInner]:
 *   - Primitives: Byte → ktc_Byte, Int → ktc_Int, ...
 *   - Arrays: ByteArray → ktc_Byte*, Array<Vec2> → Vec2Array → game_Vec2*
 *   - Nullable: Int? → ktc_Int_Optional (via [optCTypeName])
 *   - Pointers: @Ptr T → T*
 *   - Function types: (P1,P2)->R → Fun(P1,P2)->R (stored as string key)
 *   - Generics: MutableList<Int> → MutableList_Int (via mangledGenericName)
 *   - Pair/Triple/Tuple: Pair<Int,String> → ktc_Pair_Int_String
 *   - Classes: game_Vec2 → game_Vec2 (via [pfx])
 *
 * ## Main entry points:
 *
 *   [resolveTypeName]        — resolve a TypeRef to internal type string
 *   [resolveTypeNameInner]   — actual resolution logic (after @Ptr strip)
 *   [resolveIfaceName]       — resolve interface TypeRef to concrete name
 *   [cTypeStr]               — internal type → C type name
 *   [cType]                  — TypeRef → C type name (shortcut)
 *   [expandParams]           — function params → C param list string
 *   [expandCtorParams]       — ctor params → C param list string
 *   [expandCallArgs]         — call args → C arg list string
 *
 *   (helpers) [isArrayType], [isRawArrayTypeRef], [isSizedArrayTypeRef],
 *             [arrayElementCType], [arrayElementKtType],
 *             [optCTypeName], [optNone], [optSome],
 *             [isValueNullableType], [isFuncType], [parseFuncType], [cFuncPtrDecl],
 *             [defaultVal], [hasSizeAnnotation], [getSizeAnnotation],
 *             [ensurePairType], [ensureTripleType], [ensureTupleType]
 *
 *   (printf) [printfFmt], [printfArg], [escapeC], [escapeStr]
 *
 * ## State accessed (read-only):
 *   classes, interfaces, enums, classArrayTypes, pairTypeComponents,
 *   tripleTypeComponents, tupleTypeComponents, genericClassDecls, genericIfaceDecls,
 *   allGenericTypeParamNames, typeSubst, symbolPrefix, prefix
 *
 * ## Dependencies:
 *   None (leaf module). Called from all other modules.
 */

// ═══════════════════════════ C type mapping ═══════════════════════

/** Expand ctor params: array → (T* name, int32_t name$len), nullable → OptT name. */
internal fun CCodeGen.expandCtorParams(props: List<Pair<String, TypeRef>>): String {
    val parts = mutableListOf<String>()
    for ((name, type) in props) {
        val resolved = resolveTypeName(type)
        if (isFuncType(resolved)) {
            parts += cFuncPtrDecl(resolved, name)
        } else if (isArrayType(resolved)) {
            if (hasSizeAnnotation(type)) {
                parts += "${cTypeStr(resolved)} $name"
            } else {
                parts += "${cTypeStr(resolved)} $name"
                parts += "int32_t ${name}\$len"
            }
        } else if (type.nullable) {
            parts += "${optCTypeName(resolved)} $name"
        } else {
            parts += "${cType(type)} $name"
        }
    }
    return parts.joinToString(", ")
}

internal fun CCodeGen.cType(t: TypeRef): String {
    val resolved = resolveTypeName(t)
    return cTypeStr(resolved)
}

// Emit alloca+memcpy copies for all variable array params and record them as trampolined.
internal fun CCodeGen.emitArrayParamCopies(params: List<Param>, ind: String) {
    var any = false
    for (p in params) {
        if (p.isVararg) continue
        val resolved = resolveTypeName(p.type)
        val isIndirect = resolved.endsWith("*") || resolved.endsWith("*?")
        // Both nullable and non-nullable array params use ktc_ArrayTrampoline.
        // Non-nullable: copy unconditionally. Nullable: copy only when data != NULL.
        if (isArrayType(resolved) && !hasSizeAnnotation(p.type) && !isIndirect) {
            if (!any) {
                impl.appendLine("${ind}// ── trampoline array start ──")
                any = true
            }
            val elemCType = arrayElementCType(resolved)
            if (p.type.nullable) {
                impl.appendLine("${ind}$elemCType* local$${p.name} = NULL;")
                impl.appendLine("${ind}if (${p.name}.data != NULL) {")
                impl.appendLine("${ind}    local$${p.name} = ($elemCType*)ktc_alloca(sizeof($elemCType) * ${p.name}.size);")
                impl.appendLine("${ind}    memcpy(local$${p.name}, ${p.name}.data, sizeof($elemCType) * ${p.name}.size);")
                impl.appendLine("${ind}}")
            } else {
                impl.appendLine("${ind}$elemCType* local$${p.name} = ($elemCType*)ktc_alloca(sizeof($elemCType) * ${p.name}.size);")
                impl.appendLine("${ind}memcpy(local$${p.name}, ${p.name}.data, sizeof($elemCType) * ${p.name}.size);")
            }
            trampolinedParams += p.name
        }
    }
    if (any) impl.appendLine("${ind}// ── trampoline array end ──")
}

/*
Returns a trailing C comment reflecting Kotlin pointer nullability.
" /* nullable */" for Ptr<T>? (NULL is valid), " /* notnull */" for Ptr<T>.
Returns "" for non-pointer types so the call site is always unconditional.
*/
internal fun CCodeGen.ptrNullComment(inResolved: String): String = when {
    inResolved.endsWith("*?") -> " /* nullable */"
    inResolved.endsWith("*")  -> " /* notnull */"
    else -> ""
}

/** Expand a parameter list: variable array params → ktc_ArrayTrampoline, @Size arrays → T*, nullable params → OptT name. */
internal fun CCodeGen.expandParams(params: List<Param>): String {
    val parts = mutableListOf<String>()
    for (p in params) {
        val resolved = resolveTypeName(p.type)
        if (p.isVararg) {
            parts += "${cTypeStr(resolved)}* ${p.name}"
            parts += "ktc_Int ${p.name}\$len"
        } else if (isFuncType(resolved)) {
            parts += cFuncPtrDecl(resolved, p.name)
        } else if (resolved.endsWith("*")) {
            // @Ptr/@Heap/@Value-annotated type: raw pointer
            // Nullability lives in p.type.nullable
            val vNullComment = if (p.type.nullable) " /* nullable */" else " /* notnull */"
            parts += "${cTypeStr(resolved)} ${p.name}$vNullComment"
            if (isArrayType(resolved)) parts += "int32_t ${p.name}\$len"
        } else if (isArrayType(resolved)) {
            if (hasSizeAnnotation(p.type)) {
                // @Size(N) fixed array — passed as raw pointer (size known at compile time)
                parts += "${cTypeStr(resolved)} ${p.name}"
            } else {
                // Both nullable and non-nullable arrays use ktc_ArrayTrampoline for value semantics.
                // Nullable: data == NULL means the array argument was null.
                val vNullComment = if (p.type.nullable) " /* nullable */" else ""
                parts += "ktc_ArrayTrampoline ${p.name}$vNullComment /* ${arrayElementCType(resolved)}[] */"
            }
        } else if (p.type.nullable) {
            val vNullComment = if (resolved == "Any") " /* nullable */" else ""
            parts += "${optCTypeName(resolved)} ${p.name}$vNullComment"
        } else {
            parts += "${cType(p.type)} ${p.name}"
        }
    }
    return parts.joinToString(", ")
}

internal fun CCodeGen.cTypeStr(t: String): String = when {
    // Function pointer type — can't be expressed as a simple type, use void* as fallback
    // (actual declarations use cFuncPtrDecl which embeds the variable name)
    t.startsWith("Fun(") -> "void*"
    // Strip nullable marker — handled by companion $has variable or Optional
    t.endsWith("?") -> cTypeStr(t.dropLast(1))
    t == "Byte"    -> "ktc_Byte"
    t == "Short"   -> "ktc_Short"
    t == "Int"     -> "ktc_Int"
    t == "Long"    -> "ktc_Long"
    t == "Float"   -> "ktc_Float"
    t == "Double"  -> "ktc_Double"
    t == "Boolean" -> "ktc_Bool"
    t == "Char"    -> "ktc_Char"
    t == "UByte"   -> "ktc_UByte"
    t == "UShort"  -> "ktc_UShort"
    t == "UInt"    -> "ktc_UInt"
    t == "ULong"   -> "ktc_ULong"
    t == "String"  -> "ktc_String"
    t == "Any"     -> "ktc_Any"
    t == "Unit"    -> "void"
    t == "void"    -> "void"
    t == "Nothing" -> "void"
    t == "ByteArray"    -> "ktc_Byte*"
    t == "ShortArray"   -> "ktc_Short*"
    t == "IntArray"     -> "ktc_Int*"
    t == "LongArray"    -> "ktc_Long*"
    t == "FloatArray"   -> "ktc_Float*"
    t == "DoubleArray"  -> "ktc_Double*"
    t == "BooleanArray" -> "ktc_Bool*"
    t == "CharArray"    -> "ktc_Char*"
    t == "UByteArray"   -> "ktc_UByte*"
    t == "UShortArray"  -> "ktc_UShort*"
    t == "UIntArray"    -> "ktc_UInt*"
    t == "ULongArray"   -> "ktc_ULong*"
    t == "StringArray"  -> "ktc_String*"
    t == "ByteOptArray"    -> "ktc_Byte_Optional*"
    t == "ShortOptArray"   -> "ktc_Short_Optional*"
    t == "IntOptArray"     -> "ktc_Int_Optional*"
    t == "LongOptArray"    -> "ktc_Long_Optional*"
    t == "FloatOptArray"   -> "ktc_Float_Optional*"
    t == "DoubleOptArray"  -> "ktc_Double_Optional*"
    t == "BooleanOptArray" -> "ktc_Bool_Optional*"
    t == "CharOptArray"    -> "ktc_Char_Optional*"
    t == "UByteOptArray"   -> "ktc_UByte_Optional*"
    t == "UShortOptArray"  -> "ktc_UShort_Optional*"
    t == "UIntOptArray"    -> "ktc_UInt_Optional*"
    t == "ULongOptArray"   -> "ktc_ULong_Optional*"
    t == "StringOptArray"  -> "ktc_String_Optional*"
    // Pointer type: "Int*" → "int32_t*", "Vec2*" → "game_Vec2*"
    // For array types, don't add another * level — Array is already a pointer
    t.endsWith("*") -> {
        val base = t.dropLast(1)
        if (base.endsWith("Array")) cTypeStr(base) else "${cTypeStr(base)}*"
    }
    else -> {
        // Class array types: "Vec2Array" → "game_Vec2*" (pointer to element)
        if (t.endsWith("Array") && t.length > 5) {
            val elem = t.removeSuffix("Array")
            if (classArrayTypes.contains(elem) || enums.containsKey(elem)) return "${pfx(elem)}*"
            if (elem.startsWith("Pair_")) return "ktc_${elem}*"
            if (elem.startsWith("Triple_")) return "ktc_${elem}*"
            if (elem.startsWith("Tuple_")) return "ktc_${elem}*"
        }
        // Pair types: "Pair_Int_String" → "ktc_Pair_Int_String"
        if (t.startsWith("Pair_")) return "ktc_$t"
        if (t.startsWith("Triple_")) return "ktc_$t"
        if (t.startsWith("Tuple_")) return "ktc_$t"
        pfx(t)   // class/enum/object type
    }
}

internal fun CCodeGen.ensurePairType(a: String, b: String) {
    val key = "${a}_${b}"
    if (key !in emittedPairTypes) {
        emittedPairTypes.add(key)
        hdr.appendLine("typedef struct { ${cTypeStr(a)} first; ${cTypeStr(b)} second; } ktc_Pair_${a}_${b};")
    }
}

internal fun CCodeGen.ensureTripleType(a: String, b: String, c: String) {
    val key = "${a}_${b}_${c}"
    if (key !in emittedTripleTypes) {
        emittedTripleTypes.add(key)
        hdr.appendLine("typedef struct { ${cTypeStr(a)} first; ${cTypeStr(b)} second; ${cTypeStr(c)} third; } ktc_Triple_${a}_${b}_${c};")
    }
}

internal fun CCodeGen.ensureTupleType(types: List<String>) {
    val key = types.joinToString("_")
    if (key !in emittedTupleTypes) {
        emittedTupleTypes.add(key)
        val fields = types.mapIndexed { i, t -> "${cTypeStr(t)} component$i;" }.joinToString(" ")
        hdr.appendLine("typedef struct { $fields } ktc_Tuple_${key};")
    }
}

internal fun CCodeGen.resolveTypeName(t: TypeRef?): String {
    if (t == null) return "Int"
    val substituted = substituteTypeParams(t)
    // RawArray<T> → T* (raw pointer, no $len companion)
    if (substituted.name == "RawArray" && substituted.typeArgs.isNotEmpty()) {
        return resolveTypeName(substituted.typeArgs[0]) + "*"
    }
    val base = resolveTypeNameInner(substituted)
    val isPtr = substituted.annotations.any { it.name == "Ptr" }
    return if (isPtr) "${base}*" else base
}

internal fun CCodeGen.typeRefToStr(t: TypeRef?): String {
    if (t == null) return "Unit"
    val ann = if (t.annotations.any { it.name == "Ptr" }) "@Ptr " else ""
    val args = if (t.typeArgs.isNotEmpty()) "<${t.typeArgs.joinToString(", ") { typeRefToStr(it) }}>" else ""
    val nullable = if (t.nullable) "?" else ""
    return "$ann${t.name}$args$nullable"
}

/** Recursively substitute type parameters throughout a TypeRef tree. */
internal fun CCodeGen.substituteTypeParams(t: TypeRef): TypeRef {
    if (typeSubst.isEmpty()) return t
    val newName = typeSubst[t.name] ?: t.name
    val newTypeArgs = t.typeArgs.map { substituteTypeParams(it) }
    val newFuncParams = t.funcParams?.map { substituteTypeParams(it) }
    val newFuncReturn = t.funcReturn?.let { substituteTypeParams(it) }
    val newFuncReceiver = t.funcReceiver?.let { substituteTypeParams(it) }
    return if (newName != t.name || newTypeArgs != t.typeArgs || newFuncParams != t.funcParams || newFuncReturn != t.funcReturn || newFuncReceiver != t.funcReceiver) {
        TypeRef(newName, t.nullable, newTypeArgs, newFuncParams, newFuncReturn, newFuncReceiver, t.annotations)
    } else t
}

internal fun CCodeGen.resolveTypeNameInner(t: TypeRef): String {
    // Function type: (P1, P2) -> R → "Fun(P1,P2)->R"
    // Receiver function type: T.(P1) -> R → "Fun(T|P1)->R"
    if (t.funcParams != null) {
        val receiver = t.funcReceiver?.let { resolveTypeName(it) + "|" } ?: ""
        val params = t.funcParams.joinToString(",") { resolveTypeName(it) }
        val ret = resolveTypeName(t.funcReturn)
        return "Fun($receiver$params)->$ret"
    }
    // User-defined generic class takes priority over built-in aliases
    if (t.typeArgs.isNotEmpty() && classes.containsKey(t.name) && classes[t.name]!!.isGeneric) {
        val typeArgNames = t.typeArgs.map { it.name }
        // Don't register as concrete instantiation if any type arg is still a type parameter
        if (typeArgNames.any { it in allGenericTypeParamNames }) {
            return mangledGenericName(t.name, typeArgNames)
        }
        return recordGenericInstantiation(t.name, typeArgNames)
    }
    // User-defined generic interface takes priority over built-in aliases
    if (t.typeArgs.isNotEmpty() && genericIfaceDecls.containsKey(t.name)) {
        val typeArgNames = t.typeArgs.map { resolveTypeName(it) }
        return mangledGenericName(t.name, typeArgNames)
    }
    // Intrinsic Pair<A,B> — only when no user-defined class/interface named Pair
    if (t.name == "Pair" && t.typeArgs.size == 2
        && !classes.containsKey("Pair") && !genericClassDecls.containsKey("Pair") && !genericIfaceDecls.containsKey("Pair")) {
        val a = resolveTypeName(t.typeArgs[0])
        val b = resolveTypeName(t.typeArgs[1])
        pairTypes.add(Pair(a, b))
        pairTypeComponents["Pair_${a}_${b}"] = Pair(a, b)
        ensurePairType(a, b)
        return "Pair_${a}_${b}"
    }
    // Intrinsic Triple<A,B,C>
    if (t.name == "Triple" && t.typeArgs.size == 3
        && !classes.containsKey("Triple") && !genericClassDecls.containsKey("Triple") && !genericIfaceDecls.containsKey("Triple")) {
        val a = resolveTypeName(t.typeArgs[0])
        val b = resolveTypeName(t.typeArgs[1])
        val c = resolveTypeName(t.typeArgs[2])
        tripleTypeComponents["Triple_${a}_${b}_${c}"] = Triple(a, b, c)
        ensureTripleType(a, b, c)
        return "Triple_${a}_${b}_${c}"
    }
    // Intrinsic Tuple<...> — indefinite arity
    if (t.name == "Tuple" && t.typeArgs.isNotEmpty()
        && !classes.containsKey("Tuple") && !genericClassDecls.containsKey("Tuple") && !genericIfaceDecls.containsKey("Tuple")) {
        val types = t.typeArgs.map { resolveTypeName(it) }
        val key = "Tuple_${types.joinToString("_")}"
        tupleTypeComponents[key] = types
        ensureTupleType(types)
        return key
    }
    // Intrinsic StringBuffer → ktc_StrBuf (only when no user-defined class named StringBuffer)
    if (t.name == "StringBuffer" && t.typeArgs.isEmpty()
        && !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")) {
        return "ktc_StrBuf"
    }
    if (t.name == "Array" && t.typeArgs.isNotEmpty()) {
        val elemRef     = t.typeArgs[0] // element TypeRef
        val elem        = elemRef.name  // element Kotlin type name
        val nullableElem = elemRef.nullable // true for Array<T?>
        if (nullableElem) {
            return when (elem) {
                "Byte"    -> "ByteOptArray"
                "Short"   -> "ShortOptArray"
                "Int"     -> "IntOptArray"
                "Long"    -> "LongOptArray"
                "Float"   -> "FloatOptArray"
                "Double"  -> "DoubleOptArray"
                "Boolean" -> "BooleanOptArray"
                "Char"    -> "CharOptArray"
                "UByte"   -> "UByteOptArray"
                "UShort"  -> "UShortOptArray"
                "UInt"    -> "UIntOptArray"
                "ULong"   -> "ULongOptArray"
                "String"  -> "StringOptArray"
                else      -> { classArrayTypes.add(elem); "${elem}OptArray" }
            }
        }
        return when (elem) {
            "Byte"    -> "ByteArray"
            "Short"   -> "ShortArray"
            "Int"     -> "IntArray"
            "Long"    -> "LongArray"
            "Float"   -> "FloatArray"
            "Double"  -> "DoubleArray"
            "Boolean" -> "BooleanArray"
            "Char"    -> "CharArray"
            "UByte"   -> "UByteArray"
            "UShort"  -> "UShortArray"
            "UInt"    -> "UIntArray"
            "ULong"   -> "ULongArray"
            "String"  -> "StringArray"
            else      -> { classArrayTypes.add(elem); "${elem}Array" }
        }
    }
    if (t.name == "RawArray" && t.typeArgs.isNotEmpty()) {
        return resolveTypeName(t.typeArgs[0]) + "*"
    }
    // Reject unknown type constructors with args (e.g. Heap<T>, Value<T>)
    if (t.typeArgs.isNotEmpty()
        && !classes.containsKey(t.name)
        && !interfaces.containsKey(t.name)
        && !genericClassDecls.containsKey(t.name)
        && !genericIfaceDecls.containsKey(t.name)
        && t.name !in setOf("Array", "Pair", "Triple", "Tuple", "RawArray")) {
        codegenError("Unknown type '${t.name}<...>'. Use @Ptr for pointer types.")
    }
    return t.name
}

internal fun CCodeGen.defaultVal(t: String): String = when {
    t == "Int" || t == "Long" -> "0"
    t == "Float"  -> "0.0f"
    t == "Double" -> "0.0"
    t == "Boolean" -> "false"
    t == "Char"   -> "'\\0'"
    t == "String" -> "ktc_str(\"\")"
    t.endsWith("*") || t.endsWith("*?") -> "NULL"
    else -> {
        // Struct default — needs cast for validity as function argument
        val ct = cTypeStr(t.removeSuffix("?"))
        "($ct){0}"
    }
}

/** True if the internal type name represents an array (IntArray, LongArray, Vec2Array, etc.). Strips pointer/nullable suffixes. */
internal fun CCodeGen.isArrayType(t: String): Boolean {
    val base = t.removeSuffix("?").removeSuffix("*")
    return base.endsWith("Array")
}

/** True if the TypeRef is a raw Array<T> or primitive array type (not wrapped in Heap, Ptr, or Value). */
internal fun CCodeGen.isRawArrayTypeRef(t: TypeRef): Boolean {
    if (hasSizeAnnotation(t)) return false
    if (t.annotations.any { it.name == "Ptr" }) return false
    if (t.name == "Array") return true
    if (t.name in setOf(
        "ByteArray", "ShortArray", "IntArray", "LongArray",
        "FloatArray", "DoubleArray", "BooleanArray", "CharArray",
        "UByteArray", "UShortArray", "UIntArray", "ULongArray",
        "StringArray"
    )) return true
    return false
}

internal fun CCodeGen.hasSizeAnnotation(t: TypeRef): Boolean = t.annotations.any { it.name == "Size" }

internal fun CCodeGen.getSizeAnnotation(t: TypeRef): Int? {
    val ann = t.annotations.find { it.name == "Size" }
    if (ann != null && ann.args.isNotEmpty()) {
        val arg = ann.args[0]
        if (arg is IntLit) return arg.value.toInt()
        if (arg is LongLit) return arg.value.toInt()
    }
    return null
}

/** True if the TypeRef is an array type WITH @Size(N) annotation (allowed fixed-size array). */
internal fun CCodeGen.isSizedArrayTypeRef(t: TypeRef): Boolean {
    if (!hasSizeAnnotation(t)) return false
    if (t.name == "Array") return true
    if (t.name in setOf(
        "ByteArray", "ShortArray", "IntArray", "LongArray",
        "FloatArray", "DoubleArray", "BooleanArray", "CharArray",
        "UByteArray", "UShortArray", "UIntArray", "ULongArray",
        "StringArray"
    )) return true
    return false
}

/** Check if a call expression returns a sized array (@Size(N) Array<T>). */
internal fun CCodeGen.isSizedArrayReturningCall(e: Expr?): Boolean {
    if (e !is CallExpr) return false
    val name = (e.callee as? NameExpr)?.name ?: return false
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && genFun.returnType != null && isSizedArrayTypeRef(genFun.returnType))
        return true
    val sig = funSigs[name] ?: return false
    return sig.returnType != null && isSizedArrayTypeRef(sig.returnType)
}

/** Get the @Size value from a call to a sized-array-returning function. */
internal fun CCodeGen.getSizedArrayReturnSize(e: CallExpr): Int? {
    val name = (e.callee as? NameExpr)?.name ?: return null
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && genFun.returnType != null) return getSizeAnnotation(genFun.returnType)
    val sig = funSigs[name] ?: return null
    if (sig.returnType != null) return getSizeAnnotation(sig.returnType)
    return null
}

/** Get the element C type from a call to a sized-array-returning function. */
internal fun CCodeGen.getSizedArrayReturnElemType(e: CallExpr): String? {
    val name = (e.callee as? NameExpr)?.name ?: return null
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null && genFun.returnType != null) return arrayElementCType(resolveTypeName(genFun.returnType))
    val sig = funSigs[name] ?: return null
    if (sig.returnType != null) return arrayElementCType(resolveTypeName(sig.returnType))
    return null
}

internal fun CCodeGen.arrayElementCType(arrType: String?): String = when (arrType) {
    "ByteArray"    -> "ktc_Byte"
    "ShortArray"   -> "ktc_Short"
    "IntArray"     -> "ktc_Int"
    "LongArray"    -> "ktc_Long"
    "FloatArray"   -> "ktc_Float"
    "DoubleArray"  -> "ktc_Double"
    "BooleanArray" -> "ktc_Bool"
    "CharArray"    -> "ktc_Char"
    "UByteArray"   -> "ktc_UByte"
    "UShortArray"  -> "ktc_UShort"
    "UIntArray"    -> "ktc_UInt"
    "ULongArray"   -> "ktc_ULong"
    "StringArray"  -> "ktc_String"
    "ByteOptArray"    -> "ktc_Byte_Optional"
    "ShortOptArray"   -> "ktc_Short_Optional"
    "IntOptArray"     -> "ktc_Int_Optional"
    "LongOptArray"    -> "ktc_Long_Optional"
    "FloatOptArray"   -> "ktc_Float_Optional"
    "DoubleOptArray"  -> "ktc_Double_Optional"
    "BooleanOptArray" -> "ktc_Bool_Optional"
    "CharOptArray"    -> "ktc_Char_Optional"
    "UByteOptArray"   -> "ktc_UByte_Optional"
    "UShortOptArray"  -> "ktc_UShort_Optional"
    "UIntOptArray"    -> "ktc_UInt_Optional"
    "ULongOptArray"   -> "ktc_ULong_Optional"
    "StringOptArray"  -> "ktc_String_Optional"
    else -> {
        if (arrType != null) {
            // Class array: "Vec2Array" → element type "game_Vec2"
            if (arrType.endsWith("Array") && arrType.length > 5) {
                val elem = arrType.removeSuffix("Array")
                if (classArrayTypes.contains(elem) || classes.containsKey(elem) || enums.containsKey(elem)) return pfx(elem)
                if (elem.startsWith("Pair_")) return cTypeStr(elem)
            }
            // Nullable-element class array: "Vec2OptArray" → "pkg_Vec2_Optional"
            if (arrType.endsWith("OptArray") && arrType.length > 8) {
                val elem = arrType.removeSuffix("OptArray")
                if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return "${pfx(elem)}_Optional"
            }
        }
        "ktc_Int"
    }
}

internal fun CCodeGen.arrayElementKtType(arrType: String?): String = when (arrType) {
    "ByteArray"    -> "Byte"
    "ShortArray"   -> "Short"
    "IntArray"     -> "Int"
    "LongArray"    -> "Long"
    "FloatArray"   -> "Float"
    "DoubleArray"  -> "Double"
    "BooleanArray" -> "Boolean"
    "CharArray"    -> "Char"
    "UByteArray"   -> "UByte"
    "UShortArray"  -> "UShort"
    "UIntArray"    -> "UInt"
    "ULongArray"   -> "ULong"
    "StringArray"  -> "String"
    "ByteOptArray"    -> "Byte?"
    "ShortOptArray"   -> "Short?"
    "IntOptArray"     -> "Int?"
    "LongOptArray"    -> "Long?"
    "FloatOptArray"   -> "Float?"
    "DoubleOptArray"  -> "Double?"
    "BooleanOptArray" -> "Boolean?"
    "CharOptArray"    -> "Char?"
    "UByteOptArray"   -> "UByte?"
    "UShortOptArray"  -> "UShort?"
    "UIntOptArray"    -> "UInt?"
    "ULongOptArray"   -> "ULong?"
    "StringOptArray"  -> "String?"
    else -> {
        if (arrType != null) {
            // Class array: "Vec2Array" → element Kotlin type "Vec2"
            if (arrType.endsWith("Array") && arrType.length > 5) {
                val elem = arrType.removeSuffix("Array")
                if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return elem
                if (elem.startsWith("Pair_")) return elem
            }
            // Nullable-element class array: "Vec2OptArray" → "Vec2?"
            if (arrType.endsWith("OptArray") && arrType.length > 8) {
                val elem = arrType.removeSuffix("OptArray")
                if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return "${elem}?"
            }
        }
        "Int"
    }
}

// ═══════════════════════════ printf helpers ═══════════════════════

internal fun CCodeGen.printfFmt(t: String): String = when {
    t == "Byte"    -> "%\" PRId8 \""
    t == "Short"   -> "%\" PRId16 \""
    t == "Int"     -> "%\" PRId32 \""
    t == "Long"    -> "%\" PRId64 \""
    t == "Float"   -> "%f"
    t == "Double"  -> "%f"
    t == "Boolean" -> "%s"
    t == "Char"    -> "%c"
    t == "UByte"   -> "%\" PRIu8 \""
    t == "UShort"  -> "%\" PRIu16 \""
    t == "UInt"    -> "%\" PRIu32 \""
    t == "ULong"   -> "%\" PRIu64 \""
    t == "String"  -> "%.*s"
    t.endsWith("*") || t.endsWith("*?") -> "%p"
    t in enums    -> "%.*s"
    else           -> "%.*s"       // assume toString → ktc_String
}

internal fun CCodeGen.printfArg(expr: String, t: String): String = when {
    t == "Boolean" -> "($expr) ? \"true\" : \"false\""
    t == "String"  -> "(int)($expr).len, ($expr).ptr"
    t in enums -> {
        val cName = pfx(t)
        "(int)${cName}_names[($expr)].len, ${cName}_names[($expr)].ptr"
    }
    else -> expr
}

internal fun CCodeGen.escapeC(c: Char): String = when (c) {
    '\'' -> "\\'"
    '\\' -> "\\\\"
    '\n' -> "\\n"
    '\t' -> "\\t"
    '\r' -> "\\r"
    '\u0000' -> "\\0"
    else -> c.toString()
}

internal fun CCodeGen.escapeStr(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\t", "\\t")
    .replace("\r", "\\r")

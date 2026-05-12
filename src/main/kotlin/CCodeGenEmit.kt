package com.bitsycore

/**
 * ── Declaration Emission ────────────────────────────────────────────────
 *
 * Emits C structs, function signatures, and bodies for all top-level
 * Kotlin declarations: classes, interfaces, enums, objects, properties,
 * and functions (both regular and generic/monomorphized).
 *
 * ## Main entry points:
 *
 *   [emitClass]                  — class/data class struct + ctor + methods
 *   [emitGenericClass]           — monomorphized generic class instantiation
 *   [emitMethod]                 — method body with scope + defer handling
 *   [emitFun]                    — top-level fun (including main)
 *   [emitExtensionFun]           — fun Type.method() extension
 *   [emitGenericFunInstantiations] — monomorphized generic fun
 *   [emitStarExtFunInstantiations] — star-projection extension expansion
 *   [emitEnum] / [emitEnumValuesData] — enum typedef + values/valueOf
 *   [emitObject]                 — singleton object with lazy init
 *   [emitInterface]              — interface vtable struct
 *   [emitInterfaceVtablesForClass] — class → interface vtable + _as_ wrapper
 *   [emitTopProp]                — top-level property
 *
 * ## State accessed:
 *   classes, interfaces, enums, objects, funSigs, extensionFuns, classInterfaces,
 *   interfaceImplementors, typeIds, genericTypeBindings, hdr/impl/implFwd,
 *   prefix, pfx(), deferredHdrLines, classCompanions, objectsWithDispose
 *
 * ## Dependencies:
 *   Calls into [CCodeGenExpr]  (genExpr, genSbAppend)
 *   Calls into [CCodeGenStmts] (emitStmt)
 *   Calls into [CCodeGenInfer] (inferBlockType)
 *   Calls into [CCodeGenCTypes] (cType, cTypeStr, resolveTypeName, expandParams, ...)
 */

// ═══════════════════════════ Emit declarations ════════════════════

// ── class / data class ───────────────────────────────────────────

internal fun CCodeGen.emitClass(d: ClassDecl) {
    val cName = pfx(d.name)
    val ci = classes[d.name]!!

    val kind = if (d.isData) "data class" else "class"
    impl.appendLine("// ══ $kind ${d.name} ($currentSourceFile) ══")
    impl.appendLine()

    // --- header: typedef struct ---
    hdr.appendLine("// ══ $kind ${d.name} ($currentSourceFile) ══")
    hdr.appendLine("#define ${cName}_TYPE_ID ${typeIds[d.name]!!}")
    hdr.appendLine("typedef struct {")
    hdr.appendLine("    ktc_Int __type_id;")
    for ((name, type) in ci.props) {
        val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
        // RawArray<T> field → T* (raw pointer, no $len)
        val isRawArray = type.name == "RawArray" && type.typeArgs.isNotEmpty()
        val resolved = if (isRawArray) resolveTypeName(type.typeArgs[0]) + "*" else resolveTypeName(type)
        val mutComment = if (ci.isValProp(name)) "/*VAL*/ " else "/*VAR*/ "
        if (isFuncType(resolved)) {
            hdr.appendLine("    $mutComment${cFuncPtrDecl(resolved, fieldName)};")
        } else if (isArrayType(resolved)) {
            val sizeAnn = getSizeAnnotation(type)
            if (sizeAnn != null) {
                val elemType = arrayElementCType(resolved)
                hdr.appendLine("    $mutComment$elemType $fieldName[${sizeAnn}];")
            } else {
                hdr.appendLine("    $mutComment${cTypeStr(resolved)} $fieldName;")
                hdr.appendLine("    int32_t ${fieldName}\$len;")
            }
        } else if (type.nullable) {
            hdr.appendLine("    $mutComment${optCTypeName(resolved)} $fieldName;")
        } else {
            hdr.appendLine("    $mutComment${cType(type)} $fieldName;${ptrNullComment(resolved)}")
        }
    }
    hdr.appendLine("} $cName;")
    hdr.appendLine("KTC_OPTIONAL($cName);")
    hdr.appendLine()

    // --- constructor (only takes ctor params, initializes all fields) ---
    val allCtorParams = ci.ctorProps + ci.ctorPlainParams
    val paramStr = expandCtorParams(allCtorParams)
    val paramDecl = paramStr.ifEmpty { "void" }
    hdr.appendLine("$cName ${cName}_primaryConstructor($paramDecl);")
    impl.appendLine("$cName ${cName}_primaryConstructor($paramDecl) {")
    if (ci.bodyProps.isEmpty() && ci.ctorPlainParams.isEmpty() && ci.ctorProps.none { isArrayType(resolveTypeName(it.second)) || it.second.nullable }) {
        impl.appendLine("    return ($cName){${cName}_TYPE_ID, ${ci.ctorProps.joinToString(", ") { it.first }}};")
    } else {
        impl.appendLine("    $cName \$self = {0};")
        impl.appendLine("    \$self.__type_id = ${cName}_TYPE_ID;")
        for ((name, type) in ci.ctorProps) {
            val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
            val resolved = resolveTypeName(type)
            val sizeAnn = getSizeAnnotation(type)
            if (sizeAnn != null) {
                val elemType = arrayElementCType(resolved)
                impl.appendLine("    memcpy(\$self.$fieldName, $name, $sizeAnn * sizeof($elemType));")
            } else if (isArrayType(resolved)) {
                impl.appendLine("    \$self.$fieldName = $name;")
                impl.appendLine("    \$self.${fieldName}\$len = ${name}\$len;")
            } else if (type.nullable) {
                impl.appendLine("    \$self.$fieldName = $name;")
            } else {
                impl.appendLine("    \$self.$fieldName = $name;")
            }
        }
        for (bp in ci.bodyProps) {
            if (bp.init != null) {
                if (bp.line > 0) currentStmtLine = bp.line
                heapAllocTargetType = bp.type
                val expr = genExpr(bp.init)
                heapAllocTargetType = null
                flushPreStmts("    ")
                val bodyFieldName = if (bp.isPrivate) "PRIV_${bp.name}" else bp.name
                impl.appendLine("    \$self.$bodyFieldName = $expr;")
                emitBodyPropLenIfArray(bp)
            }
        }
        impl.appendLine("    return \$self;")
    }
    impl.appendLine("}")
    impl.appendLine()

    // --- data class extras: equals, toString ---
    if (d.isData) {
        emitDataClassEquals(cName, ci)
        emitDataClassToString(d.name, cName, ci)
    }

    // --- methods ---
    currentClass = d.name
    selfIsPointer = true
    pushScope()
    for ((name, type) in ci.props) {
        defineVar(name, resolveTypeName(type))
        if (!ci.isValProp(name)) markMutable(name)
    }
    // Build set of interface method names to suppress their hdr here (emitted under implements section)
    val ifaceMethodNames = d.superInterfaces.flatMap { ifaceRef ->
        val ifaceName = resolveIfaceName(ifaceRef)
        val iface = interfaces[ifaceName] ?: return@flatMap emptyList()
        (collectAllIfaceMethods(iface).map { it.name } + collectAllIfaceProperties(iface).map { it.name }).toSet()
    }.toSet()

    for (m in d.members) {
        if (m is FunDecl && m.receiver == null) {
            emitMethod(d.name, m, suppressHdr = m.name in ifaceMethodNames)
        }
    }
    // Implicit no-op dispose if not overridden
    if (d.members.none { it is FunDecl && it.name == "dispose" }) {
        hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
    }
    // Implicit hashCode — default returns __type_id, data classes hash all fields
    if (d.members.none { it is FunDecl && it.name == "hashCode" }) {
        hdr.appendLine("ktc_Int ${cName}_hashCode($cName* \$self);")
        impl.appendLine("ktc_Int ${cName}_hashCode($cName* \$self) {")
        if (d.isData && ci.props.isNotEmpty()) {
            impl.appendLine("    ktc_Int h = 0;")
            for ((name, type) in ci.props) {
                val resolved = resolveTypeName(type)
                val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
                val hashExpr = if (type.nullable && !resolved.endsWith("*")) {
                    val valueExpr = "\$self->$fieldName"
                    "(${valueExpr}.tag == ktc_SOME ? ${hashFieldExpr(resolved, "${valueExpr}.value")} : 0)"
                } else {
                    hashFieldExpr(resolved, "\$self->$fieldName")
                }
                impl.appendLine("    h = h * 31 + $hashExpr;")
            }
            impl.appendLine("    return h;")
        } else {
            impl.appendLine("    uintptr_t x = (uintptr_t)\$self;")
            impl.appendLine("    return (ktc_Int)(x ^ (x >> 32));")
        }
        impl.appendLine("}")
        impl.appendLine()
    }
    popScope()
    currentClass = null

    // Secondary constructors
    for (sctor in d.secondaryCtors) {
        emitSecondaryCtor(d.name, cName, sctor)
    }
}

/** Generate a secondary constructor function name: ClassName_constructorWithType1_Type2 */
internal fun CCodeGen.secondaryCtorName(cClass: String, params: List<Param>): String {
    if (params.isEmpty()) return "${cClass}_emptyConstructor"
    val types = params.map { resolveTypeName(it.type).removeSuffix("*") }
    return "${cClass}_constructorWith${types.joinToString("_")}"
}

/** Emit a secondary constructor that delegates to the primary constructor. */
internal fun CCodeGen.emitSecondaryCtor(className: String, cClass: String, sctor: SecondaryCtor) {
    val ctorName = secondaryCtorName(cClass, sctor.params)
    val retResolved = cClass.takeWhile { it != '_' || cClass.indexOf('_') < 0 }.let { "" }  // just use void-like return
    val extraParams = expandParams(sctor.params)
    val allP = if (extraParams.isNotEmpty()) "$cClass* \$self, $extraParams" else "$cClass* \$self"

    hdr.appendLine("$cClass $ctorName($extraParams);")
    impl.appendLine("$cClass $ctorName($extraParams) {")

    // Generate call to primary constructor for delegation
    val delegateArgs = sctor.delegation.args.joinToString(", ") { a ->
        genExpr(a.expr)
    }
    impl.appendLine("    $cClass \$self = ${cClass}_primaryConstructor($delegateArgs);")

    // Emit body using $self as the implicit receiver
    pushScope()
    currentClass = className
    selfIsPointer = true
    for (p in sctor.params) {
        val resolved = resolveTypeName(p.type)
        defineVar(p.name, resolved)
    }
    val ci = classes[className]
    if (ci != null) for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))

    for (s in sctor.body.stmts) emitStmt(s, "    ", true)
    popScope()
    currentClass = null

    impl.appendLine("    return \$self;")
    impl.appendLine("}")
    impl.appendLine()
}

/**
 * Emit a concrete instantiation of a generic class.
 * typeSubst must be set before calling (e.g. {T → Int}).
 * [mangledName] is the concrete class name (e.g. "MyList_Int").
 */
internal fun CCodeGen.emitGenericClass(templateDecl: ClassDecl, mangledName: String) {
    val cName = pfx(mangledName)
    val ci = classes[mangledName]!!

    val kind = if (templateDecl.isData) "data class" else "class"
    val concreteTypes = mangledName.removePrefix(templateDecl.name).removePrefix("_").replace("_", ", ")
    impl.appendLine("// ══ $kind ${templateDecl.name}<$concreteTypes> ($currentSourceFile) ══")
    impl.appendLine()

    // --- header: struct definition (forward typedef already emitted) ---
    hdr.appendLine("// ══ $kind ${templateDecl.name}<$concreteTypes> ($currentSourceFile) ══")
    hdr.appendLine("#define ${cName}_TYPE_ID ${typeIds[ci.name]!!}")
    hdr.appendLine("struct $cName {")
    hdr.appendLine("    ktc_Int __type_id;")
    for ((name, type) in ci.props) {
        val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
        // RawArray<T> field → T* (raw pointer, no $len)
        val isRawArray = type.name == "RawArray" && type.typeArgs.isNotEmpty()
        val resolved = if (isRawArray) resolveTypeName(type.typeArgs[0]) + "*" else resolveTypeName(type)
        val mutComment = if (ci.isValProp(name)) "/*VAL*/ " else "/*VAR*/ "
        if (isFuncType(resolved)) {
            hdr.appendLine("    $mutComment${cFuncPtrDecl(resolved, fieldName)};")
        } else if (isArrayType(resolved)) {
            val sizeAnn = getSizeAnnotation(type)
            if (sizeAnn != null) {
                val elemType = arrayElementCType(resolved)
                hdr.appendLine("    $mutComment$elemType $fieldName[${sizeAnn}];")
            } else {
                hdr.appendLine("    $mutComment${cTypeStr(resolved)} $fieldName;")
                hdr.appendLine("    int32_t ${fieldName}\$len;")
            }
        } else if (type.nullable) {
            hdr.appendLine("    $mutComment${optCTypeName(resolved)} $fieldName;")
        } else {
            hdr.appendLine("    $mutComment${cType(type)} $fieldName;${ptrNullComment(resolved)}")
        }
    }
    hdr.appendLine("};")
    hdr.appendLine("KTC_OPTIONAL($cName);")
    hdr.appendLine()

    // --- constructor ---
    val allCtorParams = ci.ctorProps + ci.ctorPlainParams
    val paramStr = expandCtorParams(allCtorParams)
    val paramDecl = paramStr.ifEmpty { "void" }
    hdr.appendLine("$cName ${cName}_primaryConstructor($paramDecl);")
    impl.appendLine("$cName ${cName}_primaryConstructor($paramDecl) {")
    if (ci.bodyProps.isEmpty() && ci.ctorPlainParams.isEmpty() && ci.ctorProps.none { isArrayType(resolveTypeName(it.second)) || it.second.nullable }) {
        impl.appendLine("    return ($cName){${cName}_TYPE_ID, ${ci.ctorProps.joinToString(", ") { it.first }}};")
    } else {
        impl.appendLine("    $cName \$self = {0};")
        impl.appendLine("    \$self.__type_id = ${cName}_TYPE_ID;")
        for ((name, type) in ci.ctorProps) {
            val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
            val resolved = resolveTypeName(type)
            val sizeAnn = getSizeAnnotation(type)
            if (sizeAnn != null) {
                val elemType = arrayElementCType(resolved)
                impl.appendLine("    memcpy(\$self.$fieldName, $name, $sizeAnn * sizeof($elemType));")
            } else if (isArrayType(resolved)) {
                impl.appendLine("    \$self.$fieldName = $name;")
                impl.appendLine("    \$self.${fieldName}\$len = ${name}\$len;")
            } else if (type.nullable) {
                impl.appendLine("    \$self.$fieldName = $name;")
            } else {
                impl.appendLine("    \$self.$fieldName = $name;")
            }
        }
        for (bp in ci.bodyProps) {
            if (bp.init != null) {
                if (bp.line > 0) currentStmtLine = bp.line
                heapAllocTargetType = bp.type
                val expr = genExpr(bp.init)
                heapAllocTargetType = null
                flushPreStmts("    ")
                val bodyFieldName = if (bp.isPrivate) "PRIV_${bp.name}" else bp.name
                impl.appendLine("    \$self.$bodyFieldName = $expr;")
                emitBodyPropLenIfArray(bp)
            }
        }
        impl.appendLine("    return \$self;")
    }
    impl.appendLine("}")
    impl.appendLine()

    // --- methods (from template AST, but with typeSubst active) ---
    currentClass = mangledName
    selfIsPointer = true
    pushScope()
    for ((name, type) in ci.props) {
        defineVar(name, resolveTypeName(type))
        if (!ci.isValProp(name)) markMutable(name)
    }
    for (m in templateDecl.members) {
        if (m is FunDecl && m.receiver == null) emitMethod(mangledName, m)
    }
    // Implicit no-op dispose if not overridden
    if (templateDecl.members.none { it is FunDecl && it.name == "dispose" }) {
        hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
    }
    // Implicit hashCode
    if (templateDecl.members.none { it is FunDecl && it.name == "hashCode" }) {
        hdr.appendLine("ktc_Int ${cName}_hashCode($cName* \$self);")
        impl.appendLine("ktc_Int ${cName}_hashCode($cName* \$self) {")
        if (templateDecl.isData && ci.props.isNotEmpty()) {
            impl.appendLine("    ktc_Int h = 0;")
            for ((name, type) in ci.props) {
                val resolved = resolveTypeName(type)
                val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
                val hashExpr = if (type.nullable && !resolved.endsWith("*")) {
                    val valueExpr = "\$self->$fieldName"
                    "(${valueExpr}.tag == ktc_SOME ? ${hashFieldExpr(resolved, "${valueExpr}.value")} : 0)"
                } else {
                    hashFieldExpr(resolved, "\$self->$fieldName")
                }
                impl.appendLine("    h = h * 31 + $hashExpr;")
            }
            impl.appendLine("    return h;")
        } else {
            impl.appendLine("    uintptr_t p = (uintptr_t)\$self; p >>= 4;")
            impl.appendLine("    ktc_UInt lo = (ktc_UInt)p;")
            impl.appendLine("    ktc_UInt hi = (ktc_UInt)(p >> 32);")
            impl.appendLine("    ktc_UInt t = (ktc_UInt)\$self->__type_id * 0x9e3779b1U;")
            impl.appendLine("    ktc_UInt h = lo ^ hi ^ t;")
            impl.appendLine("    h = ktc_fmix32(h);")
            impl.appendLine("    return (ktc_Int)h;")
        }
        impl.appendLine("}")
        impl.appendLine()
    }
    popScope()
    currentClass = null

    // Secondary constructors
    for (sctor in templateDecl.secondaryCtors) {
        emitSecondaryCtor(mangledName, cName, sctor)
    }
}

internal fun CCodeGen.emitDataClassEquals(cName: String, ci: ClassInfo) {
    hdr.appendLine("bool ${cName}_equals($cName a, $cName b);")
    impl.appendLine("bool ${cName}_equals($cName a, $cName b) {")
    val eqs = ci.props.joinToString(" && ") { (name, type) ->
        val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
        val t = resolveTypeName(type)
        when {
            type.nullable -> "(a.$fieldName.tag == b.$fieldName.tag && (a.$fieldName.tag == ktc_NONE || a.$fieldName.value == b.$fieldName.value))"
            t == "String" -> "ktc_string_eq(a.$fieldName, b.$fieldName)"
            classes[t]?.isData == true -> "${pfx(t)}_equals(a.$fieldName, b.$fieldName)"
            else -> "a.$fieldName == b.$fieldName"
        }
    }
    impl.appendLine("    return ${eqs.ifEmpty { "true" }};")
    impl.appendLine("}")
    impl.appendLine()
}

internal fun CCodeGen.emitDataClassToString(ktName: String, cName: String, ci: ClassInfo) {
    val maxLen = toStringMaxLen(ci.name)
    val maxComment = if (maxLen != null) " // max output: $maxLen chars" else ""
    hdr.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb);${maxComment}")
    impl.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb) {")
    for ((i, prop) in ci.props.withIndex()) {
        val (name, type) = prop
        val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
        val tBase = resolveTypeName(type)
        val tFull = if (type.nullable) "${tBase}?" else tBase
        val prefix = if (i == 0) "$ktName($name=" else ", $name="
        impl.appendLine("    ktc_sb_append_str(sb, ktc_str(\"$prefix\"));")
        impl.appendLine("    ${genSbAppend("sb", "\$self->$fieldName", tFull)}")
    }
    impl.appendLine("    ktc_sb_append_char(sb, ')');")
    impl.appendLine("}")
    impl.appendLine()
}

internal fun CCodeGen.emitMethod(className: String, f: FunDecl, suppressHdr: Boolean = false) {
    val cClass = pfx(className)
    val methodName = if (f.isPrivate) "PRIV_${f.name}" else f.name

    val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
    val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
    val priv = if (f.isPrivate) "private " else ""
    impl.appendLine("// ══ ${priv}fun ${f.name}($paramSig)$retSig ══")
    impl.appendLine()

    val returnsNullable = f.returnType != null && f.returnType.nullable
    val returnsSizedArray = !returnsNullable && f.returnType != null && isSizedArrayTypeRef(f.returnType)
    val retResolved = if (f.returnType != null) resolveTypeName(f.returnType) else f.body?.let { inferBlockType(it) } ?: ""
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet = when {
        returnsSizedArray -> "void"
        returnsNullable && retResolved == "Any" -> "ktc_Any"
        returnsNullable -> optRetCType
        retResolved.isNotEmpty() -> cTypeStr(retResolved)
        else -> "void"
    }
    val selfParam = "$cClass* \$self"
    val extraParams = expandParams(f.params)
    val outParam: String? = if (returnsSizedArray) {
        val elemCType = arrayElementCType(resolveTypeName(f.returnType))
        "$elemCType* \$out"
    } else null
    val allParts = mutableListOf(selfParam)
    if (extraParams.isNotEmpty()) allParts += extraParams
    if (outParam != null) allParts += outParam
    val allParams = allParts.joinToString(", ")

    if (f.isPrivate) {
        // Private: forward decl only in .c, not in .h
        implFwd.appendLine("$cRet ${cClass}_${methodName}($allParams);")
    } else if (suppressHdr) {
        deferredHdrLines.getOrPut(className) { mutableListOf() }.add("$cRet ${cClass}_${methodName}($allParams);")
    } else {
        hdr.appendLine("$cRet ${cClass}_${methodName}($allParams);")
    }
    impl.appendLine("$cRet ${cClass}_${methodName}($allParams) {")

    val prevReturnsNullable = currentFnReturnsNullable
    val prevReturnsArray = currentFnReturnsArray
    val prevReturnsSizedArray = currentFnReturnsSizedArray
    val prevSizedArraySize = currentFnSizedArraySize
    val prevSizedArrayElemType = currentFnSizedArrayElemType
    val prevReturnType = currentFnReturnType
    val prevOptRetCTypeName = currentFnOptReturnCTypeName
    currentFnReturnsNullable = returnsNullable
    currentFnReturnsArray = false
    currentFnReturnsSizedArray = returnsSizedArray
    currentFnOptReturnCTypeName = optRetCType
    if (returnsSizedArray) {
        currentFnSizedArraySize = getSizeAnnotation(f.returnType)!!
        currentFnSizedArrayElemType = arrayElementCType(resolveTypeName(f.returnType))
    }
    currentFnReturnType = retResolved

    pushScope()
    for (p in f.params) {
        val resolved = resolveTypeName(p.type)
        defineVar(p.name, when {
            p.type.nullable -> "${resolved}?"
            else -> resolved
        })
        if (p.type.nullable && isValueNullableType("${resolved}?")) markOptional(p.name)
    }
    // class props accessible via self->
    val ci = classes[className]
    if (ci != null) for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))
    val savedTrampolined1 = trampolinedParams.toHashSet(); trampolinedParams.clear()
    emitArrayParamCopies(f.params, "    ")

    val savedDefers = deferStack.toList(); deferStack.clear()
    if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
    if (f.body?.stmts?.lastOrNull() !is ReturnStmt) {
        emitDeferredBlocks("    ", insideMethod = true)
        if (returnsNullable) {
            if (retResolved == "Any") impl.appendLine("    return (ktc_Any){0};")
            else impl.appendLine("    return ${optNone(optRetCType)};")
        }
    }
    deferStack.clear(); deferStack.addAll(savedDefers)
    trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined1)
    popScope()

    currentFnReturnsNullable = prevReturnsNullable
    currentFnReturnsArray = prevReturnsArray
    currentFnReturnsSizedArray = prevReturnsSizedArray
    currentFnSizedArraySize = prevSizedArraySize
    currentFnSizedArrayElemType = prevSizedArrayElemType
    currentFnReturnType = prevReturnType
    currentFnOptReturnCTypeName = prevOptRetCTypeName

    impl.appendLine("}")
    impl.appendLine()
}

// ── extension function ───────────────────────────────────────────

internal fun CCodeGen.emitExtensionFun(f: FunDecl) {
    val recvTypeName = f.receiver!!.name
    val recvIsNullable = f.receiver.nullable
    val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
    val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
    impl.appendLine("// ══ ext fun ${recvTypeName}.${f.name}($paramSig)$retSig ($currentSourceFile) ══")
    impl.appendLine()
    val returnsSizedArray = f.returnType != null && isSizedArrayTypeRef(f.returnType)
    val returnsNullable = f.returnType != null && f.returnType.nullable
    val retResolved = if (f.returnType != null) resolveTypeName(f.returnType) else ""
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet = when {
        returnsSizedArray -> "void"
        returnsNullable && retResolved == "Any" -> "ktc_Any"
        returnsNullable -> optRetCType
        f.returnType != null -> cType(f.returnType)
        else -> "void"
    }
    val isClassType = classes.containsKey(recvTypeName)
    val cRecvType = cTypeStr(recvTypeName)
    // Nullable receiver: pass as Optional struct (value) or OptionalPtr (pointer type)
    val selfParam = if (recvIsNullable) {
        val recvOptType = optCTypeName(recvTypeName)
        "$recvOptType \$self"
    } else "$cRecvType \$self"
    val extraParams = expandParams(f.params)
    val outParam = if (returnsSizedArray) {
        val elemCType = arrayElementCType(resolveTypeName(f.returnType))
        "$elemCType* \$out"
    } else null
    val allParts = mutableListOf(selfParam)
    if (extraParams.isNotEmpty()) allParts += extraParams
    if (outParam != null) allParts += outParam
    val allParams = allParts.joinToString(", ")
    val cFnName = "${pfx(recvTypeName)}_${f.name}"

    hdr.appendLine("$cRet $cFnName($allParams);")
    impl.appendLine("$cRet $cFnName($allParams) {")

    val prevClass = currentClass
    val prevSelfIsPointer = selfIsPointer
    val prevExtRecvType = currentExtRecvType
    val prevReturnsSizedArray = currentFnReturnsSizedArray
    val prevSizedArraySize = currentFnSizedArraySize
    val prevSizedArrayElemType = currentFnSizedArrayElemType
    val prevReturnsNullable = currentFnReturnsNullable
    val prevOptRetCTypeName = currentFnOptReturnCTypeName
    currentFnReturnsSizedArray = returnsSizedArray
    currentFnReturnsNullable = returnsNullable
    currentFnOptReturnCTypeName = optRetCType
    if (returnsSizedArray) {
        currentFnSizedArraySize = getSizeAnnotation(f.returnType)!!
        currentFnSizedArrayElemType = arrayElementCType(resolveTypeName(f.returnType))
    }
    currentExtRecvType = if (recvIsNullable) "$recvTypeName?" else recvTypeName
    if (isClassType) {
        currentClass = recvTypeName
        selfIsPointer = false
    } else {
        currentClass = null
        selfIsPointer = false
    }
    pushScope()
    // If receiver is nullable, $self is an Optional struct — mark it so genName works
    if (recvIsNullable) {
        defineVar("\$self", "${recvTypeName}?")
        markOptional("\$self")
    }
    for (p in f.params) {
        val resolved = resolveTypeName(p.type)
        defineVar(p.name, when {
            p.isVararg -> "${resolved}Array"
            p.type.nullable -> "${resolved}?"
            else -> resolved
        })
        if (p.type.nullable && isValueNullableType("${resolved}?")) markOptional(p.name)
    }
    if (isClassType) {
        val ci = classes[recvTypeName]!!
        for ((name, type) in ci.props) {
            defineVar(name, resolveTypeName(type))
            if (!ci.isValProp(name)) markMutable(name)
        }
    }
    val savedTrampolined2 = trampolinedParams.toHashSet(); trampolinedParams.clear()
    emitArrayParamCopies(f.params, "    ")
    val savedDefers2 = deferStack.toList(); deferStack.clear()
    if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
    if (f.body?.stmts?.lastOrNull() !is ReturnStmt) {
        emitDeferredBlocks("    ", insideMethod = isClassType)
        if (returnsNullable) {
            if (retResolved == "Any") impl.appendLine("    return (ktc_Any){0};")
            else impl.appendLine("    return ${optNone(optRetCType)};")
        }
    }
    deferStack.clear(); deferStack.addAll(savedDefers2)
    trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined2)
    popScope()

    currentClass = prevClass
    selfIsPointer = prevSelfIsPointer
    currentExtRecvType = prevExtRecvType
    currentFnReturnsSizedArray = prevReturnsSizedArray
    currentFnSizedArraySize = prevSizedArraySize
    currentFnSizedArrayElemType = prevSizedArrayElemType
    currentFnReturnsNullable = prevReturnsNullable
    currentFnOptReturnCTypeName = prevOptRetCTypeName

    impl.appendLine("}")
    impl.appendLine()
}

// ── generic function monomorphization ────────────────────────────

/**
 * Emit monomorphized versions of a generic free function.
 * For `fun <T> sizeOfList(list: MutableList<T>)`, if called with MutableList<Int>,
 * emits `sizeOfList_Int(MutableList_Int* list)`.
 *
 * Instantiations are found by scanning call sites in the AST.
 */
internal fun CCodeGen.emitGenericFunInstantiations(f: FunDecl) {
    val instantiations = genericFunInstantiations[f.name] ?: return
    // Switch source file attribution for mem-track if this function came from another file
    val prevSourceFile = currentSourceFile
    declSourceFile[f.name]?.let { currentSourceFile = it }
    for (typeArgs in instantiations) {
        val subst = f.typeParams.zip(typeArgs).toMap()
        val prevSubst = typeSubst
        typeSubst = subst
        val mangledName = "${f.name}_${typeArgs.joinToString("_")}"

        impl.appendLine("// ══ generic ${f.name}<${typeArgs.joinToString(", ")}> ($currentSourceFile) ══")
        impl.appendLine()

        // Resolve return type and params under substitution
        val returnsArray = f.returnType != null && isArrayType(resolveTypeName(f.returnType))
        val returnsSizedArray = f.returnType != null && isSizedArrayTypeRef(f.returnType)
        val concreteRet = genericFunConcreteReturn[mangledName]
        val cRet = when {
            returnsSizedArray -> "void"
            concreteRet != null -> pfx(concreteRet)
            f.returnType != null -> cType(f.returnType)
            else -> "void"
        }
        val cName = pfx(mangledName)
        val baseParams = expandParams(f.params)
        // Prepend receiver as $self parameter for generic extensions
        val hasReceiver = f.receiver != null
        val selfParam = if (hasReceiver) "${cType(f.receiver!!)} \$self" else null
        val params = when {
            returnsSizedArray -> {
                val elemCType = arrayElementCType(resolveTypeName(f.returnType))
                val extra = "$elemCType* \$out"
                val p = if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams
                if (p.isNotEmpty()) "$p, $extra" else extra
            }
            returnsArray -> {
                val extra = "int32_t* \$len_out"
                val p = if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams
                if (p.isNotEmpty()) "$p, $extra" else extra
            }
            else -> if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams
        }

        hdr.appendLine("$cRet $cName($params);")
        impl.appendLine("$cRet $cName($params) {")

        val prevReturnsArray = currentFnReturnsArray
        val prevReturnsSizedArray = currentFnReturnsSizedArray
        val prevSizedArraySize = currentFnSizedArraySize
        val prevSizedArrayElemType = currentFnSizedArrayElemType
        val prevReturnType = currentFnReturnType
        currentFnReturnsArray = returnsArray
        currentFnReturnsSizedArray = returnsSizedArray
        if (returnsSizedArray) {
            currentFnSizedArraySize = getSizeAnnotation(f.returnType)!!
            currentFnSizedArrayElemType = arrayElementCType(resolveTypeName(f.returnType))
        }
        currentFnReturnType = concreteRet
            ?: if (f.returnType != null) {
                val base = resolveTypeName(f.returnType)
                if (f.returnType.nullable && !base.endsWith("?")) "${base}?" else base
            } else ""

        pushScope()
        for (p in f.params) {
            val resolved = resolveTypeName(p.type)
            defineVar(p.name, when {
                p.isVararg -> "${resolved}Array"  // vararg params are arrays (ptr + $len)
                p.type.nullable -> "${resolved}?"
                classes.containsKey(resolved) -> "${resolved}*"
                else -> resolved
            })
        }
        val savedTrampolined3 = trampolinedParams.toHashSet(); trampolinedParams.clear()
        emitArrayParamCopies(f.params, "    ")
        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = false)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = false)
        deferStack.clear(); deferStack.addAll(savedDefers)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined3)
        popScope()

        currentFnReturnsArray = prevReturnsArray
        currentFnReturnsSizedArray = prevReturnsSizedArray
        currentFnSizedArraySize = prevSizedArraySize
        currentFnSizedArrayElemType = prevSizedArrayElemType
        currentFnReturnType = prevReturnType
        impl.appendLine("}")
        impl.appendLine()

        typeSubst = prevSubst
    }
    currentSourceFile = prevSourceFile
}

/**
 * Emit star-projection extension functions — one per known instantiation.
 * For `fun MutableList<*>.sizeOf()`, if MutableList<Int> is known, emits
 * `MutableList_Int_sizeOf(MutableList_Int* $self)`.
 */
internal fun CCodeGen.emitStarExtFunInstantiations(f: FunDecl) {
    val recvBaseName = f.receiver!!.name
    val instantiations = genericInstantiations[recvBaseName]

    // If the receiver is a generic interface (not a class), expand per implementing class
    if (instantiations == null && genericIfaceDecls.containsKey(recvBaseName)) {
        emitStarExtFunForGenericInterface(f, recvBaseName)
        return
    }
    if (instantiations == null) return
    val emitted = mutableSetOf<String>()
    for (typeArgs in instantiations) {
        val mangledRecvName = mangledGenericName(recvBaseName, typeArgs)
        val key = "${mangledRecvName}_${f.name}"
        if (!emitted.add(key)) continue  // avoid duplicates
        // Build a concrete FunDecl with the mangled receiver name
        val concreteReceiver = TypeRef(mangledRecvName, f.receiver.nullable)
        // Set typeSubst from the generic class's type params
        val templateCi = classes[recvBaseName] ?: continue
        val subst = templateCi.typeParams.zip(typeArgs).toMap()
        val prevSubst = typeSubst
        typeSubst = subst

        val recvIsNullable = concreteReceiver.nullable
        val cRet = if (f.returnType != null) cType(f.returnType) else "void"
        val isClassType = classes.containsKey(mangledRecvName)
        val cRecvType = pfx(mangledRecvName)
        val selfParam = if (isClassType) "$cRecvType* \$self" else "$cRecvType \$self"
        val nullableExtra = if (recvIsNullable) ", bool \$self\$has" else ""
        val extraParams = expandParams(f.params)
        val allParams = if (extraParams.isEmpty()) "$selfParam$nullableExtra" else "$selfParam$nullableExtra, $extraParams"
        val cFnName = "${pfx(mangledRecvName)}_${f.name}"

        hdr.appendLine("$cRet $cFnName($allParams);")
        impl.appendLine("$cRet $cFnName($allParams) {")

        val prevClass = currentClass
        val prevSelfIsPointer = selfIsPointer
        val prevExtRecvType = currentExtRecvType
        currentExtRecvType = if (recvIsNullable) "$mangledRecvName?" else mangledRecvName
        if (isClassType) {
            currentClass = mangledRecvName
            selfIsPointer = true
        } else {
            currentClass = null
            selfIsPointer = false
        }

        pushScope()
        for (p in f.params) {
            val resolved = resolveTypeName(p.type)
            defineVar(p.name, when {
                p.type.nullable -> "${resolved}?"
                classes.containsKey(resolved) -> "${resolved}*"
                else -> resolved
            })
        }
        if (isClassType) {
            val ci = classes[mangledRecvName]!!
            for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))
        }
        val savedTrampolined4 = trampolinedParams.toHashSet(); trampolinedParams.clear()
        emitArrayParamCopies(f.params, "    ")
        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = isClassType)
        deferStack.clear(); deferStack.addAll(savedDefers)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined4)
        popScope()

        currentClass = prevClass
        selfIsPointer = prevSelfIsPointer
        currentExtRecvType = prevExtRecvType

        impl.appendLine("}")
        impl.appendLine()

        // Register as extension function on the mangled name for call resolution
        extensionFuns.getOrPut(mangledRecvName) { mutableListOf() }.add(
            FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver)
        )
        classes[mangledRecvName]?.methods?.add(
            FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver)
        )

        typeSubst = prevSubst
    }
}

/**
 * Expand a star-projection extension on a generic interface (e.g., List<*>.sizeOf())
 * into concrete implementations for each class that implements a monomorphized version.
 * For ArrayList_Int (implements List_Int) → ArrayList_Int_sizeOf
 * For ArrayList_Vec2 (implements List_Vec2) → ArrayList_Vec2_sizeOf
 */
internal fun CCodeGen.emitStarExtFunForGenericInterface(f: FunDecl, ifaceBaseName: String) {
    val emitted = mutableSetOf<String>()
    // Find all classes that implement a monomorphized version of this interface
    for ((className, ifaceList) in classInterfaces) {
        // Check if this class implements any monomorphized version of the interface
        val matchingIface = ifaceList.find { it.startsWith("${ifaceBaseName}_") }
        if (matchingIface == null) continue
        val ci = classes[className] ?: continue
        val key = "${className}_${f.name}"
        if (!emitted.add(key)) continue

        // Set up type substitution from the class's own type bindings
        val prevSubst = typeSubst
        typeSubst = genericTypeBindings[className] ?: emptyMap()

        val cRet = if (f.returnType != null) cType(f.returnType) else "void"
        val cRecvType = pfx(className)
        val selfParam = "$cRecvType* \$self"
        val extraParams = expandParams(f.params)
        val allParams = if (extraParams.isEmpty()) selfParam else "$selfParam, $extraParams"
        val cFnName = "${cRecvType}_${f.name}"

        hdr.appendLine("$cRet $cFnName($allParams);")
        impl.appendLine("$cRet $cFnName($allParams) {")

        val prevClass = currentClass
        val prevSelfIsPointer = selfIsPointer
        val prevExtRecvType = currentExtRecvType
        currentExtRecvType = className
        currentClass = className
        selfIsPointer = true

        pushScope()
        for (p in f.params) {
            val resolved = resolveTypeName(p.type)
            defineVar(p.name, when {
                p.type.nullable -> "${resolved}?"
                classes.containsKey(resolved) -> "${resolved}*"
                else -> resolved
            })
        }
        for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))
        val savedTrampolined5 = trampolinedParams.toHashSet(); trampolinedParams.clear()
        emitArrayParamCopies(f.params, "    ")
        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = true)
        deferStack.clear(); deferStack.addAll(savedDefers)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined5)
        popScope()

        currentClass = prevClass
        selfIsPointer = prevSelfIsPointer
        currentExtRecvType = prevExtRecvType

        impl.appendLine("}")
        impl.appendLine()

        // Register as extension function for call resolution
        val concreteReceiver = TypeRef(className, f.receiver!!.nullable)
        extensionFuns.getOrPut(className) { mutableListOf() }.add(
            FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver)
        )
        ci.methods.add(FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))

        typeSubst = prevSubst
    }
}

// ── enum class ───────────────────────────────────────────────────

internal fun CCodeGen.emitEnum(d: EnumDecl) {
    val cName = pfx(d.name)
    hdr.appendLine("typedef enum {")
    for ((i, e) in d.entries.withIndex()) {
        hdr.append("    ${cName}_$e")
        if (i < d.entries.lastIndex) hdr.append(",")
        hdr.appendLine()
    }
    hdr.appendLine("} $cName;")
    val n = d.entries.size
    val nameInits = d.entries.joinToString(", ") { "ktc_str(\"$it\")" }
    hdr.appendLine("extern const ktc_String ${cName}_names[$n];")
    hdr.appendLine()
    impl.appendLine("const ktc_String ${cName}_names[$n] = {$nameInits};")
    impl.appendLine()
}

internal fun CCodeGen.emitEnumValuesData() {
    for (enumName in enumValuesCalled) {
        val info = enums[enumName] ?: continue
        val cName = pfx(enumName)
        val entryNames = info.entries.joinToString(", ") { "${cName}_${it}" }
        val n = info.entries.size
        // extern declarations in header
        hdr.appendLine("extern const $cName ${cName}_values[$n];")
        hdr.appendLine("extern const int32_t ${cName}_values\$len;")
        // definitions in source
        impl.appendLine("const $cName ${cName}_values[] = {$entryNames};")
        impl.appendLine("const int32_t ${cName}_values\$len = $n;")
    }
    for (enumName in enumValueOfCalled) {
        val info = enums[enumName] ?: continue
        val cName = pfx(enumName)
        // valueOf function forward declaration in header
        hdr.appendLine("$cName ${cName}_valueOf(ktc_String name);")
        // valueOf function body in source
        val body = StringBuilder()
        body.appendLine("$cName ${cName}_valueOf(ktc_String name) {")
        for (entry in info.entries) {
            body.appendLine("    if (ktc_string_eq(name, ktc_str(\"$entry\"))) return ${cName}_$entry;")
        }
        body.appendLine("    return ${cName}_${info.entries.first()};")
        body.append("}")
        impl.appendLine(body.toString())
        impl.appendLine()
    }
    enumValuesCalled.clear()
    enumValueOfCalled.clear()
}

// ── object ───────────────────────────────────────────────────────

internal fun CCodeGen.emitObject(d: ObjectDecl) {
    val cName = pfx(d.name)
    val props = d.members.filterIsInstance<PropDecl>()
    val initBlocks = d.members.filterIsInstance<FunDecl>().filter { it.name == "init" }
    val methods = d.members.filterIsInstance<FunDecl>().filter { it.name != "init" }
    val privPrefix = { p: PropDecl -> if (p.isPrivate) "PRIV_" else "" }

    impl.appendLine("// ══ object ${d.name} ($currentSourceFile) ══")
    impl.appendLine()

    hdr.appendLine("// ══ object ${d.name} ($currentSourceFile) ══")
    hdr.appendLine("typedef struct {")
    if (props.isEmpty()) hdr.appendLine("    char _dummy;")
    for (p in props) {
        val pType = p.type ?: inferInitType(p.init)
        val resolved = resolveTypeName(pType)
        val sizeAnn = getSizeAnnotation(pType)
        if (isArrayType(resolved) && sizeAnn != null) {
            val elemType = arrayElementCType(resolved)
            val fn = privPrefix(p) + p.name
            hdr.appendLine("    $elemType ${fn}[${sizeAnn}];")
            hdr.appendLine("    int32_t ${fn}\$len;")
        } else if (isArrayType(resolved)) {
            val fn = privPrefix(p) + p.name
            hdr.appendLine("    ${cTypeStr(resolved)} ${fn};")
            hdr.appendLine("    int32_t ${fn}\$len;")
        } else {
            val fn = privPrefix(p) + p.name
            hdr.appendLine("    ${cType(pType)} ${fn};${ptrNullComment(resolved)}")
        }
    }
    hdr.appendLine("} ${cName}_t;")
    val tls = if (d.name in tlsObjects) "ktc_tls " else ""
    hdr.appendLine("extern ${tls}${cName}_t $cName;")
    hdr.appendLine()

    // global instance (zero-initialized), init flag + ensure_init are internal
    impl.appendLine("${tls}${cName}_t $cName = {0};")
    impl.appendLine("static bool ${cName}\$init = false;")
    impl.appendLine()

    // $ensure_init: lazy initialization function
    impl.appendLine("static void ${cName}_\$ensure_init(void) {")
    impl.appendLine("    if (${cName}\$init) return;")
    impl.appendLine("    ${cName}\$init = true;")
    val prevObject = currentObject
    currentObject = d.name
    pushScope()
    for (p in props) defineVar(p.name, resolveTypeName(p.type ?: inferInitType(p.init)))
    for (p in props) {
        if (p.init != null) {
            val pType = p.type ?: inferInitType(p.init)
            val resolved = resolveTypeName(pType)
            val sizeAnn = getSizeAnnotation(pType)
            val expr = genExpr(p.init)
            flushPreStmts("    ")
            if (isArrayType(resolved) && sizeAnn != null) {
                val elemType = arrayElementCType(resolved)
                val fn = privPrefix(p) + p.name
                impl.appendLine("    memcpy($cName.$fn, $expr, $sizeAnn * sizeof($elemType));")
                impl.appendLine("    $cName.${fn}\$len = ${sizeAnn};")
            } else {
                val fn = privPrefix(p) + p.name
                impl.appendLine("    $cName.$fn = $expr;")
            }
        }
    }
    // emit init { } block bodies
    for (ib in initBlocks) {
        if (ib.body != null) for (s in ib.body.stmts) emitStmt(s, "    ")
    }
    popScope()
    currentObject = prevObject
    impl.appendLine("}")
    impl.appendLine()

    // methods — inject $ensure_init() at the top of each
    for (m in methods) {
        val returnsSizedArray = m.returnType != null && isSizedArrayTypeRef(m.returnType)
        val returnsArray = m.returnType != null && !returnsSizedArray && isArrayType(resolveTypeName(m.returnType))
        val retResolved = if (m.returnType != null) resolveTypeName(m.returnType) else ""
        val cRet = when {
            returnsSizedArray -> "void"
            retResolved.isNotEmpty() -> cTypeStr(retResolved)
            else -> "void"
        }
        val baseParams = expandParams(m.params)
        val extraParam = when {
            returnsSizedArray -> {
                val elemCType = arrayElementCType(resolveTypeName(m.returnType))
                "$elemCType* \$out"
            }
            returnsArray -> "int32_t* \$len_out"
            else -> null
        }
        val params = if (extraParam != null) {
            if (baseParams.isEmpty()) extraParam else "$baseParams, $extraParam"
        } else baseParams
        hdr.appendLine("$cRet ${cName}_${m.name}($params);")
        impl.appendLine("$cRet ${cName}_${m.name}($params) {")
        impl.appendLine("    ${cName}_\$ensure_init();")
        val prevObjectM = currentObject
        currentObject = d.name
        val prevReturnsArray = currentFnReturnsArray
        val prevReturnsSizedArray = currentFnReturnsSizedArray
        val prevSizedArraySize = currentFnSizedArraySize
        val prevSizedArrayElemType = currentFnSizedArrayElemType
        val prevReturnType = currentFnReturnType
        currentFnReturnsArray = returnsArray
        currentFnReturnsSizedArray = returnsSizedArray
        currentFnReturnType = retResolved
        if (returnsSizedArray) {
            currentFnSizedArraySize = getSizeAnnotation(m.returnType)!!
            currentFnSizedArrayElemType = arrayElementCType(resolveTypeName(m.returnType))
        }
        pushScope()
        for (p in props) defineVar(p.name, resolveTypeName(p.type ?: inferInitType(p.init)))
        for (p in m.params) defineVar(p.name, if (p.isVararg) "${resolveTypeName(p.type)}Array" else resolveTypeName(p.type))
        val savedTrampolined6 = trampolinedParams.toHashSet(); trampolinedParams.clear()
        emitArrayParamCopies(m.params, "    ")
        val savedDefers3 = deferStack.toList(); deferStack.clear()
        if (m.body != null) for (s in m.body.stmts) emitStmt(s, "    ")
        if (m.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ")
        deferStack.clear(); deferStack.addAll(savedDefers3)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined6)
        popScope()
        currentFnReturnsArray = prevReturnsArray
        currentFnReturnsSizedArray = prevReturnsSizedArray
        currentFnSizedArraySize = prevSizedArraySize
        currentFnSizedArrayElemType = prevSizedArrayElemType
        currentFnReturnType = prevReturnType
        currentObject = prevObjectM
        impl.appendLine("}")
        impl.appendLine()
    }
}

// ── interface ────────────────────────────────────────────────────

/**
 * Emit a vtable struct + fat-pointer typedef for an interface.
 *
 * interface Drawable { fun draw(); fun area(): Float }
 * →
 * typedef struct {
 *     void (*draw)(void* $self);
 *     float (*area)(void* $self);
 * } game_Drawable_vt;
 *
 * typedef struct {
 *     void* obj;
 *     const game_Drawable_vt* vt;
 * } game_Drawable;
 */
/** Emit only the vtable struct + TYPE_ID for an interface (used early, before class structs). */
internal fun CCodeGen.emitInterface(d: InterfaceDecl) {
    val info = interfaces[d.name] ?: return
    emitInterfaceVtable(info)
}

/**
 * Emit interface vtable struct + TYPE_ID define.
 * Handles inherited methods/properties from super interfaces.
 */
internal fun CCodeGen.emitInterfaceVtable(info: IfaceInfo) {
    val cName = pfx(info.name)
    hdr.appendLine("// ══ interface ${info.name} ($currentSourceFile) ══")
    hdr.appendLine("#define ${cName}_TYPE_ID ${typeIds[info.name]!!}")
    // Collect all methods/properties including inherited from super interfaces
    val allMethods = collectAllIfaceMethods(info)
    val allProps = collectAllIfaceProperties(info)
    // vtable struct (named so it can be forward-declared)
    hdr.appendLine("typedef struct ${cName}_vt {")
    // Properties → getter function pointers
    for (p in allProps) {
        val ct = if (p.type != null) cType(p.type) else "int32_t"
        hdr.appendLine("    $ct (*${p.name})(void* \$self);")
    }
    for (m in allMethods) {
        val mReturnsNullable = m.returnType != null && m.returnType.nullable
        val mRetResolved = if (m.returnType != null) resolveTypeName(m.returnType) else ""
        val cRet = if (mReturnsNullable) optCTypeName(mRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
        val extraParams = m.params.joinToString("") { p ->
            val pResolved = resolveTypeName(p.type)
            if (p.type.nullable) ", ${optCTypeName(pResolved)} ${p.name}"
            else ", ${cType(p.type)} ${p.name}"
        }
        hdr.appendLine("    $cRet (*${m.name})(void* \$self$extraParams);")
    }
    if (allMethods.none { it.name == "dispose" }) {
        hdr.appendLine("    void (*dispose)(void* \$self);")
    }
    hdr.appendLine("} ${cName}_vt;")
    hdr.appendLine()
}

/**
 * Emit a concrete (non-generic) interface struct: tagged union containing all implementing classes.
 * Falls back to void* obj only for interfaces with zero known implementors.
 * Must be called after all class structs and vtables have been emitted.
 */
internal fun CCodeGen.emitIfaceInfo(info: IfaceInfo) {
    val cName = pfx(info.name)
    val impls = interfaceImplementors[info.name] ?: emptyList()
    hdr.appendLine("// ══ interface ${info.name} — tagged union ($currentSourceFile) ══")
    hdr.appendLine("typedef struct $cName {")
    if (impls.isEmpty()) {
        // Fallback: no known implementors — keep void* obj
        hdr.appendLine("    void* obj;")
    } else if (impls.size == 1) {
        // Single implementor: no union needed, use plain field
        hdr.appendLine("    ${pfx(impls[0])} ${ifaceDataName(impls[0])};")
    } else {
        // Multiple implementors: tagged union
        hdr.appendLine("    union {")
        for (className in impls) {
            hdr.appendLine("        ${pfx(className)} ${ifaceDataName(className)};")
        }
        hdr.appendLine("    } data;")
    }
    hdr.appendLine("    const ${cName}_vt* vt;")
    hdr.appendLine("} $cName;")
    hdr.appendLine()
}

/** Legacy: emit both vtable and tagged-union struct. Used for monomorphized interfaces. */
internal fun CCodeGen.emitIfaceInfoFull(info: IfaceInfo) {
    emitInterfaceVtable(info)
    emitIfaceInfo(info)
}

/** Collect all methods for an interface, including inherited from super interfaces (depth-first). */
internal fun CCodeGen.collectAllIfaceMethods(info: IfaceInfo): List<FunDecl> {
    val result = mutableListOf<FunDecl>()
    val seen = mutableSetOf<String>()
    fun collect(i: IfaceInfo) {
        for (superRef in i.superInterfaces) {
            val superName = resolveIfaceName(superRef)
            val superInfo = interfaces[superName] ?: continue
            collect(superInfo)
        }
        for (m in i.methods) {
            if (m.name !in seen) { result += m; seen += m.name }
        }
    }
    collect(info)
    return result
}

/** Collect all properties for an interface, including inherited from super interfaces. */
internal fun CCodeGen.collectAllIfaceProperties(info: IfaceInfo): List<PropDecl> {
    val result = mutableListOf<PropDecl>()
    val seen = mutableSetOf<String>()
    fun collect(i: IfaceInfo) {
        for (superRef in i.superInterfaces) {
            val superName = resolveIfaceName(superRef)
            val superInfo = interfaces[superName] ?: continue
            collect(superInfo)
        }
        for (p in i.properties) {
            if (p.name !in seen) { result += p; seen += p.name }
        }
    }
    collect(info)
    return result
}

/** Data member name for a class inside a tagged union or single-field interface struct. */
internal fun CCodeGen.ifaceDataName(className: String): String = "${pfx(className)}_data"

/** Return the ktc_hash_* expression for a resolved field type and value expression. */
internal fun CCodeGen.hashFieldExpr(resolvedType: String, valueExpr: String): String = when {
    // Nullable value types: hash tag + value (or 0 if null)
    resolvedType.endsWith("?") && isValueNullableType(resolvedType) -> {
        val base = resolvedType.removeSuffix("?")
        "(${valueExpr}.tag == ktc_SOME ? ${hashFieldExpr(base, "${valueExpr}.value")} : 0)"
    }
    resolvedType == "Byte"    -> "ktc_hash_i8($valueExpr)"
    resolvedType == "Short"   -> "ktc_hash_i16($valueExpr)"
    resolvedType == "Int"     -> "ktc_hash_i32($valueExpr)"
    resolvedType == "Long"    -> "ktc_hash_i64($valueExpr)"
    resolvedType == "Float"   -> "ktc_hash_f32($valueExpr)"
    resolvedType == "Double"  -> "ktc_hash_f64($valueExpr)"
    resolvedType == "Boolean" -> "ktc_hash_bool($valueExpr)"
    resolvedType == "Char"    -> "ktc_hash_char($valueExpr)"
    resolvedType == "UByte"   -> "ktc_hash_u8($valueExpr)"
    resolvedType == "UShort"  -> "ktc_hash_u16($valueExpr)"
    resolvedType == "UInt"    -> "ktc_hash_u32($valueExpr)"
    resolvedType == "ULong"   -> "ktc_hash_u64($valueExpr)"
    resolvedType == "String"  -> "ktc_hash_str($valueExpr)"
    resolvedType.endsWith("*") -> "((ktc_Int)(uintptr_t)($valueExpr))"
    classes.containsKey(resolvedType) || interfaces.containsKey(resolvedType) || isArrayType(resolvedType) || resolvedType.endsWith("?") ->
        "($valueExpr).__type_id"
    else -> "($valueExpr).__type_id"
}

/**
 * Generate the designated-initializer return expression for ClassName_as_IfaceName().
 * Format depends on how many implementors the interface has:
 *   0: (void*) shallow pointer  (fallback, no union)
 *   1: .ClassName = *$self     (single struct field, no data wrapper)
 *   2+: .data.ClassName = *$self  (tagged union)
 */
internal fun CCodeGen.ifaceAsInit(cIface: String, cClass: String, className: String, ifaceName: String): String {
    val impls = interfaceImplementors[ifaceName]
    val dataName = ifaceDataName(className)
    return when {
        impls == null || impls.isEmpty() -> "($cIface){(void*)\$self, &${cClass}_${ifaceName}_vt}"
        impls.size == 1 -> "($cIface){.$dataName = *\$self, .vt = &${cClass}_${ifaceName}_vt}"
        else -> "($cIface){.data.$dataName = *\$self, .vt = &${cClass}_${ifaceName}_vt}"
    }
}

/**
 * For each interface a class implements, emit:
 *   1. Property getter wrappers (for interface properties backed by class fields)
 *   2. A static const vtable instance with the class's method/property pointers
 *   3. A wrapping function:  ClassName_as_IfaceName(ClassName* $self) → IfaceName
 */
internal fun CCodeGen.emitClassInterfaceVtables(d: ClassDecl) {
    val className = d.name
    emitInterfaceVtablesForClass(className, d.superInterfaces)
}

/**
 * Emit vtables for a concrete class name implementing the given super interfaces.
 * Works for both non-generic and monomorphized generic classes.
 * @param declsOnly if true, only emit header declarations (for grouping with class struct).
 * @param implsOnly if true, only emit .c implementations (skip hdr lines).
 */
internal fun CCodeGen.emitInterfaceVtablesForClass(className: String, superIfaceRefs: List<TypeRef>, declsOnly: Boolean = false, implsOnly: Boolean = false) {
    val cClass = pfx(className)
    for (ifaceRef in superIfaceRefs) {
        val ifaceName = resolveIfaceName(ifaceRef)
        val iface = interfaces[ifaceName] ?: continue
        val cIface = pfx(ifaceName)
        val allMethods = collectAllIfaceMethods(iface)
        val allProps = collectAllIfaceProperties(iface)

        if (!implsOnly) hdr.appendLine()
        if (!implsOnly) hdr.appendLine("// ── $className implements $ifaceName ──")
        if (!declsOnly) impl.appendLine("// ── $className implements $ifaceName ──")

        // Emit property getter wrappers
        for (p in allProps) {
            val ct = if (p.type != null) cType(p.type) else "int32_t"
            val getterName = "${cClass}_${p.name}_get"
            if (!implsOnly) hdr.appendLine("$ct $getterName($cClass* \$self);")
            if (!declsOnly) {
                impl.appendLine("$ct $getterName($cClass* \$self) { return \$self->${p.name}; }")
                impl.appendLine()
            }
        }

        // static vtable instance
        if (!implsOnly) hdr.appendLine("extern const ${cIface}_vt ${cClass}_${ifaceName}_vt;")
        if (!declsOnly) {
            impl.appendLine("const ${cIface}_vt ${cClass}_${ifaceName}_vt = {")
            for (p in allProps) {
                val ct = if (p.type != null) cType(p.type) else "int32_t"
                impl.appendLine("    ($ct (*)(void*)) ${cClass}_${p.name}_get,")
            }
            for (m in allMethods) {
                val mReturnsNullable = m.returnType != null && m.returnType.nullable
                val mRetResolved = if (m.returnType != null) resolveTypeName(m.returnType) else ""
                val cRet = if (mReturnsNullable) optCTypeName(mRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
                val extraCast = m.params.joinToString("") { p ->
                    val pResolved = resolveTypeName(p.type)
                    if (p.type.nullable) ", ${optCTypeName(pResolved)}" else ", ${cType(p.type)}"
                }
                val fn = if (m.name == "dispose" && classes[className]?.methods?.none { it.name == "dispose" } == true)
                    "ktc_noop_dispose"
                else "${cClass}_${m.name}"
                impl.appendLine("    ($cRet (*)(void*$extraCast)) $fn,")
            }
            if (allMethods.none { it.name == "dispose" }) {
                val fnDispose = if (classes[className]?.methods?.none { it.name == "dispose" } == true)
                    "ktc_noop_dispose"
                else "${cClass}_dispose"
                impl.appendLine("    (void (*)(void*)) $fnDispose,")
            }
            impl.appendLine("};")
            impl.appendLine()
        }

        // wrapping function: ClassName_as_IfaceName
        if (!implsOnly) hdr.appendLine("$cIface ${cClass}_as_${ifaceName}($cClass* \$self);")
        // Emit class method declarations for interface overrides (moved from class section)
        if (!implsOnly) {
            val lines = deferredHdrLines[className]
            if (lines != null) {
                for (line in lines) {
                    hdr.appendLine(line)
                }
                deferredHdrLines.remove(className)
            }
        }
        if (!declsOnly) {
            impl.appendLine("$cIface ${cClass}_as_${ifaceName}($cClass* \$self) {")
            impl.appendLine("    return ${ifaceAsInit(cIface, cClass, className, ifaceName)};")
            impl.appendLine("}")
            impl.appendLine()
        }

        // Also emit vtables for all parent interfaces (transitive)
        // E.g., ArrayList_Int implements MutableList_Int which extends List_Int
        // → emit ArrayList_Int_as_List_Int too
        if (!declsOnly) {
            emitTransitiveInterfaceVtables(className, cClass, iface, allProps, allMethods)
        }
    }
}

/**
 * For interface inheritance chains, emit vtables for parent interfaces.
 * E.g., if ArrayList_Int implements MutableList_Int which extends List_Int,
 * emit ArrayList_Int_as_List_Int with the List_Int subset of the vtable.
 */
internal fun CCodeGen.emitTransitiveInterfaceVtables(
    className: String, cClass: String, iface: IfaceInfo,
    childProps: List<PropDecl>, childMethods: List<FunDecl>
) {
    for (superRef in iface.superInterfaces) {
        val superName = resolveIfaceName(superRef)
        val superIface = interfaces[superName] ?: continue
        val cSuper = pfx(superName)
        val superMethods = collectAllIfaceMethods(superIface)
        val superProps = collectAllIfaceProperties(superIface)

        hdr.appendLine("// ── $className implements $superName (transitive) ──")
        impl.appendLine("// ── $className implements $superName (transitive) ──")

        // Register this class as also implementing the parent interface
        val existing = classInterfaces[className]?.toMutableList() ?: mutableListOf()
        if (superName !in existing) {
            existing += superName
            classInterfaces[className] = existing
            // Also update the reverse map for tagged-union emission
            interfaceImplementors.getOrPut(superName) { mutableListOf() }.add(className)
        }

        // static vtable instance (same class methods, but only the parent's slots)
        hdr.appendLine("extern const ${cSuper}_vt ${cClass}_${superName}_vt;")
        impl.appendLine("const ${cSuper}_vt ${cClass}_${superName}_vt = {")
        for (p in superProps) {
            val ct = if (p.type != null) cType(p.type) else "int32_t"
            impl.appendLine("    ($ct (*)(void*)) ${cClass}_${p.name}_get,")
        }
        for (m in superMethods) {
            val mReturnsNullable = m.returnType != null && m.returnType.nullable
            val mRetResolved = if (m.returnType != null) resolveTypeName(m.returnType) else ""
            val cRet = if (mReturnsNullable) optCTypeName(mRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
            val extraCast = m.params.joinToString("") { p ->
                val pResolved = resolveTypeName(p.type)
                if (p.type.nullable) ", ${optCTypeName(pResolved)}" else ", ${cType(p.type)}"
            }
            val fn = if (m.name == "dispose" && classes[className]?.methods?.none { it.name == "dispose" } == true)
                "ktc_noop_dispose"
            else "${cClass}_${m.name}"
            impl.appendLine("    ($cRet (*)(void*$extraCast)) $fn,")
        }
        if (superMethods.none { it.name == "dispose" }) {
            val fnDispose = if (classes[className]?.methods?.none { it.name == "dispose" } == true)
                "ktc_noop_dispose"
            else "${cClass}_dispose"
            impl.appendLine("    (void (*)(void*)) $fnDispose,")
        }
        impl.appendLine("};")
        impl.appendLine()

        // wrapping function
        hdr.appendLine("$cSuper ${cClass}_as_${superName}($cClass* \$self);")
        impl.appendLine("$cSuper ${cClass}_as_${superName}($cClass* \$self) {")
        impl.appendLine("    return ${ifaceAsInit(cSuper, cClass, className, superName)};")
        impl.appendLine("}")
        impl.appendLine()

        // Recurse for deeper inheritance
        emitTransitiveInterfaceVtables(className, cClass, superIface, superProps, superMethods)
    }
}



// ── top-level fun ────────────────────────────────────────────────

internal fun CCodeGen.emitFun(f: FunDecl) {
    if (f.isInline) return  // inline funs are expanded at call sites, not emitted as C functions

    if (f.name != "main") {
        val paramSig = f.params.joinToString(", ") { p -> typeRefToStr(p.type) }
        val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
        impl.appendLine("// ══ fun ${f.name}($paramSig)$retSig ($currentSourceFile) ══")
        impl.appendLine()
    }
    val isMain = f.name == "main"
    val isMainWithArgs = isMain && f.params.size == 1 &&
            f.params[0].type.name == "Array" &&
            f.params[0].type.typeArgs.singleOrNull()?.name == "String"

    val returnsNullable = !isMain && f.returnType != null && f.returnType.nullable
    val returnsSizedArray = !isMain && !returnsNullable && f.returnType != null && isSizedArrayTypeRef(f.returnType)
    val returnsArray = !isMain && !returnsNullable && !returnsSizedArray && f.returnType != null && isArrayType(resolveTypeName(f.returnType))
    val retResolved = if (f.returnType != null) resolveTypeName(f.returnType) else f.body?.let { inferBlockType(it) } ?: ""
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet  = if (isMain) "int" else if (returnsSizedArray) "void" else if (returnsNullable && retResolved == "Any") "ktc_Any" else if (returnsNullable) optRetCType else if (retResolved.isNotEmpty()) cTypeStr(retResolved) else "void"
    val cName = if (isMain) "main" else pfx(f.name)
    val params = when {
        isMainWithArgs -> "int argc, char** argv"
        isMain         -> "void"
        else           -> {
            val base = expandParams(f.params)
            val extra = when {
                returnsSizedArray -> {
                    val elemCType = arrayElementCType(resolveTypeName(f.returnType))
                    "$elemCType* \$out"
                }
                returnsArray -> "int32_t* \$len_out"
                else -> null
            }
            if (extra != null) {
                if (base.isEmpty()) extra else "$base, $extra"
            } else base
        }
    }

    hdr.appendLine("$cRet $cName($params);")
    impl.appendLine("$cRet $cName($params) {")

    if (isMain) {
        impl.appendLine("    ktc_mainInit();")
    }

    val prevReturnsNullable = currentFnReturnsNullable
    val prevReturnsArray = currentFnReturnsArray
    val prevReturnsSizedArray = currentFnReturnsSizedArray
    val prevSizedArraySize = currentFnSizedArraySize
    val prevSizedArrayElemType = currentFnSizedArrayElemType
    val prevReturnType = currentFnReturnType
    val prevOptRetCTypeName = currentFnOptReturnCTypeName
    val prevIsMain = currentFnIsMain
    currentFnReturnsNullable = returnsNullable
    currentFnReturnsArray = returnsArray
    currentFnReturnsSizedArray = returnsSizedArray
    currentFnOptReturnCTypeName = optRetCType
    if (returnsSizedArray) {
        currentFnSizedArraySize = getSizeAnnotation(f.returnType)!!
        currentFnSizedArrayElemType = arrayElementCType(resolveTypeName(f.returnType))
    }
    currentFnReturnType = retResolved
    currentFnIsMain = isMain

    pushScope()
    if (isMainWithArgs) {
        // Convert argc/argv → ktc_StringArray (skip argv[0] = program name)
        val argName = f.params[0].name
        impl.appendLine("    ktc_String \$args_buf[256];")
        impl.appendLine("    int32_t \$nargs = (argc > 1) ? (int32_t)(argc - 1) : 0;")
        impl.appendLine("    if (\$nargs > 256) \$nargs = 256;")
        impl.appendLine("    for (int32_t \$i = 0; \$i < \$nargs; \$i++) {")
        impl.appendLine("        \$args_buf[\$i] = (ktc_String){argv[\$i + 1], (int32_t)strlen(argv[\$i + 1])};")
        impl.appendLine("    }")
        impl.appendLine("    ktc_String* $argName = \$args_buf;")
        impl.appendLine("    int32_t ${argName}\$len = \$nargs;")
        defineVar(argName, "StringArray")
    } else {
        for (p in f.params) {
            val resolved = resolveTypeName(p.type)
            defineVar(p.name, when {
                p.isVararg -> "${resolved}Array"  // vararg params are arrays (ptr + $len)
                p.type.nullable -> "${resolved}?"
                else -> resolved
            })
            if (p.type.nullable && isValueNullableType("${resolved}?")) markOptional(p.name)
        }
    }
    val savedTrampolined7 = trampolinedParams.toHashSet(); trampolinedParams.clear()
    if (!isMain) emitArrayParamCopies(f.params, "    ")
    val savedDefers = deferStack.toList()
    deferStack.clear()

    if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ")
    // Emit deferred blocks at end unless last stmt was a return (already emitted there)
    val lastStmt = f.body?.stmts?.lastOrNull()
    if (lastStmt !is ReturnStmt) emitDeferredBlocks("    ")
    if (isMain && objectsWithDispose.isNotEmpty()) {
        for (cName in objectsWithDispose.distinct()) {
            impl.appendLine("    ${cName}_dispose();")
        }
    }
    if (isMain && memTrack) {
        impl.appendLine("    fflush(stdout);")
        impl.appendLine("    ktc_mem_report();")
    }
    if (isMain) impl.appendLine("    return 0;")
    else if (returnsNullable && lastStmt !is ReturnStmt) {
        if (retResolved == "Any") impl.appendLine("    return (ktc_Any){0};")
        else impl.appendLine("    return ${optNone(optRetCType)};")
    }
    trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined7)
    popScope()

    deferStack.clear()
    deferStack.addAll(savedDefers)
    currentFnReturnsNullable = prevReturnsNullable
    currentFnReturnsArray = prevReturnsArray
    currentFnReturnsSizedArray = prevReturnsSizedArray
    currentFnSizedArraySize = prevSizedArraySize
    currentFnSizedArrayElemType = prevSizedArrayElemType
    currentFnReturnType = prevReturnType
    currentFnOptReturnCTypeName = prevOptRetCTypeName
    currentFnIsMain = prevIsMain
    impl.appendLine("}")
    impl.appendLine()
}

// ── top-level property ───────────────────────────────────────────

internal fun CCodeGen.emitTopProp(d: PropDecl) {
    val t = if (d.type != null) resolveTypeName(d.type) else (inferExprType(d.init) ?: "Int")
    val ct = cTypeStr(t)
    val cName = pfx(d.name)
    val tls = if (d.name in tlsProps) "ktc_tls " else ""
    val qual = if (!d.mutable) "const " else ""
    val mutComment = if (d.mutable) "/*VAR*/ " else "/*VAL*/ "
    if (d.init != null) {
        hdr.appendLine("extern $tls$qual$ct $cName;")
        impl.appendLine("$tls$qual$mutComment$ct $cName = ${genExpr(d.init)};")
    } else {
        hdr.appendLine("extern $tls$ct $cName;")
        impl.appendLine("$tls$mutComment$ct $cName = ${defaultVal(t)};")
    }
    impl.appendLine()
}

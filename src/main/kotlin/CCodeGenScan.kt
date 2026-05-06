package com.bitsycore

/**
 * ── Pre-scanning & Generic Monomorphization ─────────────────────────────
 *
 * This file runs multiple AST pre-passes before any C code is emitted.
 * It discovers all concrete instantiations of generic classes and functions
 * so that the emitter phase has complete type information.
 *
 * ## Pipeline order (called from [CCodeGen.collectAndScan]):
 *
 * 1. [scanForClassArrayTypes]      — discover class types used in Array<T>
 * 2. [scanForGenericInstantiations] — find MyList<Int>, HashMap<K,V> etc.
 * 3. [materializeGenericInstantiations] — create concrete ClassInfo entries
 * 4. [scanForGenericFunCalls]      — discover generic function call sites
 * 5. [scanGenericFunBodiesForInstantiations] — transitive discovery
 * 6. [scanGenericClassMethodBodiesForInstantiations] — from materialized types
 * 7. [computeGenericFunConcreteReturns] — interface-return → concrete type
 *
 * ## State accessed:
 *   classes, interfaces, genericClassDecls, genericIfaceDecls, genericFunDecls,
 *   genericInstantiations, genericFunInstantiations, genericTypeBindings,
 *   classInterfaces, classArrayTypes, allGenericTypeParamNames, pair/triple/tuple maps
 *
 * ## State mutated:
 *   genericInstantiations, genericFunInstantiations, genericFunConcreteReturn,
 *   classes (materialized generic instantiations), classInterfaces, classArrayTypes
 *
 * ## Dependencies:
 *   Calls into [CCodeGenInfer] (inferExprType, inferBlockType)
 *   Calls into [CCodeGenCTypes] (resolveTypeName, resolveIfaceName)
 *   Used by [CCodeGen.collectAndScan] before [CCodeGen.generate]
 */

// ═══════════════════════════ Scanning passes ═══════════════════════

/** Pre-scan AST for Array<T> type references to populate classArrayTypes. */
internal fun CCodeGen.scanForClassArrayTypes() {
    val primitives = setOf(
        "Byte", "Short", "Int", "Long", "Float", "Double", "Boolean", "Char",
        "UByte", "UShort", "UInt", "ULong", "String"
    )
    fun checkType(t: TypeRef?) {
        if (t == null) return
    if (t.name == "Array" && t.typeArgs.isNotEmpty()) {
            val elem = t.typeArgs[0].name
            if (elem !in primitives) classArrayTypes.add(elem)
        }

    }
    fun scanExpr(e: Expr?) {
        if (e == null) return
        if (e is CallExpr) {
            val name = (e.callee as? NameExpr)?.name
            if (name == "arrayOf" && e.args.isNotEmpty()) {
                val firstArg = e.args[0].expr
                if (firstArg is CallExpr) {
                    val argName = (firstArg.callee as? NameExpr)?.name
                    if (argName != null && classes.containsKey(argName)) {
                        classArrayTypes.add(argName)
                    }
                }
            }
            if (name == "arrayOfNulls" && e.typeArgs.isNotEmpty()) {
                val elem = e.typeArgs[0].name
                if (elem !in primitives) classArrayTypes.add(elem)
            }
            // mutableListOf / arrayListOf: no longer built-in
            // HashMap and mapOf use generic runtime — no per-type scanning needed
            // Recurse into args
            for (arg in e.args) scanExpr(arg.expr)
        }
        if (e is BinExpr) { scanExpr(e.left); scanExpr(e.right) }
    }
    fun scanStmt(s: Stmt) {
        when (s) {
            is VarDeclStmt -> { checkType(s.type); scanExpr(s.init) }
            is AssignStmt -> { scanExpr(s.target); scanExpr(s.value) }
            is ExprStmt -> scanExpr(s.expr)
            is ForStmt -> { scanExpr(s.iter); s.body.stmts.forEach(::scanStmt) }
            is WhileStmt -> s.body.stmts.forEach(::scanStmt)
            is DoWhileStmt -> s.body.stmts.forEach(::scanStmt)
            is ReturnStmt -> scanExpr(s.value)
            is DeferStmt -> s.body.stmts.forEach(::scanStmt)
            else -> {}
        }
    }
    fun scanBody(body: Block?) { body?.stmts?.forEach(::scanStmt) }
    for (d in file.decls) {
        when (d) {
            is FunDecl -> {
                for (p in d.params) checkType(p.type)
                d.returnType?.let { checkType(it) }
                scanBody(d.body)
            }
            is ClassDecl -> {
                for (p in d.ctorParams) checkType(p.type)
                for (m in d.members) if (m is FunDecl) {
                    for (p in m.params) checkType(p.type)
                    m.returnType?.let { checkType(it) }
                    scanBody(m.body)
                }
                for (m in d.members) if (m is PropDecl) {
                    checkType(m.type)
                    scanExpr(m.init)
                }
            }
            is PropDecl -> {
                checkType(d.type)
                scanExpr(d.init)
            }
            else -> {}
        }
    }
}

/** Pre-scan AST for concrete instantiations of generic classes (e.g. MyList<Int>). */
internal fun CCodeGen.scanForGenericInstantiations() {
    // Collect all known type param names (from generic classes and generic functions)
    // to avoid treating them as concrete type args
    val allTypeParamNames = mutableSetOf<String>()
    for (d in file.decls) {
        if (d is ClassDecl && d.typeParams.isNotEmpty()) allTypeParamNames += d.typeParams
    }

    for (d in file.decls) {
        when (d) {
            is FunDecl -> {
                // Skip generic functions — they are templates, scanned at call sites
                if (d.typeParams.isNotEmpty()) continue
                // Skip star-projection extension functions — expanded later
                if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) continue
                for (p in d.params) scanTypeRefForGenerics(p.type, allTypeParamNames)
                d.returnType?.let { scanTypeRefForGenerics(it, allTypeParamNames) }
                scanBlockForGenerics(d.body, allTypeParamNames)
            }
            is ClassDecl -> {
                // For generic classes, add their own type params to the skip set
                val skip = allTypeParamNames + d.typeParams
                for (p in d.ctorParams) scanTypeRefForGenerics(p.type, skip)
                for (m in d.members) if (m is FunDecl) {
                    for (p in m.params) scanTypeRefForGenerics(p.type, skip)
                    m.returnType?.let { scanTypeRefForGenerics(it, skip) }
                    scanBlockForGenerics(m.body, skip)
                }
                for (m in d.members) if (m is PropDecl) {
                    scanTypeRefForGenerics(m.type, skip)
                    scanExprForGenerics(m.init, skip)
                }
            }
            is PropDecl -> {
                scanTypeRefForGenerics(d.type, allTypeParamNames)
                scanExprForGenerics(d.init, allTypeParamNames)
            }
            else -> {}
        }
    }
}

internal fun CCodeGen.scanTypeRefForGenerics(t: TypeRef?, skip: Set<String> = emptySet()) {
    if (t == null) return
    if (t.typeArgs.isNotEmpty() && classes.containsKey(t.name) && classes[t.name]!!.isGeneric) {
        // Only record if all type args are concrete (not type params or star projections)
        val concreteArgs = t.typeArgs.map { it.name }
        if (concreteArgs.none { it in skip || it == "*" }) {
            recordGenericInstantiation(t.name, concreteArgs)
        }
    }
    for (arg in t.typeArgs) scanTypeRefForGenerics(arg, skip)
}

internal fun CCodeGen.scanExprForGenerics(e: Expr?, skip: Set<String> = emptySet()) {
    if (e == null) return
    when (e) {
        is CallExpr -> {
            val name = (e.callee as? NameExpr)?.name
            // Constructor call: MyList<Int>(...) or HeapAlloc<MyList<Int>>(...)
            for (ta in e.typeArgs) {
                if (ta.typeArgs.isNotEmpty() && classes.containsKey(ta.name) && classes[ta.name]!!.isGeneric) {
                    val concreteArgs = ta.typeArgs.map { it.name }
                    if (concreteArgs.none { it in skip || it == "*" }) {
                        recordGenericInstantiation(ta.name, concreteArgs)
                    }
                }
            }
            if (name != null && classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
                val concreteArgs = e.typeArgs.map { it.name }
                if (concreteArgs.none { it in skip || it == "*" }) {
                    recordGenericInstantiation(name, concreteArgs)
                }
            }
            for (a in e.args) scanExprForGenerics(a.expr, skip)
            scanExprForGenerics(e.callee, skip)
        }
        is BinExpr -> { scanExprForGenerics(e.left, skip); scanExprForGenerics(e.right, skip) }
        is DotExpr -> scanExprForGenerics(e.obj, skip)
        is SafeDotExpr -> scanExprForGenerics(e.obj, skip)
        is IndexExpr -> { scanExprForGenerics(e.obj, skip); scanExprForGenerics(e.index, skip) }
        is PrefixExpr -> scanExprForGenerics(e.expr, skip)
        is PostfixExpr -> scanExprForGenerics(e.expr, skip)
        is NotNullExpr -> scanExprForGenerics(e.expr, skip)
        is ElvisExpr -> { scanExprForGenerics(e.left, skip); scanExprForGenerics(e.right, skip) }
        is IfExpr -> {
            scanExprForGenerics(e.cond, skip)
            scanBlockForGenerics(e.then, skip)
            scanBlockForGenerics(e.els, skip)
        }
        is CastExpr -> { scanExprForGenerics(e.expr, skip); scanTypeRefForGenerics(e.type, skip) }
        is StrTemplateExpr -> e.parts.forEach { if (it is ExprPart) scanExprForGenerics(it.expr, skip) }
        is LambdaExpr -> e.body.forEach { scanStmtForGenerics(it, skip) }
        else -> {}
    }
}

internal fun CCodeGen.scanStmtForGenerics(s: Stmt, skip: Set<String> = emptySet()) {
    when (s) {
        is VarDeclStmt -> { scanTypeRefForGenerics(s.type, skip); scanExprForGenerics(s.init, skip) }
        is AssignStmt -> { scanExprForGenerics(s.target, skip); scanExprForGenerics(s.value, skip) }
        is ExprStmt -> scanExprForGenerics(s.expr, skip)
        is ForStmt -> { scanExprForGenerics(s.iter, skip); scanBlockForGenerics(s.body, skip) }
        is WhileStmt -> { scanExprForGenerics(s.cond, skip); scanBlockForGenerics(s.body, skip) }
        is DoWhileStmt -> { scanBlockForGenerics(s.body, skip); scanExprForGenerics(s.cond, skip) }
        is ReturnStmt -> scanExprForGenerics(s.value, skip)
        is DeferStmt -> scanBlockForGenerics(s.body, skip)
        else -> {}
    }
}

internal fun CCodeGen.scanBlockForGenerics(block: Block?, skip: Set<String> = emptySet()) { block?.stmts?.forEach { scanStmtForGenerics(it, skip) } }

/**
 * Create concrete ClassInfo entries for each generic instantiation discovered.
 * E.g., MyList<Int> → classes["MyList_Int"] with all T→Int substitution tracked.
 */
internal fun CCodeGen.materializeGenericInstantiations() {
    for ((baseName, instantiations) in genericInstantiations) {
        val templateCi = classes[baseName] ?: continue
        val templateDecl = genericClassDecls[baseName] ?: continue
        for (typeArgs in instantiations) {
            val mangledName = mangledGenericName(baseName, typeArgs)
            if (classes.containsKey(mangledName)) continue  // already materialized
            // Build substitution map: T→Int, U→Float, etc.
            val subst = templateCi.typeParams.zip(typeArgs).toMap()
            // Substitute types in ctor props
            val ctorProps = templateCi.ctorProps.map { (name, type) ->
                name to substituteTypeRef(type, subst)
            }
            // Substitute types in plain ctor params (not stored as fields)
            val ctorPlainParams = templateCi.ctorPlainParams.map { (name, type) ->
                name to substituteTypeRef(type, subst)
            }
            // Substitute types in body props
            val bodyProps = templateCi.bodyProps.map { bp ->
                BodyProp(bp.name, substituteTypeRef(bp.type, subst), bp.init, bp.line, bp.isPrivate)
            }
            val ci = ClassInfo(mangledName, templateCi.isData, ctorProps, ctorPlainParams, bodyProps,
                initBlocks = templateCi.initBlocks, typeParams = templateCi.typeParams, privateProps = templateCi.privateProps)
            // Copy methods from template
            for (m in templateCi.methods) ci.methods += m
            classes[mangledName] = ci
            getTypeId(mangledName)
            // Register symbol prefix for the mangled name (same as template's package)
            symbolPrefix[mangledName] = symbolPrefix[baseName] ?: prefix
            // Store type bindings for resolving method return types later
            genericTypeBindings[mangledName] = subst

            // Resolve super interfaces with type substitution
            if (templateDecl.superInterfaces.isNotEmpty()) {
                val resolvedIfaces = templateDecl.superInterfaces.map { ifaceRef ->
                    val resolved = substituteTypeRef(ifaceRef, subst)
                    resolveIfaceName(resolved)
                }
                classInterfaces[mangledName] = resolvedIfaces
                // Monomorphize each generic interface
                for (ifaceRef in templateDecl.superInterfaces) {
                    val resolved = substituteTypeRef(ifaceRef, subst)
                    materializeGenericInterface(resolved)
                }
            }
        }
    }
}

/** Resolve an interface TypeRef to its concrete name (e.g. MutableList<Int> → "MutableList_Int"). */
internal fun CCodeGen.resolveIfaceName(t: TypeRef): String {
    if (t.typeArgs.isEmpty()) return t.name
    return mangledGenericName(t.name, t.typeArgs.map { resolveTypeName(it) })
}

/**
 * Monomorphize a generic interface template. E.g., List<Int> → creates IfaceInfo("List_Int", ...).
 * Recursively processes super interfaces.
 */
internal fun CCodeGen.materializeGenericInterface(t: TypeRef) {
    if (t.typeArgs.isEmpty()) return  // non-generic, already registered
    val baseName = t.name
    val template = interfaces[baseName] ?: return
    if (template.typeParams.isEmpty()) return  // non-generic template
    val typeArgs = t.typeArgs.map { resolveTypeName(it) }
    val mangledName = mangledGenericName(baseName, typeArgs)
    if (interfaces.containsKey(mangledName)) return  // already materialized
    val subst = template.typeParams.zip(typeArgs).toMap()
    // Substitute types in methods
    val methods = template.methods.map { m ->
        m.copy(
            params = m.params.map { p -> p.copy(type = substituteTypeRef(p.type, subst)) },
            returnType = m.returnType?.let { substituteTypeRef(it, subst) }
        )
    }
    // Substitute types in properties
    val properties = template.properties.map { p ->
        p.copy(type = p.type?.let { substituteTypeRef(it, subst) })
    }
    // Resolve super interfaces
    val resolvedSupers = template.superInterfaces.map { substituteTypeRef(it, subst) }
    interfaces[mangledName] = IfaceInfo(mangledName, methods, properties, emptyList(), resolvedSupers)
    getTypeId(mangledName)
    symbolPrefix[mangledName] = symbolPrefix[baseName] ?: prefix
    // Recursively monomorphize parent interfaces
    for (superRef in resolvedSupers) {
        materializeGenericInterface(superRef)
    }
}

/** Substitute type parameters in a TypeRef: T → Int when subst = {T: Int}. */
internal fun CCodeGen.substituteTypeRef(t: TypeRef, subst: Map<String, String>): TypeRef {
    val newName = subst[t.name] ?: t.name
    val newTypeArgs = t.typeArgs.map { substituteTypeRef(it, subst) }
    return t.copy(name = newName, typeArgs = newTypeArgs)
}

// ── generic function call-site scanning ──────────────────────────

/**
 * Scan call sites for generic functions to determine concrete type bindings.
 * For `fun <T> sizeOfList(list: MutableList<T>)` called with a MutableList_Int arg,
 * we infer T=Int and record the instantiation.
 */
internal fun CCodeGen.scanForGenericFunCalls() {
    val genFunsByName = genericFunDecls.associateBy { it.name }
    if (genFunsByName.isEmpty()) return

    fun inferTypeArgsFromCall(f: FunDecl, callArgs: List<Arg>, explicitTypeArgs: List<TypeRef>): List<String>? {
        if (explicitTypeArgs.isNotEmpty()) {
            return explicitTypeArgs.map { it.name }
        }
        val subst = mutableMapOf<String, String>()
        for ((i, param) in f.params.withIndex()) {
            if (i >= callArgs.size) break
            val argExpr = callArgs[i].expr
            val argType = inferExprType(argExpr) ?: continue
            matchTypeParam(param.type, argType, f.typeParams.toSet(), subst)
        }
        if (subst.size == f.typeParams.size) {
            return f.typeParams.map { subst[it]!! }
        }
        return null
    }

    fun inferTypeArgsFromReceiver(f: FunDecl, recvType: String, callArgs: List<Arg>, explicitTypeArgs: List<TypeRef>): List<String>? {
        if (explicitTypeArgs.isNotEmpty()) {
            return explicitTypeArgs.map { it.name }
        }
        val subst = mutableMapOf<String, String>()
        // Match receiver type
        if (f.receiver != null) {
            matchTypeParam(f.receiver, recvType, f.typeParams.toSet(), subst)
        }
        // Match regular params
        for ((i, param) in f.params.withIndex()) {
            if (i >= callArgs.size) break
            val argExpr = callArgs[i].expr
            val argType = inferExprType(argExpr) ?: continue
            matchTypeParam(param.type, argType, f.typeParams.toSet(), subst)
        }
        if (subst.size == f.typeParams.size) {
            return f.typeParams.map { subst[it]!! }
        }
        return null
    }

    // Use member functions to avoid Kotlin forward-reference issues with local functions
    val scanner = object {
        fun scanExpr(e: Expr?) {
            if (e == null) return
            when (e) {
                is CallExpr -> {
                    val name = (e.callee as? NameExpr)?.name
                    if (name != null && genFunsByName.containsKey(name)) {
                        val f = genFunsByName[name]!!
                        val typeArgs = inferTypeArgsFromCall(f, e.args, e.typeArgs)
                        if (typeArgs != null) {
                            genericFunInstantiations.getOrPut(name) { mutableSetOf() }.add(typeArgs)
                        }
                    }
                    // Generic extension call: map.tryDispose() where tryDispose is generic
                    if (e.callee is DotExpr) {
                        val dotName = e.callee.name
                        if (genFunsByName.containsKey(dotName)) {
                            val f = genFunsByName[dotName]!!
                            val recvType = inferExprType(e.callee.obj)
                            val typeArgs = if (f.receiver != null && recvType != null) {
                                // Infer type args from receiver type
                                inferTypeArgsFromReceiver(f, recvType, e.args, e.typeArgs)
                            } else null
                            if (typeArgs != null) {
                                genericFunInstantiations.getOrPut(dotName) { mutableSetOf() }.add(typeArgs)
                                // Also register in extensionFuns for method dispatch
                                val mangledRecvName = substituteTypeRef(f.receiver!!, f.typeParams.zip(typeArgs).toMap()).let {
                                    resolveTypeName(it)
                                }
                                extensionFuns.getOrPut(mangledRecvName) { mutableListOf() }.add(f)
                            }
                        }
                    }
                    for (a in e.args) scanExpr(a.expr)
                    scanExpr(e.callee)
                }
                is BinExpr -> { scanExpr(e.left); scanExpr(e.right) }
                is DotExpr -> scanExpr(e.obj)
                is SafeDotExpr -> scanExpr(e.obj)
                is IndexExpr -> { scanExpr(e.obj); scanExpr(e.index) }
                is PrefixExpr -> scanExpr(e.expr)
                is PostfixExpr -> scanExpr(e.expr)
                is NotNullExpr -> scanExpr(e.expr)
                is ElvisExpr -> { scanExpr(e.left); scanExpr(e.right) }
                is IfExpr -> { scanExpr(e.cond); scanBlock(e.then); scanBlock(e.els) }
                is CastExpr -> scanExpr(e.expr)
                is StrTemplateExpr -> e.parts.forEach { if (it is ExprPart) scanExpr(it.expr) }
                else -> {}
            }
        }
        fun scanStmt(s: Stmt) {
            when (s) {
                is VarDeclStmt -> {
                    scanExpr(s.init)
                    // Track variable type for subsequent type inference in this function
                    val varType = if (s.type != null) resolveTypeName(s.type) else inferExprType(s.init)
                    if (varType != null) preScanVarTypes?.set(s.name, varType)
                }
                is AssignStmt -> { scanExpr(s.target); scanExpr(s.value) }
                is ExprStmt -> scanExpr(s.expr)
                is ForStmt -> { scanExpr(s.iter); scanBlock(s.body) }
                is WhileStmt -> { scanExpr(s.cond); scanBlock(s.body) }
                is DoWhileStmt -> { scanBlock(s.body); scanExpr(s.cond) }
                is ReturnStmt -> scanExpr(s.value)
                is DeferStmt -> scanBlock(s.body)
                else -> {}
            }
        }
        fun scanBlock(b: Block?) { b?.stmts?.forEach(::scanStmt) }
    }

    preScanVarTypes = mutableMapOf()
    for (d in file.decls) {
        when (d) {
            is FunDecl -> if (d.typeParams.isEmpty()) {
                preScanVarTypes!!.clear()  // fresh var scope per function
                // Register function params so they're available for inference
                for (p in d.params) {
                    preScanVarTypes!![p.name] = resolveTypeName(p.type)
                }
                scanner.scanBlock(d.body)
            }
            is ClassDecl -> {
                for (m in d.members) if (m is FunDecl) {
                    preScanVarTypes!!.clear()
                    for (p in m.params) {
                        preScanVarTypes!![p.name] = resolveTypeName(p.type)
                    }
                    scanner.scanBlock(m.body)
                }
            }
            else -> {}
        }
    }
    preScanVarTypes = null
}

/**
 * Scan generic function bodies for generic class instantiations that only become
 * concrete after type parameter substitution.
 * E.g., `fun <T> listOf(vararg items: T): List<T>` body has `ArrayList<T>(items.size)`.
 * When listOf is called with T=Int, we need to discover ArrayList<Int>.
 * Iterates until no new instantiations are found (handles transitive cases).
 */
internal fun CCodeGen.scanGenericFunBodiesForInstantiations() {
    var changed = true
    while (changed) {
        changed = false
        for ((funName, instantiations) in genericFunInstantiations.toMap()) {
            val funDecl = genericFunDecls.find { it.name == funName } ?: continue
            for (typeArgs in instantiations.toSet()) {
                val subst = funDecl.typeParams.zip(typeArgs).toMap()
                if (scanBodyWithSubst(funDecl.body, subst)) changed = true
            }
        }
    }
}

/**
 * Scan method bodies and return types of materialized generic classes for further
 * generic class instantiations. E.g., HashMap<Int,String>.iterator() returns
 * MapIterator<K,V> which with {K→Int,V→String} becomes MapIterator<Int,String>.
 * Iterates to fixpoint so transitive discoveries are handled.
 */
internal fun CCodeGen.scanGenericClassMethodBodiesForInstantiations() {
    var changed = true
    while (changed) {
        changed = false
        for ((baseName, instantiations) in genericInstantiations.toMap()) {
            val templateCi = classes[baseName] ?: continue
            if (!templateCi.isGeneric) continue
            val templateDecl = genericClassDecls[baseName] ?: continue
            for (typeArgs in instantiations.toSet()) {
                val subst = templateCi.typeParams.zip(typeArgs).toMap()
                // Scan method bodies and return types
                for (m in templateDecl.members) {
                    if (m is FunDecl) {
                        if (m.returnType != null && scanTypeRefWithSubst(m.returnType, subst)) changed = true
                        for (p in m.params) if (scanTypeRefWithSubst(p.type, subst)) changed = true
                        if (scanBodyWithSubst(m.body, subst)) changed = true
                    }
                }
                // Scan body property types and initializers
                for (m in templateDecl.members) {
                    if (m is PropDecl) {
                        if (m.type != null && scanTypeRefWithSubst(m.type, subst)) changed = true
                        if (scanExprWithSubst(m.init, subst)) changed = true
                    }
                }
                // Scan ctor param types
                for (p in templateDecl.ctorParams) {
                    if (scanTypeRefWithSubst(p.type, subst)) changed = true
                }
                // Scan init blocks
                for (initBlock in templateDecl.initBlocks) {
                    if (scanBodyWithSubst(initBlock, subst)) changed = true
                }
            }
        }
        // Re-materialize any newly discovered instantiations within the loop
        if (changed) materializeGenericInstantiations()
    }
}

internal fun CCodeGen.scanBodyWithSubst(block: Block?, subst: Map<String, String>): Boolean {
    if (block == null) return false
    var found = false
    for (s in block.stmts) if (scanStmtWithSubst(s, subst)) found = true
    return found
}

internal fun CCodeGen.scanStmtWithSubst(s: Stmt, subst: Map<String, String>): Boolean {
    return when (s) {
        is VarDeclStmt -> {
            var f = scanExprWithSubst(s.init, subst)
            if (s.type != null && scanTypeRefWithSubst(s.type, subst)) f = true
            f
        }
        is AssignStmt -> {
            var f = scanExprWithSubst(s.target, subst)
            if (scanExprWithSubst(s.value, subst)) f = true
            f
        }
        is ExprStmt -> scanExprWithSubst(s.expr, subst)
        is ForStmt -> {
            var f = scanExprWithSubst(s.iter, subst)
            if (scanBodyWithSubst(s.body, subst)) f = true
            f
        }
        is WhileStmt -> {
            var f = scanExprWithSubst(s.cond, subst)
            if (scanBodyWithSubst(s.body, subst)) f = true
            f
        }
        is DoWhileStmt -> {
            var f = scanBodyWithSubst(s.body, subst)
            if (scanExprWithSubst(s.cond, subst)) f = true
            f
        }
        is ReturnStmt -> scanExprWithSubst(s.value, subst)
        is DeferStmt -> scanBodyWithSubst(s.body, subst)
        else -> false
    }
}

internal fun CCodeGen.scanExprWithSubst(e: Expr?, subst: Map<String, String>): Boolean {
    if (e == null) return false
    return when (e) {
        is CallExpr -> {
            var found = false
            val name = (e.callee as? NameExpr)?.name
            // Constructor call to generic class: ArrayList<T>(...) → with T=Int → ArrayList_Int
            if (name != null && classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
                val resolvedArgs = e.typeArgs.map { subst[it.name] ?: it.name }
                if (resolvedArgs.none { it in allGenericTypeParamNames }) {
                    if (genericInstantiations[name]?.contains(resolvedArgs) != true) {
                        recordGenericInstantiation(name, resolvedArgs)
                        found = true
                    }
                }
            }
            // Check typeArgs for nested generic types (e.g., HeapAlloc<Array<T>>)
            for (ta in e.typeArgs) if (scanTypeRefWithSubst(ta, subst)) found = true
            // Check if it's a call to another generic function with resolvable type args
            if (name != null) {
                val genFun = genericFunDecls.find { it.name == name }
                if (genFun != null && e.typeArgs.isNotEmpty()) {
                    val resolvedArgs = e.typeArgs.map { subst[it.name] ?: it.name }
                    if (resolvedArgs.none { it in allGenericTypeParamNames }) {
                        val existing = genericFunInstantiations[name]
                        if (existing == null || resolvedArgs !in existing) {
                            genericFunInstantiations.getOrPut(name) { mutableSetOf() }.add(resolvedArgs)
                            found = true
                        }
                    }
                }
            }
            for (a in e.args) if (scanExprWithSubst(a.expr, subst)) found = true
            if (scanExprWithSubst(e.callee, subst)) found = true
            found
        }
        is BinExpr -> {
            var f = scanExprWithSubst(e.left, subst)
            if (scanExprWithSubst(e.right, subst)) f = true
            f
        }
        is DotExpr -> scanExprWithSubst(e.obj, subst)
        is SafeDotExpr -> scanExprWithSubst(e.obj, subst)
        is IndexExpr -> {
            var f = scanExprWithSubst(e.obj, subst)
            if (scanExprWithSubst(e.index, subst)) f = true
            f
        }
        is PrefixExpr -> scanExprWithSubst(e.expr, subst)
        is PostfixExpr -> scanExprWithSubst(e.expr, subst)
        is NotNullExpr -> scanExprWithSubst(e.expr, subst)
        is ElvisExpr -> {
            var f = scanExprWithSubst(e.left, subst)
            if (scanExprWithSubst(e.right, subst)) f = true
            f
        }
        is IfExpr -> {
            var f = scanExprWithSubst(e.cond, subst)
            if (scanBodyWithSubst(e.then, subst)) f = true
            if (scanBodyWithSubst(e.els, subst)) f = true
            f
        }
        is CastExpr -> {
            var f = scanExprWithSubst(e.expr, subst)
            if (scanTypeRefWithSubst(e.type, subst)) f = true
            f
        }
        is StrTemplateExpr -> {
            var f = false
            for (p in e.parts) if (p is ExprPart && scanExprWithSubst(p.expr, subst)) f = true
            f
        }
        is LambdaExpr -> {
            var f = false
            for (stmt in e.body) if (scanStmtWithSubst(stmt, subst)) f = true
            f
        }
        else -> false
    }
}

internal fun CCodeGen.scanTypeRefWithSubst(t: TypeRef, subst: Map<String, String>): Boolean {
    var found = false
    if (t.typeArgs.isNotEmpty()) {
        val resolvedArgs = t.typeArgs.map { subst[it.name] ?: it.name }
        // Generic class instantiation
        if (classes.containsKey(t.name) && classes[t.name]!!.isGeneric) {
            if (resolvedArgs.none { it in allGenericTypeParamNames }) {
                if (genericInstantiations[t.name]?.contains(resolvedArgs) != true) {
                    recordGenericInstantiation(t.name, resolvedArgs)
                    found = true
                }
            }
        }
    }
    for (arg in t.typeArgs) if (scanTypeRefWithSubst(arg, subst)) found = true
    return found
}

/**
 * Pre-compute concrete return types for generic functions whose declared return
 * type is an interface but whose body returns a concrete class instance.
 * This enables returning the class by value (on the stack) instead of heap-allocating.
 */
internal fun CCodeGen.computeGenericFunConcreteReturns() {
    for ((funName, instantiations) in genericFunInstantiations) {
        val funDecl = genericFunDecls.find { it.name == funName } ?: continue
        if (funDecl.returnType == null) continue
        for (typeArgs in instantiations) {
            val subst = funDecl.typeParams.zip(typeArgs).toMap()
            val prevSubst = typeSubst
            typeSubst = subst
            val resolvedReturn = resolveTypeName(funDecl.returnType)
            if (interfaces.containsKey(resolvedReturn)) {
                val concrete = inferConcreteReturnClass(funDecl.body, subst)
                if (concrete != null) {
                    val mangledName = "${funName}_${typeArgs.joinToString("_")}"
                    genericFunConcreteReturn[mangledName] = concrete
                }
            }
            typeSubst = prevSubst
        }
    }
}

/**
 * Scan a function body to determine the concrete class returned.
 * Traces return statements back to var declarations with class constructor inits.
 */
internal fun CCodeGen.inferConcreteReturnClass(body: Block?, subst: Map<String, String>): String? {
    if (body == null) return null
    val varInits = mutableMapOf<String, Expr?>()
    for (s in body.stmts) {
        if (s is VarDeclStmt) varInits[s.name] = s.init
    }
    for (s in body.stmts) {
        if (s is ReturnStmt && s.value is NameExpr) {
            val varName = s.value.name
            val init = varInits[varName]
            if (init is CallExpr) {
                val calleeName = (init.callee as? NameExpr)?.name
                if (calleeName != null && classes.containsKey(calleeName) && classes[calleeName]!!.isGeneric) {
                    val resolvedArgs = init.typeArgs.map { subst[it.name] ?: it.name }
                    val mangledName = mangledGenericName(calleeName, resolvedArgs)
                    if (classes.containsKey(mangledName)) return mangledName
                }
                if (calleeName != null && classes.containsKey(calleeName) && !classes[calleeName]!!.isGeneric) {
                    return calleeName
                }
            }
        }
    }
    return null
}

/**
 * Match a parameter type against a concrete argument type to infer type params.
 * E.g., param=MutableList<T>, argType="MutableList_Int", typeParams={T} → subst[T]=Int
 */
internal fun CCodeGen.matchTypeParam(paramType: TypeRef, argType: String, typeParams: Set<String>, subst: MutableMap<String, String>) {
    // Direct type param: param is T, arg is Int → T=Int
    if (paramType.name in typeParams) {
        subst[paramType.name] = argType
        return
    }
    // Generic class param: MutableList<T>, arg=MutableList_Int → T=Int
    if (paramType.typeArgs.isNotEmpty() && classes.containsKey(paramType.name) && classes[paramType.name]!!.isGeneric) {
        val baseType = argType.trimEnd('*', '?')
        val bindings = genericTypeBindings[baseType] ?: return
        val templateCi = classes[paramType.name] ?: return
        for ((i, typeArg) in paramType.typeArgs.withIndex()) {
            if (typeArg.name in typeParams && i < templateCi.typeParams.size) {
                val templateParam = templateCi.typeParams[i]
                bindings[templateParam]?.let { subst[typeArg.name] = it }
            }
        }
    }
    // Generic interface param: List<T>, arg=ArrayList_Int → T=Int
    // Look up what interface the arg class implements and extract type bindings from the mangled name
    if (paramType.typeArgs.isNotEmpty() && genericIfaceDecls.containsKey(paramType.name)) {
        val baseType = argType.trimEnd('*', '?')
        val ifaceTemplate = genericIfaceDecls[paramType.name] ?: return
        // Check if the arg class implements a monomorphized version of this interface
        val classIfaces = classInterfaces[baseType] ?: return
        for (ifaceName in classIfaces) {
            // Match monomorphized interface name like "List_Int" against template "List"
            if (ifaceName.startsWith(paramType.name + "_")) {
                // Extract type args from the mangled name, e.g., "List_Int" → ["Int"]
                val suffix = ifaceName.removePrefix(paramType.name + "_")
                val extractedArgs = suffix.split("_")
                for ((i, typeArg) in paramType.typeArgs.withIndex()) {
                    if (typeArg.name in typeParams && i < extractedArgs.size) {
                        subst[typeArg.name] = extractedArgs[i]
                    }
                }
                return
            }
        }
    }
    // Intrinsic Pair<A,B> param: Pair<K, V>, arg=Pair_Int_String → K=Int, V=String
    if (paramType.name == "Pair" && paramType.typeArgs.size == 2
        && !classes.containsKey("Pair") && !genericClassDecls.containsKey("Pair")) {
        val baseType = argType.trimEnd('*', '?')
        val components = pairTypeComponents[baseType]
        if (components != null) {
            val (first, second) = components
            if (paramType.typeArgs[0].name in typeParams) subst[paramType.typeArgs[0].name] = first
            if (paramType.typeArgs[1].name in typeParams) subst[paramType.typeArgs[1].name] = second
        }
    }
    // Intrinsic Triple<A,B,C> param decomposition
    if (paramType.name == "Triple" && paramType.typeArgs.size == 3
        && !classes.containsKey("Triple") && !genericClassDecls.containsKey("Triple")) {
        val baseType = argType.trimEnd('*', '?')
        val components = tripleTypeComponents[baseType]
        if (components != null) {
            val names = paramType.typeArgs.map { it.name }
            if (names[0] in typeParams) subst[names[0]] = components.first
            if (names[1] in typeParams) subst[names[1]] = components.second
            if (names[2] in typeParams) subst[names[2]] = components.third
        }
    }
    // Intrinsic Tuple<...> param decomposition
    if (paramType.name == "Tuple" && paramType.typeArgs.isNotEmpty()
        && !classes.containsKey("Tuple") && !genericClassDecls.containsKey("Tuple")) {
        val baseType = argType.trimEnd('*', '?')
        val components = tupleTypeComponents[baseType]
        if (components != null) {
            for ((i, name) in paramType.typeArgs.map { it.name }.withIndex()) {
                if (name in typeParams && i < components.size) subst[name] = components[i]
            }
        }
    }
}

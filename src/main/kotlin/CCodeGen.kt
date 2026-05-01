package com.bitsycore

/**
 * Translates a parsed KtFile AST into C11 source code.
 *
 * Symbol naming:  package dots → underscores → prefix.
 *   package game          →  game_ClassName, game_funcName
 *   package com.foo.bar   →  com_foo_bar_ClassName
 *   (no package)          →  bare names
 *
 * `fun main()` is never prefixed — always emits `int main(void)`.
 */
class CCodeGen(private val file: KtFile, private val allFiles: List<KtFile> = listOf(), private val sourceLines: List<String> = emptyList(), private val memTrack: Boolean = false, private val sourceFileName: String = "") {

    // ── Package prefix ───────────────────────────────────────────────
    private val prefix: String = file.pkg?.replace('.', '_')?.plus("_") ?: ""

    // Map symbol name → its C prefix (for cross-file references)
    private val symbolPrefix = mutableMapOf<String, String>()

    private fun pfx(name: String): String {
        if (name == "main") return name
        // Look up if this symbol belongs to a different package
        val sp = symbolPrefix[name]
        if (sp != null) return "$sp$name"
        return "$prefix$name"
    }

    // ── Symbol tables (populated by collectDecls) ────────────────────
    private data class BodyProp(val name: String, val type: TypeRef, val init: Expr?, val line: Int = 0)

    private data class ClassInfo(
        val name: String, val isData: Boolean,
        val ctorProps: List<Pair<String, TypeRef>>,
        val ctorPlainParams: List<Pair<String, TypeRef>> = emptyList(),
        val bodyProps: List<BodyProp> = emptyList(),
        val methods: MutableList<FunDecl> = mutableListOf(),
        val initBlocks: List<Block> = emptyList(),
        val typeParams: List<String> = emptyList()
    ) {
        val props: List<Pair<String, TypeRef>>
            get() = ctorProps + bodyProps.map { it.name to it.type }
        val isGeneric get() = typeParams.isNotEmpty()
    }

    private data class EnumInfo(val name: String, val entries: List<String>)
    private data class ObjInfo(val name: String, val props: List<Pair<String, TypeRef>>, val methods: MutableList<FunDecl> = mutableListOf())
    private data class FunSig(val params: List<Param>, val returnType: TypeRef?)

    private data class IfaceInfo(
        val name: String,
        val methods: List<FunDecl>,
        val properties: List<PropDecl> = emptyList(),
        val typeParams: List<String> = emptyList(),
        val superInterfaces: List<TypeRef> = emptyList()
    )

    private val classes  = mutableMapOf<String, ClassInfo>()
    private val enums    = mutableMapOf<String, EnumInfo>()
    private val objects  = mutableMapOf<String, ObjInfo>()
    private val funSigs  = mutableMapOf<String, FunSig>()
    private val topProps = mutableSetOf<String>()  // top-level property names (need pfx)
    private val extensionFuns = mutableMapOf<String, MutableList<FunDecl>>()
    private val interfaces = mutableMapOf<String, IfaceInfo>()

    // Generic functions: fun <T> name(...) — stored as templates
    private val genericFunDecls = mutableListOf<FunDecl>()
    // Star-projection extension functions: fun Foo<*>.name() — stored for expansion
    private val starExtFunDecls = mutableListOf<FunDecl>()
    // Concrete instantiations of generic functions: mangledName → (FunDecl, typeSubst)
    private val genericFunInstantiations = mutableMapOf<String, MutableSet<List<String>>>()
    // Maps mangled generic function name → concrete class return type when the declared return
    // type is an interface but the body returns a concrete class (enables stack return)
    private val genericFunConcreteReturn = mutableMapOf<String, String>()
    /** Check if a method on baseType has a nullable receiver declaration. */
    private fun hasNullableReceiverExt(baseType: String, method: String): Boolean {
        return extensionFuns[baseType]?.any { it.name == method && it.receiver?.nullable == true } == true
    }

    // Map class name → list of interface names it implements
    private val classInterfaces = mutableMapOf<String, List<String>>()

    // Track class/enum types used in Array<T> so we emit KT_ARRAY_DEF for them
    private val classArrayTypes = mutableSetOf<String>()

    // Intrinsic Pair<A,B> types: track unique (A, B) pairs used
    private val pairTypes = mutableSetOf<Pair<String, String>>()
    private val emittedPairTypes = mutableSetOf<String>()
    private val pairTypeComponents = mutableMapOf<String, Pair<String, String>>()


    // ── Generics (monomorphization) ──────────────────────────────────
    // Store original ClassDecl for generic classes so we can re-emit per instantiation
    private val genericClassDecls = mutableMapOf<String, ClassDecl>()
    // Store original InterfaceDecl for generic interfaces so we can monomorphize them
    private val genericIfaceDecls = mutableMapOf<String, InterfaceDecl>()
    // Track which source file a generic declaration came from (for mem-track attribution)
    private val declSourceFile = mutableMapOf<String, String>()
    // Active type parameter substitution map during monomorphized emission (e.g. {T → Int})
    private var typeSubst: Map<String, String> = emptyMap()
    // Track all discovered concrete instantiations: "MyList" → [["Int"], ["Float"]]
    private val genericInstantiations = mutableMapOf<String, MutableSet<List<String>>>()
    // All known type parameter names from generic classes and functions (e.g. "T", "U")
    // Used to prevent registering type params as concrete instantiations
    private val allGenericTypeParamNames = mutableSetOf<String>()

    /** Mangle a generic class name with concrete type args: MyList + [Int] → "MyList_Int" */
    private fun mangledGenericName(baseName: String, typeArgs: List<String>): String {
        return "${baseName}_${typeArgs.joinToString("_")}"
    }

    /** Record a concrete instantiation of a generic class and return the mangled name. */
    private fun recordGenericInstantiation(baseName: String, typeArgs: List<String>): String {
        genericInstantiations.getOrPut(baseName) { mutableSetOf() }.add(typeArgs)
        return mangledGenericName(baseName, typeArgs)
    }

    // Maps mangled concrete name → type substitution (e.g. "MyList_Int" → {T: "Int"})
    private val genericTypeBindings = mutableMapOf<String, Map<String, String>>()

    // ── Per-scope variable → type mapping ────────────────────────────
    private val scopes = ArrayDeque<MutableMap<String, String>>()
    private fun pushScope() { scopes.addLast(mutableMapOf()) }
    private fun popScope()  { scopes.removeLast() }
    private fun defineVar(name: String, type: String) { scopes.last()[name] = type }
    private fun lookupVar(name: String): String? { for (i in scopes.indices.reversed()) { scopes[i][name]?.let { return it } }; return preScanVarTypes?.get(name) }

    // Temporary variable type map used during scanForGenericFunCalls pre-pass
    // (allows inferExprType to resolve variable types before codegen defineVar runs)
    private var preScanVarTypes: MutableMap<String, String>? = null

    // Track mutable (var) variables — smart casts are only valid on val
    private val mutableVars = mutableSetOf<String>()
    private fun markMutable(name: String) { mutableVars.add(name) }
    private fun isMutable(name: String): Boolean = name in mutableVars

    // ── Current class context (when generating methods) ──────────────
    private var currentClass: String? = null
    private var selfIsPointer = true
    private var currentExtRecvType: String? = null

    /** Returns the class name if `type` is a heap pointer to a known class, else null.
     *  Works for all Heap variants: "T*", "T*?", "T*#" */
    private fun heapClassName(type: String?): String? {
        if (type == null) return null
        val t = type.removeSuffix("?").removeSuffix("#")
        if (!t.endsWith("*")) return null
        val base = t.dropLast(1)
        return if (classes.containsKey(base)) base else null
    }

    /** True if the internal type is a heap class pointer (any variant). */
    private fun isHeapPointerType(type: String?): Boolean = heapClassName(type) != null

    /** True if type is Heap<T?> — value-nullable, pointer always allocated, uses $has. */
    private fun isHeapValueNullable(type: String?): Boolean =
        type != null && type.endsWith("*#") && heapClassName(type) != null

    /** True if type is Heap<T>? — pointer-nullable, uses NULL. */
    private fun isHeapPtrNullable(type: String?): Boolean =
        type != null && type.endsWith("*?") && heapClassName(type) != null

    // ── Ptr<T> helpers (internal marker: "T^", "T^?", "T^#") ────────

    /** Returns the class name if `type` is a Ptr<T>, else null. */
    private fun ptrClassName(type: String?): String? {
        if (type == null) return null
        val t = type.removeSuffix("?").removeSuffix("#")
        if (!t.endsWith("^")) return null
        val base = t.dropLast(1)
        return if (classes.containsKey(base)) base else null
    }

    /** True if the internal type is a Ptr<T> (any variant). */
    private fun isPtrType(type: String?): Boolean = ptrClassName(type) != null

    /** True if type is Ptr<T>? — pointer-nullable via NULL. */
    private fun isPtrPtrNullable(type: String?): Boolean =
        type != null && type.endsWith("^?") && ptrClassName(type) != null

    /** True if type is Ptr<T?> — value-nullable via $has. */
    private fun isPtrValueNullable(type: String?): Boolean =
        type != null && type.endsWith("^#") && ptrClassName(type) != null

    // ── Value<T> helpers (internal marker: "T&", "T&?", "T&#") ──────

    /** Returns the class name if `type` is a Value<T>, else null. */
    private fun valueClassName(type: String?): String? {
        if (type == null) return null
        val t = type.removeSuffix("?").removeSuffix("#")
        if (!t.endsWith("&")) return null
        val base = t.dropLast(1)
        return if (classes.containsKey(base)) base else null
    }

    /** True if the internal type is a Value<T> (any variant). */
    private fun isValueType(type: String?): Boolean = valueClassName(type) != null

    /** True if type is Value<T>? — pointer-nullable via NULL. */
    private fun isValuePtrNullable(type: String?): Boolean =
        type != null && type.endsWith("&?") && valueClassName(type) != null

    /** True if type is Value<T?> — value-nullable via $has. */
    private fun isValueValueNullable(type: String?): Boolean =
        type != null && type.endsWith("&#") && valueClassName(type) != null

    /** Returns the class name for any indirect type: Heap<T>, Ptr<T>, or Value<T>. */
    private fun anyIndirectClassName(type: String?): String? =
        heapClassName(type) ?: ptrClassName(type) ?: valueClassName(type)

    /** True if type is a function pointer type: "Fun(P1,P2)->R" */
    private fun isFuncType(t: String): Boolean = t.startsWith("Fun(")

    /** Parse a function type string "Fun(P1,P2)->R" into (paramTypes, returnType) */
    private fun parseFuncType(t: String): Pair<List<String>, String> {
        // Format: Fun(P1,P2,...)->R
        val inner = t.removePrefix("Fun(")
        val parenEnd = inner.indexOf(")->")
        val paramStr = inner.substring(0, parenEnd)
        val retType = inner.substring(parenEnd + 3)
        val params = if (paramStr.isEmpty()) emptyList() else paramStr.split(",")
        return params to retType
    }

    /** Emit a C function pointer declaration: "retType (*name)(paramTypes)" */
    private fun cFuncPtrDecl(t: String, name: String): String {
        val (params, ret) = parseFuncType(t)
        val cRet = cTypeStr(ret)
        val cParams = if (params.isEmpty()) "void" else params.joinToString(", ") { cTypeStr(it) }
        return "$cRet (*$name)($cParams)"
    }

    // ── Nullable return tracking ─────────────────────────────────────
    private var currentFnReturnsNullable = false
    private var currentFnReturnsArray = false
    private var currentFnReturnType: String = ""
    private var currentFnIsMain = false
    private fun currentFnReturnBaseType(): String = currentFnReturnType.removeSuffix("?")

    // ── Source location tracking for error messages ──────────────────
    private var currentStmtLine: Int = 0
    /** Mutable source file name for mem-track attribution.
     *  Overridden when emitting generic instantiations from other packages (e.g. stdlib). */
    private var currentSourceFile: String = sourceFileName

    /** Throw an error with source context around the given line. */
    private fun codegenError(msg: String): Nothing {
        val line = currentStmtLine
        if (line > 0 && sourceLines.isNotEmpty()) {
            val sb = StringBuilder()
            sb.appendLine(msg)
            val from = maxOf(0, line - 3)
            val to = minOf(sourceLines.size, line + 2)
            for (i in from until to) {
                val lineNum = i + 1   // 1-indexed
                val marker = if (lineNum == line) ">>>" else "   "
                sb.appendLine("$marker %4d | %s".format(lineNum, sourceLines[i]))
            }
            error(sb.toString().trimEnd())
        } else {
            error(msg)
        }
    }

    // ── Defer stack (LIFO: last deferred = first to execute) ─────────
    private val deferStack = mutableListOf<Block>()

    /** Emit all deferred blocks in LIFO order (does NOT clear the stack). */
    private fun emitDeferredBlocks(ind: String, insideMethod: Boolean = false) {
        for (i in deferStack.indices.reversed()) {
            for (s in deferStack[i].stmts) emitStmt(s, ind, insideMethod)
        }
    }

    // ── Temp counter for stack buffers ───────────────────────────────
    private var tmpCounter = 0
    private fun tmp(): String = "\$${tmpCounter++}"

    // ── Memory tracking helpers (Kotlin source attribution) ──────────
    /** Kotlin source location string for current statement, e.g. `"File.kt", 42` */
    private fun ktSrc(): String = "\"$currentSourceFile\", $currentStmtLine"
    private fun tMalloc(sizeExpr: String) = if (memTrack) "ktc_malloc($sizeExpr, ${ktSrc()})" else "malloc($sizeExpr)"
    private fun tCalloc(nExpr: String, sizeExpr: String) = if (memTrack) "ktc_calloc($nExpr, $sizeExpr, ${ktSrc()})" else "calloc($nExpr, $sizeExpr)"
    private fun tRealloc(ptrExpr: String, sizeExpr: String) = if (memTrack) "ktc_realloc($ptrExpr, $sizeExpr, ${ktSrc()})" else "realloc($ptrExpr, $sizeExpr)"
    private fun tFree(ptrExpr: String) = if (memTrack) "ktc_free($ptrExpr, ${ktSrc()})" else "free($ptrExpr)"

    // ── Pre-statements (hoisted before the current statement) ────────
    private val preStmts = mutableListOf<String>()
    private fun flushPreStmts(ind: String) {
        for (s in preStmts) impl.appendLine("$ind$s")
        preStmts.clear()
    }

    // ── Output sections ──────────────────────────────────────────────
    private val hdr   = StringBuilder()   // .h forward decls & typedefs
    private val impl  = StringBuilder()   // .c implementations

    // ═══════════════════════════ Public entry ═════════════════════════

    data class COutput(val header: String, val source: String)

    fun generate(): COutput {
        collectDecls()

        hdr.appendLine("#pragma once")
        if (memTrack) hdr.appendLine("#define KTC_MEM_TRACK")
        hdr.appendLine("#include \"ktc_intrinsic.h\"")
        hdr.appendLine()

        // Imports → #include (skip ktc stdlib imports — handled below)
        for (imp in file.imports) {
            if (imp.startsWith("ktc")) continue
            val parts = imp.removeSuffix(".*").split('.')
            val headerName = parts.joinToString("_")
            hdr.appendLine("#include \"$headerName.h\"")
        }
        // Auto-include ktc.h for non-stdlib packages when stdlib is present
        val hasStdlib = allFiles.any { it.pkg == "ktc" }
        if (hasStdlib && file.pkg != "ktc") {
            hdr.appendLine("#include \"ktc.h\"")
        }
        hdr.appendLine()

        // Pre-scan for Array<T> type references to discover class array types early
        scanForClassArrayTypes()

        // Pre-scan for generic class instantiations and materialize concrete types
        scanForGenericInstantiations()
        materializeGenericInstantiations()

        // Scan for generic function call sites (must happen after materialization
        // so we know concrete class types for argument type inference)
        scanForGenericFunCalls()

        // Scan generic function bodies for class instantiations that only become
        // concrete after type substitution (e.g. ArrayList<T>() inside listOf<T>
        // becomes ArrayList<Int> when listOf is called with Int)
        scanGenericFunBodiesForInstantiations()
        materializeGenericInstantiations()

        // Pre-compute concrete return types for generic functions returning interfaces
        // (enables returning concrete class by value on stack instead of heap-allocating)
        computeGenericFunConcreteReturns()

        // Emit interface vtable struct + fat pointer type BEFORE classes
        // Non-generic interfaces first (they only use primitive types in signatures)
        val emittedIfaceNames = mutableSetOf<String>()
        for (d in file.decls) if (d is InterfaceDecl && d.typeParams.isEmpty()) {
            emitInterface(d)
            emittedIfaceNames += d.name
        }

        // Emit struct/enum/object declarations (defines the element types needed by generic interfaces)
        // Skip generic templates — they are emitted per concrete instantiation
        for (d in file.decls) when (d) {
            is ClassDecl  -> if (d.typeParams.isEmpty()) emitClass(d)
            is EnumDecl   -> emitEnum(d)
            is ObjectDecl -> emitObject(d)
            else -> {}
        }

        // Emit monomorphized generic interfaces AFTER class structs so types are available
        for ((name, info) in interfaces) {
            // Emit only monomorphized copies (not already emitted above as non-generic AST decl)
            if (name !in emittedIfaceNames && info.typeParams.isEmpty()
                && genericIfaceDecls.values.none { it.name == name }) {
                // Skip non-generic interfaces from other packages (they're in that package's header).
                // Monomorphized generics (e.g. MutableList_Int from MutableList<T>) are always
                // emitted here because they may reference user-defined types.
                val isMonomorphized = genericIfaceDecls.keys.any { tmpl -> name.startsWith(tmpl + "_") }
                if (isMonomorphized) {
                    emitIfaceInfo(info)
                } else {
                    val isCrossPackage = symbolPrefix[name]?.let { it.isNotEmpty() && it != prefix } == true
                    if (!isCrossPackage) {
                        emitIfaceInfo(info)
                    }
                }
            }
        }

        // Emit monomorphized generic class instantiations
        for ((baseName, instantiations) in genericInstantiations) {
            val templateDecl = genericClassDecls[baseName] ?: continue
            for (typeArgs in instantiations) {
                val mangledName = mangledGenericName(baseName, typeArgs)
                val templateCi = classes[baseName] ?: continue
                // Set type substitution for this instantiation
                typeSubst = templateCi.typeParams.zip(typeArgs).toMap()
                // Switch source file attribution for mem-track
                val prevSourceFile = currentSourceFile
                declSourceFile[baseName]?.let { currentSourceFile = it }
                emitGenericClass(templateDecl, mangledName)
                currentSourceFile = prevSourceFile
                typeSubst = emptyMap()
            }
        }

        // Emit static vtable instances + wrapping functions for interface implementations
        // Non-generic classes:
        for (d in file.decls) if (d is ClassDecl && d.typeParams.isEmpty() && d.superInterfaces.isNotEmpty()) {
            emitClassInterfaceVtables(d)
        }
        // Monomorphized generic classes:
        for ((baseName, instantiations) in genericInstantiations) {
            val templateDecl = genericClassDecls[baseName] ?: continue
            if (templateDecl.superInterfaces.isEmpty()) continue
            for (typeArgs in instantiations) {
                val mangledName = mangledGenericName(baseName, typeArgs)
                val ci = classes[mangledName] ?: continue
                val subst = ci.typeParams.ifEmpty { templateDecl.typeParams }.zip(typeArgs).toMap()
                    .ifEmpty { genericTypeBindings[mangledName] ?: emptyMap() }
                val resolvedIfaces = templateDecl.superInterfaces.map { substituteTypeRef(it, subst) }
                typeSubst = subst
                emitInterfaceVtablesForClass(mangledName, resolvedIfaces)
                typeSubst = emptyMap()
            }
        }


        // Emit top-level functions and properties
        for (d in file.decls) when (d) {
            is FunDecl  -> {
                // Skip generic function templates and star-projection extensions — handled below
                if (d.typeParams.isNotEmpty()) continue
                if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) continue
                if (d.receiver != null) emitExtensionFun(d) else emitFun(d)
            }
            is PropDecl -> emitTopProp(d)
            else -> {}
        }

        // Emit monomorphized generic functions
        for (f in genericFunDecls) emitGenericFunInstantiations(f)

        // Emit star-projection extension functions (one per known instantiation)
        for (f in starExtFunDecls) emitStarExtFunInstantiations(f)

        val srcName = prefix.trimEnd('_').ifEmpty {
            sourceFileName.removeSuffix(".kt").ifEmpty { "main" }
        }
        val src = StringBuilder()
        src.appendLine("#include \"$srcName.h\"")
        src.appendLine()
        src.append(impl)

        return COutput(hdr.toString(), src.toString())
    }

    // ═══════════════════════════ Collect declarations (pre-pass) ═════

    private fun collectDecls() {
        // Collect from all files for cross-reference resolution
        for (f in allFiles) {
            val fpfx = f.pkg?.replace('.', '_')?.plus("_") ?: ""
            for (d in f.decls) {
                collectDecl(d)
                // Record the source file for generic declarations (for mem-track attribution)
                if (f.sourceFile.isNotEmpty()) {
                    when (d) {
                        is ClassDecl -> if (d.typeParams.isNotEmpty()) declSourceFile[d.name] = f.sourceFile
                        is FunDecl -> if (d.typeParams.isNotEmpty()) declSourceFile[d.name] = f.sourceFile
                        else -> {}
                    }
                }
                // Record the prefix for cross-file symbols
                when (d) {
                    is ClassDecl -> symbolPrefix[d.name] = fpfx
                    is EnumDecl -> symbolPrefix[d.name] = fpfx
                    is InterfaceDecl -> symbolPrefix[d.name] = fpfx
                    is ObjectDecl -> symbolPrefix[d.name] = fpfx
                    is FunDecl -> {
                        if (d.receiver == null) symbolPrefix[d.name] = fpfx
                    }
                    else -> {}
                }
            }
        }
        // Current file's symbols use current prefix (overwrite any from allFiles)
        for (d in file.decls) {
            collectDecl(d)
            when (d) {
                is ClassDecl -> symbolPrefix[d.name] = prefix
                is EnumDecl -> symbolPrefix[d.name] = prefix
                is InterfaceDecl -> symbolPrefix[d.name] = prefix
                is ObjectDecl -> symbolPrefix[d.name] = prefix
                is FunDecl -> {
                    if (d.receiver == null) symbolPrefix[d.name] = prefix
                }
                else -> {}
            }
        }
    }

    private fun collectDecl(d: Decl) {
        when (d) {
            is ClassDecl -> {
                val ctorProps = d.ctorParams.filter { it.isVal || it.isVar }.map { it.name to it.type }
                val ctorPlainParams = d.ctorParams.filter { !it.isVal && !it.isVar }.map { it.name to it.type }
                val bodyProps = d.members.filterIsInstance<PropDecl>().map { p ->
                    BodyProp(p.name, p.type ?: TypeRef(inferExprType(p.init) ?: "Int"), p.init, p.line)
                }
                val ci = ClassInfo(d.name, d.isData, ctorProps, ctorPlainParams, bodyProps, initBlocks = d.initBlocks, typeParams = d.typeParams)
                if (d.typeParams.isNotEmpty()) allGenericTypeParamNames += d.typeParams
                for (m in d.members) if (m is FunDecl && m.receiver == null) ci.methods += m
                classes[d.name] = ci
                if (d.typeParams.isNotEmpty()) genericClassDecls[d.name] = d
                if (d.superInterfaces.isNotEmpty()) classInterfaces[d.name] = d.superInterfaces.map { it.name }
            }
            is EnumDecl  -> enums[d.name] = EnumInfo(d.name, d.entries)
            is InterfaceDecl -> {
                interfaces[d.name] = IfaceInfo(d.name, d.methods, d.properties, d.typeParams, d.superInterfaces)
                if (d.typeParams.isNotEmpty()) {
                    genericIfaceDecls[d.name] = d
                    allGenericTypeParamNames += d.typeParams
                }
            }
            is ObjectDecl -> {
                val props = d.members.filterIsInstance<PropDecl>().map { it.name to (it.type ?: TypeRef("Int")) }
                val oi = ObjInfo(d.name, props)
                for (m in d.members) if (m is FunDecl) oi.methods += m
                objects[d.name] = oi
            }
            is FunDecl -> {
                if (d.typeParams.isNotEmpty()) {
                    // Generic function template — store for monomorphization
                    // (dedup: overwrite funSig, but only add to list if not already present)
                    if (genericFunDecls.none { it === d }) genericFunDecls += d
                    funSigs[d.name] = FunSig(d.params, d.returnType)
                    allGenericTypeParamNames += d.typeParams
                } else if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) {
                    // Star-projection extension function — store for expansion
                    if (starExtFunDecls.none { it === d }) starExtFunDecls += d
                } else if (d.receiver != null) {
                    val recvName = d.receiver.name
                    extensionFuns.getOrPut(recvName) { mutableListOf() }.add(d)
                    // Register as method on the class for inference
                    classes[recvName]?.methods?.add(d)
                    funSigs["${recvName}.${d.name}"] = FunSig(d.params, d.returnType)
                } else {
                    funSigs[d.name] = FunSig(d.params, d.returnType)
                }
            }
            is PropDecl  -> { topProps.add(d.name) }
        }
    }

    /** Lightweight type inference from AST node during pre-scan (no codegen context). */
    private fun scanInferType(e: Expr): String? = when (e) {
        is IntLit    -> "Int"
        is LongLit   -> "Long"
        is FloatLit  -> "Float"
        is DoubleLit -> "Double"
        is BoolLit   -> "Boolean"
        is CharLit   -> "Char"
        is StrLit    -> "String"
        is CallExpr  -> (e.callee as? NameExpr)?.name?.let { n ->
            if (classes.containsKey(n)) n else null
        }
        else -> null
    }

    /** Pre-scan AST for Array<T> type references to populate classArrayTypes. */
    private fun scanForClassArrayTypes() {
        val primitives = setOf("Int", "Long", "Float", "Double", "Boolean", "Char", "String")
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
    private fun scanForGenericInstantiations() {
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

    private fun scanTypeRefForGenerics(t: TypeRef?, skip: Set<String> = emptySet()) {
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

    private fun scanExprForGenerics(e: Expr?, skip: Set<String> = emptySet()) {
        if (e == null) return
        when (e) {
            is CallExpr -> {
                val name = (e.callee as? NameExpr)?.name
                // Constructor call: MyList<Int>(...) or malloc<MyList<Int>>(...)
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
            else -> {}
        }
    }

    private fun scanStmtForGenerics(s: Stmt, skip: Set<String> = emptySet()) {
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

    private fun scanBlockForGenerics(block: Block?, skip: Set<String> = emptySet()) { block?.stmts?.forEach { scanStmtForGenerics(it, skip) } }

    /**
     * Create concrete ClassInfo entries for each generic instantiation discovered.
     * E.g., MyList<Int> → classes["MyList_Int"] with all T→Int substitution tracked.
     */
    private fun materializeGenericInstantiations() {
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
                    BodyProp(bp.name, substituteTypeRef(bp.type, subst), bp.init, bp.line)
                }
                val ci = ClassInfo(mangledName, templateCi.isData, ctorProps, ctorPlainParams, bodyProps,
                    initBlocks = templateCi.initBlocks)
                // Copy methods from template
                for (m in templateCi.methods) ci.methods += m
                classes[mangledName] = ci
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
    private fun resolveIfaceName(t: TypeRef): String {
        if (t.typeArgs.isEmpty()) return t.name
        return mangledGenericName(t.name, t.typeArgs.map { it.name })
    }

    /**
     * Monomorphize a generic interface template. E.g., List<Int> → creates IfaceInfo("List_Int", ...).
     * Recursively processes super interfaces.
     */
    private fun materializeGenericInterface(t: TypeRef) {
        if (t.typeArgs.isEmpty()) return  // non-generic, already registered
        val baseName = t.name
        val template = interfaces[baseName] ?: return
        if (template.typeParams.isEmpty()) return  // non-generic template
        val typeArgs = t.typeArgs.map { it.name }
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
        symbolPrefix[mangledName] = symbolPrefix[baseName] ?: prefix
        // Recursively monomorphize parent interfaces
        for (superRef in resolvedSupers) {
            materializeGenericInterface(superRef)
        }
    }

    /** Substitute type parameters in a TypeRef: T → Int when subst = {T: Int}. */
    private fun substituteTypeRef(t: TypeRef, subst: Map<String, String>): TypeRef {
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
    private fun scanForGenericFunCalls() {
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
    private fun scanGenericFunBodiesForInstantiations() {
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

    private fun scanBodyWithSubst(block: Block?, subst: Map<String, String>): Boolean {
        if (block == null) return false
        var found = false
        for (s in block.stmts) if (scanStmtWithSubst(s, subst)) found = true
        return found
    }

    private fun scanStmtWithSubst(s: Stmt, subst: Map<String, String>): Boolean {
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

    private fun scanExprWithSubst(e: Expr?, subst: Map<String, String>): Boolean {
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
                // Check typeArgs for nested generic types (e.g., malloc<Array<T>>)
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
            else -> false
        }
    }

    private fun scanTypeRefWithSubst(t: TypeRef, subst: Map<String, String>): Boolean {
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
    private fun computeGenericFunConcreteReturns() {
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
    private fun inferConcreteReturnClass(body: Block?, subst: Map<String, String>): String? {
        if (body == null) return null
        val varInits = mutableMapOf<String, Expr?>()
        for (s in body.stmts) {
            if (s is VarDeclStmt) varInits[s.name] = s.init
        }
        for (s in body.stmts) {
            if (s is ReturnStmt && s.value is NameExpr) {
                val varName = (s.value as NameExpr).name
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
    private fun matchTypeParam(paramType: TypeRef, argType: String, typeParams: Set<String>, subst: MutableMap<String, String>) {
        // Direct type param: param is T, arg is Int → T=Int
        if (paramType.name in typeParams) {
            subst[paramType.name] = argType
            return
        }
        // Generic class param: MutableList<T>, arg=MutableList_Int → T=Int
        if (paramType.typeArgs.isNotEmpty() && classes.containsKey(paramType.name) && classes[paramType.name]!!.isGeneric) {
            val baseType = argType.trimEnd('*', '&', '^', '?', '#')
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
            val baseType = argType.trimEnd('*', '&', '^', '?', '#')
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
            val baseType = argType.trimEnd('*', '&', '^', '?', '#')
            val components = pairTypeComponents[baseType]
            if (components != null) {
                val (first, second) = components
                if (paramType.typeArgs[0].name in typeParams) subst[paramType.typeArgs[0].name] = first
                if (paramType.typeArgs[1].name in typeParams) subst[paramType.typeArgs[1].name] = second
            }
        }
    }

    // ═══════════════════════════ Emit declarations ════════════════════

    // ── class / data class ───────────────────────────────────────────

    private fun emitClass(d: ClassDecl) {
        val cName = pfx(d.name)
        val ci = classes[d.name]!!

        // --- header: typedef struct ---
        hdr.appendLine("typedef struct {")
        for ((name, type) in ci.props) {
            val resolved = resolveTypeName(type)
            if (isFuncType(resolved)) {
                hdr.appendLine("    ${cFuncPtrDecl(resolved, name)};")
            } else if (isArrayType(resolved)) {
                hdr.appendLine("    ${cTypeStr(resolved)} $name;")
                hdr.appendLine("    int32_t ${name}\$len;")
            } else if (type.nullable) {
                hdr.appendLine("    ${cTypeStr(resolved)} $name;")
                hdr.appendLine("    bool ${name}\$has;")
            } else {
                hdr.appendLine("    ${cType(type)} $name;")
            }
        }
        hdr.appendLine("} $cName;")
        hdr.appendLine()

        // --- constructor (only takes ctor params, initializes all fields) ---
        val allCtorParams = ci.ctorProps + ci.ctorPlainParams
        val paramStr = expandCtorParams(allCtorParams)
        val paramDecl = paramStr.ifEmpty { "void" }
        hdr.appendLine("$cName ${cName}_create($paramDecl);")
        impl.appendLine("$cName ${cName}_create($paramDecl) {")
        if (ci.bodyProps.isEmpty() && ci.ctorPlainParams.isEmpty() && ci.ctorProps.none { isArrayType(resolveTypeName(it.second)) || it.second.nullable }) {
            impl.appendLine("    return ($cName){${ci.ctorProps.joinToString(", ") { it.first }}};")
        } else {
            impl.appendLine("    $cName \$self = {0};")
            for ((name, type) in ci.ctorProps) {
                val resolved = resolveTypeName(type)
                if (isArrayType(resolved)) {
                    impl.appendLine("    \$self.$name = $name;")
                    impl.appendLine("    \$self.${name}\$len = ${name}\$len;")
                } else if (type.nullable) {
                    impl.appendLine("    \$self.$name = $name;")
                    impl.appendLine("    \$self.${name}\$has = ${name}\$has;")
                } else {
                    impl.appendLine("    \$self.$name = $name;")
                }
            }
            for (bp in ci.bodyProps) {
                if (bp.init != null) {
                    if (bp.line > 0) currentStmtLine = bp.line
                    val expr = genExpr(bp.init)
                    flushPreStmts("    ")
                    impl.appendLine("    \$self.${bp.name} = $expr;")
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

        // --- heap constructor: ClassName_new(args) → ClassName* ---
        emitHeapNew(cName, ci)

        // --- methods ---
        currentClass = d.name
        selfIsPointer = true
        pushScope()
        for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))
        for (m in d.members) {
            if (m is FunDecl && m.receiver == null) emitMethod(d.name, m)
        }
        popScope()
        currentClass = null
    }

    /**
     * Emit a concrete instantiation of a generic class.
     * typeSubst must be set before calling (e.g. {T → Int}).
     * [mangledName] is the concrete class name (e.g. "MyList_Int").
     */
    private fun emitGenericClass(templateDecl: ClassDecl, mangledName: String) {
        val cName = pfx(mangledName)
        val ci = classes[mangledName]!!

        // --- header: typedef struct ---
        hdr.appendLine("typedef struct {")
        for ((name, type) in ci.props) {
            val resolved = resolveTypeName(type)
            if (isFuncType(resolved)) {
                hdr.appendLine("    ${cFuncPtrDecl(resolved, name)};")
            } else if (isArrayType(resolved)) {
                hdr.appendLine("    ${cTypeStr(resolved)} $name;")
                hdr.appendLine("    int32_t ${name}\$len;")
            } else if (type.nullable) {
                hdr.appendLine("    ${cTypeStr(resolved)} $name;")
                hdr.appendLine("    bool ${name}\$has;")
            } else {
                hdr.appendLine("    ${cType(type)} $name;")
            }
        }
        hdr.appendLine("} $cName;")
        hdr.appendLine()

        // --- constructor ---
        val allCtorParams = ci.ctorProps + ci.ctorPlainParams
        val paramStr = expandCtorParams(allCtorParams)
        val paramDecl = paramStr.ifEmpty { "void" }
        hdr.appendLine("$cName ${cName}_create($paramDecl);")
        impl.appendLine("$cName ${cName}_create($paramDecl) {")
        if (ci.bodyProps.isEmpty() && ci.ctorPlainParams.isEmpty() && ci.ctorProps.none { isArrayType(resolveTypeName(it.second)) || it.second.nullable }) {
            impl.appendLine("    return ($cName){${ci.ctorProps.joinToString(", ") { it.first }}};")
        } else {
            impl.appendLine("    $cName \$self = {0};")
            for ((name, type) in ci.ctorProps) {
                val resolved = resolveTypeName(type)
                if (isArrayType(resolved)) {
                    impl.appendLine("    \$self.$name = $name;")
                    impl.appendLine("    \$self.${name}\$len = ${name}\$len;")
                } else if (type.nullable) {
                    impl.appendLine("    \$self.$name = $name;")
                    impl.appendLine("    \$self.${name}\$has = ${name}\$has;")
                } else {
                    impl.appendLine("    \$self.$name = $name;")
                }
            }
            for (bp in ci.bodyProps) {
                if (bp.init != null) {
                    if (bp.line > 0) currentStmtLine = bp.line
                    val expr = genExpr(bp.init)
                    flushPreStmts("    ")
                    impl.appendLine("    \$self.${bp.name} = $expr;")
                    emitBodyPropLenIfArray(bp)
                }
            }
            impl.appendLine("    return \$self;")
        }
        impl.appendLine("}")
        impl.appendLine()

        // --- heap constructor ---
        emitHeapNew(cName, ci)

        // --- methods (from template AST, but with typeSubst active) ---
        currentClass = mangledName
        selfIsPointer = true
        pushScope()
        for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))
        for (m in templateDecl.members) {
            if (m is FunDecl && m.receiver == null) emitMethod(mangledName, m)
        }
        popScope()
        currentClass = null
    }

    private fun emitDataClassEquals(cName: String, ci: ClassInfo) {
        hdr.appendLine("bool ${cName}_equals($cName a, $cName b);")
        impl.appendLine("bool ${cName}_equals($cName a, $cName b) {")
        val eqs = ci.props.joinToString(" && ") { (name, type) ->
            val t = resolveTypeName(type)
            when {
                t == "String" -> "kt_string_eq(a.$name, b.$name)"
                classes[t]?.isData == true -> "${pfx(t)}_equals(a.$name, b.$name)"
                else -> "a.$name == b.$name"
            }
        }
        impl.appendLine("    return ${eqs.ifEmpty { "true" }};")
        impl.appendLine("}")
        impl.appendLine()
    }

    private fun emitDataClassToString(ktName: String, cName: String, ci: ClassInfo) {
        hdr.appendLine("void ${cName}_toString($cName \$self, kt_StrBuf* sb);")
        impl.appendLine("void ${cName}_toString($cName \$self, kt_StrBuf* sb) {")
        impl.appendLine("    kt_sb_append_cstr(sb, \"$ktName(\");")
        for ((i, prop) in ci.props.withIndex()) {
            val (name, type) = prop
            val t = resolveTypeName(type)
            if (i > 0) impl.appendLine("    kt_sb_append_cstr(sb, \", \");")
            impl.appendLine("    kt_sb_append_cstr(sb, \"$name=\");")
            impl.appendLine("    ${genSbAppend("sb", "\$self.$name", t)}")
        }
        impl.appendLine("    kt_sb_append_char(sb, ')');")
        impl.appendLine("}")
        impl.appendLine()
    }

    /** Generate ClassName_new(args) → ClassName* (heap constructor). */
    private fun emitHeapNew(cName: String, ci: ClassInfo) {
        val allCtorParams = ci.ctorProps + ci.ctorPlainParams
        val paramStr = expandCtorParams(allCtorParams)
        val paramDecl = paramStr.ifEmpty { "void" }
        hdr.appendLine("$cName* ${cName}_new($paramDecl);")
        impl.appendLine("$cName* ${cName}_new($paramDecl) {")
        impl.appendLine("    $cName* \$p = ($cName*)malloc(sizeof($cName));")
        impl.appendLine("    if (\$p) *\$p = ${cName}_create(${allCtorParams.joinToString(", ") { it.first }});")
        impl.appendLine("    return \$p;")
        impl.appendLine("}")
        impl.appendLine()
    }

    private fun emitMethod(className: String, f: FunDecl) {
        val cClass = pfx(className)
        val returnsNullable = f.returnType != null && f.returnType.nullable
        val cRet = when {
            returnsNullable -> "bool"
            f.returnType != null -> cType(f.returnType)
            else -> "void"
        }
        val selfParam = "$cClass* \$self"
        val extraParams = expandParams(f.params)
        val outParam = if (returnsNullable) "${cType(f.returnType!!)}* \$out" else null
        val allParts = mutableListOf(selfParam)
        if (extraParams.isNotEmpty()) allParts += extraParams
        if (outParam != null) allParts += outParam
        val allParams = allParts.joinToString(", ")

        hdr.appendLine("$cRet ${cClass}_${f.name}($allParams);")
        impl.appendLine("$cRet ${cClass}_${f.name}($allParams) {")

        val prevReturnsNullable = currentFnReturnsNullable
        val prevReturnsArray = currentFnReturnsArray
        val prevReturnType = currentFnReturnType
        currentFnReturnsNullable = returnsNullable
        currentFnReturnsArray = false
        currentFnReturnType = if (f.returnType != null) resolveTypeName(f.returnType) else ""

        pushScope()
        for (p in f.params) {
            val resolved = resolveTypeName(p.type)
            val wasTypeParam = typeSubst.containsKey(p.type.name)
            defineVar(p.name, when {
                p.type.nullable -> "${resolved}?"
                !wasTypeParam && classes.containsKey(resolved) -> "${resolved}*"
                else -> resolved
            })
        }
        // class props accessible via self->
        val ci = classes[className]
        if (ci != null) for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))

        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) {
            emitDeferredBlocks("    ", insideMethod = true)
            if (returnsNullable) impl.appendLine("    return false;")
        }
        deferStack.clear(); deferStack.addAll(savedDefers)
        popScope()

        currentFnReturnsNullable = prevReturnsNullable
        currentFnReturnsArray = prevReturnsArray
        currentFnReturnType = prevReturnType

        impl.appendLine("}")
        impl.appendLine()
    }

    // ── extension function ───────────────────────────────────────────

    private fun emitExtensionFun(f: FunDecl) {
        val recvTypeName = f.receiver!!.name
        val recvIsNullable = f.receiver.nullable
        val cRet = if (f.returnType != null) cType(f.returnType) else "void"
        val isClassType = classes.containsKey(recvTypeName)
        val cRecvType = cTypeStr(recvTypeName)
        val selfParam = if (isClassType) "$cRecvType* \$self" else "$cRecvType \$self"
        val nullableExtra = if (recvIsNullable) ", bool \$self\$has" else ""
        val extraParams = expandParams(f.params)
        val allParams = if (extraParams.isEmpty()) "$selfParam$nullableExtra" else "$selfParam$nullableExtra, $extraParams"
        val cFnName = "${pfx(recvTypeName)}_${f.name}"

        hdr.appendLine("$cRet $cFnName($allParams);")
        impl.appendLine("$cRet $cFnName($allParams) {")

        val prevClass = currentClass
        val prevSelfIsPointer = selfIsPointer
        val prevExtRecvType = currentExtRecvType
        currentExtRecvType = if (recvIsNullable) "$recvTypeName?" else recvTypeName
        if (isClassType) {
            currentClass = recvTypeName
            selfIsPointer = true
        } else {
            currentClass = null
            selfIsPointer = false
        }

        pushScope()
        for (p in f.params) {
            val resolved = resolveTypeName(p.type)
            defineVar(p.name, when {
                p.isVararg -> "${resolved}Array"
                p.type.nullable -> "${resolved}?"
                classes.containsKey(resolved) -> "${resolved}*"
                else -> resolved
            })
        }
        if (isClassType) {
            val ci = classes[recvTypeName]!!
            for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))
        }
        val savedDefers2 = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = isClassType)
        deferStack.clear(); deferStack.addAll(savedDefers2)
        popScope()

        currentClass = prevClass
        selfIsPointer = prevSelfIsPointer
        currentExtRecvType = prevExtRecvType

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
    private fun emitGenericFunInstantiations(f: FunDecl) {
        val instantiations = genericFunInstantiations[f.name] ?: return
        // Switch source file attribution for mem-track if this function came from another file
        val prevSourceFile = currentSourceFile
        declSourceFile[f.name]?.let { currentSourceFile = it }
        for (typeArgs in instantiations) {
            val subst = f.typeParams.zip(typeArgs).toMap()
            val prevSubst = typeSubst
            typeSubst = subst
            val mangledName = "${f.name}_${typeArgs.joinToString("_")}"

            // Resolve return type and params under substitution
            val returnsArray = f.returnType != null && isArrayType(resolveTypeName(f.returnType))
            // If we pre-computed a concrete class return for this instantiation, use it
            val concreteRet = genericFunConcreteReturn[mangledName]
            val cRet = if (concreteRet != null) {
                "${pfx(concreteRet)}"
            } else if (f.returnType != null) cType(f.returnType) else "void"
            val cName = pfx(mangledName)
            val baseParams = expandParams(f.params)
            val params = if (returnsArray) {
                if (baseParams.isEmpty()) "int32_t* \$len_out" else "$baseParams, int32_t* \$len_out"
            } else baseParams

            hdr.appendLine("$cRet $cName($params);")
            impl.appendLine("$cRet $cName($params) {")

            val prevReturnsArray = currentFnReturnsArray
            val prevReturnType = currentFnReturnType
            currentFnReturnsArray = returnsArray
            currentFnReturnType = if (concreteRet != null) {
                concreteRet
            } else if (f.returnType != null) {
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
            val savedDefers = deferStack.toList(); deferStack.clear()
            if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = false)
            if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = false)
            deferStack.clear(); deferStack.addAll(savedDefers)
            popScope()

            currentFnReturnsArray = prevReturnsArray
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
    private fun emitStarExtFunInstantiations(f: FunDecl) {
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
            val savedDefers = deferStack.toList(); deferStack.clear()
            if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
            if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = isClassType)
            deferStack.clear(); deferStack.addAll(savedDefers)
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
    private fun emitStarExtFunForGenericInterface(f: FunDecl, ifaceBaseName: String) {
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
            val savedDefers = deferStack.toList(); deferStack.clear()
            if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
            if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = true)
            deferStack.clear(); deferStack.addAll(savedDefers)
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

    private fun emitEnum(d: EnumDecl) {
        val cName = pfx(d.name)
        hdr.appendLine("typedef enum {")
        for ((i, e) in d.entries.withIndex()) {
            hdr.append("    ${cName}_$e")
            if (i < d.entries.lastIndex) hdr.append(",")
            hdr.appendLine()
        }
        hdr.appendLine("} $cName;")
        hdr.appendLine()
    }

    // ── object ───────────────────────────────────────────────────────

    private fun emitObject(d: ObjectDecl) {
        val cName = pfx(d.name)
        val props = d.members.filterIsInstance<PropDecl>()

        hdr.appendLine("typedef struct {")
        for (p in props) hdr.appendLine("    ${cType(p.type ?: TypeRef("Int"))} ${p.name};")
        hdr.appendLine("} ${cName}_t;")
        hdr.appendLine("extern ${cName}_t $cName;")
        hdr.appendLine()

        // global instance with initial values
        val inits = props.joinToString(", ") { p -> if (p.init != null) genExpr(p.init) else defaultVal(resolveTypeName(p.type ?: TypeRef("Int"))) }
        impl.appendLine("${cName}_t $cName = {$inits};")
        impl.appendLine()

        // methods
        for (m in d.members) if (m is FunDecl) {
            val cRet = if (m.returnType != null) cType(m.returnType) else "void"
            val params = expandParams(m.params)
            hdr.appendLine("$cRet ${cName}_${m.name}($params);")
            impl.appendLine("$cRet ${cName}_${m.name}($params) {")
            pushScope()
            for (p in m.params) defineVar(p.name, if (p.isVararg) "${resolveTypeName(p.type)}Array" else resolveTypeName(p.type))
            val savedDefers3 = deferStack.toList(); deferStack.clear()
            if (m.body != null) for (s in m.body.stmts) emitStmt(s, "    ")
            if (m.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ")
            deferStack.clear(); deferStack.addAll(savedDefers3)
            popScope()
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
    private fun emitInterface(d: InterfaceDecl) {
        val info = interfaces[d.name] ?: return
        emitIfaceInfo(info)
    }

    /**
     * Emit a concrete (non-generic) interface: vtable struct + fat pointer type.
     * Handles inherited methods/properties from super interfaces.
     */
    private fun emitIfaceInfo(info: IfaceInfo) {
        val cName = pfx(info.name)
        // Collect all methods/properties including inherited from super interfaces
        val allMethods = collectAllIfaceMethods(info)
        val allProps = collectAllIfaceProperties(info)
        // vtable struct
        hdr.appendLine("typedef struct {")
        // Properties → getter function pointers
        for (p in allProps) {
            val ct = if (p.type != null) cType(p.type) else "int32_t"
            hdr.appendLine("    $ct (*${p.name})(void* \$self);")
        }
        for (m in allMethods) {
            val mReturnsNullable = m.returnType != null && m.returnType.nullable
            val cRet = if (mReturnsNullable) "bool" else if (m.returnType != null) cType(m.returnType) else "void"
            val extraParams = m.params.joinToString("") { p -> ", ${cType(p.type)} ${p.name}" }
            val outParam = if (mReturnsNullable) ", ${cType(m.returnType!!)}* \$out" else ""
            hdr.appendLine("    $cRet (*${m.name})(void* \$self$extraParams$outParam);")
        }
        hdr.appendLine("} ${cName}_vt;")
        hdr.appendLine()
        // fat pointer struct
        hdr.appendLine("typedef struct {")
        hdr.appendLine("    void* obj;")
        hdr.appendLine("    const ${cName}_vt* vt;")
        hdr.appendLine("} $cName;")
        hdr.appendLine()
    }

    /** Collect all methods for an interface, including inherited from super interfaces (depth-first). */
    private fun collectAllIfaceMethods(info: IfaceInfo): List<FunDecl> {
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
    private fun collectAllIfaceProperties(info: IfaceInfo): List<PropDecl> {
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

    /**
     * For each interface a class implements, emit:
     *   1. Property getter wrappers (for interface properties backed by class fields)
     *   2. A static const vtable instance with the class's method/property pointers
     *   3. A wrapping function:  ClassName_as_IfaceName(ClassName* $self) → IfaceName
     */
    private fun emitClassInterfaceVtables(d: ClassDecl) {
        val className = d.name
        emitInterfaceVtablesForClass(className, d.superInterfaces)
    }

    /**
     * Emit vtables for a concrete class name implementing the given super interfaces.
     * Works for both non-generic and monomorphized generic classes.
     */
    private fun emitInterfaceVtablesForClass(className: String, superIfaceRefs: List<TypeRef>) {
        val cClass = pfx(className)
        for (ifaceRef in superIfaceRefs) {
            val ifaceName = resolveIfaceName(ifaceRef)
            val iface = interfaces[ifaceName] ?: continue
            val cIface = pfx(ifaceName)
            val allMethods = collectAllIfaceMethods(iface)
            val allProps = collectAllIfaceProperties(iface)

            // Emit property getter wrappers
            for (p in allProps) {
                val ct = if (p.type != null) cType(p.type) else "int32_t"
                val getterName = "${cClass}_${p.name}_get"
                hdr.appendLine("$ct $getterName($cClass* \$self);")
                impl.appendLine("$ct $getterName($cClass* \$self) { return \$self->${p.name}; }")
                impl.appendLine()
            }

            // static vtable instance
            hdr.appendLine("extern const ${cIface}_vt ${cClass}_${ifaceName}_vt;")
            impl.appendLine("const ${cIface}_vt ${cClass}_${ifaceName}_vt = {")
            for (p in allProps) {
                val ct = if (p.type != null) cType(p.type) else "int32_t"
                impl.appendLine("    ($ct (*)(void*)) ${cClass}_${p.name}_get,")
            }
            for (m in allMethods) {
                val mReturnsNullable = m.returnType != null && m.returnType.nullable
                val cRet = if (mReturnsNullable) "bool" else if (m.returnType != null) cType(m.returnType) else "void"
                val extraCast = m.params.joinToString("") { p -> ", ${cType(p.type)}" }
                val outCast = if (mReturnsNullable) ", ${cType(m.returnType!!)}*" else ""
                impl.appendLine("    ($cRet (*)(void*$extraCast$outCast)) ${cClass}_${m.name},")
            }
            impl.appendLine("};")
            impl.appendLine()

            // wrapping function: ClassName_as_IfaceName
            hdr.appendLine("$cIface ${cClass}_as_${ifaceName}($cClass* \$self);")
            impl.appendLine("$cIface ${cClass}_as_${ifaceName}($cClass* \$self) {")
            impl.appendLine("    return ($cIface){(void*)\$self, &${cClass}_${ifaceName}_vt};")
            impl.appendLine("}")
            impl.appendLine()

            // Also emit vtables for all parent interfaces (transitive)
            // E.g., ArrayList_Int implements MutableList_Int which extends List_Int
            // → emit ArrayList_Int_as_List_Int too
            emitTransitiveInterfaceVtables(className, cClass, iface, allProps, allMethods)
        }
    }

    /**
     * For interface inheritance chains, emit vtables for parent interfaces.
     * E.g., if ArrayList_Int implements MutableList_Int which extends List_Int,
     * emit ArrayList_Int_as_List_Int with the List_Int subset of the vtable.
     */
    private fun emitTransitiveInterfaceVtables(
        className: String, cClass: String, iface: IfaceInfo,
        childProps: List<PropDecl>, childMethods: List<FunDecl>
    ) {
        for (superRef in iface.superInterfaces) {
            val superName = resolveIfaceName(superRef)
            val superIface = interfaces[superName] ?: continue
            val cSuper = pfx(superName)
            val superMethods = collectAllIfaceMethods(superIface)
            val superProps = collectAllIfaceProperties(superIface)

            // Register this class as also implementing the parent interface
            val existing = classInterfaces[className]?.toMutableList() ?: mutableListOf()
            if (superName !in existing) {
                existing += superName
                classInterfaces[className] = existing
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
                val cRet = if (mReturnsNullable) "bool" else if (m.returnType != null) cType(m.returnType) else "void"
                val extraCast = m.params.joinToString("") { p -> ", ${cType(p.type)}" }
                val outCast = if (mReturnsNullable) ", ${cType(m.returnType!!)}*" else ""
                impl.appendLine("    ($cRet (*)(void*$extraCast$outCast)) ${cClass}_${m.name},")
            }
            impl.appendLine("};")
            impl.appendLine()

            // wrapping function
            hdr.appendLine("$cSuper ${cClass}_as_${superName}($cClass* \$self);")
            impl.appendLine("$cSuper ${cClass}_as_${superName}($cClass* \$self) {")
            impl.appendLine("    return ($cSuper){(void*)\$self, &${cClass}_${superName}_vt};")
            impl.appendLine("}")
            impl.appendLine()

            // Recurse for deeper inheritance
            emitTransitiveInterfaceVtables(className, cClass, superIface, superProps, superMethods)
        }
    }



    // ── top-level fun ────────────────────────────────────────────────

    private fun emitFun(f: FunDecl) {
        val isMain = f.name == "main"
        val isMainWithArgs = isMain && f.params.size == 1 &&
                f.params[0].type.name == "Array" &&
                f.params[0].type.typeArgs.singleOrNull()?.name == "String"

        val returnsNullable = !isMain && f.returnType != null && f.returnType.nullable
        val returnsArray = !isMain && !returnsNullable && f.returnType != null && isArrayType(resolveTypeName(f.returnType))
        val cRet  = if (isMain) "int" else if (returnsNullable) "bool" else if (f.returnType != null) cType(f.returnType) else "void"
        val cName = if (isMain) "main" else pfx(f.name)
        val params = when {
            isMainWithArgs -> "int argc, char** argv"
            isMain         -> "void"
            else           -> {
                val base = expandParams(f.params)
                val extra = when {
                    returnsNullable -> {
                        val outType = cType(f.returnType!!)
                        "$outType* \$out"
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

        val prevReturnsNullable = currentFnReturnsNullable
        val prevReturnsArray = currentFnReturnsArray
        val prevReturnType = currentFnReturnType
        val prevIsMain = currentFnIsMain
        currentFnReturnsNullable = returnsNullable
        currentFnReturnsArray = returnsArray
        currentFnReturnType = if (f.returnType != null) resolveTypeName(f.returnType) else ""
        currentFnIsMain = isMain

        pushScope()
        if (isMainWithArgs) {
            // Convert argc/argv → kt_StringArray (skip argv[0] = program name)
            val argName = f.params[0].name
            impl.appendLine("    kt_String \$args_buf[256];")
            impl.appendLine("    int32_t \$nargs = (argc > 1) ? (int32_t)(argc - 1) : 0;")
            impl.appendLine("    if (\$nargs > 256) \$nargs = 256;")
            impl.appendLine("    for (int32_t \$i = 0; \$i < \$nargs; \$i++) {")
            impl.appendLine("        \$args_buf[\$i] = (kt_String){argv[\$i + 1], (int32_t)strlen(argv[\$i + 1])};")
            impl.appendLine("    }")
            impl.appendLine("    kt_String* $argName = \$args_buf;")
            impl.appendLine("    int32_t ${argName}\$len = \$nargs;")
            defineVar(argName, "StringArray")
        } else {
            for (p in f.params) {
                val resolved = resolveTypeName(p.type)
                defineVar(p.name, when {
                    p.isVararg -> "${resolved}Array"  // vararg params are arrays (ptr + $len)
                    p.type.nullable -> "${resolved}?"
                    classes.containsKey(resolved) -> "${resolved}*"  // class params are pointers (reference semantics)
                    else -> resolved
                })
            }
        }
        val savedDefers = deferStack.toList()
        deferStack.clear()

        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ")
        // Emit deferred blocks at end unless last stmt was a return (already emitted there)
        val lastStmt = f.body?.stmts?.lastOrNull()
        if (lastStmt !is ReturnStmt) emitDeferredBlocks("    ")
        if (isMain && memTrack) impl.appendLine("    ktc_mem_report();")
        if (isMain) impl.appendLine("    return 0;")
        else if (returnsNullable && lastStmt !is ReturnStmt) impl.appendLine("    return false;")
        popScope()

        deferStack.clear()
        deferStack.addAll(savedDefers)
        currentFnReturnsNullable = prevReturnsNullable
        currentFnReturnsArray = prevReturnsArray
        currentFnReturnType = prevReturnType
        currentFnIsMain = prevIsMain
        impl.appendLine("}")
        impl.appendLine()
    }

    // ── top-level property ───────────────────────────────────────────

    private fun emitTopProp(d: PropDecl) {
        val t = if (d.type != null) resolveTypeName(d.type) else (inferExprType(d.init) ?: "Int")
        val ct = cTypeStr(t)
        val cName = pfx(d.name)
        val qual = if (!d.mutable) "const " else ""
        if (d.init != null) {
            hdr.appendLine("extern $qual$ct $cName;")
            impl.appendLine("$qual$ct $cName = ${genExpr(d.init)};")
        } else {
            hdr.appendLine("extern $ct $cName;")
            impl.appendLine("$ct $cName = ${defaultVal(t)};")
        }
        impl.appendLine()
    }

    // ═══════════════════════════ Statements ═══════════════════════════

    private fun emitStmt(s: Stmt, ind: String, insideMethod: Boolean = false) {
        if (s.line > 0) currentStmtLine = s.line
        when (s) {
            is VarDeclStmt  -> emitVarDecl(s, ind, insideMethod)
            is AssignStmt   -> emitAssign(s, ind, insideMethod)
            is ReturnStmt   -> emitReturn(s, ind)
            is ExprStmt     -> emitExprStmt(s, ind, insideMethod)
            is ForStmt      -> emitFor(s, ind, insideMethod)
            is WhileStmt    -> { impl.appendLine("${ind}while (${genExpr(s.cond)}) {"); emitBlock(s.body, ind, insideMethod); impl.appendLine("$ind}") }
            is DoWhileStmt  -> { impl.appendLine("${ind}do {"); emitBlock(s.body, ind, insideMethod); impl.appendLine("$ind} while (${genExpr(s.cond)});") }
            is BreakStmt    -> impl.appendLine("${ind}break;")
            is ContinueStmt -> impl.appendLine("${ind}continue;")
            is DeferStmt    -> deferStack.add(s.body)
        }
        // Smart cast: if (x == null) return/break/continue → narrow x to non-null after
        applyGuardSmartCast(s)
    }

    /** After `if (x == null) return/break/continue` (no else), narrow x from T? to T. */
    private fun applyGuardSmartCast(s: Stmt) {
        if (s !is ExprStmt) return
        val ifExpr = s.expr as? IfExpr ?: return
        if (ifExpr.els != null) return  // must have no else branch
        // Body must be a single early-exit statement
        val bodyStmt = ifExpr.then.stmts.singleOrNull() ?: return
        if (bodyStmt !is ReturnStmt && bodyStmt !is BreakStmt && bodyStmt !is ContinueStmt) return
        // Condition must be x == null
        val casts = extractElseSmartCasts(ifExpr.cond)
        for ((name, nonNullType) in casts) {
            defineVar(name, nonNullType)
        }
    }

    private fun emitBlock(b: Block, ind: String, insideMethod: Boolean = false) {
        for (s in b.stmts) emitStmt(s, "$ind    ", insideMethod)
    }

    // ── var / val ────────────────────────────────────────────────────

    private fun emitVarDecl(s: VarDeclStmt, ind: String, method: Boolean) {
        val tRaw = if (s.type != null) resolveTypeName(s.type) else (inferExprType(s.init) ?: "Int")
        val inferredNullable = s.type == null && tRaw.endsWith("?")
        val inferredPtr = s.type == null && (tRaw.endsWith("^") || tRaw.endsWith("^?") || tRaw.endsWith("^#"))
        val t = if (inferredNullable) tRaw.removeSuffix("?") else tRaw
        // malloc/calloc/realloc return nullable pointers (may return NULL)
        val isAlloc = s.type == null && isAllocCall(s.init)

        // Indirect type flags — covers Heap<T>, Ptr<T>, Value<T>
        val isHeapValNull = t.endsWith("*#")
        val isPtrValNull  = t.endsWith("^#")
        val isValValNull  = t.endsWith("&#")
        val isAnyValNull  = isHeapValNull || isPtrValNull || isValValNull   // value-nullable, $has

        val isHeapPtrNull = !isAnyValNull && isHeapPointerType(t) &&
                (s.type?.nullable == true || s.init is NullLit || inferredNullable || isAlloc)
        val isPtrPtrNull  = !isAnyValNull && isPtrType(t) &&
                (s.type?.nullable == true || s.init is NullLit || inferredNullable)
        val isValPtrNull  = !isAnyValNull && isValueType(t) &&
                (s.type?.nullable == true || s.init is NullLit || inferredNullable)
        // Raw pointer nullable: "Int*", "Pointer" etc. — not class-based, uses NULL
        val isRawPtrNull  = !isAnyValNull && !isHeapPointerType(t) && !isPtrType(t) && !isValueType(t) &&
                (t.endsWith("*") || t == "Pointer") && (inferredNullable || isAlloc)
        val isAnyPtrNull  = isHeapPtrNull || isPtrPtrNull || isValPtrNull || isRawPtrNull  // pointer-nullable, NULL

        val isNullable = !isAnyValNull && !isAnyPtrNull &&
                (s.type?.nullable == true || s.init is NullLit || isNullableReturningCall(s.init)
                        || inferredNullable)

        val isPtr  = isPtrType(t) || isPtrValNull || isPtrPtrNull           // Ptr<T> — needs $heap companion
        val isInferredPtr = inferredPtr                                     // init expr returned a Ptr type

        // Register type in scope
        defineVar(s.name, when {
            isAnyValNull   -> t                        // "T*#" / "T^#" / "T&#" as-is
            isAnyPtrNull   -> "${t}?"                  // "T*?" / "T^?" / "T&?"
            isNullable     -> "${t}?"                  // "Int?" etc.
            else           -> t
        })
        if (s.mutable) markMutable(s.name)

        // ── Function pointer type: special declaration syntax ──
        if (isFuncType(t)) {
            if (s.init != null) {
                val expr = genExpr(s.init)
                flushPreStmts(ind)
                impl.appendLine("$ind${cFuncPtrDecl(t, s.name)} = $expr;")
            } else {
                impl.appendLine("$ind${cFuncPtrDecl(t, s.name)} = NULL;")
            }
            return
        }

        val ct = cTypeStr(t)
        // Don't const class types, Pointer, typed pointers, nullable, arrays, or interface types
        val qual = if (!s.mutable && !classes.containsKey(t) && !interfaces.containsKey(t) && t != "Pointer"
            && !t.endsWith("*") && !t.endsWith("*#") && !t.endsWith("^") && !t.endsWith("^#")
            && !t.endsWith("&") && !t.endsWith("&#")
            && !isArrayType(t)
            && !isNullable && !isAnyPtrNull && !isAnyValNull) "const " else ""

        if (s.init != null) {
            val arrayInit = tryArrayOfInit(s.name, s.init, ct, t, ind)
            if (arrayInit != null) { impl.appendLine(arrayInit); return }

            when {
                // ── Heap<T?> / Ptr<T?> / Value<T?> : always allocated, $has tracks value ──
                isAnyValNull -> {
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$ct ${s.name} = $expr;")
                    impl.appendLine("${ind}bool ${s.name}\$has = true;")
                    if (isPtr || isInferredPtr) {
                        impl.appendLine("${ind}bool ${s.name}\$heap = ${expr}\$heap;")
                    }
                }
                // ── Heap<T>? / Ptr<T>? / Value<T>? : pointer nullable via NULL ──
                isAnyPtrNull -> {
                    if (s.init is NullLit) {
                        impl.appendLine("$ind$ct ${s.name} = NULL;")
                        if (isPtr || isInferredPtr) {
                            impl.appendLine("${ind}bool ${s.name}\$heap = false;")
                        }
                    } else {
                        val expr = genExpr(s.init)
                        flushPreStmts(ind)
                        impl.appendLine("$ind$ct ${s.name} = $expr;")
                        if (isPtr || isInferredPtr) {
                            impl.appendLine("${ind}bool ${s.name}\$heap = ${expr}\$heap;")
                        }
                    }
                }
                // ── Value nullable (existing system with $has) ──
                isNullable -> {
                    if (s.init is NullLit) {
                        impl.appendLine("$ind$ct ${s.name} = ${defaultVal(t)};")
                        impl.appendLine("${ind}bool ${s.name}\$has = false;")
                    } else if (isNullableReturningCall(s.init)) {
                        impl.appendLine("$ind$ct ${s.name};")
                        val expr = genExprWithNullableOut(s.init, s.name)
                        flushPreStmts(ind)
                        impl.appendLine("${ind}bool ${s.name}\$has = $expr;")
                    } else if (inferredNullable) {
                        val expr = genExpr(s.init)
                        flushPreStmts(ind)
                        impl.appendLine("$ind$ct ${s.name} = $expr;")
                        impl.appendLine("${ind}bool ${s.name}\$has = ${expr}\$has;")
                    } else {
                        val expr = genExpr(s.init)
                        flushPreStmts(ind)
                        impl.appendLine("$ind$ct ${s.name} = $expr;")
                        impl.appendLine("${ind}bool ${s.name}\$has = true;")
                    }
                }
                // ── Non-nullable ──
                else -> {
                    // Interface variable initialized from implementing class → auto-wrap
                    if (interfaces.containsKey(t) && s.init != null) {
                        val initType = inferExprType(s.init)
                        if (initType != null && classes.containsKey(initType) && classInterfaces[initType]?.contains(t) == true) {
                            val backing = tmp()
                            val expr = genExpr(s.init)
                            flushPreStmts(ind)
                            impl.appendLine("$ind${pfx(initType)} $backing = $expr;")
                            impl.appendLine("$ind$ct ${s.name} = ${pfx(initType)}_as_$t(&$backing);")
                            return
                        }
                    }
                    // Array-returning function call: declare $len first, pass &$len as out-param
                    if (isArrayType(t) && isArrayReturningCall(s.init)) {
                        impl.appendLine("${ind}int32_t ${s.name}\$len;")
                        val expr = genExprWithArrayLenOut(s.init, s.name)
                        flushPreStmts(ind)
                        impl.appendLine("$ind$qual$ct ${s.name} = $expr;")
                        return
                    }
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$qual$ct ${s.name} = $expr;")
                    // Array type: emit $len companion (copy from temp's $len)
                    if (isArrayType(t)) {
                        impl.appendLine("${ind}const int32_t ${s.name}\$len = ${expr}\$len;")
                    }
                    // Ptr<T> needs $heap companion
                    if (isPtr || isInferredPtr) {
                        impl.appendLine("${ind}bool ${s.name}\$heap = ${expr}\$heap;")
                    }
                }
            }
        } else {
            when {
                isAnyValNull -> {
                    impl.appendLine("$ind$ct ${s.name} = NULL; /* warning: must be initialized */")
                    impl.appendLine("${ind}bool ${s.name}\$has = false;")
                    if (isPtr) impl.appendLine("${ind}bool ${s.name}\$heap = false;")
                }
                isAnyPtrNull -> {
                    impl.appendLine("$ind$ct ${s.name} = NULL;")
                    if (isPtr) impl.appendLine("${ind}bool ${s.name}\$heap = false;")
                }
                else -> {
                    impl.appendLine("$ind$ct ${s.name} = ${defaultVal(t)};")
                    if (isNullable) impl.appendLine("${ind}bool ${s.name}\$has = false;")
                    if (isPtr) impl.appendLine("${ind}bool ${s.name}\$heap = false;")
                }
            }
        }
    }


    private fun tryArrayOfInit(varName: String, init: Expr, ct: String, t: String, ind: String): String? {
        if (init !is CallExpr) return null
        val callee = (init.callee as? NameExpr)?.name ?: return null
        val elemType = when (callee) {
            "intArrayOf" -> "int32_t"; "longArrayOf" -> "int64_t"
            "floatArrayOf" -> "float"; "doubleArrayOf" -> "double"
            "booleanArrayOf" -> "bool"; "charArrayOf" -> "char"
            "arrayOf" -> {
                // Infer element type from first argument or from declared type
                val elemKt = if (init.args.isNotEmpty()) inferExprType(init.args[0].expr) ?: "Int" else "Int"
                cTypeStr(elemKt)
            }
            else -> return null
        }
        val args = init.args.joinToString(", ") { genExpr(it.expr) }
        val n = init.args.size
        return "${ind}$elemType ${varName}[] = {$args};\n${ind}const int32_t ${varName}\$len = $n;"
    }

    /** Check if an expression is a call to a function known to return nullable. */
    private fun isNullableReturningCall(e: Expr?): Boolean {
        if (e !is CallExpr) return false
        val name = (e.callee as? NameExpr)?.name ?: return false
        return funSigs[name]?.returnType?.nullable == true
    }

    /** Check if a call expression returns an array type (function has $len_out parameter). */
    private fun isArrayReturningCall(e: Expr?): Boolean {
        if (e !is CallExpr) return false
        val name = (e.callee as? NameExpr)?.name ?: return false
        // Check generic functions
        val genFun = genericFunDecls.find { it.name == name }
        if (genFun != null && genFun.returnType != null) {
            val typeArgNames = if (e.typeArgs.isNotEmpty()) e.typeArgs.map { resolveTypeName(it) }
            else return false
            val subst = genFun.typeParams.zip(typeArgNames).toMap()
            val saved = typeSubst; typeSubst = subst
            val retType = resolveTypeName(genFun.returnType)
            typeSubst = saved
            return isArrayType(retType)
        }
        // Check regular functions
        val sig = funSigs[name] ?: return false
        return sig.returnType != null && !sig.returnType.nullable && isArrayType(resolveTypeName(sig.returnType))
    }

    /** Check if an expression is a malloc/calloc/realloc call (returns nullable pointer). */
    private fun isAllocCall(e: Expr?): Boolean {
        if (e !is CallExpr) return false
        val name = (e.callee as? NameExpr)?.name ?: return false
        return name in setOf("malloc", "calloc", "realloc")
    }

    /** Extract the allocation size argument from malloc<Array<T>>(size) or realloc<Array<T>>(ptr, size).
     *  Unwraps NotNullExpr (!!). Returns the size Expr or null. */
    private fun extractAllocSize(e: Expr?): Expr? {
        val inner = if (e is NotNullExpr) e.expr else e
        if (inner !is CallExpr) return null
        val name = (inner.callee as? NameExpr)?.name ?: return null
        return when (name) {
            "malloc"  -> inner.args.firstOrNull()?.expr  // malloc<Array<T>>(size)
            "calloc"  -> inner.args.firstOrNull()?.expr  // calloc<Array<T>>(size)
            "realloc" -> inner.args.getOrNull(1)?.expr   // realloc<Array<T>>(ptr, size)
            else      -> null
        }
    }

    /** If a body prop is an array type, emit $self.name$len = allocSize after the assignment. */
    private fun emitBodyPropLenIfArray(bp: BodyProp) {
        val resolved = resolveTypeName(bp.type)
        if (!isArrayType(resolved)) return
        val allocSize = extractAllocSize(bp.init)
        if (allocSize != null) {
            impl.appendLine("    \$self.${bp.name}\$len = ${genExpr(allocSize)};")
        }
    }

    /** Generate a call expression that returns nullable, appending &outVar as extra arg.
     *  The function returns bool (has value), and writes the value through the out pointer. */
    private fun genExprWithNullableOut(e: Expr, outVar: String): String {
        if (e !is CallExpr) return genExpr(e)
        val name = (e.callee as? NameExpr)?.name ?: return genExpr(e)
        val cName = pfx(name)
        val sig = funSigs[name]
        val args = expandCallArgs(e.args, sig?.params)
        val extraArg = "&$outVar"
        val allArgs = if (args.isEmpty()) extraArg else "$args, $extraArg"
        return "$cName($allArgs)"
    }

    /** Generate a call expression that returns an array, appending &name$len as extra arg
     *  to receive the array length through the $len_out out-parameter. */
    private fun genExprWithArrayLenOut(e: Expr, varName: String): String {
        if (e !is CallExpr) return genExpr(e)
        val name = (e.callee as? NameExpr)?.name ?: return genExpr(e)
        // For generic function calls, use the mangled name and fill defaults
        val genFun = genericFunDecls.find { it.name == name }
        if (genFun != null && e.typeArgs.isNotEmpty()) {
            val typeArgNames = e.typeArgs.map { resolveTypeName(it) }
            val mangledName = "${name}_${typeArgNames.joinToString("_")}"
            val prevSubst = typeSubst
            typeSubst = genFun.typeParams.zip(typeArgNames).toMap()
            val filledArgs = fillDefaults(e.args, genFun.params, genFun.params.associate { it.name to it.default })
            val expandedArgs = expandCallArgs(filledArgs, genFun.params)
            typeSubst = prevSubst
            val extraArg = "&${varName}\$len"
            val allArgs = if (expandedArgs.isEmpty()) extraArg else "$expandedArgs, $extraArg"
            return "${pfx(mangledName)}($allArgs)"
        }
        // Regular function
        val cName = pfx(name)
        val sig = funSigs[name]
        val filledArgs = if (sig != null) fillDefaults(e.args, sig.params, sig.params.associate { it.name to it.default }) else e.args
        val args = expandCallArgs(filledArgs, sig?.params)
        val extraArg = "&${varName}\$len"
        val allArgs = if (args.isEmpty()) extraArg else "$args, $extraArg"
        return "$cName($allArgs)"
    }

    // ── assignment ───────────────────────────────────────────────────

    private fun emitAssign(s: AssignStmt, ind: String, method: Boolean) {

        // operator set: a[i] = v → ClassName_set(&a, i, v)
        if (s.target is IndexExpr && s.op == "=") {
            val objType = inferExprType(s.target.obj)
            if (objType != null && classes.containsKey(objType)) {
                val setMethod = classes[objType]?.methods?.find { it.name == "set" && it.isOperator }
                if (setMethod != null) {
                    val recv = genExpr(s.target.obj)
                    val idx = genExpr(s.target.index)
                    val value = genExpr(s.value)
                    flushPreStmts(ind)
                    impl.appendLine("$ind${pfx(objType)}_set(&$recv, $idx, $value);")
                    return
                }
            }
            if (objType != null && anyIndirectClassName(objType)?.let { classes.containsKey(it) } == true) {
                val baseClass = anyIndirectClassName(objType)!!
                val setMethod = classes[baseClass]?.methods?.find { it.name == "set" && it.isOperator }
                if (setMethod != null) {
                    val recv = genExpr(s.target.obj)
                    val idx = genExpr(s.target.index)
                    val value = genExpr(s.value)
                    flushPreStmts(ind)
                    impl.appendLine("$ind${pfx(baseClass)}_set($recv, $idx, $value);")
                    return
                }
            }
            if (objType != null && interfaces.containsKey(objType)) {
                val ifaceInfo = interfaces[objType]
                val setMethod = ifaceInfo?.methods?.find { it.name == "set" && it.isOperator }
                    ?: collectAllIfaceMethods(ifaceInfo!!).find { it.name == "set" && it.isOperator }
                if (setMethod != null) {
                    val recv = genExpr(s.target.obj)
                    val idx = genExpr(s.target.index)
                    val value = genExpr(s.value)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$recv.vt->set($recv.obj, $idx, $value);")
                    return
                }
            }
        }

        val target = genLValue(s.target, method)
        val varName = (s.target as? NameExpr)?.name
        val varType = if (varName != null) lookupVar(varName) else null
        val isAnyValNullVar = isHeapValueNullable(varType) || isPtrValueNullable(varType) || isValueValueNullable(varType)
        val isAnyPtrNullVar = isHeapPtrNullable(varType) || isPtrPtrNullable(varType) || isValuePtrNullable(varType)

        when {
            // value-nullable (*<T?> / ^<T?> / &<T?>) = null → clear value, keep pointer
            isAnyValNullVar && s.value is NullLit -> {
                impl.appendLine("$ind${target}\$has = false;")
            }
            // value-nullable = value → set value
            isAnyValNullVar -> {
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind*$target = $value;")
                impl.appendLine("$ind${target}\$has = true;")
            }
            // pointer-nullable (*<T>? / ^<T>? / &<T>?) = null → NULL pointer
            isAnyPtrNullVar && s.value is NullLit -> {
                impl.appendLine("$ind$target = NULL;")
            }
            // pointer-nullable = value → assign pointer
            isAnyPtrNullVar -> {
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind$target ${s.op} $value;")
            }
            // Value nullable = null → $has = false
            varType != null && varType.endsWith("?") && s.value is NullLit -> {
                val baseType = varType.removeSuffix("?")
                impl.appendLine("$ind$target = ${defaultVal(baseType)};")
                impl.appendLine("$ind${target}\$has = false;")
            }
            // General case
            else -> {
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind$target ${s.op} $value;")
                if (varType != null && varType.endsWith("?") && anyIndirectClassName(varType) == null) {
                    impl.appendLine("$ind${target}\$has = true;")
                }
                // Update array $len when assigning from malloc/realloc
                if (varType != null && isArrayType(varType) && s.op == "=") {
                    val allocSize = extractAllocSize(s.value)
                    if (allocSize != null) {
                        impl.appendLine("$ind${target}\$len = ${genExpr(allocSize)};")
                    }
                }
            }
        }
    }

    // ── return ───────────────────────────────────────────────────────

    private fun emitReturn(s: ReturnStmt, ind: String) {
        if (currentFnReturnsNullable) {
            if (s.value == null || s.value is NullLit) {
                emitDeferredBlocks(ind)
                impl.appendLine("${ind}return false;")
            } else {
                val expr = genExpr(s.value)
                flushPreStmts(ind)
                if (deferStack.isNotEmpty()) {
                    val t = tmp()
                    impl.appendLine("$ind${cTypeStr(currentFnReturnBaseType())} $t = $expr;")
                    impl.appendLine("$ind*\$out = $t;")
                    emitDeferredBlocks(ind)
                    impl.appendLine("${ind}return true;")
                } else {
                    impl.appendLine("$ind*\$out = $expr;")
                    impl.appendLine("${ind}return true;")
                }
            }
        } else {
            if (s.value != null) {
                val expr = genExpr(s.value)
                flushPreStmts(ind)
                if (currentFnReturnsArray) {
                    // Array return: pass length through out-parameter
                    impl.appendLine("$ind*\$len_out = ${expr}\$len;")
                    if (deferStack.isNotEmpty()) {
                        val t = tmp()
                        impl.appendLine("$ind${cTypeStr(currentFnReturnType)} $t = $expr;")
                        emitDeferredBlocks(ind)
                        impl.appendLine("${ind}return $t;")
                    } else {
                        impl.appendLine("${ind}return $expr;")
                    }
                } else if (deferStack.isNotEmpty()) {
                    // Evaluate return value into temp, run defers, then return
                    val retType = currentFnReturnType.ifEmpty { inferExprType(s.value) ?: "Int" }
                    val t = tmp()
                    impl.appendLine("$ind${cTypeStr(retType)} $t = $expr;")
                    emitDeferredBlocks(ind)
                    impl.appendLine("${ind}return $t;")
                } else {
                    // Auto-wrap class → interface if return type is an interface
                    val exprType = inferExprType(s.value)
                    val retIface = currentFnReturnType
                    if (retIface.isNotEmpty() && interfaces.containsKey(retIface)
                        && exprType != null && classes.containsKey(exprType)
                        && classInterfaces[exprType]?.contains(retIface) == true) {
                        // Heap-allocate the class so the interface fat pointer outlives this scope
                        val t = tmp()
                        val cExprType = pfx(exprType)
                        impl.appendLine("$ind${cExprType}* $t = ${tMalloc("sizeof($cExprType)")};")
                        impl.appendLine("$ind*$t = $expr;")
                        impl.appendLine("${ind}return ${cExprType}_as_$retIface($t);")
                    } else {
                        impl.appendLine("${ind}return $expr;")
                    }
                }
            } else {
                emitDeferredBlocks(ind)
                impl.appendLine(if (currentFnIsMain) "${ind}return 0;" else "${ind}return;")
            }
        }
    }

    // ── expression statement (may be println, method call, etc.) ─────

    private fun emitExprStmt(s: ExprStmt, ind: String, method: Boolean) {
        val e = s.expr
        // if / when used as statements
        if (e is IfExpr) { emitIfStmt(e, ind, method); return }
        if (e is WhenExpr) { emitWhenStmt(e, ind, method); return }
        // println / print as statements — avoid GCC statement-expressions
        if (e is CallExpr && e.callee is NameExpr) {
            val name = e.callee.name
            if (name == "println") { emitPrintlnStmt(e.args, ind); return }
            if (name == "print")   { emitPrintStmt(e.args, ind); return }
        }
        // Heap/Ptr/Value .set(val) as statement — only when class has no own set() method
        if (e is CallExpr && e.callee is DotExpr && e.callee.name == "set") {
            val recvType = inferExprType(e.callee.obj)
            val baseClass = anyIndirectClassName(recvType)
            if (baseClass != null && classes[baseClass]?.methods?.any { it.name == "set" } != true) {
                val recv = genExpr(e.callee.obj)
                val argStr = e.args.joinToString(", ") { genExpr(it.expr) }
                flushPreStmts(ind)
                impl.appendLine("$ind*$recv = $argStr;")
                // If receiver is value-nullable (*<T?>), also set $has = true
                val recvVarName = (e.callee.obj as? NameExpr)?.name
                val recvVarType = if (recvVarName != null) lookupVar(recvVarName) else null
                if (isHeapValueNullable(recvVarType) || isPtrValueNullable(recvVarType) || isValueValueNullable(recvVarType)) {
                    impl.appendLine("$ind${recvVarName}\$has = true;")
                }
                return
            }
        }
        // Safe method call as statement: a?.print() → if (a$has) { String_print(a); }
        if (e is CallExpr && e.callee is SafeDotExpr) {
            val safe = e.callee
            val recvName = (safe.obj as? NameExpr)?.name
            val recvType = if (recvName != null) lookupVar(recvName) else null
            if (recvType != null && recvType.endsWith("?")) {
                val dotExpr = DotExpr(safe.obj, safe.name)
                val callExpr = genMethodCall(dotExpr, e.args)
                flushPreStmts(ind)
                impl.appendLine("${ind}if (${recvName}\$has) { $callExpr; }")
                return
            }
        }
        val expr = genExpr(e)
        flushPreStmts(ind)
        impl.appendLine("$ind$expr;")
    }

    /** Emit println as C statements. */
    private fun emitPrintlnStmt(args: List<Arg>, ind: String) {
        if (args.isEmpty()) { impl.appendLine("${ind}printf(\"\\n\");"); return }
        emitPrintStmtInner(args, ind, newline = true)
    }

    private fun emitPrintStmt(args: List<Arg>, ind: String) {
        if (args.isEmpty()) return
        emitPrintStmtInner(args, ind, newline = false)
    }

    private fun emitPrintStmtInner(args: List<Arg>, ind: String, newline: Boolean) {
        val arg = args[0].expr
        val nl = if (newline) "\\n" else ""

        // String template
        if (arg is StrTemplateExpr) {
            if (templateNeedsStrBuf(arg)) {
                emitPrintTemplateViaStrBuf(arg, ind, newline)
            } else {
                impl.appendLine("$ind${genPrintfFromTemplate(arg, nl)};")
            }
            return
        }

        val t = inferExprType(arg) ?: "Int"
        val expr = genExpr(arg)
        flushPreStmts(ind)

        // Nullable → if ($has) print(value) else print("null")
        if (t.endsWith("?")) {
            val baseT = t.removeSuffix("?")
            val hasExpr = "${expr}\$has"
            val fmt = printfFmt(baseT) + nl
            val a = printfArg(expr, baseT)
            impl.appendLine("${ind}if ($hasExpr) { printf(\"$fmt\", $a); }")
            impl.appendLine("${ind}else { printf(\"null$nl\"); }")
            return
        }

        // data class → emit toString into StrBuf, then printf
        if (classes.containsKey(t) && classes[t]!!.isData) {
            val buf = tmp()
            impl.appendLine("${ind}char ${buf}[256];")
            impl.appendLine("${ind}kt_StrBuf ${buf}_sb = {${buf}, 0, 256};")
            impl.appendLine("${ind}${pfx(t)}_toString($expr, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr);")
            return
        }

        // Heap/Ptr/Value pointer to data class → dereference, then toString
        val indirectBase = anyIndirectClassName(t)
        if (indirectBase != null && classes[indirectBase]?.isData == true) {
            val buf = tmp()
            impl.appendLine("${ind}char ${buf}[256];")
            impl.appendLine("${ind}kt_StrBuf ${buf}_sb = {${buf}, 0, 256};")
            impl.appendLine("${ind}${pfx(indirectBase)}_toString(*$expr, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr);")
            return
        }

        val fmt = printfFmt(t) + nl
        val a = printfArg(expr, t)
        impl.appendLine("${ind}printf(\"$fmt\", $a);")
    }

    /** Check if a template contains data class or nullable expressions (need StrBuf). */
    private fun templateNeedsStrBuf(tmpl: StrTemplateExpr): Boolean {
        return tmpl.parts.any { part ->
            part is ExprPart && run {
                val t = inferExprType(part.expr) ?: "Int"
                classes.containsKey(t) || t.endsWith("?")
            }
        }
    }

    /** Emit a println/print of a complex string template via kt_StrBuf. */
    private fun emitPrintTemplateViaStrBuf(tmpl: StrTemplateExpr, ind: String, newline: Boolean) {
        val buf = tmp()
        impl.appendLine("${ind}char ${buf}[256];")
        impl.appendLine("${ind}kt_StrBuf ${buf}_sb = {${buf}, 0, 256};")
        for (part in tmpl.parts) {
            when (part) {
                is LitPart -> impl.appendLine("${ind}kt_sb_append_cstr(&${buf}_sb, \"${escapeStr(part.text)}\");")
                is ExprPart -> {
                    val t = inferExprType(part.expr) ?: "Int"
                    val expr = genExpr(part.expr)
                    flushPreStmts(ind)
                    impl.appendLine("$ind${genSbAppend("&${buf}_sb", expr, t)}")
                }
            }
        }
        val nl = if (newline) "\\n" else ""
        impl.appendLine("${ind}printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr);")
    }

    // ── if (as statement) ────────────────────────────────────────────

    /**
     * Detect smart-cast candidates from a condition expression.
     * Returns a list of (varName, nonNullType) pairs for variables proven non-null.
     * Handles value nullable ("T?", "T*#") and pointer nullable ("T*?").
     */
    private fun extractSmartCasts(cond: Expr): List<Pair<String, String>> {
        val casts = mutableListOf<Pair<String, String>>()
        fun trySmartCast(name: String) {
            if (isMutable(name)) return  // var cannot be smart-cast
            val type = lookupVar(name)
            if (type != null && (type.endsWith("?") || type.endsWith("#"))) {
                casts.add(name to type.dropLast(1))
            }
        }
        fun tryThisSmartCast() {
            val type = currentExtRecvType
            if (type != null && type.endsWith("?")) {
                casts.add("\$self" to type.dropLast(1))
            }
        }
        when {
            // x != null
            cond is BinExpr && cond.op == "!=" && cond.right is NullLit && cond.left is NameExpr ->
                trySmartCast(cond.left.name)
            // null != x
            cond is BinExpr && cond.op == "!=" && cond.left is NullLit && cond.right is NameExpr ->
                trySmartCast(cond.right.name)
            // this != null
            cond is BinExpr && cond.op == "!=" && cond.right is NullLit && cond.left is ThisExpr ->
                tryThisSmartCast()
            // null != this
            cond is BinExpr && cond.op == "!=" && cond.left is NullLit && cond.right is ThisExpr ->
                tryThisSmartCast()
        }
        return casts
    }

    /** Detect smart-casts for the else branch (condition that proves null in the then branch). */
    private fun extractElseSmartCasts(cond: Expr): List<Pair<String, String>> {
        val casts = mutableListOf<Pair<String, String>>()
        fun trySmartCast(name: String) {
            if (isMutable(name)) return  // var cannot be smart-cast
            val type = lookupVar(name)
            if (type != null && (type.endsWith("?") || type.endsWith("#"))) {
                casts.add(name to type.dropLast(1))
            }
        }
        when {
            // x == null → in else branch, x is non-null
            cond is BinExpr && cond.op == "==" && cond.right is NullLit && cond.left is NameExpr ->
                trySmartCast(cond.left.name)
            cond is BinExpr && cond.op == "==" && cond.left is NullLit && cond.right is NameExpr ->
                trySmartCast(cond.right.name)
        }
        return casts
    }

    private fun emitIfStmt(e: IfExpr, ind: String, method: Boolean) {
        impl.appendLine("${ind}if (${genExpr(e.cond)}) {")
        // Smart cast: narrow nullable types in then-branch
        val thenCasts = extractSmartCasts(e.cond)
        if (thenCasts.isNotEmpty()) {
            pushScope()
            for ((name, nonNullType) in thenCasts) defineVar(name, nonNullType)
        }
        emitBlock(e.then, ind, method)
        if (thenCasts.isNotEmpty()) popScope()

        if (e.els != null) {
            // check for else-if chain
            val single = e.els.stmts.singleOrNull()
            if (single is ExprStmt && single.expr is IfExpr) {
                impl.appendLine("$ind} else ")
                emitIfStmt(single.expr, ind, method)
                return
            }
            impl.appendLine("$ind} else {")
            // Smart cast: narrow nullable types in else-branch (x == null → else has x non-null)
            val elseCasts = extractElseSmartCasts(e.cond)
            if (elseCasts.isNotEmpty()) {
                pushScope()
                for ((name, nonNullType) in elseCasts) defineVar(name, nonNullType)
            }
            emitBlock(e.els, ind, method)
            if (elseCasts.isNotEmpty()) popScope()
        }
        impl.appendLine("$ind}")
    }

    // ── when (as statement) ──────────────────────────────────────────

    private fun emitWhenStmt(e: WhenExpr, ind: String, method: Boolean) {
        for ((bi, br) in e.branches.withIndex()) {
            if (br.conds == null) {
                // else branch
                impl.appendLine("${ind}else {")
            } else {
                val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                val keyword = if (bi == 0) "if" else "else if"
                impl.appendLine("$ind$keyword ($condStr) {")
            }
            emitBlock(br.body, ind, method)
            impl.appendLine("$ind}")
        }
    }

    private fun genWhenCond(c: WhenCond, subject: Expr?): String {
        val subj = if (subject != null) genExpr(subject) else ""
        return when (c) {
            is ExprCond -> if (subject != null) "$subj == ${genExpr(c.expr)}" else genExpr(c.expr)
            is InCond   -> {
                val range = c.expr
                val neg = if (c.negated) "!" else ""
                if (range is BinExpr && range.op == "..") {
                    "${neg}($subj >= ${genExpr(range.left)} && $subj <= ${genExpr(range.right)})"
                } else "${neg}(/* in ${genExpr(range)} */)"   // fallback
            }
            is IsCond   -> "/* is ${c.type.name} */"  // no RTTI in subset
        }
    }

    // ── for ──────────────────────────────────────────────────────────

    private fun emitFor(s: ForStmt, ind: String, method: Boolean) {
        val iter = s.iter
        // Unwrap "step" wrapper: (rangeExpr step N)
        val step: String?
        val rangeExpr: Expr
        if (iter is BinExpr && iter.op == "step") {
            step = genExpr(iter.right)
            rangeExpr = iter.left
        } else {
            step = null
            rangeExpr = iter
        }
        when {
            // for (i in a..b)   inclusive range
            rangeExpr is BinExpr && rangeExpr.op == ".." -> {
                val inc = if (step != null) "${s.varName} += $step" else "${s.varName}++"
                impl.appendLine("${ind}for (int32_t ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} <= ${genExpr(rangeExpr.right)}; $inc) {")
                pushScope(); defineVar(s.varName, "Int")
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
            // for (i in a until b)  or  for (i in a..<b)
            rangeExpr is BinExpr && (rangeExpr.op == "until" || rangeExpr.op == "..<") -> {
                val inc = if (step != null) "${s.varName} += $step" else "${s.varName}++"
                impl.appendLine("${ind}for (int32_t ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} < ${genExpr(rangeExpr.right)}; $inc) {")
                pushScope(); defineVar(s.varName, "Int")
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
            // for (i in a downTo b)
            rangeExpr is BinExpr && rangeExpr.op == "downTo" -> {
                val dec = if (step != null) "${s.varName} -= $step" else "${s.varName}--"
                impl.appendLine("${ind}for (int32_t ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} >= ${genExpr(rangeExpr.right)}; $dec) {")
                pushScope(); defineVar(s.varName, "Int")
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
            // for (item in array/arrayList)  — iterate over elements
            else -> {
                val arrExpr = genExpr(rangeExpr)
                val idx = tmp()
                val arrType = inferExprType(rangeExpr)
                // Array: use \$len and direct indexing
                val elemType = arrayElementCType(arrType)
                impl.appendLine("${ind}for (int32_t $idx = 0; $idx < ${arrExpr}\$len; $idx++) {")
                impl.appendLine("$ind    $elemType ${s.varName} = ${arrExpr}[$idx];")
                pushScope(); defineVar(s.varName, arrayElementKtType(arrType))
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
        }
    }

    // ═══════════════════════════ Expression codegen ═══════════════════

    /** Generate an expression for use as a C function argument.
     *  String literals are emitted as raw C strings (not kt_str wrapped). */
    private fun genCArg(e: Expr): String = when (e) {
        is StrLit -> "\"${escapeStr(e.value)}\""
        else -> genExpr(e)
    }

    fun genExpr(e: Expr): String = when (e) {
        is IntLit    -> "${e.value}"
        is LongLit   -> "${e.value}LL"
        is DoubleLit -> "${e.value}"
        is FloatLit  -> "${e.value}f"
        is BoolLit   -> if (e.value) "true" else "false"
        is CharLit   -> "'${escapeC(e.value)}'"
        is StrLit    -> "kt_str(\"${escapeStr(e.value)}\")"
        is NullLit   -> "0 /* null */"
        is ThisExpr  -> if (selfIsPointer) "(*\$self)" else "\$self"
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
                        val ct = cTypeStr(retBase)
                        val t = tmp()
                        preStmts += "$ct $t;"
                        preStmts += "bool ${t}\$has = $recv.vt->get($recv.obj, $idx, &$t);"
                        defineVar(t, "${retBase}?")
                        t
                    } else {
                        "$recv.vt->get($recv.obj, $idx)"
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
        is ElvisExpr   -> "(${genExpr(e.left)}\$has ? ${genExpr(e.left)} : ${genExpr(e.right)})"
        is StrTemplateExpr -> genStrTemplate(e)
        is IsCheckExpr -> "/* is-check */ true"
        is CastExpr    -> "(${cType(e.type)})(${genExpr(e.expr)})"
        is FunRefExpr  -> pfx(e.name)    // ::functionName → C function pointer
    }

    // ── names (may resolve to enum, object field, self->field) ───────

    private fun genName(e: NameExpr): String {
        // Check if it's a known variable in scope
        if (lookupVar(e.name) != null) {
            if (currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
                return "\$self->${e.name}"
            }
            return e.name
        }
        // Top-level property: apply package prefix
        if (e.name in topProps) return pfx(e.name)
        return e.name
    }

    // ── binary ───────────────────────────────────────────────────────

    private fun genBin(e: BinExpr): String {
        val lt = inferExprType(e.left)
        // `to` infix → Pair compound literal
        if (e.op == "to") {
            val aType = inferExprType(e.left) ?: "Int"
            val bType = inferExprType(e.right) ?: "Int"
            pairTypes.add(Pair(aType, bType))
            pairTypeComponents["Pair_${aType}_${bType}"] = Pair(aType, bType)
            ensurePairType(aType, bType)
            val pairCType = "kt_Pair_${aType}_${bType}"
            return "($pairCType){${genExpr(e.left)}, ${genExpr(e.right)}}"
        }
        // null comparison
        if ((e.op == "==" || e.op == "!=") && (e.left is NullLit || e.right is NullLit)) {
            val nonNull = if (e.left is NullLit) e.right else e.left
            // this == null / this != null inside nullable-receiver extension
            if (nonNull is ThisExpr) {
                val thisType = inferExprType(nonNull)
                if (thisType != null && thisType.endsWith("?")) {
                    return if (e.op == "==") "!\$self\$has" else "\$self\$has"
                }
            }
            val varName = (nonNull as? NameExpr)?.name
            val varType = if (varName != null) lookupVar(varName) else null
            if (varType != null) {
                // Heap<T>? / Ptr<T>? / Value<T>? → compare pointer to NULL
                if (isHeapPtrNullable(varType) || isPtrPtrNullable(varType) || isValuePtrNullable(varType)) {
                    return if (e.op == "==") "$varName == NULL" else "$varName != NULL"
                }
                // Heap<T?> / Ptr<T?> / Value<T?> → use $has
                if (isHeapValueNullable(varType) || isPtrValueNullable(varType) || isValueValueNullable(varType)) {
                    val has = "${varName}\$has"
                    return if (e.op == "==") "!$has" else has
                }
                // Value nullable → use $has
                if (varType.endsWith("?")) {
                    val has = "${varName}\$has"
                    return if (e.op == "==") "!$has" else has
                }
            }
        }
        // data class == → ClassName_equals
        if (e.op == "==" && lt != null && classes[lt]?.isData == true) {
            return "${pfx(lt)}_equals(${genExpr(e.left)}, ${genExpr(e.right)})"
        }
        // String == → kt_string_eq
        if (e.op == "==" && lt == "String") {
            return "kt_string_eq(${genExpr(e.left)}, ${genExpr(e.right)})"
        }
        if (e.op == "!=" && lt == "String") {
            return "!kt_string_eq(${genExpr(e.left)}, ${genExpr(e.right)})"
        }
        // String <, >, <=, >= → kt_string_cmp
        if (lt == "String" && e.op in listOf("<", ">", "<=", ">=")) {
            return "(kt_string_cmp(${genExpr(e.left)}, ${genExpr(e.right)}) ${e.op} 0)"
        }
        // String + → kt_string_cat
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
                    val call = "$recv.vt->${containsMethod.name}($recv.obj, $elem)"
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

    private fun genStringConcat(e: BinExpr): String {
        val buf = tmp()
        preStmts += "char ${buf}[512];"
        return "kt_string_cat($buf, sizeof($buf), ${genExpr(e.left)}, ${genExpr(e.right)})"
    }

    // ── function / constructor call ──────────────────────────────────

    private fun genCall(e: CallExpr): String {
        // Method call: DotExpr(receiver, method)(args)
        if (e.callee is DotExpr) {
            // C package passthrough: c.printf(...) → printf(...)
            // String literals are emitted as raw C strings (not kt_str wrapped)
            if (e.callee.obj is NameExpr && (e.callee.obj as NameExpr).name == "c") {
                val cFnName = e.callee.name
                val argStr = e.args.joinToString(", ") { genCArg(it.expr) }
                return "$cFnName($argStr)"
            }
            // Reject non-safe call on nullable receiver (unless the extension accepts nullable receiver)
            val recvType = inferExprType(e.callee.obj)
            if (recvType != null && recvType.endsWith("?")) {
                val baseType = recvType.removeSuffix("?")
                if (!hasNullableReceiverExt(baseType, e.callee.name)) {
                    val recvSrc = (e.callee.obj as? NameExpr)?.name ?: e.callee.obj.toString()
                    codegenError("Only safe (?.) calls are allowed on a nullable receiver of type '$recvType': $recvSrc.${e.callee.name}()")
                }
            }
            return genMethodCall(e.callee, e.args)
        }
        if (e.callee is SafeDotExpr) return genSafeMethodCall(e.callee, e.args)

        val name = (e.callee as? NameExpr)?.name ?: return "${genExpr(e.callee)}(${e.args.joinToString(", ") { genExpr(it.expr) }})"
        val args = e.args

        // Built-in functions
        when (name) {
            "println" -> return genPrintln(args)
            "print"   -> return genPrint(args)
            "malloc"  -> {
                if (e.typeArgs.isNotEmpty()) {
                    val ta = e.typeArgs[0]
                    // malloc<Array<T>>(n) → typed array allocation: (elemC*)malloc(sizeof(elemC) * (size_t)(n))
                    if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                        val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                        val elemC = cTypeStr(elemName)
                        return "($elemC*)${tMalloc("sizeof($elemC) * (size_t)(${genExpr(args[0].expr)})")}"
                    }
                    var typeName = typeSubst[ta.name] ?: ta.name
                    // Resolve generic class: malloc<MyList<Int>>(...) → MyList_Int_new(...)
                    if (ta.typeArgs.isNotEmpty() && classes.containsKey(typeName) && classes[typeName]!!.isGeneric) {
                        typeName = mangledGenericName(typeName, ta.typeArgs.map { it.name })
                    }
                    // Class heap constructor: malloc<MyClass>(args) → inline alloc + create
                    if (classes.containsKey(typeName)) {
                        val cName = pfx(typeName)
                        val argStr = args.joinToString(", ") { genExpr(it.expr) }
                        if (memTrack) {
                            // Inline: allocate with Kotlin source, then init via _create
                            val t = tmp()
                            preStmts += "$cName* $t = ($cName*)${tMalloc("sizeof($cName)")};"
                            preStmts += "if ($t) *$t = ${cName}_create($argStr);"
                            return t
                        }
                        return "${cName}_new($argStr)"
                    }
                    // malloc<T>() with no args → single element: (T*)malloc(sizeof(T))
                    val elemC = cTypeStr(typeName)
                    if (args.isEmpty()) {
                        return "($elemC*)${tMalloc("sizeof($elemC)")}"
                    }
                    // malloc<T>(n) → array allocation: (T*)malloc(sizeof(T) * (size_t)(n))
                    return "($elemC*)${tMalloc("sizeof($elemC) * (size_t)(${genExpr(args[0].expr)})")}"
                }
                return tMalloc("(size_t)(${genExpr(args[0].expr)})")
            }
            "calloc"  -> {
                if (e.typeArgs.isNotEmpty()) {
                    val ta = e.typeArgs[0]
                    val elemName = if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                        typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                    } else {
                        typeSubst[ta.name] ?: ta.name
                    }
                    val elemC = cTypeStr(elemName)
                    return "($elemC*)${tCalloc("(size_t)(${genExpr(args[0].expr)})", "sizeof($elemC)")}"
                }
                return tCalloc("(size_t)(${genExpr(args[0].expr)})", "(size_t)(${genExpr(args[1].expr)})")
            }
            "realloc" -> {
                if (e.typeArgs.isNotEmpty()) {
                    val ta = e.typeArgs[0]
                    val elemName = if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                        typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                    } else {
                        typeSubst[ta.name] ?: ta.name
                    }
                    val elemC = cTypeStr(elemName)
                    return "($elemC*)${tRealloc(genExpr(args[0].expr), "sizeof($elemC) * (size_t)(${genExpr(args[1].expr)})")}"
                }
                return tRealloc(genExpr(args[0].expr), "(size_t)(${genExpr(args[1].expr)})")
            }
            "free"    -> return tFree(genExpr(args[0].expr))
            "intArrayOf", "longArrayOf", "floatArrayOf", "doubleArrayOf",
            "booleanArrayOf", "charArrayOf" -> {
                // handled in emitVarDecl; if used as expr, wrap in compound literal
                return genArrayOfExpr(name, args)
            }
            "arrayOf" -> {
                return genArrayOfExpr(name, args)
            }
            "IntArray" -> return genNewArray("int32_t", args)
            "LongArray" -> return genNewArray("int64_t", args)
            "FloatArray" -> return genNewArray("float", args)
            "DoubleArray" -> return genNewArray("double", args)
            "BooleanArray" -> return genNewArray("bool", args)
            "CharArray" -> return genNewArray("char", args)
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
            val pairCType = "kt_Pair_${a}_${b}"
            return "($pairCType){${genExpr(args[0].expr)}, ${genExpr(args[1].expr)}}"
        }

        // Function pointer call: variable with function type → just call it
        val varType = lookupVar(name)
        if (varType != null && isFuncType(varType)) {
            val argStr = args.joinToString(", ") { genExpr(it.expr) }
            return "$name($argStr)"
        }

        // Constructor call (known class)
        // Handle generic class constructor: MyList<Int>(8) → MyList_Int_create(8)
        if (classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
            // Apply typeSubst so type params (e.g. T) resolve to concrete types (e.g. Int)
            // when inside a generic function body
            val resolvedTypeArgs = e.typeArgs.map { substituteTypeParams(it) }.map { it.name }
            val mangledName = mangledGenericName(name, resolvedTypeArgs)
            val ci = classes[mangledName] ?: error("Generic class '$mangledName' not materialized (typeSubst=$typeSubst)")
            val templateDecl = genericClassDecls[name]
            val allParams = ci.ctorProps + ci.ctorPlainParams
            val filledArgs = fillDefaults(args, allParams.map { Param(it.first, it.second) }, allParams.associate {
                val cp = templateDecl?.ctorParams?.find { p -> p.name == it.first }
                it.first to cp?.default
            })
            return "${pfx(mangledName)}_create(${filledArgs.joinToString(", ") { genExpr(it.expr) }})"
        }
        if (classes.containsKey(name)) {
            val ci = classes[name]!!
            val allParams = ci.ctorProps + ci.ctorPlainParams
            val filledArgs = fillDefaults(args, allParams.map { Param(it.first, it.second) }, allParams.associate {
                // find matching ctor param default
                val cp = (file.decls.filterIsInstance<ClassDecl>().find { c -> c.name == name })
                    ?.ctorParams?.find { p -> p.name == it.first }
                it.first to cp?.default
            })
            return "${pfx(name)}_create(${filledArgs.joinToString(", ") { genExpr(it.expr) }})"
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

        // If function returns nullable, hoist to preStmt with temp var
        if (sig?.returnType?.nullable == true) {
            val retType = resolveTypeName(sig.returnType)
            val ct = cTypeStr(retType)
            val t = tmp()
            val hasVar = "${t}\$has"
            preStmts += "$ct $t;"
            val allArgs = if (expandedArgs.isEmpty()) "&$t" else "$expandedArgs, &$t"
            preStmts += "bool $hasVar = ${pfx(name)}($allArgs);"
            defineVar(t, "${retType}?")
            return t
        }

        return "${pfx(name)}($expandedArgs)"
    }

    /** Expand call arguments: array → (arg, arg$len); nullable → (arg, arg$has); class→interface wrapping; vararg packing. */
    private fun expandCallArgs(args: List<Arg>, params: List<Param>?): String {
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
                if (isArrayType(paramType)) {
                    parts += expr
                    parts += "${expr}\$len"
                } else if (param.type.nullable) {
                    if (arg.expr is NullLit) {
                        parts += "${defaultVal(paramType)}"
                        parts += "false"
                    } else {
                        parts += expr
                        val argVarName = (arg.expr as? NameExpr)?.name
                        val argVarType = if (argVarName != null) lookupVar(argVarName) else null
                        if (argVarType != null && argVarType.endsWith("?")) {
                            parts += "${expr}\$has"
                        } else {
                            parts += "true"
                        }
                    }
                } else if (interfaces.containsKey(paramType)) {
                    val argType = inferExprType(arg.expr)
                    val baseArgType = argType?.trimEnd('*', '&', '^', '?', '#')
                    if (baseArgType != null && classes.containsKey(baseArgType) && classInterfaces[baseArgType]?.contains(paramType) == true) {
                        if (argType != null && (argType.endsWith("&") || argType.endsWith("^"))) {
                            parts += "${pfx(baseArgType)}_as_$paramType($expr)"
                        } else if (argType != null && argType.endsWith("*")) {
                            parts += "${pfx(baseArgType)}_as_$paramType($expr)"
                        } else {
                            parts += "${pfx(baseArgType)}_as_$paramType(&$expr)"
                        }
                    } else {
                        parts += expr
                    }
                } else {
                    val argType = inferExprType(arg.expr)
                    if (classes.containsKey(paramType)) {
                        if (argType != null && (argType == "${paramType}*" || argType == "${paramType}&" || argType == "${paramType}^")) {
                            parts += expr
                        } else {
                            parts += "&$expr"
                        }
                    } else {
                        parts += expr
                    }
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


    private fun genMethodCall(dot: DotExpr, args: List<Arg>): String {
        val rawRecvType = inferExprType(dot.obj)
        val recvType = rawRecvType?.removeSuffix("?")
        val recv = genExpr(dot.obj)
        val method = dot.name
        val argStr = args.joinToString(", ") { genExpr(it.expr) }

        // Built-in methods
        when (method) {
            "toString" -> return genToString(recv, recvType ?: "Int")
            "toInt" -> {
                if (recvType == "String") return "kt_str_toInt($recv)"
                return "((int32_t)($recv))"
            }
            "toLong" -> {
                if (recvType == "String") return "kt_str_toLong($recv)"
                return "((int64_t)($recv))"
            }
            "toFloat" -> {
                if (recvType == "String") return "((float)kt_str_toDouble($recv))"
                return "((float)($recv))"
            }
            "toDouble" -> {
                if (recvType == "String") return "kt_str_toDouble($recv)"
                return "((double)($recv))"
            }
            "toByte" -> return "((int8_t)($recv))"
            "toChar" -> return "((char)($recv))"
            // Nullable string-to-number: toIntOrNull, toLongOrNull, toFloatOrNull, toDoubleOrNull
            "toIntOrNull" -> if (recvType == "String") {
                val t = tmp()
                preStmts += "int32_t $t;"
                preStmts += "bool ${t}\$has = kt_str_toIntOrNull($recv, &$t);"
                return t
            }
            "toLongOrNull" -> if (recvType == "String") {
                val t = tmp()
                preStmts += "int64_t $t;"
                preStmts += "bool ${t}\$has = kt_str_toLongOrNull($recv, &$t);"
                return t
            }
            "toDoubleOrNull" -> if (recvType == "String") {
                val t = tmp()
                preStmts += "double $t;"
                preStmts += "bool ${t}\$has = kt_str_toDoubleOrNull($recv, &$t);"
                return t
            }
            "toFloatOrNull" -> if (recvType == "String") {
                val t = tmp()
                preStmts += "double ${t}_d;"
                preStmts += "bool ${t}\$has = kt_str_toDoubleOrNull($recv, &${t}_d);"
                preStmts += "float $t = (float)${t}_d;"
                return t
            }
            "substring" -> if (recvType == "String") {
                val from = genExpr(args[0].expr)
                val to = if (args.size >= 2) genExpr(args[1].expr) else "$recv.len"
                return "kt_string_substring($recv, $from, $to)"
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
                    "Int" -> "kt_hash_i32($recv)"
                    "Long" -> "kt_hash_i64($recv)"
                    "Float" -> "kt_hash_f32($recv)"
                    "Double" -> "kt_hash_f64($recv)"
                    "Boolean" -> "kt_hash_bool($recv)"
                    "Char" -> "kt_hash_char($recv)"
                    "String" -> "kt_hash_str($recv)"
                    else -> "${pfx(rt)}_hashCode(&($recv))"
                }
            }
        }

        // Array .size → name\$len
        if (method == "size" && recvType != null && isArrayType(recvType)) return "${recv}\$len"

        // Heap class pointer methods
        val heapBase = heapClassName(recvType)
        if (heapBase != null) {
            // If class defines the method, delegate to it (e.g. class has its own set/get)
            val classHasMethod = classes[heapBase]?.methods?.any { it.name == method } == true
            if (classHasMethod) {
                val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
                val methodDecl = classes[heapBase]?.methods?.find { it.name == method }
                if (methodDecl?.returnType?.nullable == true) {
                    return genNullableMethodCall(heapBase, "${pfx(heapBase)}_$method", allArgs, methodDecl)
                }
                return "${pfx(heapBase)}_$method($allArgs)"
            }
            when (method) {
                // .value() → same pointer, typed as Value<T> (no copy)
                "value" -> return recv
                // .deref() → stack copy: *p
                "deref" -> return "(*$recv)"
                // .set(val) — mostly handled at statement level (emitExprStmt), fallback:
                "set" -> return "(*$recv = $argStr)"
                // .copy() on data class
                "copy" -> if (classes[heapBase]?.isData == true) {
                    return genDataClassCopy(recv, heapBase, args, heap = true)
                }
                // .toHeap() on heap pointer — identity, already on heap
                "toHeap" -> return recv
                // .toPtr() → same pointer with $heap = true
                "toPtr" -> {
                    val t = tmp()
                    preStmts += "${cTypeStr("$heapBase*")} $t = $recv;"
                    preStmts += "bool ${t}\$heap = true;"
                    return t
                }
            }
            // general class method — pointer passed directly
            val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
            return "${pfx(heapBase)}_$method($allArgs)"
        }

        // Ptr<T> methods
        val ptrBase = ptrClassName(recvType)
        if (ptrBase != null) {
            val classHasMethod = classes[ptrBase]?.methods?.any { it.name == method } == true
            if (classHasMethod) {
                val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
                val methodDecl = classes[ptrBase]?.methods?.find { it.name == method }
                if (methodDecl?.returnType?.nullable == true) {
                    return genNullableMethodCall(ptrBase, "${pfx(ptrBase)}_$method", allArgs, methodDecl)
                }
                return "${pfx(ptrBase)}_$method($allArgs)"
            }
            when (method) {
                // .value() → same pointer, typed as Value<T> (no copy)
                "value" -> return recv
                // .deref() → stack copy: *p
                "deref" -> return "(*$recv)"
                // .set(val) → *p = val
                "set" -> return "(*$recv = $argStr)"
                // .copy() on data class
                "copy" -> if (classes[ptrBase]?.isData == true) {
                    return genDataClassCopy(recv, ptrBase, args, heap = true)
                }
                // .isHeap() → $heap companion
                "isHeap" -> return "${recv}\$heap"
                // .asHeap() → nullable: $heap ? p : NULL
                "asHeap" -> {
                    val t = tmp()
                    preStmts += "${cTypeStr("$ptrBase*")} $t = ${recv}\$heap ? $recv : NULL;"
                    return t
                }
                // .toPtr() → identity
                "toPtr" -> return recv
                // .toHeap() → only valid if isHeap
                "toHeap" -> return recv
            }
            // general class method — pointer passed directly
            val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
            return "${pfx(ptrBase)}_$method($allArgs)"
        }

        // Value<T> — transparent delegation: all method calls go to the class
        val valBase = valueClassName(recvType)
        if (valBase != null) {
            when (method) {
                // .deref() → stack copy: *p
                "deref" -> return "(*$recv)"
                // .toPtr() → same pointer, $heap unknown (conservative false)
                "toPtr" -> {
                    val t = tmp()
                    preStmts += "${cTypeStr("$valBase*")} $t = $recv;"
                    preStmts += "bool ${t}\$heap = false;"
                    return t
                }
            }
            // All other method calls → class method, pointer passed directly
            val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
            val methodDecl = classes[valBase]?.methods?.find { it.name == method }
            if (methodDecl?.returnType?.nullable == true) {
                return genNullableMethodCall(valBase, "${pfx(valBase)}_$method", allArgs, methodDecl)
            }
            return "${pfx(valBase)}_$method($allArgs)"
        }

        // Interface method dispatch → d.vt->method(d.obj, args)
        if (recvType != null && interfaces.containsKey(recvType)) {
            val allArgs = if (argStr.isEmpty()) "$recv.obj" else "$recv.obj, $argStr"
            // Check if this interface method returns nullable
            val ifaceInfo = interfaces[recvType]
            val ifaceMethod = ifaceInfo?.methods?.find { it.name == method }
                ?: collectAllIfaceMethods(ifaceInfo!!).find { it.name == method }
            if (ifaceMethod?.returnType?.nullable == true) {
                val retType = resolveTypeName(ifaceMethod.returnType)
                val ct = cTypeStr(retType)
                val t = tmp()
                preStmts += "$ct $t;"
                val callArgs = if (allArgs.isEmpty()) "&$t" else "$allArgs, &$t"
                preStmts += "bool ${t}\$has = $recv.vt->$method($callArgs);"
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
            // .toPtr() → &value, $heap = false
            if (method == "toPtr") {
                val t = tmp()
                preStmts += "${pfx(recvType)}* $t = &$recv;"
                preStmts += "bool ${t}\$heap = false;"
                return t
            }
            val nullableRecv = hasNullableReceiverExt(recvType, method)
            val selfArg = if (nullableRecv) {
                val recvName = (dot.obj as? NameExpr)?.name
                val hasFlag = if (dot.obj is ThisExpr) "\$self\$has"
                              else if (recvName != null) "${recvName}\$has"
                              else "true"
                "&$recv, $hasFlag"
            } else "&$recv"
            val allArgs = if (argStr.isEmpty()) selfArg else "$selfArg, $argStr"
            // Nullable return: use out-pointer pattern
            val methodDecl = classes[recvType]?.methods?.find { it.name == method }
            if (methodDecl?.returnType?.nullable == true) {
                return genNullableMethodCall(recvType, "${pfx(recvType)}_$method", allArgs, methodDecl)
            }
            return "${pfx(recvType)}_$method($allArgs)"
        }
        // Object method
        if (recvType != null && objects.containsKey(recvType)) {
            return "${pfx(recvType)}_$method($argStr)"
        }
        // Enum → field access
        if (recvType != null && enums.containsKey(recvType)) {
            return "${pfx(recvType)}_$method"
        }

        // Extension function on non-class type (Int, String, etc.)
        if (recvType != null) {
            val extFun = extensionFuns[recvType]?.find { it.name == method }
            if (extFun != null) {
                val nullableRecv = extFun.receiver?.nullable == true
                val recvArg = if (nullableRecv) {
                    // Pass receiver value + $has flag for nullable-receiver extensions
                    val recvName = (dot.obj as? NameExpr)?.name
                    val hasFlag = if (dot.obj is ThisExpr) "\$self\$has"
                                  else if (recvName != null) "${recvName}\$has"
                                  else "true"
                    "$recv, $hasFlag"
                } else recv
                val allArgs = if (argStr.isEmpty()) recvArg else "$recvArg, $argStr"
                return "${pfx(recvType)}_$method($allArgs)"
            }
        }

        return "$recv.$method($argStr)"   // fallback
    }

    /** Generate a method call that returns nullable via out-pointer. */
    private fun genNullableMethodCall(className: String, fnExpr: String, allArgs: String, methodDecl: FunDecl): String {
        val retBase = resolveMethodReturnType(className, methodDecl.returnType).removeSuffix("?")
        val ct = cTypeStr(retBase)
        val t = tmp()
        preStmts += "$ct $t;"
        val callArgs = if (allArgs.isEmpty()) "&$t" else "$allArgs, &$t"
        preStmts += "bool ${t}\$has = $fnExpr($callArgs);"
        defineVar(t, "${retBase}?")
        return t
    }

    /** Generate data class copy. `heap` = true when receiver is a heap pointer. */
    private fun genDataClassCopy(recv: String, className: String, args: List<Arg>, heap: Boolean): String {
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

    private fun genSafeMethodCall(dot: SafeDotExpr, args: List<Arg>): String {
        val recv = genExpr(dot.obj)
        val recvName = (dot.obj as? NameExpr)?.name
        // x?.method(args) → x$has ? ClassName_method(x, args) : defaultVal
        val dotExpr = DotExpr(dot.obj, dot.name)
        val call = genMethodCall(dotExpr, args)
        return "(${recvName}\$has ? $call : 0)"
    }

    // ── dot access (property, enum) ──────────────────────────────────

    private fun genDot(e: DotExpr): String {
        // C package passthrough: c.EXIT_SUCCESS → EXIT_SUCCESS, c.NULL → NULL
        if (e.obj is NameExpr && e.obj.name == "c") {
            return e.name
        }

        val recvType = inferExprType(e.obj)
        val recv = genExpr(e.obj)

        // Reject non-safe access on nullable receiver (enum/object are never nullable)
        val isEnumOrObj = e.obj is NameExpr && (enums.containsKey(e.obj.name) || objects.containsKey(e.obj.name))
        if (recvType != null && recvType.endsWith("?") && !isEnumOrObj) {
            val recvSrc = (e.obj as? NameExpr)?.name ?: e.obj.toString()
            codegenError("Only safe (?.) access is allowed on a nullable receiver of type '$recvType': $recvSrc.${e.name}")
        }

        // Enum entry: Color.RED → game_Color_RED
        if (e.obj is NameExpr && enums.containsKey(e.obj.name)) {
            return "${pfx(e.obj.name)}_${e.name}"
        }
        // Object field: Config.debug → game_Config.debug
        if (e.obj is NameExpr && objects.containsKey(e.obj.name)) {
            return "${pfx(e.obj.name)}.${e.name}"
        }
        // Array .size → name\$len
        if (e.name == "size" && recvType != null && isArrayType(recvType)) return "${recv}\$len"
        if (e.name == "length" && recvType == "String") return "$recv.len"

        // Heap class pointer: p->field
        if (heapClassName(recvType) != null) {
            return "$recv->${e.name}"
        }
        // Ptr<T> or Value<T>: p->field (same deref as Heap)
        if (ptrClassName(recvType) != null || valueClassName(recvType) != null) {
            return "$recv->${e.name}"
        }

        // Interface property access via vtable: list.size → list.vt->size(list.obj)
        if (recvType != null && interfaces.containsKey(recvType)) {
            val iface = interfaces[recvType]!!
            val allProps = collectAllIfaceProperties(iface)
            if (allProps.any { it.name == e.name }) {
                return "$recv.vt->${e.name}($recv.obj)"
            }
        }

        return "$recv.${e.name}"
    }

    private fun genSafeDot(e: SafeDotExpr): String {
        val recv = genExpr(e.obj)
        // x?.field → x$has ? x.field : 0
        return "(${recv}\$has ? $recv.${e.name} : 0)"
    }

    // ── !! (not-null assertion) ─────────────────────────────────────────

    private fun genNotNull(e: NotNullExpr): String {
        val inner = genExpr(e.expr)
        val innerType = inferExprType(e.expr)
        val loc = "$sourceFileName:$currentStmtLine"

        // Pointer-nullable: type ends with "*", "^", "&", or is "Pointer"
        val baseType = innerType?.removeSuffix("?")?.removeSuffix("#") ?: ""
        val isPtr = baseType.endsWith("*") || baseType.endsWith("^") || baseType.endsWith("&")
                || baseType == "Pointer" || isAllocCall(e.expr)

        if (isPtr) {
            val ct = cTypeStr(baseType.ifEmpty { "void*" })
            // Simple name — no temp needed
            if (e.expr is NameExpr) {
                preStmts += "if (!$inner) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
                return inner
            }
            val t = tmp()
            preStmts += "$ct $t = $inner;"
            preStmts += "if (!$t) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
            return t
        }

        // Value-nullable variable: check $has companion
        if (innerType != null && (innerType.endsWith("?") || innerType.endsWith("#")) && e.expr is NameExpr) {
            val name = (e.expr as NameExpr).name
            preStmts += "if (!${name}\$has) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
            return inner
        }

        // Fallback: no check (non-nullable expression)
        return inner
    }

    // ── if expression (as C ternary or temp) ─────────────────────────

    private fun genIfExpr(e: IfExpr): String {
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
    private fun blockAsSingleExpr(b: Block): Expr? {
        if (b.stmts.size == 1) {
            val s = b.stmts[0]
            if (s is ExprStmt) return s.expr
        }
        return null
    }

    /** Emit block statements into preStmts, assigning last expression to tempVar. */
    private fun emitBlockIntoTemp(b: Block, tempVar: String, indent: String) {
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
    private fun emitStmtToPreStmts(s: Stmt, indent: String) {
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

    private fun inferIfExprType(e: IfExpr): String? {
        val thenType = inferBlockType(e.then)
        if (thenType != null) return thenType
        if (e.els != null) return inferBlockType(e.els)
        return null
    }

    private fun inferBlockType(b: Block): String? {
        val last = b.stmts.lastOrNull() ?: return null
        return when (last) {
            is ExprStmt -> inferExprType(last.expr)
            is ReturnStmt -> if (last.value != null) inferExprType(last.value) else null
            else -> null
        }
    }

    // ── when expression (nested ternary or temp) ──────────────────────

    private fun genWhenExpr(e: WhenExpr): String {
        // Check if all branches are single-expression → nested ternary
        val allSimple = e.branches.all { blockAsSingleExpr(it.body) != null }
        if (allSimple) {
            val sb = StringBuilder()
            for (br in e.branches) {
                val expr = genExpr(blockAsSingleExpr(br.body)!!)
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
                if (bi > 0) preStmts += "} else {"
                else preStmts += "{"
            } else {
                val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                val keyword = if (bi == 0) "if" else "} else if"
                preStmts += "$keyword ($condStr) {"
            }
            emitBlockIntoTemp(br.body, t, "    ")
        }
        preStmts += "}"
        return t
    }

    private fun inferWhenExprType(e: WhenExpr): String? {
        for (br in e.branches) {
            val t = inferBlockType(br.body)
            if (t != null) return t
        }
        return null
    }

    // ── println / print (expression context — rare) ──────────────────

    private fun genPrintln(args: List<Arg>): String {
        if (args.isEmpty()) return "printf(\"\\n\")"
        return genPrintCall(args, newline = true)
    }

    private fun genPrint(args: List<Arg>): String {
        if (args.isEmpty()) return "(void)0"
        return genPrintCall(args, newline = false)
    }

    private fun genPrintCall(args: List<Arg>, newline: Boolean): String {
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
            preStmts += "char ${buf}[256];"
            preStmts += "kt_StrBuf ${buf}_sb = {${buf}, 0, 256};"
            preStmts += "${pfx(t)}_toString($expr, &${buf}_sb);"
            return "printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr)"
        }
        // Heap/Ptr/Value to data class → deref, then toString
        val indirectBase = anyIndirectClassName(t)
        if (indirectBase != null && classes[indirectBase]?.isData == true) {
            val buf = tmp()
            preStmts += "char ${buf}[256];"
            preStmts += "kt_StrBuf ${buf}_sb = {${buf}, 0, 256};"
            preStmts += "${pfx(indirectBase)}_toString(*$expr, &${buf}_sb);"
            return "printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr)"
        }

        val fmt = printfFmt(t) + nl
        val a = printfArg(expr, t)
        return "printf(\"$fmt\", $a)"
    }

    private fun genPrintfFromTemplate(tmpl: StrTemplateExpr, nl: String): String {
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

    // ── string template (returns kt_String via preStmts) ─────────────

    private fun genStrTemplate(e: StrTemplateExpr): String {
        val buf = tmp()
        preStmts += "char ${buf}[256];"
        preStmts += "kt_StrBuf ${buf}_sb = {${buf}, 0, 256};"
        for (part in e.parts) {
            when (part) {
                is LitPart -> preStmts += "kt_sb_append_cstr(&${buf}_sb, \"${escapeStr(part.text)}\");"
                is ExprPart -> {
                    val t = inferExprType(part.expr) ?: "Int"
                    val expr = genExpr(part.expr)
                    preStmts += genSbAppend("&${buf}_sb", expr, t)
                }
            }
        }
        return "kt_sb_to_string(&${buf}_sb)"
    }

    // ── toString dispatch ────────────────────────────────────────────

    private fun genToString(recv: String, type: String): String {
        if (classes.containsKey(type) && classes[type]!!.isData) {
            val buf = tmp()
            preStmts += "char ${buf}[256];"
            preStmts += "kt_StrBuf ${buf}_sb = {${buf}, 0, 256};"
            preStmts += "${pfx(type)}_toString($recv, &${buf}_sb);"
            return "kt_sb_to_string(&${buf}_sb)"
        }
        return when (type) {
            "Int" -> {
                val buf = tmp()
                preStmts += "char ${buf}[32];"
                preStmts += "kt_StrBuf ${buf}_sb = {${buf}, 0, 32};"
                preStmts += "kt_sb_append_int(&${buf}_sb, $recv);"
                "kt_sb_to_string(&${buf}_sb)"
            }
            "Long" -> {
                val buf = tmp()
                preStmts += "char ${buf}[32];"
                preStmts += "kt_StrBuf ${buf}_sb = {${buf}, 0, 32};"
                preStmts += "kt_sb_append_long(&${buf}_sb, $recv);"
                "kt_sb_to_string(&${buf}_sb)"
            }
            "String" -> recv
            else -> "kt_str(\"<$type>\")"
        }
    }

    // ── StrBuf append helper ─────────────────────────────────────────

    private fun genSbAppend(sbRef: String, expr: String, type: String): String {
        // Nullable → conditionally append "null" or the value
        if (type.endsWith("?")) {
            val baseT = type.removeSuffix("?")
            val inner = genSbAppend(sbRef, expr, baseT).removeSuffix(";")
            return "if (${expr}\$has) { $inner; } else { kt_sb_append_cstr($sbRef, \"null\"); }"
        }
        return when (type) {
            "Int" -> "kt_sb_append_int($sbRef, $expr);"
            "Long" -> "kt_sb_append_long($sbRef, $expr);"
            "Float" -> "kt_sb_append_double($sbRef, (double)$expr);"
            "Double" -> "kt_sb_append_double($sbRef, $expr);"
            "Boolean" -> "kt_sb_append_bool($sbRef, $expr);"
            "Char" -> "kt_sb_append_char($sbRef, $expr);"
            "String" -> "kt_sb_append_str($sbRef, $expr);"
            else -> {
                if (classes.containsKey(type) && classes[type]!!.isData) {
                    "${pfx(type)}_toString($expr, $sbRef);"
                } else {
                    "kt_sb_append_cstr($sbRef, \"<$type>\");"
                }
            }
        }
    }

    // ── arrayOf helpers ──────────────────────────────────────────────

    private fun genArrayOfExpr(name: String, args: List<Arg>): String {
        val elemType = when (name) {
            "intArrayOf" -> "int32_t"; "longArrayOf" -> "int64_t"
            "floatArrayOf" -> "float"; "doubleArrayOf" -> "double"
            "booleanArrayOf" -> "bool"; "charArrayOf" -> "char"
            "arrayOf" -> {
                val elemKt = if (args.isNotEmpty()) inferExprType(args[0].expr) ?: "Int" else "Int"
                cTypeStr(elemKt)
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

    private fun genNewArray(elemCType: String, args: List<Arg>): String {
        val size = if (args.isNotEmpty()) genExpr(args[0].expr) else "0"
        val t = tmp()
        preStmts += "$elemCType* $t = ($elemCType*)ktc_alloca(sizeof($elemCType) * (size_t)($size));"
        preStmts += "memset($t, 0, sizeof($elemCType) * (size_t)($size));"
        preStmts += "const int32_t ${t}\$len = $size;"
        return t
    }

    /** Heap-allocated array via calloc — safe to return from functions. */
    private fun genHeapArray(elemCType: String, args: List<Arg>): String {
        val size = if (args.isNotEmpty()) genExpr(args[0].expr) else "0"
        val t = tmp()
        preStmts += "$elemCType* $t = ($elemCType*)${tCalloc("(size_t)($size)", "sizeof($elemCType)")};"
        preStmts += "const int32_t ${t}\$len = $size;"
        return t
    }


    // ── fill default arguments ───────────────────────────────────────

    private fun fillDefaults(args: List<Arg>, params: List<Param>, defaults: Map<String, Expr?>): List<Arg> {
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

    private fun genLValue(e: Expr, method: Boolean): String {
        return when (e) {
            is NameExpr -> {
                if (method && currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true)
                    "\$self->${e.name}"
                else e.name
            }
            is DotExpr -> {
                if (e.obj is NameExpr && objects.containsKey(e.obj.name))
                    "${pfx(e.obj.name)}.${e.name}"
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

    // ═══════════════════════════ Type inference ═══════════════════════

    private fun inferExprType(e: Expr?): String? = when (e) {
        null        -> null
        is IntLit   -> "Int"
        is LongLit  -> "Long"
        is DoubleLit -> "Double"
        is FloatLit -> "Float"
        is BoolLit  -> "Boolean"
        is CharLit  -> "Char"
        is StrLit, is StrTemplateExpr -> "String"
        is NullLit  -> null
        is ThisExpr -> lookupVar("\$self") ?: currentExtRecvType ?: currentClass
        is NameExpr -> lookupVar(e.name) ?: run {
            // Could be enum type
            if (enums.containsKey(e.name)) e.name else null
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
            else inferExprType(e.left)  // arithmetic inherits left type
        }
        is PrefixExpr -> if (e.op == "!") "Boolean" else inferExprType(e.expr)
        is PostfixExpr -> inferExprType(e.expr)
        is CallExpr -> inferCallType(e)
        is DotExpr  -> inferDotType(e)
        is SafeDotExpr -> inferDotTypeSafe(e)
        is IndexExpr -> inferIndexType(e)
        is IfExpr   -> inferExprType(e.then.stmts.lastOrNull()?.let { (it as? ExprStmt)?.expr ?: (it as? ReturnStmt)?.value })
        is WhenExpr -> e.branches.firstOrNull()?.body?.stmts?.lastOrNull()?.let { inferExprType((it as? ExprStmt)?.expr) }
        is NotNullExpr -> inferExprType(e.expr)?.removeSuffix("?")?.removeSuffix("#")
        is ElvisExpr -> (inferExprType(e.left) ?: inferExprType(e.right))?.removeSuffix("?")?.removeSuffix("#")
        is IsCheckExpr -> "Boolean"
        is CastExpr -> e.type.name
        is FunRefExpr -> {
            // Look up the function signature and build a Fun(...)->R type string
            val sig = funSigs[e.name]
            if (sig != null) {
                val params = sig.params.joinToString(",") { resolveTypeName(it.type) }
                val ret = if (sig.returnType != null) resolveTypeName(sig.returnType) else "Unit"
                "Fun($params)->$ret"
            } else null
        }
    }

    private fun inferCallType(e: CallExpr): String? {
        val name = (e.callee as? NameExpr)?.name
        if (name != null) {
            // Pair constructor (intrinsic — only when no user-defined class named Pair)
            if (name == "Pair" && !classes.containsKey("Pair") && !genericClassDecls.containsKey("Pair")) {
                val a = if (e.typeArgs.size == 2) resolveTypeName(e.typeArgs[0]) else inferExprType(e.args.getOrNull(0)?.expr) ?: "Int"
                val b = if (e.typeArgs.size == 2) resolveTypeName(e.typeArgs[1]) else inferExprType(e.args.getOrNull(1)?.expr) ?: "Int"
                return "Pair_${a}_${b}"
            }
            // Generic class constructor: MyList<Int>(8) → "MyList_Int"
            // Apply typeSubst so type params resolve inside generic function bodies
            if (classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
                val resolvedArgs = e.typeArgs.map { substituteTypeParams(it) }.map { it.name }
                return mangledGenericName(name, resolvedArgs)
            }
            if (classes.containsKey(name)) return name
            if (name == "malloc" || name == "calloc" || name == "realloc") {
                if (e.typeArgs.isNotEmpty()) {
                    val ta = e.typeArgs[0]
                    // malloc<Array<Int>>(n) → Int* (element type pointer)
                    if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                        val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                        return "${elemName}*"
                    }
                    // malloc<MyList<Int>>(...) → MyList_Int* (generic class heap pointer)
                    if (ta.typeArgs.isNotEmpty() && classes.containsKey(ta.name) && classes[ta.name]!!.isGeneric) {
                        return "${mangledGenericName(ta.name, ta.typeArgs.map { it.name })}*"
                    }
                    val resolvedName = typeSubst[ta.name] ?: ta.name
                    return "${resolvedName}*"
                }
                return "Pointer"
            }
            if (name == "intArrayOf" || name == "IntArray") return "IntArray"
            if (name == "longArrayOf" || name == "LongArray") return "LongArray"
            if (name == "floatArrayOf" || name == "FloatArray") return "FloatArray"
            if (name == "doubleArrayOf" || name == "DoubleArray") return "DoubleArray"
            if (name == "booleanArrayOf" || name == "BooleanArray") return "BooleanArray"
            if (name == "charArrayOf" || name == "CharArray") return "CharArray"
            if (name == "arrayOf") {
                // Infer element type from first argument
                val elemType = if (e.args.isNotEmpty()) inferExprType(e.args[0].expr) ?: "Int" else "Int"
                return when (elemType) {
                    "Int" -> "IntArray"; "Long" -> "LongArray"
                    "Float" -> "FloatArray"; "Double" -> "DoubleArray"
                    "Boolean" -> "BooleanArray"; "Char" -> "CharArray"
                    "String" -> "StringArray"
                    else -> { classArrayTypes.add(elemType); "${elemType}Array" }
                }
            }
            // Generic Array<T>(size) constructor
            if (name == "Array" && e.typeArgs.isNotEmpty()) {
                val elemName = resolveTypeName(e.typeArgs[0])
                return "${elemName}Array"
            }
            // Generic function call: newArray<Int>(5) → resolve return type with type substitution
            // Also handles implicit type args inferred from arguments
            val genFun = genericFunDecls.find { it.name == name }
            if (genFun != null && genFun.returnType != null) {
                val typeArgNames = if (e.typeArgs.isNotEmpty()) {
                    e.typeArgs.map { resolveTypeName(it) }
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
                    val result = resolveTypeName(genFun.returnType)
                    typeSubst = saved
                    return result
                }
            }
            funSigs[name]?.returnType?.let { return resolveTypeName(it) }
        }
        if (e.callee is DotExpr) return inferMethodReturnType(e.callee, e.args)
        return null
    }

    /** Resolve a method return type, applying generic bindings if the class is a concrete generic instantiation. */
    private fun resolveMethodReturnType(className: String, returnType: TypeRef?): String {
        if (returnType == null) return "Unit"
        val bindings = genericTypeBindings[className]
        val base = if (bindings != null) {
            val saved = typeSubst
            typeSubst = bindings
            val result = resolveTypeName(returnType)
            typeSubst = saved
            result
        } else {
            resolveTypeName(returnType)
        }
        return if (returnType.nullable && !base.endsWith("?")) "${base}?" else base
    }

    private fun inferMethodReturnType(dot: DotExpr, args: List<Arg>): String? {
        // C package: can't infer return type of C functions
        if (dot.obj is NameExpr && dot.obj.name == "c") return null
        val recvType = inferExprType(dot.obj) ?: return null
        val method = dot.name
        if (method == "toString") return "String"
        if (method == "toInt") return "Int"
        if (method == "toLong") return "Long"
        if (method == "toFloat") return "Float"
        if (method == "toDouble") return "Double"
        if (method == "toIntOrNull") return "Int?"
        if (method == "toLongOrNull") return "Long?"
        if (method == "toFloatOrNull") return "Float?"
        if (method == "toDoubleOrNull") return "Double?"
        if (method == "hashCode") return "Int"
        // String methods
        if (recvType == "String") {
            return when (method) {
                "substring" -> "String"
                "startsWith", "endsWith", "contains", "isEmpty", "isNotEmpty" -> "Boolean"
                "indexOf" -> "Int"
                else -> null
            }
        }
        // Heap pointer methods
        val heapBase = heapClassName(recvType)
        if (heapBase != null) {
            // Class method takes priority over built-in pointer methods
            val classMethod = classes[heapBase]?.methods?.find { it.name == method }
            if (classMethod != null) {
                return resolveMethodReturnType(heapBase, classMethod.returnType)
            }
            return when (method) {
                "value" -> "${heapBase}&"               // .value() → Value<T> (no copy)
                "deref" -> heapBase                     // .deref() → T (stack copy)
                "set" -> "Unit"
                "copy" -> heapBase                      // .copy() → T (stack copy)
                "toHeap" -> "${heapBase}*"              // identity, already heap
                "toPtr" -> "${heapBase}^"               // .toPtr() → Ptr<T>
                else -> null
            }
        }
        // Ptr<T> methods
        val ptrBase = ptrClassName(recvType)
        if (ptrBase != null) {
            val classMethod = classes[ptrBase]?.methods?.find { it.name == method }
            if (classMethod != null) {
                return resolveMethodReturnType(ptrBase, classMethod.returnType)
            }
            return when (method) {
                "value" -> "${ptrBase}&"                // .value() → Value<T> (no copy)
                "deref" -> ptrBase                      // .deref() → T (stack copy)
                "set" -> "Unit"
                "copy" -> ptrBase
                "isHeap" -> "Boolean"
                "asHeap" -> "${ptrBase}*?"              // .asHeap() → Heap<T>? (pointer-nullable)
                "toPtr" -> "${ptrBase}^"                // identity
                "toHeap" -> "${ptrBase}*"
                else -> null
            }
        }
        // Value<T> methods — transparent delegation
        val valBase = valueClassName(recvType)
        if (valBase != null) {
            return when (method) {
                "deref" -> valBase                      // .deref() → T (stack copy)
                "toPtr" -> "${valBase}^"                // .toPtr() → Ptr<T>
                else -> {
                    val m = classes[valBase]?.methods?.find { it.name == method }
                    if (m != null) resolveMethodReturnType(valBase, m.returnType) else null
                }
            }
        }
        // Stack class methods
        if (recvType != null && classes.containsKey(recvType)) {
            if (method == "copy") return recvType        // .copy() → T (stack copy)
            if (method == "toHeap") return "${recvType}*" // .toHeap() → Heap<T>
            if (method == "toPtr") return "${recvType}^"  // .toPtr() → Ptr<T>
        }
        // Interface method
        val iface = interfaces[recvType]
        if (iface != null) {
            val m = iface.methods.find { it.name == method }
            if (m != null) return if (m.returnType != null) resolveTypeName(m.returnType) else "Unit"
        }
        // Class method
        val ci = classes[recvType]
        if (ci != null) {
            val m = ci.methods.find { it.name == method }
            if (m != null) return resolveMethodReturnType(recvType, m.returnType)
        }
        // Extension function on non-class type
        val extFun = extensionFuns[recvType]?.find { it.name == method }
        if (extFun != null) return if (extFun.returnType != null) resolveTypeName(extFun.returnType) else "Unit"
        return null
    }

    private fun inferDotType(e: DotExpr): String? {
        // C package: can't infer type of C constants/macros
        if (e.obj is NameExpr && e.obj.name == "c") return null
        if (e.obj is NameExpr && enums.containsKey(e.obj.name)) return e.obj.name
        if (e.obj is NameExpr && objects.containsKey(e.obj.name)) {
            val prop = objects[e.obj.name]?.props?.find { it.first == e.name }
            return if (prop != null) resolveTypeName(prop.second) else null
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
        if (e.name == "size" && recvType.endsWith("Array")) return "Int"
        if (e.name == "length" && recvType == "String") return "Int"
        // Heap/Ptr/Value pointer field access → look up in base class
        val indirectBase = anyIndirectClassName(recvType)
        if (indirectBase != null) {
            val ci = classes[indirectBase] ?: return null
            val prop = ci.props.find { it.first == e.name }
            return if (prop != null) resolveTypeName(prop.second) else null
        }
        val ci = classes[recvType] ?: return null
        val prop = ci.props.find { it.first == e.name }
        return if (prop != null) resolveTypeName(prop.second) else null
    }

    private fun inferDotTypeSafe(e: SafeDotExpr): String? = null
    private fun inferIndexType(e: IndexExpr): String? {
        val t = inferExprType(e.obj) ?: return null
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
        // Typed pointer: "Int*" → "Int"
        if (t.endsWith("*")) return t.dropLast(1)
        return arrayElementKtType(t)
    }

    // ═══════════════════════════ C type mapping ═══════════════════════

    /** Expand ctor params: array → (T* name, int32_t name$len), nullable → (T name, bool name$has). */
    private fun expandCtorParams(props: List<Pair<String, TypeRef>>): String {
        val parts = mutableListOf<String>()
        for ((name, type) in props) {
            val resolved = resolveTypeName(type)
            if (isFuncType(resolved)) {
                parts += cFuncPtrDecl(resolved, name)
            } else if (isArrayType(resolved)) {
                parts += "${cTypeStr(resolved)} $name"
                parts += "int32_t ${name}\$len"
            } else if (type.nullable) {
                parts += "${cTypeStr(resolved)} $name"
                parts += "bool ${name}\$has"
            } else {
                parts += "${cType(type)} $name"
            }
        }
        return parts.joinToString(", ")
    }

    private fun cType(t: TypeRef): String {
        val resolved = resolveTypeName(t)
        return cTypeStr(resolved)
    }

    /** Expand a parameter list: array params → (T* name, int32_t name$len), nullable params → (T name, bool name$has). */
    private fun expandParams(params: List<Param>): String {
        val parts = mutableListOf<String>()
        for (p in params) {
            val resolved = resolveTypeName(p.type)
            // Check if the original param type was a generic type parameter (e.g., T → Vec2).
            // If so, don't apply class-by-pointer — the interface vtable and callers expect by-value.
            val wasTypeParam = typeSubst.containsKey(p.type.name)
            if (p.isVararg) {
                parts += "${cTypeStr(resolved)}* ${p.name}"
                parts += "int32_t ${p.name}\$len"
            } else if (isFuncType(resolved)) {
                parts += cFuncPtrDecl(resolved, p.name)
            } else if (isArrayType(resolved)) {
                parts += "${cTypeStr(resolved)} ${p.name}"
                parts += "int32_t ${p.name}\$len"
            } else if (p.type.nullable) {
                parts += "${cTypeStr(resolved)} ${p.name}"
                parts += "bool ${p.name}\$has"
            } else if (!wasTypeParam && classes.containsKey(resolved)) {
                // Kotlin classes are reference types — pass by pointer for correct mutation semantics
                // But NOT for generic type params (T→Vec2): interface vtable expects by-value
                parts += "${cTypeStr(resolved)}* ${p.name}"
            } else {
                parts += "${cType(p.type)} ${p.name}"
            }
        }
        return parts.joinToString(", ")
    }

    private fun cTypeStr(t: String): String = when {
        // Function pointer type — can't be expressed as a simple type, use void* as fallback
        // (actual declarations use cFuncPtrDecl which embeds the variable name)
        t.startsWith("Fun(") -> "void*"
        // Strip nullable marker — handled by companion $has variable
        t.endsWith("?") -> cTypeStr(t.dropLast(1))
        // Strip heap-value-nullable marker — also handled by $has
        t.endsWith("#") -> cTypeStr(t.dropLast(1))
        t == "Int"     -> "int32_t"
        t == "Long"    -> "int64_t"
        t == "Float"   -> "float"
        t == "Double"  -> "double"
        t == "Boolean" -> "bool"
        t == "Char"    -> "char"
        t == "String"  -> "kt_String"
        t == "Unit"    -> "void"
        t == "Pointer" -> "void*"
        t == "IntArray"     -> "int32_t*"
        t == "LongArray"    -> "int64_t*"
        t == "FloatArray"   -> "float*"
        t == "DoubleArray"  -> "double*"
        t == "BooleanArray" -> "bool*"
        t == "CharArray"    -> "char*"
        t == "StringArray"  -> "kt_String*"
        // Typed pointer: "Int*" → "int32_t*", "Vec2*" → "game_Vec2*"
        t.endsWith("*") -> "${cTypeStr(t.dropLast(1))}*"
        // Ptr<T>: "Vec2^" → "game_Vec2*" (same C type as Heap)
        t.endsWith("^") -> "${cTypeStr(t.dropLast(1))}*"
        // Value<T>: "Vec2&" → "game_Vec2*" (same C type as Heap)
        t.endsWith("&") -> "${cTypeStr(t.dropLast(1))}*"
        else -> {
            // Class array types: "Vec2Array" → "game_Vec2*" (pointer to element)
            if (t.endsWith("Array") && t.length > 5) {
                val elem = t.removeSuffix("Array")
                if (classArrayTypes.contains(elem)) return "${pfx(elem)}*"
                if (elem.startsWith("Pair_")) return "kt_${elem}*"
            }
            // Pair types: "Pair_Int_String" → "kt_Pair_Int_String"
            if (t.startsWith("Pair_")) return "kt_$t"
            pfx(t)   // class/enum/object type
        }
    }

    private fun ensurePairType(a: String, b: String) {
        val key = "${a}_${b}"
        if (key !in emittedPairTypes) {
            emittedPairTypes.add(key)
            hdr.appendLine("typedef struct { ${cTypeStr(a)} first; ${cTypeStr(b)} second; } kt_Pair_${a}_${b};")
        }
    }

    private fun resolveTypeName(t: TypeRef?): String {
        if (t == null) return "Int"
        return resolveTypeNameInner(substituteTypeParams(t))
    }

    /** Recursively substitute type parameters throughout a TypeRef tree. */
    private fun substituteTypeParams(t: TypeRef): TypeRef {
        if (typeSubst.isEmpty()) return t
        val newName = typeSubst[t.name] ?: t.name
        val newTypeArgs = t.typeArgs.map { substituteTypeParams(it) }
        val newFuncParams = t.funcParams?.map { substituteTypeParams(it) }
        val newFuncReturn = t.funcReturn?.let { substituteTypeParams(it) }
        return if (newName != t.name || newTypeArgs != t.typeArgs || newFuncParams != t.funcParams || newFuncReturn != t.funcReturn) {
            TypeRef(newName, t.nullable, newTypeArgs, newFuncParams, newFuncReturn)
        } else t
    }

    private fun resolveTypeNameInner(t: TypeRef): String {
        // Function type: (P1, P2) -> R → "Fun(P1,P2)->R"
        if (t.funcParams != null) {
            val params = t.funcParams.joinToString(",") { resolveTypeName(it) }
            val ret = resolveTypeName(t.funcReturn)
            return "Fun($params)->$ret"
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
        if (t.name == "Array" && t.typeArgs.isNotEmpty()) {
            val elem = t.typeArgs[0].name
            return when (elem) {
                "Int"     -> "IntArray"
                "Long"    -> "LongArray"
                "Float"   -> "FloatArray"
                "Double"  -> "DoubleArray"
                "Boolean" -> "BooleanArray"
                "Char"    -> "CharArray"
                "String"  -> "StringArray"
                else      -> { classArrayTypes.add(elem); "${elem}Array" }
            }
        }
        if (t.name == "Pointer" && t.typeArgs.isNotEmpty()) {
            val inner = t.typeArgs[0]
            // Pointer<Array<Int>> → "Int*" (element type pointer, same C representation)
            if (inner.name == "Array" && inner.typeArgs.isNotEmpty()) {
                return "${resolveTypeName(inner.typeArgs[0])}*"
            }
            return "${resolveTypeName(inner)}*"
        }
        // Heap<MyClass> → "MyClass*"; Heap<MyClass?> → "MyClass*#"
        // Heap<Array<T>> → same C type as Array<T> (already a pointer)
        if (t.name == "Heap" && t.typeArgs.isNotEmpty()) {
            val inner = t.typeArgs[0]
            if (inner.name == "Array") {
                return resolveTypeName(inner)
            }
            val resolved = resolveTypeName(inner)
            return if (inner.nullable) "${resolved}*#" else "${resolved}*"
        }
        // Ptr<MyClass> → "MyClass^"; Ptr<MyClass?> → "MyClass^#"
        if (t.name == "Ptr" && t.typeArgs.isNotEmpty()) {
            val inner = t.typeArgs[0]
            if (inner.name == "Array") {
                return resolveTypeName(inner)
            }
            val resolved = resolveTypeName(inner)
            return if (inner.nullable) "${resolved}^#" else "${resolved}^"
        }
        // Value<MyClass> → "MyClass&"; Value<MyClass?> → "MyClass&#"
        if (t.name == "Value" && t.typeArgs.isNotEmpty()) {
            val inner = t.typeArgs[0]
            if (inner.name == "Array") {
                return resolveTypeName(inner)
            }
            val resolved = resolveTypeName(inner)
            return if (inner.nullable) "${resolved}&#" else "${resolved}&"
        }
        return t.name
    }

    private fun defaultVal(t: String): String = when {
        t == "Int" || t == "Long" -> "0"
        t == "Float"  -> "0.0f"
        t == "Double" -> "0.0"
        t == "Boolean" -> "false"
        t == "Char"   -> "'\\0'"
        t == "String" -> "kt_str(\"\")"
        t == "Pointer" -> "NULL"
        t.endsWith("*") || t.endsWith("*?") || t.endsWith("*#") -> "NULL"
        t.endsWith("^") || t.endsWith("^?") || t.endsWith("^#") -> "NULL"
        t.endsWith("&") || t.endsWith("&?") || t.endsWith("&#") -> "NULL"
        else -> "{0}"
    }

    /** True if the internal type name represents an array (IntArray, LongArray, Vec2Array, etc.) */
    private fun isArrayType(t: String): Boolean =
        t.endsWith("Array")

    private fun arrayElementCType(arrType: String?): String = when (arrType) {
        "IntArray"     -> "int32_t"
        "LongArray"    -> "int64_t"
        "FloatArray"   -> "float"
        "DoubleArray"  -> "double"
        "BooleanArray" -> "bool"
        "CharArray"    -> "char"
        "StringArray"  -> "kt_String"
        else -> {
            if (arrType != null) {
                // Class array: "Vec2Array" → element type "game_Vec2"
                if (arrType.endsWith("Array") && arrType.length > 5) {
                    val elem = arrType.removeSuffix("Array")
                    if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return pfx(elem)
                    // Pair or other known types: use cTypeStr
                    if (elem.startsWith("Pair_")) return cTypeStr(elem)
                }
            }
            "int32_t"
        }
    }

    private fun arrayElementKtType(arrType: String?): String = when (arrType) {
        "IntArray"     -> "Int"
        "LongArray"    -> "Long"
        "FloatArray"   -> "Float"
        "DoubleArray"  -> "Double"
        "BooleanArray" -> "Boolean"
        "CharArray"    -> "Char"
        "StringArray"  -> "String"
        else -> {
            if (arrType != null) {
                // Class array: "Vec2Array" → element Kotlin type "Vec2"
                if (arrType.endsWith("Array") && arrType.length > 5) {
                    val elem = arrType.removeSuffix("Array")
                    if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return elem
                    // Pair or other known types
                    if (elem.startsWith("Pair_")) return elem
                }
            }
            "Int"
        }
    }

    // ═══════════════════════════ printf helpers ═══════════════════════

    private fun printfFmt(t: String): String = when {
        t == "Int"     -> "%\" PRId32 \""
        t == "Long"    -> "%\" PRId64 \""
        t == "Float"   -> "%f"
        t == "Double"  -> "%f"
        t == "Boolean" -> "%s"
        t == "Char"    -> "%c"
        t == "String"  -> "%.*s"
        t == "Pointer" -> "%p"
        t.endsWith("*") || t.endsWith("*?") || t.endsWith("*#") -> "%p"
        else           -> "%.*s"       // assume toString → kt_String
    }

    private fun printfArg(expr: String, t: String): String = when (t) {
        "Boolean" -> "($expr) ? \"true\" : \"false\""
        "String"  -> "(int)($expr).len, ($expr).ptr"
        else -> expr
    }

    private fun escapeC(c: Char): String = when (c) {
        '\'' -> "\\'"
        '\\' -> "\\\\"
        '\n' -> "\\n"
        '\t' -> "\\t"
        '\r' -> "\\r"
        '\u0000' -> "\\0"
        else -> c.toString()
    }

    private fun escapeStr(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
}

package com.bitsycore

/**
 * Translates a parsed KtFile AST into C11 source code.
 *
 * ## Pipeline
 *
 * ```
 * Kotlin source → Lexer → Parser → AST → CCodeGen → .c/.h files
 * ```
 *
 * ## Architecture (split across 8 files, all in package `com.bitsycore`)
 *
 * | File                 | Lines | Role                                             |
 * |----------------------|-------|--------------------------------------------------|
 * | `CCodeGen.kt`        | 1043  | **Orchestrator**: state, `collectDecls()`, `generate()` |
 * | `CCodeGenStructures.kt` |  60 | Data classes (ClassInfo, BodyProp, etc.)          |
 * | `CCodeGenScan.kt`    |   789 | Pre-scanning: discover generic instantiations     |
 * | `CCodeGenEmit.kt`    |  1497 | Declaration emission: classes, functions, vtables |
 * | `CCodeGenStmts.kt`   |  1351 | Statement codegen: var/if/for/return/inline        |
 * | `CCodeGenExpr.kt`    |  2124 | Expression codegen: genExpr → genCall/genDot/...  |
 * | `CCodeGenInfer.kt`   |   460 | Type inference: inferExprType → inferCallType/... |
 * | `CCodeGenCTypes.kt`  |   574 | C type mapping: resolveTypeName, cTypeStr, printf |
 *
 * ## State conventions
 *
 * All functions across all files are extension functions on `CCodeGen`.
 * State members are `internal` (not `private`) so extension functions
 * in other files can access them.
 *
 * ## Pipeline phases (called by `generate()`)
 *
 * 1. **collectDecls()** — populate symbol tables
 * 2. **scanForClassArrayTypes()** — discover class types in Array<T>
 * 3. **scanForGenericInstantiations()** — find concrete generic usage
 * 4. **materializeGenericInstantiations()** — create concrete ClassInfo
 * 5. **scanForGenericFunCalls()** — discover generic function call sites
 * 6. **scanGenericFunBodiesForInstantiations()** — transitive discovery
 * 7. **scanGenericClassMethodBodiesForInstantiations()** — from materialized
 * 8. **computeGenericFunConcreteReturns()** — interface → concrete return
 * 9. **Emit declarations** — structs, functions, vtables, methods
 * 10. **Output assembly** — .h and .c strings
 *
 * ## Symbol naming
 *
 *   package game          →  game_ClassName, game_funcName
 *   package com.foo.bar   →  com_foo_bar_ClassName
 *   (no package)          →  bare names
 *
 * `fun main()` is never prefixed — always emits `int main(void)`.
 */
class CCodeGen(internal val file: KtFile, internal val allFiles: List<KtFile> = listOf(), internal val sourceLines: List<String> = emptyList(), internal val memTrack: Boolean = false, internal val sourceFileName: String = "") {

    // ── Package prefix ───────────────────────────────────────────────
    internal val prefix: String = file.pkg?.replace('.', '_')?.plus("_") ?: ""

    // Map symbol name → its C prefix (for cross-file references)
    internal val symbolPrefix = mutableMapOf<String, String>()

    internal fun pfx(name: String): String {
        if (name == "main") return name
        // Look up if this symbol belongs to a different package
        val sp = symbolPrefix[name]
        if (sp != null) return "$sp$name"
        return "$prefix$name"
    }

    internal fun inferredTypeRef(typeName: String?): TypeRef? {
        if (typeName == null) return null
        return TypeRef(typeName)
    }

    /** Convert internal type name to Kotlin display name: "Wrapper_String" → "Wrapper<String>" */
    internal fun ktDisplayName(internal: String): String {
        // Pair/Triple/Tuple intrinsics
        pairTypeComponents[internal]?.let { return "Pair<${it.first}, ${it.second}>" }
        tripleTypeComponents[internal]?.let { return "Triple<${it.first}, ${it.second}, ${it.third}>" }
        tupleTypeComponents[internal]?.let { types -> return "Tuple<${types.joinToString(", ")}>" }
        // Generic class instantiation: find base name and split by _
        for (baseName in genericClassDecls.keys) {
            if (internal.startsWith("${baseName}_")) {
                val typeArgs = internal.removePrefix("${baseName}_").split("_")
                return "$baseName<${typeArgs.joinToString(", ")}>"
            }
        }
        return internal
    }

    // ── Symbol tables (populated by collectDecls) ────────────────────
    // Data classes now in CCodeGenStructures.kt

    internal val classes  = mutableMapOf<String, ClassInfo>()
    internal val enums    = mutableMapOf<String, EnumInfo>()
    internal val enumValuesCalled  = mutableSetOf<String>()
    internal val enumValueOfCalled = mutableSetOf<String>()
    internal val objects  = mutableMapOf<String, ObjInfo>()
    internal val funSigs  = mutableMapOf<String, FunSig>()
    internal val inlineFunDecls = mutableMapOf<String, FunDecl>()
    internal val inlineExtFunDecls = mutableMapOf<String, FunDecl>()  // inline generic extension funs, keyed by method name
    internal var activeLambdas: Map<String, ActiveLambda> = emptyMap()
    internal val lambdaParamSubst = mutableMapOf<String, String>()  // also stores "\$this" → receiver C expr during inline ext expansion
    // Deferred hdr declarations: className → list of hdr lines (for methods moved to implements section)
    internal val deferredHdrLines = mutableMapOf<String, MutableList<String>>()
    internal val lambdaParamTypes = mutableMapOf<String, String>()  // lambda param name → Kotlin type, used by inferExprType so .size etc. resolve correctly
    internal var inlineReturnVar: String? = null  // result var name (value pos), "" (stmt pos), null (not inside inline)
    internal var inlineEndLabel: String? = null   // goto label after the inline block to handle early return
    internal var currentInd: String = "    "  // current emit indentation, kept in sync by emitStmt
    internal var inlineCounter: Int = 0  // counter for unique inline temp variable names and end labels
    internal val topProps = mutableSetOf<String>()  // top-level property names (need pfx)
    internal val valTopProps = mutableSetOf<String>()  // top-level val properties (cannot be reassigned)
    internal val extensionFuns = mutableMapOf<String, MutableList<FunDecl>>()
    internal val interfaces = mutableMapOf<String, IfaceInfo>()
    // Type ID registry: each class/interface gets an incrementing integer ID for is/as checks
    internal val typeIds = mutableMapOf<String, Int>()
    internal var nextTypeId = 0
    internal fun getTypeId(name: String): Int = typeIds.getOrPut(name) { nextTypeId++ }
    // Maps class name → synthetic companion object name (e.g. "Foo" → "Foo_Companion")
    internal val classCompanions = mutableMapOf<String, String>()

    // Generic functions: fun <T> name(...) — stored as templates
    internal val genericFunDecls = mutableListOf<FunDecl>()
    // Star-projection extension functions: fun Foo<*>.name() — stored for expansion
    internal val starExtFunDecls = mutableListOf<FunDecl>()
    // Concrete instantiations of generic functions: mangledName → (FunDecl, typeSubst)
    internal val genericFunInstantiations = mutableMapOf<String, MutableSet<List<String>>>()
    // Maps mangled generic function name → concrete class return type when the declared return
    // type is an interface but the body returns a concrete class (enables stack return)
    internal val genericFunConcreteReturn = mutableMapOf<String, String>()
    /** Check if a method on baseType has a nullable receiver declaration. */
    internal fun hasNullableReceiverExt(baseType: String, method: String): Boolean {
        return extensionFuns[baseType]?.any { it.name == method && it.receiver?.nullable == true } == true
    }

    // Map class name → list of interface names it implements
    internal val classInterfaces = mutableMapOf<String, List<String>>()
    // Reverse map: interface name → list of class names that implement it
    internal val interfaceImplementors = mutableMapOf<String, MutableList<String>>()

    // Track class/enum types used in Array<T> so we emit KT_ARRAY_DEF for them
    internal val classArrayTypes = mutableSetOf<String>()

    // Intrinsic Pair<A,B> types: track unique pairs used
    internal val pairTypes = mutableSetOf<Pair<String, String>>()
    internal val emittedPairTypes = mutableSetOf<String>()
    internal val pairTypeComponents = mutableMapOf<String, Pair<String, String>>()

    // Intrinsic Triple<A,B,C> types
    internal val tripleTypeComponents = mutableMapOf<String, Triple<String, String, String>>()
    internal val emittedTripleTypes = mutableSetOf<String>()

    // Intrinsic Tuple<...> types (indefinite arity)
    internal val tupleTypeComponents = mutableMapOf<String, List<String>>()
    internal val emittedTupleTypes = mutableSetOf<String>()


    // ── Generics (monomorphization) ──────────────────────────────────
    // Store original ClassDecl for generic classes so we can re-emit per instantiation
    internal val genericClassDecls = mutableMapOf<String, ClassDecl>()
    // Store original InterfaceDecl for generic interfaces so we can monomorphize them
    internal val genericIfaceDecls = mutableMapOf<String, InterfaceDecl>()
    // Track which source file a generic declaration came from (for mem-track attribution)
    internal val declSourceFile = mutableMapOf<String, String>()
    // Active type parameter substitution map during monomorphized emission (e.g. {T → Int})
    internal var typeSubst: Map<String, String> = emptyMap()
    // Track all discovered concrete instantiations: "MyList" → [["Int"], ["Float"]]
    internal val genericInstantiations = mutableMapOf<String, MutableSet<List<String>>>()
    // All known type parameter names from generic classes and functions (e.g. "T", "U")
    // Used to prevent registering type params as concrete instantiations
    internal val allGenericTypeParamNames = mutableSetOf<String>()

    /** Mangle a generic class name with concrete type args: MyList + [Int] → "MyList_Int" */
    internal fun mangledGenericName(baseName: String, typeArgs: List<String>): String {
        return "${baseName}_${typeArgs.joinToString("_")}"
    }

    /** Record a concrete instantiation of a generic class and return the mangled name. */
    internal fun recordGenericInstantiation(baseName: String, typeArgs: List<String>): String {
        genericInstantiations.getOrPut(baseName) { mutableSetOf() }.add(typeArgs)
        return mangledGenericName(baseName, typeArgs)
    }

    // Maps mangled concrete name → type substitution (e.g. "MyList_Int" → {T: "Int"})
    internal val genericTypeBindings = mutableMapOf<String, Map<String, String>>()

    // ── Per-scope variable → type mapping ────────────────────────────
    internal val scopes = ArrayDeque<MutableMap<String, String>>()
    internal fun pushScope() { scopes.addLast(mutableMapOf()); optValVarNames.addLast(mutableSetOf()); mutableVarScopes.addLast(mutableSetOf()) }
    internal fun popScope()  { scopes.removeLast(); optValVarNames.removeLast(); mutableVarScopes.removeLast() }
    internal fun defineVar(name: String, type: String) { scopes.last()[name] = type }
    internal fun lookupVar(name: String): String? { for (i in scopes.indices.reversed()) { scopes[i][name]?.let { return it } }; return preScanVarTypes?.get(name) }

    // Temporary variable type map used during scanForGenericFunCalls pre-pass
    // (allows inferExprType to resolve variable types before codegen defineVar runs)
    internal var preScanVarTypes: MutableMap<String, String>? = null

    // Track mutable (var) variables — smart casts are only valid on val, val reassignment is an error.
    // Scoped: each pushScope() adds a new set, popScope() removes it.
    internal val mutableVarScopes = ArrayDeque<MutableSet<String>>()
    internal fun markMutable(name: String) { mutableVarScopes.lastOrNull()?.add(name) }
    internal fun isMutable(name: String): Boolean = mutableVarScopes.any { name in it }

    // Track variables stored as Optional structs (value-nullable T? → OptT in C).
    // When a variable is in this set but its current scope type is non-nullable (smart cast),
    // genName returns name.value to unwrap the Optional.
    // Scoped: each pushScope() adds a new set, popScope() removes it, so allocations never leak across scopes.
    internal val optValVarNames = ArrayDeque<MutableSet<String>>()
    internal fun markOptional(name: String) { optValVarNames.lastOrNull()?.add(name) }
    internal fun isOptional(name: String): Boolean = optValVarNames.any { name in it }

    // ── Current class context (when generating methods) ──────────────
    internal var currentClass: String? = null
    internal var currentObject: String? = null
    internal var selfIsPointer = true
    // Objects with dispose methods — called on main() exit
    internal val objectsWithDispose = mutableListOf<String>()  // cName of objects with dispose

    // ── Trampolined array params (pass-by-value copy on stack) ────────
    // Names of array parameters whose data has been copied via alloca+memcpy.
    // genName redirects these to their local$name copy; .size uses the trampoline field.
    internal val trampolinedParams = mutableSetOf<String>()
    internal var currentExtRecvType: String? = null
    // Target type for HeapAlloc/HeapArrayZero/HeapArrayResize inference (context from LHS)
    internal var heapAllocTargetType: TypeRef? = null

    /** Returns the class name if `type` is a pointer to a known class, else null. */
    internal fun pointerClassName(type: String?): String? {
        if (type == null) return null
        val t = type.removeSuffix("?")
        if (!t.endsWith("*")) return null
        val base = t.dropLast(1)
        return if (classes.containsKey(base)) base else null
    }

    /** True if the internal type is a class pointer (any variant). */
    internal fun isPointerType(type: String?): Boolean = pointerClassName(type) != null

    /** Returns the class name for any indirect (pointer) type. */
    internal fun anyIndirectClassName(type: String?): String? = pointerClassName(type)

    /** True if type is a function pointer type: "Fun(P1,P2)->R" */
    internal fun isFuncType(t: String): Boolean = t.startsWith("Fun(")

    /** Parse a function type string "Fun(P1,P2)->R" or "Fun(R|P1,P2)->R" (receiver function) into (paramTypes, returnType) */
    internal fun parseFuncType(t: String): Pair<List<String>, String> {
        // Format: Fun(P1,P2,...)->R or Fun(R|P1,P2)->R
        val inner = t.removePrefix("Fun(")
        val parenEnd = inner.indexOf(")->")
        val paramStr = inner.substring(0, parenEnd)
        val retType = inner.substring(parenEnd + 3)
        val params = if (paramStr.isEmpty()) emptyList() else paramStr.split(",").map { it.removeSuffix("|") }
        return params to retType
    }

    /** Emit a C function pointer declaration: "retType (*name)(paramTypes)" */
    internal fun cFuncPtrDecl(t: String, name: String): String {
        val (params, ret) = parseFuncType(t)
        val cRet = cTypeStr(ret)
        val cParams = if (params.isEmpty()) "void" else params.joinToString(", ") { cTypeStr(it) }
        return "$cRet (*$name)($cParams)"
    }

    // ── Optional type helpers ────────────────────────────────────────

    /* Returns true if type is value-nullable (T?) where T is a struct/primitive — uses Optional struct.
    Pointer types (@Ptr T?) use NULL instead. Arrays use ktc_ArrayTrampoline. */
    internal fun isValueNullableType(internalType: String?): Boolean {
        if (internalType == null) return false
        if (!internalType.endsWith("?")) return false
        val base = internalType.removeSuffix("?")
        // Pointer types use NULL, not Optional
        if (base.endsWith("*")) return false
        // Arrays use ktc_ArrayTrampoline, not Optional
        if (isArrayType(base)) return false
        return true
    }

    /* Maps an internal type string to its C Optional struct type name. */
    internal fun optCTypeName(internalType: String): String {
        return when (val base = internalType.removeSuffix("?")) {
            "Byte"    -> "ktc_Byte_Optional"
            "Short"   -> "ktc_Short_Optional"
            "Int"     -> "ktc_Int_Optional"
            "Long"    -> "ktc_Long_Optional"
            "Float"   -> "ktc_Float_Optional"
            "Double"  -> "ktc_Double_Optional"
            "Boolean" -> "ktc_Bool_Optional"
            "Char"    -> "ktc_Char_Optional"
            "UByte"   -> "ktc_UByte_Optional"
            "UShort"  -> "ktc_UShort_Optional"
            "UInt"    -> "ktc_UInt_Optional"
            "ULong"   -> "ktc_ULong_Optional"
            "String"  -> "ktc_String_Optional"
            else -> {
                val sp = symbolPrefix[base]
                val cName = if (sp != null) "${sp}${base}" else "${prefix}${base}"
                "${cName}_Optional"
            }
        }
    }

    /* Returns a C literal for "no value" for the given Optional C type. */
    internal fun optNone(optCType: String): String = "($optCType){ktc_NONE}"

    /* Returns a C literal for "has value" for the given Optional C type. */
    internal fun optSome(optCType: String, expr: String): String = "($optCType){ktc_SOME, $expr}"

    // ── Nullable return tracking ─────────────────────────────────────
    internal var currentFnReturnsNullable = false
    internal var currentFnReturnsArray = false
    internal var currentFnReturnsSizedArray = false
    internal var currentFnSizedArraySize = 0
    internal var currentFnSizedArrayElemType = ""
    internal var currentFnReturnType: String = ""
    internal var currentFnOptReturnCTypeName: String = ""  // Optional C type for nullable returns
    internal var currentFnIsMain = false
    internal fun currentFnReturnBaseType(): String = currentFnReturnType.removeSuffix("?")

    // ── Source location tracking for error messages ──────────────────
    internal var currentStmtLine: Int = 0
    /** Mutable source file name for mem-track attribution.
     *  Overridden when emitting generic instantiations from other packages (e.g. stdlib). */
    internal var currentSourceFile: String = sourceFileName

    /** Throw an error with source context around the given line. */
    internal fun codegenError(msg: String): Nothing {
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
    internal val deferStack = mutableListOf<Block>()

    /** Emit all deferred blocks in LIFO order (does NOT clear the stack). */
    internal fun emitDeferredBlocks(ind: String, insideMethod: Boolean = false) {
        for (i in deferStack.indices.reversed()) {
            for (s in deferStack[i].stmts) emitStmt(s, ind, insideMethod)
        }
    }

    // ── Temp counter for stack buffers ───────────────────────────────
    internal var tmpCounter = 0
    internal fun tmp(): String = "$${tmpCounter++}"

    // ── Memory tracking helpers (Kotlin source attribution) ──────────
    /** Kotlin source location string for current statement, e.g. `"File.kt", 42` */
    internal fun ktSrc(): String = "\"$currentSourceFile\", $currentStmtLine"
    internal fun tMalloc(sizeExpr: String) = if (memTrack) "ktc_malloc($sizeExpr, ${ktSrc()})" else "malloc($sizeExpr)"
    internal fun tCalloc(nExpr: String, sizeExpr: String) = if (memTrack) "ktc_calloc($nExpr, $sizeExpr, ${ktSrc()})" else "calloc($nExpr, $sizeExpr)"
    internal fun tRealloc(ptrExpr: String, sizeExpr: String) = if (memTrack) "ktc_realloc($ptrExpr, $sizeExpr, ${ktSrc()})" else "realloc($ptrExpr, $sizeExpr)"
    internal fun tFree(ptrExpr: String) = if (memTrack) "ktc_free($ptrExpr, ${ktSrc()})" else "free($ptrExpr)"

    // ── Pre-statements (hoisted before the current statement) ────────
    internal val preStmts = mutableListOf<String>()
    internal fun flushPreStmts(ind: String) {
        for (s in preStmts) impl.appendLine("$ind$s")
        preStmts.clear()
    }

    // ── Output sections ──────────────────────────────────────────────
    internal val hdr   = StringBuilder()   // .h forward decls & typedefs
    internal val impl  = StringBuilder()   // .c implementations
    internal val implFwd = StringBuilder()  // .c private forward decls (prepended at end)

    // ═══════════════════════════ Public entry ═════════════════════════

    fun collectAndScan() {
        collectDecls()
        scanForClassArrayTypes()
        scanForGenericInstantiations()
        materializeGenericInstantiations()
        scanForGenericFunCalls()
        scanGenericFunBodiesForInstantiations()
        materializeGenericInstantiations()
        scanGenericClassMethodBodiesForInstantiations()
        materializeGenericInstantiations()
        computeGenericFunConcreteReturns()
    }

    fun dumpSemantics(): String {
        val sb = StringBuilder()
        sb.appendLine("╔══════════════════════════════════════════════════╗")
        sb.appendLine("║  Semantic Analysis  —  ${file.sourceFile ?: file.pkg ?: "unknown"}".padEnd(49) + "║")
        sb.appendLine("╚══════════════════════════════════════════════════╝")
        sb.appendLine()

        // ── Package & imports ──
        if (file.pkg != null) sb.appendLine("package ${file.pkg}")
        if (file.imports.isNotEmpty()) {
            for (imp in file.imports) sb.appendLine("import $imp")
            sb.appendLine()
        }

        // ── Classes ──
        if (classes.isNotEmpty()) {
            sb.appendLine("── Classes ──")
            for ((name, ci) in classes) {
                val tp = if (ci.typeParams.isNotEmpty()) "<${ci.typeParams.joinToString(", ")}>" else ""
                val ifaces = classInterfaces[name]?.joinToString(", ") ?: ""
                val ifs = if (ifaces.isNotEmpty()) " : $ifaces" else ""
                val data = if (ci.isData) "(data) " else ""
                sb.appendLine("  ${data}class $name$tp$ifs")
                sb.appendLine("    type_id: ${typeIds[name]}")
                for ((pn, pt) in ci.props) {
                    val priv = if (pn in ci.privateProps) "private " else ""
                    val valMark = if (ci.isValProp(pn)) "val" else "var"
                    sb.appendLine("    $priv$valMark $pn: ${typeRefToStr(pt)}")
                }
                if (ci.ctorPlainParams.isNotEmpty()) {
                    for ((pn, pt) in ci.ctorPlainParams) {
                        sb.appendLine("    param $pn: ${typeRefToStr(pt)}")
                    }
                }
                for (m in ci.methods) {
                    val priv = if (m.isPrivate) "private " else ""
                    val op = if (m.isOperator) "operator " else ""
                    val tp2 = if (m.typeParams.isNotEmpty()) "<${m.typeParams.joinToString(", ")}>" else ""
                    val ret = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                    val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                    sb.appendLine("    ${priv}${op}fun $tp2${m.name}($params)$ret")
                }
            }
            sb.appendLine()
        }

        // ── Interfaces ──
        if (interfaces.isNotEmpty()) {
            sb.appendLine("── Interfaces ──")
            for ((name, iface) in interfaces) {
                val tp = if (iface.typeParams.isNotEmpty()) "<${iface.typeParams.joinToString(", ")}>" else ""
                val sup = if (iface.superInterfaces.isNotEmpty())
                    " : ${iface.superInterfaces.joinToString(", ") { typeRefToStr(it) }}" else ""
                sb.appendLine("  interface $name$tp$sup")
                for (p in iface.properties) {
                    val mut = if (p.mutable) "var" else "val"
                    val pt = if (p.type != null) ": ${typeRefToStr(p.type)}" else ""
                    sb.appendLine("    $mut ${p.name}$pt")
                }
                for (m in iface.methods) {
                    val op = if (m.isOperator) "operator " else ""
                    val tp2 = if (m.typeParams.isNotEmpty()) "<${m.typeParams.joinToString(", ")}>" else ""
                    val ret = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                    val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                    sb.appendLine("    ${op}fun $tp2${m.name}($params)$ret")
                }
            }
            sb.appendLine()
        }

        // ── Enums ──
        if (enums.isNotEmpty()) {
            sb.appendLine("── Enums ──")
            for ((name, ei) in enums) {
                sb.appendLine("  enum $name { ${ei.entries.joinToString(", ")} }")
            }
            sb.appendLine()
        }

        // ── Objects ──
        if (objects.isNotEmpty()) {
            sb.appendLine("── Objects ──")
            for ((name, oi) in objects) {
                sb.appendLine("  object $name")
                for ((pn, pt) in oi.props) {
                    sb.appendLine("    val $pn: ${pt}")
                }
                for (m in oi.methods) {
                    val ret = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                    val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                    sb.appendLine("    fun ${m.name}($params)$ret")
                }
            }
            sb.appendLine()
        }

        // ── Functions ──
        if (funSigs.isNotEmpty()) {
            sb.appendLine("── Function Signatures ──")
            for ((name, sig) in funSigs) {
                val ret = sig.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                val params = sig.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                val src = declSourceFile[name]?.let { "  // $it" } ?: ""
                sb.appendLine("  fun $name($params)$ret$src")
            }
            sb.appendLine()
        }

        // ── Extension functions ──
        if (extensionFuns.isNotEmpty()) {
            sb.appendLine("── Extension Functions ──")
            for ((typeName, funs) in extensionFuns) {
                for (f in funs) {
                    val ret = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                    val params = f.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
                    sb.appendLine("  fun $typeName.${f.name}($params)$ret")
                }
            }
            sb.appendLine()
        }

        // ── Generic class templates ──
        if (genericClassDecls.isNotEmpty()) {
            sb.appendLine("── Generic Class Templates (un-instantiated) ──")
            for ((name, decl) in genericClassDecls) {
                val tps = decl.typeParams.joinToString(", ")
                val src = declSourceFile[name]?.let { "  // $it" } ?: ""
                sb.appendLine("  $name<$tps>$src")
            }
            sb.appendLine()
        }

        // ── Generic instantiations ──
        if (genericInstantiations.isNotEmpty()) {
            sb.appendLine("── Generic Class Instantiations ──")
            for ((base, insts) in genericInstantiations) {
                for (typeArgs in insts) {
                    val bindings = genericTypeBindings[mangledGenericName(base, typeArgs)]
                    sb.appendLine("  $base<${typeArgs.joinToString(", ")}>")
                    if (bindings != null) {
                        sb.appendLine("    subst: ${bindings.entries.joinToString(", ") { "${it.key}→${it.value}" }}")
                    }
                }
            }
            sb.appendLine()
        }

        // ── Generic functions ──
        if (genericFunDecls.isNotEmpty()) {
            sb.appendLine("── Generic Function Templates ──")
            for (f in genericFunDecls) {
                val tps = "<${f.typeParams.joinToString(", ")}>"
                val params = f.params.joinToString(", ") { p ->
                    val va = if (p.isVararg) "vararg " else ""
                    val def = if (p.default != null) " = ..." else ""
                    "$va${p.name}: ${typeRefToStr(p.type)}$def"
                }
                val ret = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
                val recv = if (f.receiver != null) "${typeRefToStr(f.receiver)}." else ""
                val src = declSourceFile[f.name]?.let { "  // $it" } ?: ""
                sb.appendLine("  fun $tps $recv${f.name}($params)$ret$src")
            }
            sb.appendLine()
        }

        if (genericFunInstantiations.isNotEmpty()) {
            sb.appendLine("── Generic Function Instantiations ──")
            for ((mangled, typeArgsList) in genericFunInstantiations) {
                for (typeArgs in typeArgsList) {
                    sb.appendLine("  $mangled(${typeArgs.joinToString(", ")})")
                }
            }
            sb.appendLine()
        }

        // ── Generic function concrete returns ──
        if (genericFunConcreteReturn.isNotEmpty()) {
            sb.appendLine("── Generic Function Concrete Returns ──")
            for ((mangled, ret) in genericFunConcreteReturn) {
                sb.appendLine("  $mangled → $ret")
            }
            sb.appendLine()
        }

        // ── Interface implementors ──
        if (interfaceImplementors.isNotEmpty()) {
            sb.appendLine("── Interface Implementors ──")
            for ((iface, impls) in interfaceImplementors) {
                sb.appendLine("  $iface ← ${impls.joinToString(", ")}")
            }
            sb.appendLine()
        }

        // ── Companion objects ──
        if (classCompanions.isNotEmpty()) {
            sb.appendLine("── Companion Objects ──")
            for ((cls, companion) in classCompanions) {
                sb.appendLine("  $cls → $companion")
            }
            sb.appendLine()
        }

        // ── Pair / Triple / Tuple types ──
        if (pairTypeComponents.isNotEmpty()) {
            sb.appendLine("── Pair Types ──")
            for ((key, pair) in pairTypeComponents) {
                sb.appendLine("  $key = (${pair.first}, ${pair.second})")
            }
            sb.appendLine()
        }
        if (tripleTypeComponents.isNotEmpty()) {
            sb.appendLine("── Triple Types ──")
            for ((key, triple) in tripleTypeComponents) {
                sb.appendLine("  $key = (${triple.first}, ${triple.second}, ${triple.third})")
            }
            sb.appendLine()
        }

        // ── Class array types ──
        if (classArrayTypes.isNotEmpty()) {
            sb.appendLine("── Class Array Types ──")
            sb.appendLine("  ${classArrayTypes.joinToString(", ")}")
            sb.appendLine()
        }

        return sb.toString()
    }

    fun generate(): COutput {
        collectDecls()

        hdr.appendLine("#pragma once")
        if (memTrack) hdr.appendLine("#define KTC_MEM_TRACK")
        hdr.appendLine("#include \"ktc_intrinsic.h\"")
        hdr.appendLine()

        // Imports → #include (skip ktc stdlib imports — handled below)
        for (imp in file.imports) {
            if (imp.startsWith("ktc_std")) continue
            val parts = imp.removeSuffix(".*").split('.')
            val headerName = parts.joinToString("_")
            hdr.appendLine("#include \"$headerName.h\"")
        }
        // Auto-include ktc_std.h for non-stdlib packages when stdlib is present
        val hasStdlib = allFiles.any { it.pkg == "ktc.std" }
        if (hasStdlib && file.pkg != "ktc_std") {
            hdr.appendLine("#include \"ktc_std.h\"")
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

        // Scan materialized generic class method bodies for further generic instantiations
        // (e.g., HashMap<Int,String>.iterator() creates MapIterator<Int,String>)
        scanGenericClassMethodBodiesForInstantiations()
        materializeGenericInstantiations()

        // Pre-compute concrete return types for generic functions returning interfaces
        // (enables returning concrete class by value on stack instead of heap-allocating)
        computeGenericFunConcreteReturns()

        // Emit interface vtable struct BEFORE classes (TYPE_ID + vtable function pointers)
        // The tagged-union struct is emitted later after all class vtables are processed.
        // Non-generic interfaces first (they only use primitive types in signatures)
        val emittedIfaceNames = mutableSetOf<String>()
        for (d in file.decls) if (d is InterfaceDecl && d.typeParams.isEmpty()) {
            emitInterface(d)
            emittedIfaceNames += d.name
        }

        // Forward-declare all interface structs so class _as_ declarations
        // can reference them before the tagged union is fully defined.
        // Skip generic templates (with type params) — only concrete/monomorphized types need forwarding.
        hdr.appendLine("// forward declarations")
        var emittedAny = false
        for ((name, info) in interfaces) {
            if (info.typeParams.isNotEmpty()) continue
            val cName = pfx(name)
            hdr.appendLine("typedef struct $cName $cName;")
            emittedAny = true
        }
        if (emittedAny) hdr.appendLine()

        // Emit struct/enum/object declarations (defines the element types needed by generic interfaces)
        // Skip generic templates — they are emitted per concrete instantiation
        var firstClass = true
        for (d in file.decls) when (d) {
            is ClassDecl  -> if (d.typeParams.isEmpty()) {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                emitClass(d)
                // Emit interface vtable header declarations right after the class struct
                if (d.superInterfaces.isNotEmpty()) {
                    emitInterfaceVtablesForClass(d.name, d.superInterfaces, declsOnly = true)
                }
                // Emit companion objects declared inside this class
                for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                    hdr.appendLine()
                    emitObject(ObjectDecl("${d.name}_${vMember.name}", vMember.members))
                }
            }
            is EnumDecl   -> emitEnum(d)
            is ObjectDecl -> {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                emitObject(d)
            }
            else -> {}
        }

        // Emit forward declarations for all monomorphized generic class types
        // so method signatures can reference them before their full definitions
        for ((baseName, instantiations) in genericInstantiations) {
            if (!genericClassDecls.containsKey(baseName)) continue
            for (typeArgs in instantiations) {
                val mangledName = mangledGenericName(baseName, typeArgs)
                val cName = pfx(mangledName)
                hdr.appendLine("typedef struct $cName $cName;")
            }
        }
        if (genericInstantiations.isNotEmpty()) hdr.appendLine()

        // Emit monomorphized generic class instantiations BEFORE generic interfaces,
        // because interface vtable structs may reference generic class types as return types
        // (e.g., MapIterator<Int,String> returned by Map<Int,String>.iterator())

        // Forward-declare all monomorphized generic interface vtable structs so that
        // class _as_ declarations can reference them before the full vtable definition.
        var emittedMonoFwd = false
        for ((name, info) in interfaces) {
            val isMonomorphized = genericIfaceDecls.keys.any { tmpl -> name.startsWith(tmpl + "_") }
            if (isMonomorphized) {
                val cName = pfx(name)
                hdr.appendLine("typedef struct ${cName}_vt ${cName}_vt;")
                emittedMonoFwd = true
            }
        }
        if (emittedMonoFwd) hdr.appendLine()

        for ((baseName, instantiations) in genericInstantiations) {
            val templateDecl = genericClassDecls[baseName] ?: continue
            for (typeArgs in instantiations) {
                if (!firstClass) hdr.appendLine()
                firstClass = false
                val mangledName = mangledGenericName(baseName, typeArgs)
                val templateCi = classes[baseName] ?: continue
                // Set type substitution for this instantiation
                typeSubst = templateCi.typeParams.zip(typeArgs).toMap()
                // Switch source file attribution for mem-track
                val prevSourceFile = currentSourceFile
                declSourceFile[baseName]?.let { currentSourceFile = it }
                emitGenericClass(templateDecl, mangledName)
                // Emit interface vtable header declarations right after the class struct
                if (templateDecl.superInterfaces.isNotEmpty()) {
                    val resolvedIfaces = templateDecl.superInterfaces.map { substituteTypeRef(it, typeSubst) }
                    emitInterfaceVtablesForClass(mangledName, resolvedIfaces, declsOnly = true)
                }
                currentSourceFile = prevSourceFile
                typeSubst = emptyMap()
            }
        }

        // Emit monomorphized generic interfaces AFTER generic class structs so types are available
        // Only emit the VTABLE here; the tagged-union struct is emitted later after all class vtables.
        val emittedMonoIfaceVtables = mutableSetOf<String>()
        for ((name, info) in interfaces) {
            // Emit only monomorphized copies (not already emitted above as non-generic AST decl)
            if (name !in emittedIfaceNames && info.typeParams.isEmpty()
                && genericIfaceDecls.values.none { it.name == name }) {
                // Skip non-generic interfaces from other packages (they're in that package's header).
                // Monomorphized generics (e.g. MutableList_Int from MutableList<T>) are always
                // emitted here because they may reference user-defined types.
                val isMonomorphized = genericIfaceDecls.keys.any { tmpl -> name.startsWith(tmpl + "_") }
                if (isMonomorphized) {
                    emitInterfaceVtable(info)
                    emittedMonoIfaceVtables += name
                } else {
                    val isCrossPackage = symbolPrefix[name]?.let { it.isNotEmpty() && it != prefix } == true
                    if (!isCrossPackage) {
                        emitInterfaceVtable(info)
                        emittedMonoIfaceVtables += name
                    }
                }
            }
        }

        // Build initial reverse map: interface → list of implementing classes
        // (from classInterfaces populated during collectDecl and materializeGenericClass).
        // Updated incrementally by emitTransitiveInterfaceVtables for transitive parent interfaces.
        for ((className, ifaces) in classInterfaces) {
            for (iface in ifaces) {
                interfaceImplementors.getOrPut(iface) { mutableListOf() }.add(className)
            }
        }

        // Emit static vtable instances + wrapping functions for interface implementations
        // Non-generic classes:
        for (d in file.decls) if (d is ClassDecl && d.typeParams.isEmpty() && d.superInterfaces.isNotEmpty()) {
            emitInterfaceVtablesForClass(d.name, d.superInterfaces, implsOnly = true)
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
                emitInterfaceVtablesForClass(mangledName, resolvedIfaces, implsOnly = true)
                typeSubst = emptyMap()
            }
        }

        // Emit tagged-union struct for ALL interfaces (non-generic + monomorphized).
        // Must be AFTER class struct definitions so union members are complete types
        // and AFTER all vtables so transitive implementors are known.
        val hasTaggedUnions = interfaces.keys.any { it in emittedIfaceNames || it in emittedMonoIfaceVtables }
        if (hasTaggedUnions) hdr.appendLine()
        for ((name, info) in interfaces) {
            if (name in emittedIfaceNames) {
                emitIfaceInfo(info)
            } else if (name in emittedMonoIfaceVtables) {
                emitIfaceInfo(info)
            }
        }

        // Emit top-level functions and properties
        for (d in file.decls) when (d) {
            is FunDecl  -> {
                // Skip generic function templates and star-projection extensions — handled below
                if (d.typeParams.isNotEmpty()) continue
                if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) continue
                // Skip extensions on generic types (expanded per class by emitStarExtFunInstantiations)
                if (d.receiver != null && d.receiver.typeArgs.isNotEmpty()
                    && (genericIfaceDecls.containsKey(d.receiver.name) || genericClassDecls.containsKey(d.receiver.name))) continue
                if (d.receiver != null) emitExtensionFun(d) else emitFun(d)
            }
            is PropDecl -> emitTopProp(d)
            else -> {}
        }

        // Emit monomorphized generic functions
        for (f in genericFunDecls) emitGenericFunInstantiations(f)

        // Emit star-projection extension functions (one per known instantiation)
        for (f in starExtFunDecls) emitStarExtFunInstantiations(f)

        // Emit enum values arrays and valueOf functions (only for enums referenced via enumValues/enumValueOf)
        emitEnumValuesData()

        val srcName = prefix.trimEnd('_').ifEmpty {
            sourceFileName.removeSuffix(".kt").ifEmpty { "main" }
        }
        val src = StringBuilder()
        src.appendLine("#include \"$srcName.h\"")
        src.appendLine()
        if (implFwd.isNotEmpty()) {
            src.append(implFwd)
            src.appendLine()
        }
        src.append(impl)

        return COutput(hdr.toString(), src.toString())
    }

    // ═══════════════════════════ Collect declarations (pre-pass) ═════

    internal fun collectDecls() {
        objectsWithDispose.clear()
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
                    is ClassDecl -> {
                        symbolPrefix[d.name] = fpfx
                        // Register companion object prefix
                        for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                            symbolPrefix["${d.name}_${vMember.name}"] = fpfx
                        }
                    }
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
            collectDecl(d, validate = true)
            when (d) {
                is ClassDecl -> {
                    symbolPrefix[d.name] = prefix
                    // Register companion object prefix
                    for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                        symbolPrefix["${d.name}_${vMember.name}"] = prefix
                    }
                }
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

    internal fun collectDecl(d: Decl, validate: Boolean = false) {
        when (d) {
            is ClassDecl -> {
                for (p in d.ctorParams) {
                    if ((p.isVal || p.isVar) && isRawArrayTypeRef(p.type)) {
                        codegenError("Class property '${p.name}' cannot have raw array type '${p.type.name}'. Use @Ptr Array<T> instead")
                    }
                }
                for (p in d.members.filterIsInstance<PropDecl>()) {
                    val propType = p.type ?: inferInitType(p.init)
                    if (isRawArrayTypeRef(propType)) {
                        currentStmtLine = p.line
                        codegenError("Class property '${p.name}' cannot have raw array type '${propType.name}'. Use @Ptr Array<T> or Ptr<Array<T>> instead")
                    }
                }
                val ctorProps = d.ctorParams.filter { it.isVal || it.isVar }.map { it.name to it.type }
                val valCtorPropNames = d.ctorParams.filter { it.isVal }.map { it.name }.toSet()
                val ctorPlainParams = d.ctorParams.filter { !it.isVal && !it.isVar }.map { it.name to it.type }
                val bodyProps = d.members.filterIsInstance<PropDecl>().map { p ->
                    BodyProp(p.name, p.type ?: inferInitType(p.init), p.init, p.line, p.isPrivate, p.mutable, p.isPrivateSet)
                }
                val privateProps = (bodyProps.filter { it.isPrivate }.map { it.name } + d.ctorParams.filter { it.isPrivate && (it.isVal || it.isVar) }.map { it.name }).toSet()
                val privateSetPropNames = bodyProps.filter { it.isPrivateSet }.map { it.name }.toSet()
                val ci = ClassInfo(d.name, d.isData, ctorProps, ctorPlainParams, bodyProps, initBlocks = d.initBlocks, typeParams = d.typeParams, privateProps = privateProps, valCtorProps = valCtorPropNames, privateSetProps = privateSetPropNames)
                if (d.typeParams.isNotEmpty()) allGenericTypeParamNames += d.typeParams
                for (m in d.members) if (m is FunDecl && m.receiver == null) {
                    if (m.returnType != null && isRawArrayTypeRef(m.returnType)) {
                        codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use @Ptr Array<T> or Ptr<Array<T>> instead")
                    }
                    ci.methods += m
                }
                classes[d.name] = ci
                getTypeId(d.name)
                if (d.typeParams.isNotEmpty()) genericClassDecls[d.name] = d
                if (d.superInterfaces.isNotEmpty()) classInterfaces[d.name] = d.superInterfaces.map { it.name }
                // Verify all interface methods are implemented with override (current file, non-stdlib)
                if (validate && file.pkg != "ktc.std") {
                    val classMethodNames = ci.methods.associateBy { it.name }
                    for (ifaceRef in d.superInterfaces) {
                        val ifaceName = resolveIfaceName(ifaceRef)
                        val iface = interfaces[ifaceName] ?: continue
                        for (m in collectAllIfaceMethods(iface)) {
                            val impl = classMethodNames[m.name]
                            when {
                                impl == null ->
                                    codegenError("Class '${d.name}' must implement '${m.name}' from interface '$ifaceName'")
                                !impl.isOverride ->
                                    codegenError("Method '${m.name}' in class '${d.name}' must be marked 'override'")
                            }
                        }
                    }
                    // dispose() and hashCode() are implicitly overrides — always require the keyword
                    for (m in ci.methods) {
                        if ((m.name == "dispose" || m.name == "hashCode") && !m.isOverride) {
                            codegenError("Method '${m.name}' in class '${d.name}' must be marked 'override'")
                        }
                    }
                    // Check for bogus override on methods that don't match any interface
                    // (dispose is implicitly an override of the no-op dispose every class gets)
                    val allIfaceMethodNames = d.superInterfaces.flatMap { ifaceRef ->
                        val ifaceName = resolveIfaceName(ifaceRef)
                        interfaces[ifaceName]?.let { collectAllIfaceMethods(it).map { m -> m.name } } ?: emptyList()
                    }.toSet() + "dispose" + "hashCode"
                    for (m in ci.methods) {
                        if (m.isOverride && m.name !in allIfaceMethodNames) {
                            codegenError("Method '${m.name}' is marked 'override' but does not override any interface method")
                        }
                    }
                }
                // Collect companion objects declared inside this class
                for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
                    val vCompanionSynthName = "${d.name}_${vMember.name}" // e.g. "Foo_Companion"
                    classCompanions[d.name] = vCompanionSynthName
                    collectDecl(ObjectDecl(vCompanionSynthName, vMember.members))
                }
            }
            is EnumDecl  -> enums[d.name] = EnumInfo(d.name, d.entries)
            is InterfaceDecl -> {
                interfaces[d.name] = IfaceInfo(d.name, d.methods, d.properties, d.typeParams, d.superInterfaces)
                getTypeId(d.name)
                if (d.typeParams.isNotEmpty()) {
                    genericIfaceDecls[d.name] = d
                    allGenericTypeParamNames += d.typeParams
                }
            }
            is ObjectDecl -> {
                for (p in d.members.filterIsInstance<PropDecl>()) {
                    val propType = p.type ?: inferInitType(p.init)
                    if (isRawArrayTypeRef(propType)) {
                        currentStmtLine = p.line
                        codegenError("Object property '${p.name}' cannot have raw array type '${propType.name}'. Use @Ptr Array<T> or Ptr<Array<T>> instead")
                    }
                }
                val props = d.members.filterIsInstance<PropDecl>().map { it.name to (it.type ?: TypeRef("Int")) }
                val oi = ObjInfo(d.name, props)
                for (m in d.members) if (m is FunDecl) {
                    if (m.returnType != null && isRawArrayTypeRef(m.returnType)) {
                        codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use @Ptr Array<T> or Ptr<Array<T>> instead")
                    }
                    oi.methods += m
                }
                objects[d.name] = oi
                // dispose()/hashCode() are implicitly overrides — always require the keyword (current file, non-stdlib)
                if (validate && file.pkg != "ktc.std") {
                    for (m in d.members) if (m is FunDecl && (m.name == "dispose" || m.name == "hashCode") && !m.isOverride) {
                        codegenError("Method '${m.name}' in object '${d.name}' must be marked 'override'")
                    }
                }
                // Track objects with dispose for auto-call on main exit (current file only)
                if (validate) {
                    for (m in d.members) if (m is FunDecl && m.name == "dispose") {
                        val cName = pfx(d.name)
                        if (cName !in objectsWithDispose) objectsWithDispose.add(cName)
                    }
                }
            }
            is FunDecl -> {
                if (d.returnType != null && isRawArrayTypeRef(d.returnType)) {
                    codegenError("Function '${d.name}' cannot return raw array type '${d.returnType.name}'. Use @Ptr Array<T> or Ptr<Array<T>> instead")
                }
                val effectiveReturnType = d.returnType ?: d.body?.let { inferredTypeRef(inferBlockType(it)) }
                if (d.typeParams.isNotEmpty()) {
                    // Generic function template — store for monomorphization
                    // (dedup: overwrite funSig, but only add to list if not already present)
                    if (genericFunDecls.none { it === d }) genericFunDecls += d
                    funSigs[d.name] = FunSig(d.params, effectiveReturnType)
                    allGenericTypeParamNames += d.typeParams
                    if (d.isInline && d.receiver != null) inlineExtFunDecls[d.name] = d
                } else if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) {
                    // Star-projection extension function — store for expansion
                    if (starExtFunDecls.none { it === d }) starExtFunDecls += d
                } else if (d.receiver != null && d.receiver.typeArgs.isNotEmpty()
                    && (genericIfaceDecls.containsKey(d.receiver.name) || genericClassDecls.containsKey(d.receiver.name))) {
                    // Extension on generic type e.g. fun Map<K,V>.tryDispose() — expand per concrete type
                    if (starExtFunDecls.none { it === d }) starExtFunDecls += d
                } else if (d.receiver != null) {
                    val recvName = d.receiver.name
                    extensionFuns.getOrPut(recvName) { mutableListOf() }.add(d)
                    // Register as method on the class for inference
                    classes[recvName]?.methods?.add(d)
                    funSigs["${recvName}.${d.name}"] = FunSig(d.params, effectiveReturnType)
                } else {
                    funSigs[d.name] = FunSig(d.params, effectiveReturnType)
                    if (d.isInline) inlineFunDecls[d.name] = d
                }
            }
            is PropDecl  -> { topProps.add(d.name); if (!d.mutable) valTopProps.add(d.name) }
        }
    }
}

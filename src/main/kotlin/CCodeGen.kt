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
class CCodeGen(private val file: KtFile, private val allFiles: List<KtFile> = listOf()) {

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
    private data class BodyProp(val name: String, val type: TypeRef, val init: Expr?)

    private data class ClassInfo(
        val name: String, val isData: Boolean,
        val ctorProps: List<Pair<String, TypeRef>>,
        val bodyProps: List<BodyProp> = emptyList(),
        val methods: MutableList<FunDecl> = mutableListOf(),
        val initBlocks: List<Block> = emptyList()
    ) {
        val props: List<Pair<String, TypeRef>>
            get() = ctorProps + bodyProps.map { it.name to it.type }
    }

    private data class EnumInfo(val name: String, val entries: List<String>)
    private data class ObjInfo(val name: String, val props: List<Pair<String, TypeRef>>, val methods: MutableList<FunDecl> = mutableListOf())
    private data class FunSig(val params: List<Param>, val returnType: TypeRef?)

    private data class IfaceInfo(val name: String, val methods: List<FunDecl>)

    private val classes  = mutableMapOf<String, ClassInfo>()
    private val enums    = mutableMapOf<String, EnumInfo>()
    private val objects  = mutableMapOf<String, ObjInfo>()
    private val funSigs  = mutableMapOf<String, FunSig>()
    private val extensionFuns = mutableMapOf<String, MutableList<FunDecl>>()
    private val interfaces = mutableMapOf<String, IfaceInfo>()

    // Map class name → list of interface names it implements
    private val classInterfaces = mutableMapOf<String, List<String>>()

    // Track class/enum types used in Array<T> so we emit KT_ARRAY_DEF for them
    private val classArrayTypes = mutableSetOf<String>()

    // Track ArrayList types used so we emit struct + methods for them
    // Each entry is the element Kotlin type, e.g. "Int", "String", "Vec2"
    private val arrayListElemTypes = mutableSetOf<String>()

    // Track HashMap types used: each entry is "KeyType:ValueType", e.g. "Int:Int", "String:Int"
    private val hashMapTypes = mutableSetOf<String>()

    // ── Per-scope variable → type mapping ────────────────────────────
    private val scopes = ArrayDeque<MutableMap<String, String>>()
    private fun pushScope() { scopes.addLast(mutableMapOf()) }
    private fun popScope()  { scopes.removeLast() }
    private fun defineVar(name: String, type: String) { scopes.last()[name] = type }
    private fun lookupVar(name: String): String? { for (i in scopes.indices.reversed()) { scopes[i][name]?.let { return it } }; return null }

    // ── Current class context (when generating methods) ──────────────
    private var currentClass: String? = null
    private var selfIsPointer = true

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
    private var currentFnReturnType: String = ""
    private fun currentFnReturnBaseType(): String = currentFnReturnType.removeSuffix("?")

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
        hdr.appendLine("#include \"ktc_runtime.h\"")
        hdr.appendLine()

        // Imports → #include (skip ktc stdlib imports — they're JVM-only)
        for (imp in file.imports) {
            if (imp.startsWith("ktc")) continue  // ktc stdlib is built into the runtime
            // import foo.bar.* → #include "foo_bar.h"
            // import foo.bar.Baz → #include "foo_bar.h"
            val parts = imp.removeSuffix(".*").split('.')
            val headerName = parts.joinToString("_")
            hdr.appendLine("#include \"$headerName.h\"")
        }
        if (file.imports.any { !it.startsWith("ktc") }) hdr.appendLine()

        // Pre-scan for Array<T> type references to discover class array types early
        scanForClassArrayTypes()

        // Emit interface vtable struct + fat pointer type BEFORE classes
        for (d in file.decls) if (d is InterfaceDecl) emitInterface(d)

        // Emit struct/enum/object declarations first (defines the element types)
        for (d in file.decls) when (d) {
            is ClassDecl  -> emitClass(d)
            is EnumDecl   -> emitEnum(d)
            is ObjectDecl -> emitObject(d)
            else -> {}
        }

        // Emit static vtable instances + wrapping functions for interface implementations
        for (d in file.decls) if (d is ClassDecl && d.superInterfaces.isNotEmpty()) {
            emitClassInterfaceVtables(d)
        }

        // Emit ArrayList struct + methods for each element type used
        for (elem in arrayListElemTypes) emitArrayList(elem)

        // Emit HashMap struct + methods for each key:value type pair used
        for (kv in hashMapTypes) emitHashMap(kv)

        // Emit top-level functions and properties
        for (d in file.decls) when (d) {
            is FunDecl  -> if (d.receiver != null) emitExtensionFun(d) else emitFun(d)
            is PropDecl -> emitTopProp(d)
            else -> {}
        }

        val srcName = prefix.trimEnd('_').ifEmpty { "main" }
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
                val bodyProps = d.members.filterIsInstance<PropDecl>().map { p ->
                    BodyProp(p.name, p.type ?: TypeRef(inferExprType(p.init) ?: "Int"), p.init)
                }
                val ci = ClassInfo(d.name, d.isData, ctorProps, bodyProps, initBlocks = d.initBlocks)
                for (m in d.members) if (m is FunDecl && m.receiver == null) ci.methods += m
                classes[d.name] = ci
                if (d.superInterfaces.isNotEmpty()) classInterfaces[d.name] = d.superInterfaces
            }
            is EnumDecl  -> enums[d.name] = EnumInfo(d.name, d.entries)
            is InterfaceDecl -> interfaces[d.name] = IfaceInfo(d.name, d.methods)
            is ObjectDecl -> {
                val props = d.members.filterIsInstance<PropDecl>().map { it.name to (it.type ?: TypeRef("Int")) }
                val oi = ObjInfo(d.name, props)
                for (m in d.members) if (m is FunDecl) oi.methods += m
                objects[d.name] = oi
            }
            is FunDecl -> {
                if (d.receiver != null) {
                    val recvName = d.receiver.name
                    extensionFuns.getOrPut(recvName) { mutableListOf() }.add(d)
                    // Register as method on the class for inference
                    classes[recvName]?.methods?.add(d)
                    funSigs["${recvName}.${d.name}"] = FunSig(d.params, d.returnType)
                } else {
                    funSigs[d.name] = FunSig(d.params, d.returnType)
                }
            }
            is PropDecl  -> { /* top-level props handled during emit */ }
        }
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
            if ((t.name == "ArrayList" || t.name == "MutableList") && t.typeArgs.isNotEmpty()) {
                arrayListElemTypes.add(t.typeArgs[0].name)
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
                // mutableListOf / arrayListOf: infer elem from first arg
                if ((name == "mutableListOf" || name == "arrayListOf") && e.args.isNotEmpty()) {
                    val firstArg = e.args[0].expr
                    if (firstArg is CallExpr) {
                        val argName = (firstArg.callee as? NameExpr)?.name
                        if (argName != null && classes.containsKey(argName)) {
                            arrayListElemTypes.add(argName)
                        }
                    } else if (firstArg is IntLit) { arrayListElemTypes.add("Int") }
                    else if (firstArg is FloatLit) { arrayListElemTypes.add("Float") }
                    else if (firstArg is DoubleLit) { arrayListElemTypes.add("Double") }
                    else if (firstArg is StrLit) { arrayListElemTypes.add("String") }
                }
                // ArrayList constructors: IntArrayList(), Vec2ArrayList()
                val alElem = if (name != null) arrayListConstructorElem(name) else null
                if (alElem != null) {
                    arrayListElemTypes.add(alElem)
                }
                // HashMap constructors: IntIntHashMap(), StringIntHashMap()
                val hmKV = if (name != null) hashMapConstructorKV(name) else null
                if (hmKV != null) {
                    hashMapTypes.add(hmKV)
                }
                // Recurse into args
                for (arg in e.args) scanExpr(arg.expr)
            }
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
        val paramStr = expandCtorParams(ci.ctorProps)
        val paramDecl = paramStr.ifEmpty { "void" }
        hdr.appendLine("$cName ${cName}_create($paramDecl);")
        impl.appendLine("$cName ${cName}_create($paramDecl) {")
        if (ci.bodyProps.isEmpty() && ci.ctorProps.none { isArrayType(resolveTypeName(it.second)) || it.second.nullable }) {
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
                if (bp.init != null) impl.appendLine("    \$self.${bp.name} = ${genExpr(bp.init)};")
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

        // --- toHeap: ClassName_toHeap(value) → ClassName* ---
        emitToHeap(cName)

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
        val paramStr = expandCtorParams(ci.ctorProps)
        val paramDecl = paramStr.ifEmpty { "void" }
        hdr.appendLine("$cName* ${cName}_new($paramDecl);")
        impl.appendLine("$cName* ${cName}_new($paramDecl) {")
        impl.appendLine("    $cName* \$p = ($cName*)malloc(sizeof($cName));")
        impl.appendLine("    *\$p = ${cName}_create(${ci.ctorProps.joinToString(", ") { it.first }});")
        impl.appendLine("    return \$p;")
        impl.appendLine("}")
        impl.appendLine()
    }

    /** Generate ClassName_toHeap(value) → ClassName* (stack→heap copy). */
    private fun emitToHeap(cName: String) {
        hdr.appendLine("$cName* ${cName}_toHeap($cName \$v);")
        impl.appendLine("$cName* ${cName}_toHeap($cName \$v) {")
        impl.appendLine("    $cName* \$p = ($cName*)malloc(sizeof($cName));")
        impl.appendLine("    *\$p = \$v;")
        impl.appendLine("    return \$p;")
        impl.appendLine("}")
        impl.appendLine()
    }

    private fun emitMethod(className: String, f: FunDecl) {
        val cClass = pfx(className)
        val cRet = if (f.returnType != null) cType(f.returnType) else "void"
        val selfParam = "$cClass* \$self"
        val extraParams = expandParams(f.params)
        val allParams = if (extraParams.isEmpty()) selfParam else "$selfParam, $extraParams"

        hdr.appendLine("$cRet ${cClass}_${f.name}($allParams);")
        impl.appendLine("$cRet ${cClass}_${f.name}($allParams) {")

        pushScope()
        for (p in f.params) defineVar(p.name, resolveTypeName(p.type))
        // class props accessible via self->
        val ci = classes[className]
        if (ci != null) for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))

        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = true)
        deferStack.clear(); deferStack.addAll(savedDefers)
        popScope()

        impl.appendLine("}")
        impl.appendLine()
    }

    // ── extension function ───────────────────────────────────────────

    private fun emitExtensionFun(f: FunDecl) {
        val recvTypeName = f.receiver!!.name
        val cRet = if (f.returnType != null) cType(f.returnType) else "void"
        val isClassType = classes.containsKey(recvTypeName)
        val cRecvType = cTypeStr(recvTypeName)
        val selfParam = if (isClassType) "$cRecvType* \$self" else "$cRecvType \$self"
        val extraParams = expandParams(f.params)
        val allParams = if (extraParams.isEmpty()) selfParam else "$selfParam, $extraParams"
        val cFnName = "${pfx(recvTypeName)}_${f.name}"

        hdr.appendLine("$cRet $cFnName($allParams);")
        impl.appendLine("$cRet $cFnName($allParams) {")

        val prevClass = currentClass
        val prevSelfIsPointer = selfIsPointer
        if (isClassType) {
            currentClass = recvTypeName
            selfIsPointer = true
        } else {
            currentClass = null
            selfIsPointer = false
        }

        pushScope()
        for (p in f.params) defineVar(p.name, resolveTypeName(p.type))
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

        impl.appendLine("}")
        impl.appendLine()
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
            for (p in m.params) defineVar(p.name, resolveTypeName(p.type))
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
        val cName = pfx(d.name)
        // vtable struct
        hdr.appendLine("typedef struct {")
        for (m in d.methods) {
            val cRet = if (m.returnType != null) cType(m.returnType) else "void"
            val extraParams = m.params.joinToString("") { p -> ", ${cType(p.type)} ${p.name}" }
            hdr.appendLine("    $cRet (*${m.name})(void* \$self$extraParams);")
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

    /**
     * For each interface a class implements, emit:
     *   1. A static const vtable instance with the class's method pointers
     *   2. A wrapping function:  ClassName_as_IfaceName(ClassName* $self) → IfaceName
     */
    private fun emitClassInterfaceVtables(d: ClassDecl) {
        val cClass = pfx(d.name)
        for (ifaceName in d.superInterfaces) {
            val iface = interfaces[ifaceName] ?: continue
            val cIface = pfx(ifaceName)

            // static vtable instance
            hdr.appendLine("extern const ${cIface}_vt ${cClass}_${ifaceName}_vt;")
            impl.appendLine("const ${cIface}_vt ${cClass}_${ifaceName}_vt = {")
            for (m in iface.methods) {
                val cRet = if (m.returnType != null) cType(m.returnType) else "void"
                val extraCast = m.params.joinToString("") { p -> ", ${cType(p.type)}" }
                impl.appendLine("    ($cRet (*)(void*$extraCast)) ${cClass}_${m.name},")
            }
            impl.appendLine("};")
            impl.appendLine()

            // wrapping function: ClassName_as_IfaceName
            hdr.appendLine("$cIface ${cClass}_as_${ifaceName}($cClass* \$self);")
            impl.appendLine("$cIface ${cClass}_as_${ifaceName}($cClass* \$self) {")
            impl.appendLine("    return ($cIface){(void*)\$self, &${cClass}_${ifaceName}_vt};")
            impl.appendLine("}")
            impl.appendLine()
        }
    }

    // ── ArrayList codegen ────────────────────────────────────────────

    private fun emitArrayList(elemKt: String) {
        val elemC = arrayListElemCType(elemKt)
        val typeName = "${arrayListCPrefix(elemKt)}ArrayList"

        // Struct typedef
        hdr.appendLine("typedef struct {")
        hdr.appendLine("    $elemC* ptr;")
        hdr.appendLine("    int32_t len;")
        hdr.appendLine("    int32_t cap;")
        hdr.appendLine("} $typeName;")
        hdr.appendLine()

        // create
        hdr.appendLine("$typeName ${typeName}_create(int32_t cap);")
        impl.appendLine("$typeName ${typeName}_create(int32_t cap) {")
        impl.appendLine("    return ($typeName){($elemC*)malloc(sizeof($elemC) * (size_t)cap), 0, cap};")
        impl.appendLine("}")
        impl.appendLine()

        // add
        hdr.appendLine("void ${typeName}_add($typeName* \$self, $elemC elem);")
        impl.appendLine("void ${typeName}_add($typeName* \$self, $elemC elem) {")
        impl.appendLine("    if (\$self->len >= \$self->cap) {")
        impl.appendLine("        \$self->cap = \$self->cap > 0 ? \$self->cap * 2 : 4;")
        impl.appendLine("        \$self->ptr = ($elemC*)realloc(\$self->ptr, sizeof($elemC) * (size_t)\$self->cap);")
        impl.appendLine("    }")
        impl.appendLine("    \$self->ptr[\$self->len++] = elem;")
        impl.appendLine("}")
        impl.appendLine()

        // get
        hdr.appendLine("$elemC ${typeName}_get($typeName* \$self, int32_t idx);")
        impl.appendLine("$elemC ${typeName}_get($typeName* \$self, int32_t idx) {")
        impl.appendLine("    return \$self->ptr[idx];")
        impl.appendLine("}")
        impl.appendLine()

        // set
        hdr.appendLine("void ${typeName}_set($typeName* \$self, int32_t idx, $elemC v);")
        impl.appendLine("void ${typeName}_set($typeName* \$self, int32_t idx, $elemC v) {")
        impl.appendLine("    \$self->ptr[idx] = v;")
        impl.appendLine("}")
        impl.appendLine()

        // removeAt
        hdr.appendLine("void ${typeName}_removeAt($typeName* \$self, int32_t idx);")
        impl.appendLine("void ${typeName}_removeAt($typeName* \$self, int32_t idx) {")
        impl.appendLine("    for (int32_t \$i = idx; \$i < \$self->len - 1; \$i++) \$self->ptr[\$i] = \$self->ptr[\$i + 1];")
        impl.appendLine("    \$self->len--;")
        impl.appendLine("}")
        impl.appendLine()

        // clear
        hdr.appendLine("void ${typeName}_clear($typeName* \$self);")
        impl.appendLine("void ${typeName}_clear($typeName* \$self) {")
        impl.appendLine("    \$self->len = 0;")
        impl.appendLine("}")
        impl.appendLine()

        // free
        hdr.appendLine("void ${typeName}_free($typeName* \$self);")
        impl.appendLine("void ${typeName}_free($typeName* \$self) {")
        impl.appendLine("    free(\$self->ptr);")
        impl.appendLine("    \$self->ptr = NULL;")
        impl.appendLine("    \$self->len = 0;")
        impl.appendLine("    \$self->cap = 0;")
        impl.appendLine("}")
        impl.appendLine()

        hdr.appendLine()
    }

    // ── HashMap codegen ──────────────────────────────────────────────

    /** Parse "KeyType:ValueType" pair. */
    private fun hashMapKV(kv: String): Pair<String, String> {
        val parts = kv.split(":")
        return parts[0] to parts[1]
    }

    /** C type name for a HashMap. e.g. "Int:Int" → "kt_IntIntHashMap", "String:Vec2" → "kt_StringGame_Vec2HashMap" */
    private fun hashMapCName(kv: String): String {
        val (keyKt, valKt) = hashMapKV(kv)
        return "${hashMapCPrefix(keyKt)}${valKt}HashMap"
    }

    /** C prefix for HashMap key type: primitives → "kt_Key", classes → pfx(Key) */
    private fun hashMapCPrefix(keyKt: String): String = when (keyKt) {
        "Int", "Long", "Float", "Double", "Boolean", "Char", "String" -> "kt_${keyKt}"
        else -> pfx(keyKt)
    }

    /** Hash expression for a key type. */
    private fun hashMapHashExpr(keyKt: String, keyExpr: String, tn: String): String = when (keyKt) {
        "Int"     -> "((uint32_t)($keyExpr))"
        "Long"    -> "((uint32_t)(($keyExpr) ^ (($keyExpr) >> 32)))"
        "Float"   -> "((uint32_t)(*(uint32_t*)&($keyExpr)))"
        "Double"  -> "({ uint64_t \$b; memcpy(&\$b, &($keyExpr), 8); (uint32_t)(\$b ^ (\$b >> 32)); })"
        "Boolean" -> "((uint32_t)($keyExpr))"
        "Char"    -> "((uint32_t)($keyExpr))"
        "String"  -> "${tn}_strhash($keyExpr)"
        else      -> "((uint32_t)($keyExpr))"  // fallback for class types
    }

    /** Equality expression for a key type. */
    private fun hashMapKeyEq(keyKt: String, a: String, b: String): String = when (keyKt) {
        "String" -> "kt_string_eq($a, $b)"
        else     -> "($a == $b)"
    }

    /** Default/empty key value for a type. */
    private fun hashMapEmptyKey(keyKt: String): String = when (keyKt) {
        "Int"     -> "0"
        "Long"    -> "0"
        "Float"   -> "0.0f"
        "Double"  -> "0.0"
        "Boolean" -> "false"
        "Char"    -> "'\\0'"
        "String"  -> "(kt_String){NULL, 0}"
        else      -> "{0}"
    }

    /** Expression to check if a slot is occupied. */
    private fun hashMapSlotOccupied(slotExpr: String): String {
        return "$slotExpr"
    }

    /**
     * Emit a complete open-addressing hash map for a key:value type pair.
     * Uses a parallel "occupied" bool array for simplicity.
     */
    private fun emitHashMap(kv: String) {
        val (keyKt, valKt) = hashMapKV(kv)
        val keyC = cTypeStr(keyKt)
        val valC = cTypeStr(valKt)
        val tn = hashMapCName(kv)

        // If key is String, emit a string hash helper
        if (keyKt == "String") {
            hdr.appendLine("uint32_t ${tn}_strhash(kt_String s);")
            impl.appendLine("uint32_t ${tn}_strhash(kt_String s) {")
            impl.appendLine("    uint32_t h = 2166136261u;")
            impl.appendLine("    for (int32_t i = 0; i < s.len; i++) { h ^= (uint8_t)s.ptr[i]; h *= 16777619u; }")
            impl.appendLine("    return h;")
            impl.appendLine("}")
            impl.appendLine()
        }

        // Struct: keys[], vals[], occ[], cap, len
        hdr.appendLine("typedef struct {")
        hdr.appendLine("    $keyC*  keys;")
        hdr.appendLine("    $valC*  vals;")
        hdr.appendLine("    bool*   occ;")
        hdr.appendLine("    int32_t cap;")
        hdr.appendLine("    int32_t len;")
        hdr.appendLine("} $tn;")
        hdr.appendLine()

        // create
        hdr.appendLine("$tn ${tn}_create(int32_t cap);")
        impl.appendLine("$tn ${tn}_create(int32_t cap) {")
        impl.appendLine("    $tn m;")
        impl.appendLine("    m.cap = cap;")
        impl.appendLine("    m.len = 0;")
        impl.appendLine("    m.keys = ($keyC*)calloc((size_t)cap, sizeof($keyC));")
        impl.appendLine("    m.vals = ($valC*)calloc((size_t)cap, sizeof($valC));")
        impl.appendLine("    m.occ  = (bool*)calloc((size_t)cap, sizeof(bool));")
        impl.appendLine("    return m;")
        impl.appendLine("}")
        impl.appendLine()

        // Internal: grow + rehash
        hdr.appendLine("void ${tn}_grow($tn* \$self);")
        impl.appendLine("void ${tn}_grow($tn* \$self) {")
        impl.appendLine("    int32_t oldCap = \$self->cap;")
        impl.appendLine("    $keyC* oldKeys = \$self->keys;")
        impl.appendLine("    $valC* oldVals = \$self->vals;")
        impl.appendLine("    bool*  oldOcc  = \$self->occ;")
        impl.appendLine("    int32_t newCap = oldCap * 2;")
        impl.appendLine("    \$self->keys = ($keyC*)calloc((size_t)newCap, sizeof($keyC));")
        impl.appendLine("    \$self->vals = ($valC*)calloc((size_t)newCap, sizeof($valC));")
        impl.appendLine("    \$self->occ  = (bool*)calloc((size_t)newCap, sizeof(bool));")
        impl.appendLine("    \$self->cap  = newCap;")
        impl.appendLine("    \$self->len  = 0;")
        impl.appendLine("    for (int32_t \$i = 0; \$i < oldCap; \$i++) {")
        impl.appendLine("        if (oldOcc[\$i]) ${tn}_put(\$self, oldKeys[\$i], oldVals[\$i]);")
        impl.appendLine("    }")
        impl.appendLine("    free(oldKeys); free(oldVals); free(oldOcc);")
        impl.appendLine("}")
        impl.appendLine()

        // put
        val hashExpr = hashMapHashExpr(keyKt, "key", tn)
        val keyEq = hashMapKeyEq(keyKt, "\$self->keys[\$idx]", "key")
        hdr.appendLine("void ${tn}_put($tn* \$self, $keyC key, $valC val);")
        impl.appendLine("void ${tn}_put($tn* \$self, $keyC key, $valC val) {")
        impl.appendLine("    if (\$self->len * 2 >= \$self->cap) ${tn}_grow(\$self);")
        impl.appendLine("    uint32_t \$h = $hashExpr;")
        impl.appendLine("    int32_t \$idx = (int32_t)(\$h % (uint32_t)\$self->cap);")
        impl.appendLine("    while (\$self->occ[\$idx]) {")
        impl.appendLine("        if ($keyEq) { \$self->vals[\$idx] = val; return; }")
        impl.appendLine("        \$idx = (\$idx + 1) % \$self->cap;")
        impl.appendLine("    }")
        impl.appendLine("    \$self->keys[\$idx] = key;")
        impl.appendLine("    \$self->vals[\$idx] = val;")
        impl.appendLine("    \$self->occ[\$idx]  = true;")
        impl.appendLine("    \$self->len++;")
        impl.appendLine("}")
        impl.appendLine()

        // get (returns value, undefined if not present — use containsKey first)
        hdr.appendLine("$valC ${tn}_get($tn* \$self, $keyC key);")
        impl.appendLine("$valC ${tn}_get($tn* \$self, $keyC key) {")
        impl.appendLine("    uint32_t \$h = $hashExpr;")
        impl.appendLine("    int32_t \$idx = (int32_t)(\$h % (uint32_t)\$self->cap);")
        impl.appendLine("    while (\$self->occ[\$idx]) {")
        impl.appendLine("        if ($keyEq) return \$self->vals[\$idx];")
        impl.appendLine("        \$idx = (\$idx + 1) % \$self->cap;")
        impl.appendLine("    }")
        impl.appendLine("    $valC \$zero = {0}; return \$zero;")
        impl.appendLine("}")
        impl.appendLine()

        // containsKey
        hdr.appendLine("bool ${tn}_containsKey($tn* \$self, $keyC key);")
        impl.appendLine("bool ${tn}_containsKey($tn* \$self, $keyC key) {")
        impl.appendLine("    uint32_t \$h = $hashExpr;")
        impl.appendLine("    int32_t \$idx = (int32_t)(\$h % (uint32_t)\$self->cap);")
        impl.appendLine("    while (\$self->occ[\$idx]) {")
        impl.appendLine("        if ($keyEq) return true;")
        impl.appendLine("        \$idx = (\$idx + 1) % \$self->cap;")
        impl.appendLine("    }")
        impl.appendLine("    return false;")
        impl.appendLine("}")
        impl.appendLine()

        // remove
        hdr.appendLine("bool ${tn}_remove($tn* \$self, $keyC key);")
        impl.appendLine("bool ${tn}_remove($tn* \$self, $keyC key) {")
        impl.appendLine("    uint32_t \$h = $hashExpr;")
        impl.appendLine("    int32_t \$idx = (int32_t)(\$h % (uint32_t)\$self->cap);")
        impl.appendLine("    while (\$self->occ[\$idx]) {")
        impl.appendLine("        if ($keyEq) {")
        impl.appendLine("            \$self->occ[\$idx] = false;")
        impl.appendLine("            \$self->len--;")
        // Rehash the cluster after the removed slot
        impl.appendLine("            int32_t \$j = (\$idx + 1) % \$self->cap;")
        impl.appendLine("            while (\$self->occ[\$j]) {")
        impl.appendLine("                $keyC \$rk = \$self->keys[\$j];")
        impl.appendLine("                $valC \$rv = \$self->vals[\$j];")
        impl.appendLine("                \$self->occ[\$j] = false;")
        impl.appendLine("                \$self->len--;")
        impl.appendLine("                ${tn}_put(\$self, \$rk, \$rv);")
        impl.appendLine("                \$j = (\$j + 1) % \$self->cap;")
        impl.appendLine("            }")
        impl.appendLine("            return true;")
        impl.appendLine("        }")
        impl.appendLine("        \$idx = (\$idx + 1) % \$self->cap;")
        impl.appendLine("    }")
        impl.appendLine("    return false;")
        impl.appendLine("}")
        impl.appendLine()

        // clear
        hdr.appendLine("void ${tn}_clear($tn* \$self);")
        impl.appendLine("void ${tn}_clear($tn* \$self) {")
        impl.appendLine("    memset(\$self->occ, 0, (size_t)\$self->cap * sizeof(bool));")
        impl.appendLine("    \$self->len = 0;")
        impl.appendLine("}")
        impl.appendLine()

        // free
        hdr.appendLine("void ${tn}_free($tn* \$self);")
        impl.appendLine("void ${tn}_free($tn* \$self) {")
        impl.appendLine("    free(\$self->keys); free(\$self->vals); free(\$self->occ);")
        impl.appendLine("    \$self->keys = NULL; \$self->vals = NULL; \$self->occ = NULL;")
        impl.appendLine("    \$self->cap = 0; \$self->len = 0;")
        impl.appendLine("}")
        impl.appendLine()

        hdr.appendLine()
    }

    // ── top-level fun ────────────────────────────────────────────────

    private fun emitFun(f: FunDecl) {
        val isMain = f.name == "main"
        val isMainWithArgs = isMain && f.params.size == 1 &&
                f.params[0].type.name == "Array" &&
                f.params[0].type.typeArgs.singleOrNull()?.name == "String"

        val returnsNullable = !isMain && f.returnType != null && f.returnType.nullable
        val cRet  = if (isMain) "int" else if (f.returnType != null) cType(f.returnType) else "void"
        val cName = if (isMain) "main" else pfx(f.name)
        val params = when {
            isMainWithArgs -> "int argc, char** argv"
            isMain         -> "void"
            else           -> {
                val base = expandParams(f.params)
                if (returnsNullable) {
                    if (base.isEmpty()) "bool* \$has_out" else "$base, bool* \$has_out"
                } else base
            }
        }

        hdr.appendLine("$cRet $cName($params);")
        impl.appendLine("$cRet $cName($params) {")

        val prevReturnsNullable = currentFnReturnsNullable
        val prevReturnType = currentFnReturnType
        currentFnReturnsNullable = returnsNullable
        currentFnReturnType = if (f.returnType != null) resolveTypeName(f.returnType) else ""

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
                defineVar(p.name, if (p.type.nullable) "${resolved}?" else resolved)
            }
        }
        val savedDefers = deferStack.toList()
        deferStack.clear()

        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ")
        // Emit deferred blocks at end unless last stmt was a return (already emitted there)
        val lastStmt = f.body?.stmts?.lastOrNull()
        if (lastStmt !is ReturnStmt) emitDeferredBlocks("    ")
        if (isMain) impl.appendLine("    return 0;")
        popScope()

        deferStack.clear()
        deferStack.addAll(savedDefers)
        currentFnReturnsNullable = prevReturnsNullable
        currentFnReturnType = prevReturnType
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
    }

    private fun emitBlock(b: Block, ind: String, insideMethod: Boolean = false) {
        for (s in b.stmts) emitStmt(s, "$ind    ", insideMethod)
    }

    // ── var / val ────────────────────────────────────────────────────

    private fun emitVarDecl(s: VarDeclStmt, ind: String, method: Boolean) {
        val t = if (s.type != null) resolveTypeName(s.type) else (inferExprType(s.init) ?: "Int")
        val isHeapValNull = t.endsWith("*#")          // Heap<T?> — value-nullable, $has
        val isHeapPtrNull = !isHeapValNull &&          // Heap<T>? — pointer-nullable, NULL
                isHeapPointerType(t) &&
                (s.type?.nullable == true || s.init is NullLit)
        val isNullable = !isHeapValNull && !isHeapPtrNull &&
                (s.type?.nullable == true || s.init is NullLit)

        // Register type in scope
        defineVar(s.name, when {
            isHeapValNull  -> t                        // "T*#" as-is
            isHeapPtrNull  -> "${t}?"                  // "T*?"
            isNullable     -> "${t}?"                  // "Int?" etc.
            else           -> t
        })

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
        // Don't const class types, Pointer, typed pointers, nullable, ArrayList, or interface types
        val qual = if (!s.mutable && !classes.containsKey(t) && !interfaces.containsKey(t) && t != "Pointer"
            && !t.endsWith("*") && !t.endsWith("*#") && !t.endsWith("ArrayList")
            && !isNullable && !isHeapPtrNull && !isHeapValNull) "const " else ""

        if (s.init != null) {
            val arrayInit = tryArrayOfInit(s.name, s.init, ct, t, ind)
            if (arrayInit != null) { impl.appendLine(arrayInit); return }

            when {
                // ── Heap<T?> : always allocated, $has tracks value ──
                isHeapValNull -> {
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$ct ${s.name} = $expr;")
                    impl.appendLine("${ind}bool ${s.name}\$has = true;")
                }
                // ── Heap<T>? : pointer nullable via NULL ──
                isHeapPtrNull -> {
                    if (s.init is NullLit) {
                        impl.appendLine("$ind$ct ${s.name} = NULL;")
                    } else {
                        val expr = genExpr(s.init)
                        flushPreStmts(ind)
                        impl.appendLine("$ind$ct ${s.name} = $expr;")
                    }
                }
                // ── Value nullable (existing system with $has) ──
                isNullable -> {
                    if (s.init is NullLit) {
                        impl.appendLine("$ind$ct ${s.name} = ${defaultVal(t)};")
                        impl.appendLine("${ind}bool ${s.name}\$has = false;")
                    } else if (isNullableReturningCall(s.init)) {
                        impl.appendLine("${ind}bool ${s.name}\$has;")
                        val expr = genExprWithNullableOut(s.init, "${s.name}\$has")
                        flushPreStmts(ind)
                        impl.appendLine("$ind$ct ${s.name} = $expr;")
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
                    val expr = genExpr(s.init)
                    flushPreStmts(ind)
                    impl.appendLine("$ind$qual$ct ${s.name} = $expr;")
                }
            }
        } else {
            when {
                isHeapValNull -> {
                    // Heap<T?> without init — can't auto-allocate, error or fallback
                    impl.appendLine("$ind$ct ${s.name} = NULL; /* warning: Heap<T?> must be initialized */")
                    impl.appendLine("${ind}bool ${s.name}\$has = false;")
                }
                isHeapPtrNull -> {
                    impl.appendLine("$ind$ct ${s.name} = NULL;")
                }
                else -> {
                    impl.appendLine("$ind$ct ${s.name} = ${defaultVal(t)};")
                    if (isNullable) impl.appendLine("${ind}bool ${s.name}\$has = false;")
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

    /** Generate a call expression that returns nullable, appending &hasVar as extra arg. */
    private fun genExprWithNullableOut(e: Expr, hasVar: String): String {
        if (e !is CallExpr) return genExpr(e)
        val name = (e.callee as? NameExpr)?.name ?: return genExpr(e)
        val cName = pfx(name)
        val sig = funSigs[name]
        val args = expandCallArgs(e.args, sig?.params)
        val extraArg = "&$hasVar"
        val allArgs = if (args.isEmpty()) extraArg else "$args, $extraArg"
        return "$cName($allArgs)"
    }

    // ── assignment ───────────────────────────────────────────────────

    private fun emitAssign(s: AssignStmt, ind: String, method: Boolean) {
        // HashMap/ArrayList index assignment: map[key] = value → put/set call
        if (s.target is IndexExpr && s.op == "=") {
            val objType = inferExprType(s.target.obj)
            if (objType != null && objType.endsWith("HashMap")) {
                val typeName = cTypeStr(objType)
                val obj = genExpr(s.target.obj)
                val key = genExpr(s.target.index)
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind${typeName}_put(&$obj, $key, $value);")
                return
            }
            if (objType != null && objType.endsWith("ArrayList")) {
                val typeName = cTypeStr(objType)
                val obj = genExpr(s.target.obj)
                val idx = genExpr(s.target.index)
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind${typeName}_set(&$obj, $idx, $value);")
                return
            }
        }

        val target = genLValue(s.target, method)
        val varName = (s.target as? NameExpr)?.name
        val varType = if (varName != null) lookupVar(varName) else null

        when {
            // Heap<T?> = null → clear value, keep pointer
            isHeapValueNullable(varType) && s.value is NullLit -> {
                impl.appendLine("$ind${target}\$has = false;")
            }
            // Heap<T?> = value → set value
            isHeapValueNullable(varType) -> {
                val value = genExpr(s.value)
                flushPreStmts(ind)
                impl.appendLine("$ind*$target = $value;")
                impl.appendLine("$ind${target}\$has = true;")
            }
            // Heap<T>? = null → NULL pointer
            isHeapPtrNullable(varType) && s.value is NullLit -> {
                impl.appendLine("$ind$target = NULL;")
            }
            // Heap<T>? = value → assign pointer
            isHeapPtrNullable(varType) -> {
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
                if (varType != null && varType.endsWith("?") && !isHeapPointerType(varType)) {
                    impl.appendLine("$ind${target}\$has = true;")
                }
            }
        }
    }

    // ── return ───────────────────────────────────────────────────────

    private fun emitReturn(s: ReturnStmt, ind: String) {
        if (currentFnReturnsNullable) {
            if (s.value == null || s.value is NullLit) {
                emitDeferredBlocks(ind)
                impl.appendLine("$ind*\$has_out = false;")
                impl.appendLine("${ind}return ${defaultVal(currentFnReturnBaseType())};")
            } else {
                val expr = genExpr(s.value)
                flushPreStmts(ind)
                if (deferStack.isNotEmpty()) {
                    val t = tmp()
                    impl.appendLine("$ind${cTypeStr(currentFnReturnBaseType())} $t = $expr;")
                    impl.appendLine("$ind*\$has_out = true;")
                    emitDeferredBlocks(ind)
                    impl.appendLine("${ind}return $t;")
                } else {
                    impl.appendLine("$ind*\$has_out = true;")
                    impl.appendLine("${ind}return $expr;")
                }
            }
        } else {
            if (s.value != null) {
                val expr = genExpr(s.value)
                flushPreStmts(ind)
                if (deferStack.isNotEmpty()) {
                    // Evaluate return value into temp, run defers, then return
                    val retType = currentFnReturnType.ifEmpty { inferExprType(s.value) ?: "Int" }
                    val t = tmp()
                    impl.appendLine("$ind${cTypeStr(retType)} $t = $expr;")
                    emitDeferredBlocks(ind)
                    impl.appendLine("${ind}return $t;")
                } else {
                    impl.appendLine("${ind}return $expr;")
                }
            } else {
                emitDeferredBlocks(ind)
                impl.appendLine("${ind}return;")
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
        // Heap .set(val) as statement — also updates $has for Heap<T?>
        if (e is CallExpr && e.callee is DotExpr && e.callee.name == "set") {
            val recvType = inferExprType(e.callee.obj)
            if (heapClassName(recvType) != null) {
                val recv = genExpr(e.callee.obj)
                val argStr = e.args.joinToString(", ") { genExpr(it.expr) }
                flushPreStmts(ind)
                impl.appendLine("$ind*$recv = $argStr;")
                // If receiver is Heap<T?>, also set $has = true
                val recvVarName = (e.callee.obj as? NameExpr)?.name
                val recvVarType = if (recvVarName != null) lookupVar(recvVarName) else null
                if (isHeapValueNullable(recvVarType)) {
                    impl.appendLine("$ind${recvVarName}\$has = true;")
                }
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

        // data class → emit toString into StrBuf, then printf
        if (classes.containsKey(t) && classes[t]!!.isData) {
            val buf = tmp()
            impl.appendLine("${ind}char ${buf}[256];")
            impl.appendLine("${ind}kt_StrBuf ${buf}_sb = {${buf}, 0, 256};")
            impl.appendLine("${ind}${pfx(t)}_toString($expr, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr);")
            return
        }

        // Heap pointer to data class → dereference, then toString
        val heapBase = heapClassName(t)
        if (heapBase != null && classes[heapBase]?.isData == true) {
            val buf = tmp()
            impl.appendLine("${ind}char ${buf}[256];")
            impl.appendLine("${ind}kt_StrBuf ${buf}_sb = {${buf}, 0, 256};")
            impl.appendLine("${ind}${pfx(heapBase)}_toString(*$expr, &${buf}_sb);")
            impl.appendLine("${ind}printf(\"%.*s$nl\", (int)${buf}_sb.len, ${buf}_sb.ptr);")
            return
        }

        val fmt = printfFmt(t) + nl
        val a = printfArg(expr, t)
        impl.appendLine("${ind}printf(\"$fmt\", $a);")
    }

    /** Check if a template contains data class expressions (need StrBuf). */
    private fun templateNeedsStrBuf(tmpl: StrTemplateExpr): Boolean {
        return tmpl.parts.any { part ->
            part is ExprPart && run {
                val t = inferExprType(part.expr) ?: "Int"
                classes.containsKey(t)
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
            val type = lookupVar(name)
            if (type != null && (type.endsWith("?") || type.endsWith("#"))) {
                casts.add(name to type.dropLast(1))
            }
        }
        when {
            // x != null
            cond is BinExpr && cond.op == "!=" && cond.right is NullLit && cond.left is NameExpr ->
                trySmartCast(cond.left.name)
            // null != x
            cond is BinExpr && cond.op == "!=" && cond.left is NullLit && cond.right is NameExpr ->
                trySmartCast(cond.right.name)
        }
        return casts
    }

    /** Detect smart-casts for the else branch (condition that proves null in the then branch). */
    private fun extractElseSmartCasts(cond: Expr): List<Pair<String, String>> {
        val casts = mutableListOf<Pair<String, String>>()
        fun trySmartCast(name: String) {
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
            // for (i in a until b)
            rangeExpr is BinExpr && rangeExpr.op == "until" -> {
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
                if (arrType != null && arrType.endsWith("ArrayList")) {
                    // ArrayList: use .len and .ptr[idx]
                    val elemKt = arrType.removeSuffix("ArrayList")
                    val elemC = cTypeStr(elemKt)
                    impl.appendLine("${ind}for (int32_t $idx = 0; $idx < $arrExpr.len; $idx++) {")
                    impl.appendLine("$ind    $elemC ${s.varName} = $arrExpr.ptr[$idx];")
                    pushScope(); defineVar(s.varName, elemKt)
                    emitBlock(s.body, ind, method)
                    popScope()
                    impl.appendLine("$ind}")
                } else {
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
    }

    // ═══════════════════════════ Expression codegen ═══════════════════

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
            if (objType != null && objType.endsWith("HashMap")) {
                // HashMap: map[key] → TypeName_get(&map, key)
                "${cTypeStr(objType)}_get(&${genExpr(e.obj)}, ${genExpr(e.index)})"
            } else if (objType != null && objType.endsWith("ArrayList")) {
                // ArrayList: list[idx] → TypeName_get(&list, idx)
                "${cTypeStr(objType)}_get(&${genExpr(e.obj)}, ${genExpr(e.index)})"
            } else if (objType != null && (objType.endsWith("*") || isArrayType(objType))) {
                // Typed pointer or array: direct indexing
                "${genExpr(e.obj)}[${genExpr(e.index)}]"
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        }
        is IfExpr      -> genIfExpr(e)
        is WhenExpr    -> genWhenExpr(e)
        is NotNullExpr -> genExpr(e.expr)   // x!! → just x (value)
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
        return e.name
    }

    // ── binary ───────────────────────────────────────────────────────

    private fun genBin(e: BinExpr): String {
        val lt = inferExprType(e.left)
        // null comparison
        if ((e.op == "==" || e.op == "!=") && (e.left is NullLit || e.right is NullLit)) {
            val nonNull = if (e.left is NullLit) e.right else e.left
            val varName = (nonNull as? NameExpr)?.name
            val varType = if (varName != null) lookupVar(varName) else null
            if (varType != null) {
                // Heap<T>? → compare pointer to NULL
                if (isHeapPtrNullable(varType)) {
                    return if (e.op == "==") "$varName == NULL" else "$varName != NULL"
                }
                // Heap<T?> → use $has
                if (isHeapValueNullable(varType)) {
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
        return "(${genExpr(e.left)} ${e.op} ${genExpr(e.right)})"
    }

    private fun genStringConcat(e: BinExpr): String {
        val buf = tmp()
        // We can't declare a local inside an expression, so we use a compound literal trick:
        // For now, generate a kt_string_cat_stack helper
        return "kt_string_cat($buf, ${genExpr(e.left)}, ${genExpr(e.right)})"
    }

    // ── function / constructor call ──────────────────────────────────

    private fun genCall(e: CallExpr): String {
        // Method call: DotExpr(receiver, method)(args)
        if (e.callee is DotExpr) return genMethodCall(e.callee, e.args)
        if (e.callee is SafeDotExpr) return genSafeMethodCall(e.callee, e.args)

        val name = (e.callee as? NameExpr)?.name ?: return "${genExpr(e.callee)}(${e.args.joinToString(", ") { genExpr(it.expr) }})"
        val args = e.args

        // Built-in functions
        when (name) {
            "println" -> return genPrintln(args)
            "print"   -> return genPrint(args)
            "malloc"  -> {
                if (e.typeArgs.isNotEmpty()) {
                    val typeName = e.typeArgs[0].name
                    // Class heap constructor: malloc<MyClass>(args) → MyClass_new(args)
                    if (classes.containsKey(typeName)) {
                        val cName = pfx(typeName)
                        val argStr = args.joinToString(", ") { genExpr(it.expr) }
                        return "${cName}_new($argStr)"
                    }
                    // Primitive typed malloc: malloc<Int>(n) → (int32_t*)malloc(...)
                    val elemC = cTypeStr(typeName)
                    return "($elemC*)malloc(sizeof($elemC) * (size_t)(${genExpr(args[0].expr)}))"
                }
                return "malloc((size_t)(${genExpr(args[0].expr)}))"
            }
            "calloc"  -> {
                if (e.typeArgs.isNotEmpty()) {
                    val elemC = cTypeStr(e.typeArgs[0].name)
                    return "($elemC*)calloc((size_t)(${genExpr(args[0].expr)}), sizeof($elemC))"
                }
                return "calloc((size_t)(${genExpr(args[0].expr)}), (size_t)(${genExpr(args[1].expr)}))"
            }
            "realloc" -> {
                if (e.typeArgs.isNotEmpty()) {
                    val elemC = cTypeStr(e.typeArgs[0].name)
                    return "($elemC*)realloc(${genExpr(args[0].expr)}, sizeof($elemC) * (size_t)(${genExpr(args[1].expr)}))"
                }
                return "realloc(${genExpr(args[0].expr)}, (size_t)(${genExpr(args[1].expr)}))"
            }
            "free"    -> return "free(${genExpr(args[0].expr)})"
            "mutableListOf", "arrayListOf" -> {
                return genMutableListOf(args)
            }
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
        }

        // ArrayList constructor: IntArrayList() or IntArrayList(cap)
        val arrayListElem = arrayListConstructorElem(name)
        if (arrayListElem != null) {
            arrayListElemTypes.add(arrayListElem)
            val typeName = "${arrayListCPrefix(arrayListElem)}ArrayList"
            val cap = if (args.isNotEmpty()) genExpr(args[0].expr) else "16"
            return "${typeName}_create($cap)"
        }

        // HashMap constructor: IntIntHashMap() or IntIntHashMap(cap)
        val hmKV = hashMapConstructorKV(name)
        if (hmKV != null) {
            hashMapTypes.add(hmKV)
            val typeName = hashMapCName(hmKV)
            val cap = if (args.isNotEmpty()) genExpr(args[0].expr) else "16"
            return "${typeName}_create($cap)"
        }

        // Function pointer call: variable with function type → just call it
        val varType = lookupVar(name)
        if (varType != null && isFuncType(varType)) {
            val argStr = args.joinToString(", ") { genExpr(it.expr) }
            return "$name($argStr)"
        }

        // Constructor call (known class)
        if (classes.containsKey(name)) {
            val ci = classes[name]!!
            val filledArgs = fillDefaults(args, ci.ctorProps.map { Param(it.first, it.second) }, ci.ctorProps.associate {
                // find matching ctor param default
                val cp = (file.decls.filterIsInstance<ClassDecl>().find { c -> c.name == name })
                    ?.ctorParams?.find { p -> p.name == it.first }
                it.first to cp?.default
            })
            return "${pfx(name)}_create(${filledArgs.joinToString(", ") { genExpr(it.expr) }})"
        }

        // Enum access (should be handled as DotExpr, but just in case)

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
            preStmts += "bool $hasVar;"
            val allArgs = if (expandedArgs.isEmpty()) "&$hasVar" else "$expandedArgs, &$hasVar"
            preStmts += "$ct $t = ${pfx(name)}($allArgs);"
            defineVar(t, "${retType}?")
            return t
        }

        return "${pfx(name)}($expandedArgs)"
    }

    /** Expand call arguments: array → (arg, arg$len); nullable → (arg, arg$has); class→interface wrapping. */
    private fun expandCallArgs(args: List<Arg>, params: List<Param>?): String {
        val parts = mutableListOf<String>()
        for ((i, arg) in args.withIndex()) {
            val expr = genExpr(arg.expr)
            val param = params?.getOrNull(i)
            val paramType = param?.let { resolveTypeName(it.type) }
            if (paramType != null && isArrayType(paramType)) {
                parts += expr
                parts += "${expr}\$len"
            } else if (param?.type?.nullable == true) {
                if (arg.expr is NullLit) {
                    parts += "${defaultVal(paramType ?: "Int")}"
                    parts += "false"
                } else {
                    parts += expr
                    // Check if the arg is a nullable variable (has $has companion)
                    val argVarName = (arg.expr as? NameExpr)?.name
                    val argVarType = if (argVarName != null) lookupVar(argVarName) else null
                    if (argVarType != null && argVarType.endsWith("?")) {
                        parts += "${expr}\$has"
                    } else {
                        parts += "true"
                    }
                }
            } else if (paramType != null && interfaces.containsKey(paramType)) {
                // Auto-wrap class → interface
                val argType = inferExprType(arg.expr)
                if (argType != null && classes.containsKey(argType) && classInterfaces[argType]?.contains(paramType) == true) {
                    parts += "${pfx(argType)}_as_$paramType(&$expr)"
                } else {
                    parts += expr
                }
            } else {
                parts += expr
            }
        }
        return parts.joinToString(", ")
    }

    /** Returns the element Kotlin type if `name` is an ArrayList constructor, else null. */
    private fun arrayListConstructorElem(name: String): String? {
        if (name.endsWith("ArrayList") && name.length > 9) {
            val elem = name.removeSuffix("ArrayList")
            // Element type must start with uppercase (e.g. IntArrayList, Vec2ArrayList)
            if (elem[0].isUpperCase()) return elem
        }
        return null
    }

    /** Returns "KeyType:ValueType" if `name` is a HashMap constructor like "IntIntHashMap", else null.
     *  Convention: names are <KeyType><ValueType>HashMap.
     *  Both key and value type start with uppercase. We try to split by matching known type prefixes. */
    private fun hashMapConstructorKV(name: String): String? {
        if (!name.endsWith("HashMap") || name.length <= 7) return null
        val body = name.removeSuffix("HashMap")
        // Try to split body into two known type names
        val types = listOf("Int", "Long", "Float", "Double", "Boolean", "Char", "String") + classes.keys + enums.keys
        for (kt in types.sortedByDescending { it.length }) {
            if (body.startsWith(kt)) {
                val rest = body.removePrefix(kt)
                if (rest.isNotEmpty() && rest[0].isUpperCase()) {
                    // Check if rest is also a known type
                    if (rest in types) return "$kt:$rest"
                }
            }
        }
        return null
    }

    private fun genMethodCall(dot: DotExpr, args: List<Arg>): String {
        val recvType = inferExprType(dot.obj)
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
        }

        // Array .size → name\$len
        if (method == "size" && recvType != null && isArrayType(recvType)) return "${recv}\$len"

        // ArrayList methods
        if (recvType != null && recvType.endsWith("ArrayList")) {
            val typeName = cTypeStr(recvType)
            return when (method) {
                "add"      -> "${typeName}_add(&$recv, $argStr)"
                "get"      -> "${typeName}_get(&$recv, $argStr)"
                "set"      -> "${typeName}_set(&$recv, $argStr)"
                "removeAt" -> "${typeName}_removeAt(&$recv, $argStr)"
                "clear"    -> "${typeName}_clear(&$recv)"
                "free"     -> "${typeName}_free(&$recv)"
                "size"     -> "$recv.len"
                else       -> "$recv.$method($argStr)"
            }
        }

        // HashMap methods
        if (recvType != null && recvType.endsWith("HashMap")) {
            val typeName = cTypeStr(recvType)
            return when (method) {
                "put"         -> "${typeName}_put(&$recv, $argStr)"
                "get"         -> "${typeName}_get(&$recv, $argStr)"
                "containsKey" -> "${typeName}_containsKey(&$recv, $argStr)"
                "remove"      -> "${typeName}_remove(&$recv, $argStr)"
                "clear"       -> "${typeName}_clear(&$recv)"
                "free"        -> "${typeName}_free(&$recv)"
                "size"        -> "$recv.len"
                else          -> "$recv.$method($argStr)"
            }
        }

        // ArrayList .size via property access (handled by genDot below)

        // Heap class pointer methods
        val heapBase = heapClassName(recvType)
        if (heapBase != null) {
            when (method) {
                // .value() → dereference: *p
                "value" -> return "(*$recv)"
                // .set(val) — mostly handled at statement level (emitExprStmt), fallback:
                "set" -> return "(*$recv = $argStr)"
                // .copy() on data class
                "copy" -> if (classes[heapBase]?.isData == true) {
                    return genDataClassCopy(recv, heapBase, args, heap = true)
                }
                // .toHeap() on heap pointer — identity, already on heap
                "toHeap" -> return recv
            }
            // general class method — pointer passed directly
            val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
            return "${pfx(heapBase)}_$method($allArgs)"
        }

        // Interface method dispatch → d.vt->method(d.obj, args)
        if (recvType != null && interfaces.containsKey(recvType)) {
            val allArgs = if (argStr.isEmpty()) "$recv.obj" else "$recv.obj, $argStr"
            return "$recv.vt->$method($allArgs)"
        }

        // Class method or extension function on class type (stack value)
        if (recvType != null && classes.containsKey(recvType)) {
            // .copy() on data class
            if (method == "copy" && classes[recvType]?.isData == true) {
                return genDataClassCopy(recv, recvType, args, heap = false)
            }
            // .toHeap() → ClassName_toHeap(value)
            if (method == "toHeap") {
                return "${pfx(recvType)}_toHeap($recv)"
            }
            val allArgs = if (argStr.isEmpty()) "&$recv" else "&$recv, $argStr"
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
                val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
                return "${pfx(recvType)}_$method($allArgs)"
            }
        }

        return "$recv.$method($argStr)"   // fallback
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
        val argStr = args.joinToString(", ") { genExpr(it.expr) }
        // x?.method(args) → x$has ? x.method(args) : defaultVal
        return "(${recv}\$has ? /* ${dot.name}($argStr) */ 0 : 0)"  // TODO: proper dispatch
    }

    // ── dot access (property, enum) ──────────────────────────────────

    private fun genDot(e: DotExpr): String {
        val recvType = inferExprType(e.obj)
        val recv = genExpr(e.obj)

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
        // ArrayList .size
        if (e.name == "size" && recvType?.endsWith("ArrayList") == true) return "$recv.len"
        // HashMap .size
        if (e.name == "size" && recvType?.endsWith("HashMap") == true) return "$recv.len"
        if (e.name == "length" && recvType == "String") return "$recv.len"

        // Heap class pointer: p->field
        if (heapClassName(recvType) != null) {
            return "$recv->${e.name}"
        }

        return "$recv.${e.name}"
    }

    private fun genSafeDot(e: SafeDotExpr): String {
        val recv = genExpr(e.obj)
        // x?.field → x$has ? x.field : 0
        return "(${recv}\$has ? $recv.${e.name} : 0)"
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

        // data class → use preStmts for toString buffer
        if (classes.containsKey(t) && classes[t]!!.isData) {
            val buf = tmp()
            preStmts += "char ${buf}[256];"
            preStmts += "kt_StrBuf ${buf}_sb = {${buf}, 0, 256};"
            preStmts += "${pfx(t)}_toString($expr, &${buf}_sb);"
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
        preStmts += "$elemCType ${t}[$size];"
        preStmts += "memset($t, 0, sizeof($elemCType) * (size_t)($size));"
        preStmts += "const int32_t ${t}\$len = $size;"
        return t
    }

    /** Generates mutableListOf(a, b, c) → create + add calls via preStmts */
    private fun genMutableListOf(args: List<Arg>): String {
        val elemKt = if (args.isNotEmpty()) inferExprType(args[0].expr) ?: "Int" else "Int"
        arrayListElemTypes.add(elemKt)
        val typeName = "${arrayListCPrefix(elemKt)}ArrayList"
        val t = tmp()
        val cap = if (args.size > 4) args.size else args.size.coerceAtLeast(4)
        preStmts.add("$typeName $t = ${typeName}_create($cap);")
        for (a in args) {
            preStmts.add("${typeName}_add(&$t, ${genExpr(a.expr)});")
        }
        return t
    }

    // ── fill default arguments ───────────────────────────────────────

    private fun fillDefaults(args: List<Arg>, params: List<Param>, defaults: Map<String, Expr?>): List<Arg> {
        if (args.size >= params.size) return args
        // Named args: reorder
        val hasNamed = args.any { it.name != null }
        if (hasNamed) {
            val result = params.map { p ->
                val explicit = args.find { it.name == p.name }
                explicit ?: Arg(p.name, defaults[p.name] ?: IntLit(0))
            }
            return result
        }
        // Positional: fill missing from defaults
        val result = args.toMutableList()
        for (i in args.size until params.size) {
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
                    val op = if (heapClassName(recvType) != null) "->" else "."
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
        is ThisExpr -> currentClass
        is NameExpr -> lookupVar(e.name) ?: run {
            // Could be enum type
            if (enums.containsKey(e.name)) e.name else null
        }
        is BinExpr  -> {
            if (e.op in setOf("==", "!=", "<", ">", "<=", ">=", "&&", "||", "in", "!in")) "Boolean"
            else if (e.op == "..") "IntRange"
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
            if (classes.containsKey(name)) return name
            if (name == "malloc" || name == "calloc" || name == "realloc") {
                if (e.typeArgs.isNotEmpty()) return "${e.typeArgs[0].name}*"
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
            if (name == "mutableListOf" || name == "arrayListOf") {
                val elemType = if (e.args.isNotEmpty()) inferExprType(e.args[0].expr) ?: "Int" else "Int"
                arrayListElemTypes.add(elemType)
                return "${elemType}ArrayList"
            }
            // ArrayList constructors: IntArrayList(), Vec2ArrayList(cap)
            val alElem = arrayListConstructorElem(name)
            if (alElem != null) {
                arrayListElemTypes.add(alElem)
                return "${alElem}ArrayList"
            }
            // HashMap constructors: IntIntHashMap(), StringIntHashMap(cap)
            val hmKV = hashMapConstructorKV(name)
            if (hmKV != null) {
                hashMapTypes.add(hmKV)
                val (keyKt, valKt) = hashMapKV(hmKV)
                return "${keyKt}${valKt}HashMap"
            }
            funSigs[name]?.returnType?.let { return resolveTypeName(it) }
        }
        if (e.callee is DotExpr) return inferMethodReturnType(e.callee, e.args)
        return null
    }

    private fun inferMethodReturnType(dot: DotExpr, args: List<Arg>): String? {
        val recvType = inferExprType(dot.obj) ?: return null
        val method = dot.name
        if (method == "toString") return "String"
        if (method == "toInt") return "Int"
        if (method == "toLong") return "Long"
        if (method == "toFloat") return "Float"
        if (method == "toDouble") return "Double"
        // Heap pointer methods
        val heapBase = heapClassName(recvType)
        if (heapBase != null) {
            return when (method) {
                "value" -> heapBase                    // .value() → T (stack copy)
                "set" -> "Unit"
                "copy" -> heapBase                     // .copy() → T (stack copy)
                "toHeap" -> "${heapBase}*"             // identity, already heap
                else -> {
                    // Delegate to class method lookup
                    val m = classes[heapBase]?.methods?.find { it.name == method }
                    if (m != null) (if (m.returnType != null) resolveTypeName(m.returnType) else "Unit") else null
                }
            }
        }
        // Stack class methods
        if (recvType != null && classes.containsKey(recvType)) {
            if (method == "copy") return recvType       // .copy() → T (stack copy)
            if (method == "toHeap") return "${recvType}*" // .toHeap() → Heap<T>
        }
        // ArrayList methods
        if (recvType.endsWith("ArrayList")) {
            val elemKt = recvType.removeSuffix("ArrayList")
            return when (method) {
                "get"  -> elemKt
                "size" -> "Int"
                "add", "set", "removeAt", "clear", "free" -> "Unit"
                else -> null
            }
        }
        // HashMap methods
        if (recvType.endsWith("HashMap") && !recvType.endsWith("ArrayList")) {
            val body = recvType.removeSuffix("HashMap")
            val kvStr = hashMapConstructorKV("${body}HashMap")
            if (kvStr != null) {
                val (_, valKt) = hashMapKV(kvStr)
                return when (method) {
                    "get"         -> valKt
                    "containsKey" -> "Boolean"
                    "remove"      -> "Boolean"
                    "size"        -> "Int"
                    "put", "clear", "free" -> "Unit"
                    else -> null
                }
            }
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
            if (m != null) return if (m.returnType != null) resolveTypeName(m.returnType) else "Unit"
        }
        // Extension function on non-class type
        val extFun = extensionFuns[recvType]?.find { it.name == method }
        if (extFun != null) return if (extFun.returnType != null) resolveTypeName(extFun.returnType) else "Unit"
        return null
    }

    private fun inferDotType(e: DotExpr): String? {
        if (e.obj is NameExpr && enums.containsKey(e.obj.name)) return e.obj.name
        if (e.obj is NameExpr && objects.containsKey(e.obj.name)) {
            val prop = objects[e.obj.name]?.props?.find { it.first == e.name }
            return if (prop != null) resolveTypeName(prop.second) else null
        }
        val recvType = inferExprType(e.obj) ?: return null
        if (e.name == "size" && recvType.endsWith("Array")) return "Int"
        if (e.name == "size" && recvType.endsWith("ArrayList")) return "Int"
        if (e.name == "size" && recvType.endsWith("HashMap")) return "Int"
        if (e.name == "length" && recvType == "String") return "Int"
        // Heap pointer field access → look up in base class
        val heapBase = heapClassName(recvType)
        if (heapBase != null) {
            val ci = classes[heapBase] ?: return null
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
        // HashMap: map[key] → value type
        if (t.endsWith("HashMap")) {
            val kv = hashMapConstructorKV(t)
            if (kv != null) {
                val (_, valKt) = hashMapKV(kv)
                return valKt
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
            if (isFuncType(resolved)) {
                parts += cFuncPtrDecl(resolved, p.name)
            } else if (isArrayType(resolved)) {
                parts += "${cTypeStr(resolved)} ${p.name}"
                parts += "int32_t ${p.name}\$len"
            } else if (p.type.nullable) {
                parts += "${cTypeStr(resolved)} ${p.name}"
                parts += "bool ${p.name}\$has"
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
        else -> {
            // Class array types: "Vec2Array" → "game_Vec2*" (pointer to element)
            if (t.endsWith("Array") && !t.endsWith("ArrayList") && t.length > 5) {
                val elem = t.removeSuffix("Array")
                if (classArrayTypes.contains(elem)) return "${pfx(elem)}*"
            }
            // ArrayList types: "IntArrayList" → "kt_IntArrayList", "Vec2ArrayList" → "game_Vec2ArrayList"
            if (t.endsWith("ArrayList")) {
                val elem = t.removeSuffix("ArrayList")
                return "${arrayListCPrefix(elem)}ArrayList"
            }
            // HashMap types: "IntIntHashMap" → "kt_IntIntHashMap"
            if (t.endsWith("HashMap")) {
                val kv = hashMapConstructorKV(t)
                if (kv != null) return hashMapCName(kv)
            }
            pfx(t)   // class/enum/object type
        }
    }

    /** C name prefix for ArrayList element type: primitives→"kt_", classes→pfx() */
    private fun arrayListCPrefix(elemKt: String): String = when (elemKt) {
        "Int", "Long", "Float", "Double", "Boolean", "Char", "String" -> "kt_${elemKt}"
        else -> pfx(elemKt)
    }

    /** C element type for an ArrayList given its Kotlin element type */
    private fun arrayListElemCType(elemKt: String): String = cTypeStr(elemKt)

    private fun resolveTypeName(t: TypeRef?): String {
        if (t == null) return "Int"
        // Function type: (P1, P2) -> R → "Fun(P1,P2)->R"
        if (t.funcParams != null) {
            val params = t.funcParams.joinToString(",") { resolveTypeName(it) }
            val ret = resolveTypeName(t.funcReturn)
            return "Fun($params)->$ret"
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
        if ((t.name == "ArrayList" || t.name == "MutableList") && t.typeArgs.isNotEmpty()) {
            val elem = t.typeArgs[0].name
            arrayListElemTypes.add(elem)
            return "${elem}ArrayList"
        }
        if (t.name == "Pointer" && t.typeArgs.isNotEmpty()) {
            return "${t.typeArgs[0].name}*"
        }
        // Heap<MyClass> → "MyClass*"; Heap<MyClass?> → "MyClass*#" (value-nullable)
        if (t.name == "Heap" && t.typeArgs.isNotEmpty()) {
            val inner = t.typeArgs[0]
            return if (inner.nullable) "${inner.name}*#" else "${inner.name}*"
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
        else -> "{0}"
    }

    /** True if the internal type name represents an array (IntArray, LongArray, Vec2Array, etc.) */
    private fun isArrayType(t: String): Boolean =
        t.endsWith("Array") && !t.endsWith("ArrayList")

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
                // ArrayList types: "IntArrayList" → "int32_t", "Vec2ArrayList" → "game_Vec2"
                if (arrType.endsWith("ArrayList")) {
                    val elem = arrType.removeSuffix("ArrayList")
                    return cTypeStr(elem)
                }
                // Class array: "Vec2Array" → element type "game_Vec2"
                if (arrType.endsWith("Array") && arrType.length > 5) {
                    val elem = arrType.removeSuffix("Array")
                    if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return pfx(elem)
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
                // ArrayList types: "IntArrayList" → "Int", "Vec2ArrayList" → "Vec2"
                if (arrType.endsWith("ArrayList")) {
                    return arrType.removeSuffix("ArrayList")
                }
                // Class array: "Vec2Array" → element Kotlin type "Vec2"
                if (arrType.endsWith("Array") && arrType.length > 5) {
                    val elem = arrType.removeSuffix("Array")
                    if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return elem
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

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
class CCodeGen(private val file: KtFile) {

    // ── Package prefix ───────────────────────────────────────────────
    private val prefix: String = file.pkg?.replace('.', '_')?.plus("_") ?: ""
    private fun pfx(name: String): String = if (name == "main") name else "$prefix$name"

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

    private val classes  = mutableMapOf<String, ClassInfo>()
    private val enums    = mutableMapOf<String, EnumInfo>()
    private val objects  = mutableMapOf<String, ObjInfo>()
    private val funSigs  = mutableMapOf<String, FunSig>()
    private val extensionFuns = mutableMapOf<String, MutableList<FunDecl>>()

    // Track class/enum types used in Array<T> so we emit KT_ARRAY_DEF for them
    private val classArrayTypes = mutableSetOf<String>()

    // Track ArrayList types used so we emit struct + methods for them
    // Each entry is the element Kotlin type, e.g. "Int", "String", "Vec2"
    private val arrayListElemTypes = mutableSetOf<String>()

    // ── Per-scope variable → type mapping ────────────────────────────
    private val scopes = ArrayDeque<MutableMap<String, String>>()
    private fun pushScope() { scopes.addLast(mutableMapOf()) }
    private fun popScope()  { scopes.removeLast() }
    private fun defineVar(name: String, type: String) { scopes.last()[name] = type }
    private fun lookupVar(name: String): String? { for (i in scopes.indices.reversed()) { scopes[i][name]?.let { return it } }; return null }

    // ── Current class context (when generating methods) ──────────────
    private var currentClass: String? = null
    private var selfIsPointer = true

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

        // Imports → #include
        for (imp in file.imports) {
            val path = imp.replace('.', '_')
            hdr.appendLine("#include \"$path/$path.h\"")
        }
        if (file.imports.isNotEmpty()) hdr.appendLine()

        // Pre-scan for Array<T> type references to discover class array types early
        scanForClassArrayTypes()

        // Emit struct/enum/object declarations first (defines the element types)
        for (d in file.decls) when (d) {
            is ClassDecl  -> emitClass(d)
            is EnumDecl   -> emitEnum(d)
            is ObjectDecl -> emitObject(d)
            else -> {}
        }

        // Emit ArrayList struct + methods for each element type used
        for (elem in arrayListElemTypes) emitArrayList(elem)

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
        for (d in file.decls) collectDecl(d)
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
            }
            is EnumDecl  -> enums[d.name] = EnumInfo(d.name, d.entries)
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
                // Recurse into args
                for (arg in e.args) scanExpr(arg.expr)
            }
        }
        fun scanStmt(s: Stmt) {
            when (s) {
                is VarDeclStmt -> { checkType(s.type); scanExpr(s.init) }
                is ExprStmt -> scanExpr(s.expr)
                is ForStmt -> { scanExpr(s.iter); s.body.stmts.forEach(::scanStmt) }
                is WhileStmt -> s.body.stmts.forEach(::scanStmt)
                is DoWhileStmt -> s.body.stmts.forEach(::scanStmt)
                is ReturnStmt -> scanExpr(s.value)
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
            if (isArrayType(resolved)) {
                hdr.appendLine("    ${cTypeStr(resolved)} $name;")
                hdr.appendLine("    int32_t ${name}_len;")
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
        if (ci.bodyProps.isEmpty() && ci.ctorProps.none { isArrayType(resolveTypeName(it.second)) }) {
            impl.appendLine("    return ($cName){${ci.ctorProps.joinToString(", ") { it.first }}};")
        } else {
            impl.appendLine("    $cName self = {0};")
            for ((name, type) in ci.ctorProps) {
                val resolved = resolveTypeName(type)
                if (isArrayType(resolved)) {
                    impl.appendLine("    self.$name = $name;")
                    impl.appendLine("    self.${name}_len = ${name}_len;")
                } else {
                    impl.appendLine("    self.$name = $name;")
                }
            }
            for (bp in ci.bodyProps) {
                if (bp.init != null) impl.appendLine("    self.${bp.name} = ${genExpr(bp.init)};")
            }
            impl.appendLine("    return self;")
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
        hdr.appendLine("void ${cName}_toString($cName self, kt_StrBuf* sb);")
        impl.appendLine("void ${cName}_toString($cName self, kt_StrBuf* sb) {")
        impl.appendLine("    kt_sb_append_cstr(sb, \"$ktName(\");")
        for ((i, prop) in ci.props.withIndex()) {
            val (name, type) = prop
            val t = resolveTypeName(type)
            if (i > 0) impl.appendLine("    kt_sb_append_cstr(sb, \", \");")
            impl.appendLine("    kt_sb_append_cstr(sb, \"$name=\");")
            impl.appendLine("    ${genSbAppend("sb", "self.$name", t)}")
        }
        impl.appendLine("    kt_sb_append_char(sb, ')');")
        impl.appendLine("}")
        impl.appendLine()
    }

    private fun emitMethod(className: String, f: FunDecl) {
        val cClass = pfx(className)
        val cRet = if (f.returnType != null) cType(f.returnType) else "void"
        val selfParam = "$cClass* self"
        val extraParams = expandParams(f.params)
        val allParams = if (extraParams.isEmpty()) selfParam else "$selfParam, $extraParams"

        hdr.appendLine("$cRet ${cClass}_${f.name}($allParams);")
        impl.appendLine("$cRet ${cClass}_${f.name}($allParams) {")

        pushScope()
        for (p in f.params) defineVar(p.name, resolveTypeName(p.type))
        // class props accessible via self->
        val ci = classes[className]
        if (ci != null) for ((name, type) in ci.props) defineVar(name, resolveTypeName(type))

        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
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
        val selfParam = if (isClassType) "$cRecvType* self" else "$cRecvType self"
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
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
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
            if (m.body != null) for (s in m.body.stmts) emitStmt(s, "    ")
            popScope()
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
        hdr.appendLine("void ${typeName}_add($typeName* self, $elemC elem);")
        impl.appendLine("void ${typeName}_add($typeName* self, $elemC elem) {")
        impl.appendLine("    if (self->len >= self->cap) {")
        impl.appendLine("        self->cap = self->cap > 0 ? self->cap * 2 : 4;")
        impl.appendLine("        self->ptr = ($elemC*)realloc(self->ptr, sizeof($elemC) * (size_t)self->cap);")
        impl.appendLine("    }")
        impl.appendLine("    self->ptr[self->len++] = elem;")
        impl.appendLine("}")
        impl.appendLine()

        // get
        hdr.appendLine("$elemC ${typeName}_get($typeName* self, int32_t idx);")
        impl.appendLine("$elemC ${typeName}_get($typeName* self, int32_t idx) {")
        impl.appendLine("    return self->ptr[idx];")
        impl.appendLine("}")
        impl.appendLine()

        // set
        hdr.appendLine("void ${typeName}_set($typeName* self, int32_t idx, $elemC v);")
        impl.appendLine("void ${typeName}_set($typeName* self, int32_t idx, $elemC v) {")
        impl.appendLine("    self->ptr[idx] = v;")
        impl.appendLine("}")
        impl.appendLine()

        // removeAt
        hdr.appendLine("void ${typeName}_removeAt($typeName* self, int32_t idx);")
        impl.appendLine("void ${typeName}_removeAt($typeName* self, int32_t idx) {")
        impl.appendLine("    for (int32_t _i = idx; _i < self->len - 1; _i++) self->ptr[_i] = self->ptr[_i + 1];")
        impl.appendLine("    self->len--;")
        impl.appendLine("}")
        impl.appendLine()

        // clear
        hdr.appendLine("void ${typeName}_clear($typeName* self);")
        impl.appendLine("void ${typeName}_clear($typeName* self) {")
        impl.appendLine("    self->len = 0;")
        impl.appendLine("}")
        impl.appendLine()

        // free
        hdr.appendLine("void ${typeName}_free($typeName* self);")
        impl.appendLine("void ${typeName}_free($typeName* self) {")
        impl.appendLine("    free(self->ptr);")
        impl.appendLine("    self->ptr = NULL;")
        impl.appendLine("    self->len = 0;")
        impl.appendLine("    self->cap = 0;")
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

        val cRet  = if (isMain) "int" else if (f.returnType != null) cType(f.returnType) else "void"
        val cName = if (isMain) "main" else pfx(f.name)
        val params = when {
            isMainWithArgs -> "int argc, char** argv"
            isMain         -> "void"
            else           -> expandParams(f.params)
        }

        hdr.appendLine("$cRet $cName($params);")
        impl.appendLine("$cRet $cName($params) {")

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
            impl.appendLine("    int32_t ${argName}_len = \$nargs;")
            defineVar(argName, "StringArray")
        } else {
            for (p in f.params) defineVar(p.name, resolveTypeName(p.type))
        }
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ")
        if (isMain) impl.appendLine("    return 0;")
        popScope()

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
        }
    }

    private fun emitBlock(b: Block, ind: String, insideMethod: Boolean = false) {
        for (s in b.stmts) emitStmt(s, "$ind    ", insideMethod)
    }

    // ── var / val ────────────────────────────────────────────────────

    private fun emitVarDecl(s: VarDeclStmt, ind: String, method: Boolean) {
        val t = if (s.type != null) resolveTypeName(s.type) else (inferExprType(s.init) ?: "Int")
        defineVar(s.name, t)
        val ct = cTypeStr(t)
        // Don't const class types, Pointer, typed pointers, or ArrayList — they need mutable addresses
        val qual = if (!s.mutable && !classes.containsKey(t) && t != "Pointer" && !t.endsWith("*") && !t.endsWith("ArrayList")) "const " else ""
        if (s.init != null) {
            // Special case: intArrayOf / longArrayOf etc.
            val arrayInit = tryArrayOfInit(s.name, s.init, ct, t, ind)
            if (arrayInit != null) { impl.appendLine(arrayInit); return }
            val expr = genExpr(s.init)
            flushPreStmts(ind)
            impl.appendLine("$ind$qual$ct ${s.name} = $expr;")
        } else {
            impl.appendLine("$ind$ct ${s.name} = ${defaultVal(t)};")
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
        return "${ind}$elemType ${varName}[] = {$args};\n${ind}const int32_t ${varName}_len = $n;"
    }

    // ── assignment ───────────────────────────────────────────────────

    private fun emitAssign(s: AssignStmt, ind: String, method: Boolean) {
        val target = genLValue(s.target, method)
        val value = genExpr(s.value)
        flushPreStmts(ind)
        impl.appendLine("$ind$target ${s.op} $value;")
    }

    // ── return ───────────────────────────────────────────────────────

    private fun emitReturn(s: ReturnStmt, ind: String) {
        if (s.value != null) {
            val expr = genExpr(s.value)
            flushPreStmts(ind)
            impl.appendLine("${ind}return $expr;")
        } else {
            impl.appendLine("${ind}return;")
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

    private fun emitIfStmt(e: IfExpr, ind: String, method: Boolean) {
        impl.appendLine("${ind}if (${genExpr(e.cond)}) {")
        emitBlock(e.then, ind, method)
        if (e.els != null) {
            // check for else-if chain
            val single = e.els.stmts.singleOrNull()
            if (single is ExprStmt && single.expr is IfExpr) {
                impl.appendLine("$ind} else ")
                emitIfStmt(single.expr, ind, method)
                return
            }
            impl.appendLine("$ind} else {")
            emitBlock(e.els, ind, method)
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
        when {
            // for (i in a..b)   inclusive range
            iter is BinExpr && iter.op == ".." -> {
                impl.appendLine("${ind}for (int32_t ${s.varName} = ${genExpr(iter.left)}; ${s.varName} <= ${genExpr(iter.right)}; ${s.varName}++) {")
                pushScope(); defineVar(s.varName, "Int")
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
            // for (i in a until b)
            iter is BinExpr && iter.op == "until" -> {
                impl.appendLine("${ind}for (int32_t ${s.varName} = ${genExpr(iter.left)}; ${s.varName} < ${genExpr(iter.right)}; ${s.varName}++) {")
                pushScope(); defineVar(s.varName, "Int")
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
            // for (i in a downTo b)
            iter is BinExpr && iter.op == "downTo" -> {
                impl.appendLine("${ind}for (int32_t ${s.varName} = ${genExpr(iter.left)}; ${s.varName} >= ${genExpr(iter.right)}; ${s.varName}--) {")
                pushScope(); defineVar(s.varName, "Int")
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
            // for (item in array/arrayList)  — iterate over elements
            else -> {
                val arrExpr = genExpr(iter)
                val idx = tmp()
                val arrType = inferExprType(iter)
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
                    // Array: use _len and direct indexing
                    val elemType = arrayElementCType(arrType)
                    impl.appendLine("${ind}for (int32_t $idx = 0; $idx < ${arrExpr}_len; $idx++) {")
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
        is NullLit   -> "KT_NULL_VAL"
        is ThisExpr  -> if (selfIsPointer) "(*self)" else "self"
        is NameExpr  -> genName(e)
        is BinExpr   -> genBin(e)
        is PrefixExpr  -> "(${e.op}${genExpr(e.expr)})"
        is PostfixExpr -> "(${genExpr(e.expr)}${e.op})"
        is CallExpr    -> genCall(e)
        is DotExpr     -> genDot(e)
        is SafeDotExpr -> genSafeDot(e)
        is IndexExpr   -> {
            val objType = inferExprType(e.obj)
            if (objType != null && (objType.endsWith("*") || isArrayType(objType))) {
                // Typed pointer or array: direct indexing
                "${genExpr(e.obj)}[${genExpr(e.index)}]"
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        }
        is IfExpr      -> genIfExpr(e)
        is WhenExpr    -> genWhenExpr(e)
        is NotNullExpr -> "(${genExpr(e.expr)}).val"
        is ElvisExpr   -> "((${genExpr(e.left)}).has ? (${genExpr(e.left)}).val : ${genExpr(e.right)})"
        is StrTemplateExpr -> genStrTemplate(e)
        is IsCheckExpr -> "/* is-check */ true"
        is CastExpr    -> "(${cType(e.type)})(${genExpr(e.expr)})"
    }

    // ── names (may resolve to enum, object field, self->field) ───────

    private fun genName(e: NameExpr): String {
        // Check if it's a known variable in scope
        if (lookupVar(e.name) != null) {
            if (currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
                return "self->${e.name}"
            }
            return e.name
        }
        return e.name
    }

    // ── binary ───────────────────────────────────────────────────────

    private fun genBin(e: BinExpr): String {
        val lt = inferExprType(e.left)
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
                    val elemC = cTypeStr(e.typeArgs[0].name)
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
        return "${pfx(name)}($expandedArgs)"
    }

    /** Expand call arguments: if the matching param is an array type, emit (arg, arg_len). */
    private fun expandCallArgs(args: List<Arg>, params: List<Param>?): String {
        val parts = mutableListOf<String>()
        for ((i, arg) in args.withIndex()) {
            val expr = genExpr(arg.expr)
            val paramType = params?.getOrNull(i)?.let { resolveTypeName(it.type) }
            if (paramType != null && isArrayType(paramType)) {
                parts += expr
                parts += "${expr}_len"
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

    private fun genMethodCall(dot: DotExpr, args: List<Arg>): String {
        val recvType = inferExprType(dot.obj)
        val recv = genExpr(dot.obj)
        val method = dot.name
        val argStr = args.joinToString(", ") { genExpr(it.expr) }

        // Built-in methods
        when (method) {
            "toString" -> return genToString(recv, recvType ?: "Int")
            "toInt" -> return "((int32_t)($recv))"
            "toLong" -> return "((int64_t)($recv))"
            "toFloat" -> return "((float)($recv))"
            "toDouble" -> return "((double)($recv))"
        }

        // Array .size → name_len
        if (method == "size" && recvType != null && isArrayType(recvType)) return "${recv}_len"

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

        // ArrayList .size via property access (handled by genDot below)
        // Class method or extension function on class type
        if (recvType != null && classes.containsKey(recvType)) {
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

    private fun genSafeMethodCall(dot: SafeDotExpr, args: List<Arg>): String {
        val recv = genExpr(dot.obj)
        return "($recv.has ? /* safe call */ 0 : 0)"  // simplified
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
        // Array .size → name_len
        if (e.name == "size" && recvType != null && isArrayType(recvType)) return "${recv}_len"
        // ArrayList .size
        if (e.name == "size" && recvType?.endsWith("ArrayList") == true) return "$recv.len"
        if (e.name == "length" && recvType == "String") return "$recv.len"

        return "$recv.${e.name}"
    }

    private fun genSafeDot(e: SafeDotExpr): String {
        val recv = genExpr(e.obj)
        return "($recv.has ? $recv.val.${e.name} : 0)"   // simplified nullable access
    }

    // ── if expression (as C ternary or temp) ─────────────────────────

    private fun genIfExpr(e: IfExpr): String {
        if (e.els == null) return "(${genExpr(e.cond)} ? ${genBlockExpr(e.then)} : 0)"
        return "(${genExpr(e.cond)} ? ${genBlockExpr(e.then)} : ${genBlockExpr(e.els)})"
    }

    private fun genBlockExpr(b: Block): String {
        // If block has a single ExprStmt, use its expr directly
        val last = b.stmts.lastOrNull()
        if (b.stmts.size == 1 && last is ExprStmt) return genExpr(last.expr)
        if (last is ReturnStmt && last.value != null) return genExpr(last.value)
        return "0"   // fallback for complex blocks — should use temp vars in full impl
    }

    // ── when expression (nested ternary) ─────────────────────────────

    private fun genWhenExpr(e: WhenExpr): String {
        val sb = StringBuilder()
        for (br in e.branches) {
            if (br.conds == null) {
                sb.append(genBlockExpr(br.body))
            } else {
                val cond = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                sb.append("($cond) ? ${genBlockExpr(br.body)} : ")
            }
        }
        return sb.toString()
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
        preStmts += "const int32_t ${t}_len = $n;"
        return t
    }

    private fun genNewArray(elemCType: String, args: List<Arg>): String {
        val size = if (args.isNotEmpty()) genExpr(args[0].expr) else "0"
        val t = tmp()
        preStmts += "$elemCType ${t}[$size];"
        preStmts += "memset($t, 0, sizeof($elemCType) * (size_t)($size));"
        preStmts += "const int32_t ${t}_len = $size;"
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
                    "self->${e.name}"
                else e.name
            }
            is DotExpr -> {
                if (e.obj is NameExpr && objects.containsKey(e.obj.name))
                    "${pfx(e.obj.name)}.${e.name}"
                else "${genExpr(e.obj)}.${e.name}"
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
        is NotNullExpr -> inferExprType(e.expr)   // strip nullable
        is ElvisExpr -> inferExprType(e.left) ?: inferExprType(e.right)
        is IsCheckExpr -> "Boolean"
        is CastExpr -> e.type.name
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
        if (e.name == "length" && recvType == "String") return "Int"
        val ci = classes[recvType] ?: return null
        val prop = ci.props.find { it.first == e.name }
        return if (prop != null) resolveTypeName(prop.second) else null
    }

    private fun inferDotTypeSafe(e: SafeDotExpr): String? = null
    private fun inferIndexType(e: IndexExpr): String? {
        val t = inferExprType(e.obj) ?: return null
        // Typed pointer: "Int*" → "Int"
        if (t.endsWith("*")) return t.dropLast(1)
        return arrayElementKtType(t)
    }

    // ═══════════════════════════ C type mapping ═══════════════════════

    /** Expand ctor params: array params become (T* name, int32_t name_len) pairs. */
    private fun expandCtorParams(props: List<Pair<String, TypeRef>>): String {
        val parts = mutableListOf<String>()
        for ((name, type) in props) {
            val resolved = resolveTypeName(type)
            if (isArrayType(resolved)) {
                parts += "${cTypeStr(resolved)} $name"
                parts += "int32_t ${name}_len"
            } else {
                parts += "${cType(type)} $name"
            }
        }
        return parts.joinToString(", ")
    }

    private fun cType(t: TypeRef): String {
        val resolved = resolveTypeName(t)
        val base = cTypeStr(resolved)
        return if (t.nullable) "kt_Nullable_${t.name}" else base
    }

    /** Expand a parameter list: array params become (T* name, int32_t name_len) pairs. */
    private fun expandParams(params: List<Param>): String {
        val parts = mutableListOf<String>()
        for (p in params) {
            val resolved = resolveTypeName(p.type)
            if (isArrayType(resolved)) {
                parts += "${cTypeStr(resolved)} ${p.name}"
                parts += "int32_t ${p.name}_len"
            } else {
                parts += "${cType(p.type)} ${p.name}"
            }
        }
        return parts.joinToString(", ")
    }

    private fun cTypeStr(t: String): String = when {
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
        return t.name
    }

    private fun defaultVal(t: String): String = when (t) {
        "Int", "Long" -> "0"
        "Float"  -> "0.0f"
        "Double" -> "0.0"
        "Boolean" -> "false"
        "Char"   -> "'\\0'"
        "String" -> "kt_str(\"\")"
        "Pointer" -> "NULL"
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

    private fun printfFmt(t: String): String = when (t) {
        "Int"     -> "%\" PRId32 \""
        "Long"    -> "%\" PRId64 \""
        "Float"   -> "%f"
        "Double"  -> "%f"
        "Boolean" -> "%s"
        "Char"    -> "%c"
        "String"  -> "%.*s"
        "Pointer" -> "%p"
        else      -> "%.*s"       // assume toString → kt_String
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

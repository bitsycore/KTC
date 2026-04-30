package com.bitsycore

class Parser(private val tokens: List<Token>) {
    private var pos = 0
    private var nesting = 0          // depth inside () [] {}

    // ═══════════════════════════ Public entry ═════════════════════════

    fun parseFile(): KtFile {
        skipNL()
        val pkg = if (at(TokenType.PACKAGE)) { advance(); parseQualifiedName().also { skipTerminator() } } else null
        val imports = mutableListOf<String>()
        while (at(TokenType.IMPORT)) { advance(); imports += parseQualifiedName(); skipTerminator() }
        val decls = mutableListOf<Decl>()
        while (!at(TokenType.EOF)) { skipNL(); if (at(TokenType.EOF)) break; decls += parseDecl() }
        return KtFile(pkg, imports, decls)
    }

    // ═══════════════════════════ Declarations ═════════════════════════

    private fun parseDecl(): Decl {
        skipNL()
        return when {
            at(TokenType.FUN)    -> parseFunDecl()
            at(TokenType.DATA)   -> { advance(); expect(TokenType.CLASS); parseClassDecl(isData = true) }
            at(TokenType.CLASS)  -> { advance(); parseClassDecl(isData = false) }
            at(TokenType.ENUM)   -> { advance(); expect(TokenType.CLASS); parseEnumDecl() }
            at(TokenType.OBJECT) -> parseObjectDecl()
            at(TokenType.VAL)    -> parsePropDecl(mutable = false)
            at(TokenType.VAR)    -> parsePropDecl(mutable = true)
            at(TokenType.INIT)   -> { advance(); FunDecl("init", emptyList(), null, parseBlock()) }
            else -> error("Expected declaration at ${cur()}")
        }
    }

    // ── fun ──────────────────────────────────────────────────────────

    private fun parseFunDecl(): FunDecl {
        expect(TokenType.FUN)
        val name = expectIdent()
        expect(TokenType.LPAREN); nesting++
        val params = parseParamList()
        expect(TokenType.RPAREN); nesting--
        val retType = if (at(TokenType.COLON)) { advance(); skipNL(); parseTypeRef() } else null
        skipNL()
        val body: Block? = when {
            at(TokenType.LBRACE) -> parseBlock()
            at(TokenType.EQ) -> { advance(); skipNL(); val e = parseExpr(); skipTerminator(); Block(listOf(ReturnStmt(e))) }
            else -> null
        }
        skipTerminator()
        return FunDecl(name, params, retType, body)
    }

    private fun parseParamList(): List<Param> {
        val list = mutableListOf<Param>()
        skipNL()
        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            val name = expectIdent()
            expect(TokenType.COLON); skipNL()
            val type = parseTypeRef()
            val default = if (at(TokenType.EQ)) { advance(); skipNL(); parseExpr() } else null
            list += Param(name, type, default)
            if (at(TokenType.COMMA)) { advance(); skipNL() } else break
        }
        skipNL()
        return list
    }

    // ── class / data class ───────────────────────────────────────────

    private fun parseClassDecl(isData: Boolean): ClassDecl {
        val name = expectIdent()
        val ctorParams = if (at(TokenType.LPAREN)) {
            advance(); nesting++
            val p = parseCtorParams()
            expect(TokenType.RPAREN); nesting--
            p
        } else emptyList()
        skipNL()
        val members = mutableListOf<Decl>()
        val inits = mutableListOf<Block>()
        if (at(TokenType.LBRACE)) {
            advance(); nesting++; skipNL()
            while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                skipNL(); if (at(TokenType.RBRACE)) break
                if (at(TokenType.INIT)) {
                    advance(); inits += parseBlock(); skipTerminator()
                } else {
                    members += parseDecl()
                }
                skipNL()
            }
            expect(TokenType.RBRACE); nesting--
        }
        skipTerminator()
        return ClassDecl(name, isData, ctorParams, members, inits)
    }

    private fun parseCtorParams(): List<CtorParam> {
        val list = mutableListOf<CtorParam>()
        skipNL()
        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            var isVal = false; var isVar = false
            if (at(TokenType.VAL)) { isVal = true; advance() }
            else if (at(TokenType.VAR)) { isVar = true; advance() }
            val name = expectIdent()
            expect(TokenType.COLON); skipNL()
            val type = parseTypeRef()
            val default = if (at(TokenType.EQ)) { advance(); skipNL(); parseExpr() } else null
            list += CtorParam(name, type, default, isVal, isVar)
            if (at(TokenType.COMMA)) { advance(); skipNL() } else break
        }
        skipNL()
        return list
    }

    // ── enum class ───────────────────────────────────────────────────

    private fun parseEnumDecl(): EnumDecl {
        val name = expectIdent()
        expect(TokenType.LBRACE); nesting++; skipNL()
        val entries = mutableListOf<String>()
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF) && !at(TokenType.SEMICOLON)) {
            entries += expectIdent()
            if (at(TokenType.COMMA)) { advance(); skipNL() } else break
        }
        skipNL()
        // optional trailing ; and members — skip for now
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) advance()
        expect(TokenType.RBRACE); nesting--
        skipTerminator()
        return EnumDecl(name, entries)
    }

    // ── object ───────────────────────────────────────────────────────

    private fun parseObjectDecl(): ObjectDecl {
        expect(TokenType.OBJECT)
        val name = expectIdent()
        skipNL()
        val members = mutableListOf<Decl>()
        if (at(TokenType.LBRACE)) {
            advance(); nesting++; skipNL()
            while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
                skipNL(); if (at(TokenType.RBRACE)) break
                members += parseDecl(); skipNL()
            }
            expect(TokenType.RBRACE); nesting--
        }
        skipTerminator()
        return ObjectDecl(name, members)
    }

    // ── val / var (top-level or class-level property) ────────────────

    private fun parsePropDecl(mutable: Boolean): PropDecl {
        advance()   // skip val/var
        val name = expectIdent()
        val type = if (at(TokenType.COLON)) { advance(); skipNL(); parseTypeRef() } else null
        val init = if (at(TokenType.EQ)) { advance(); skipNL(); parseExpr() } else null
        skipTerminator()
        return PropDecl(name, type, init, mutable)
    }

    // ═══════════════════════════ Statements ═══════════════════════════

    private fun parseBlock(): Block {
        expect(TokenType.LBRACE); nesting++; skipNL()
        val stmts = mutableListOf<Stmt>()
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            skipNL(); if (at(TokenType.RBRACE)) break
            stmts += parseStmt()
            skipTerminator()
            skipNL()
        }
        expect(TokenType.RBRACE); nesting--
        return Block(stmts)
    }

    private fun parseStmt(): Stmt {
        skipNL()
        return when {
            at(TokenType.VAL) -> parseVarDeclStmt(mutable = false)
            at(TokenType.VAR) -> parseVarDeclStmt(mutable = true)
            at(TokenType.RETURN) -> { advance(); val v = if (atExprStart()) parseExpr() else null; ReturnStmt(v) }
            at(TokenType.FOR)    -> parseForStmt()
            at(TokenType.WHILE)  -> parseWhileStmt()
            at(TokenType.DO)     -> parseDoWhileStmt()
            at(TokenType.BREAK)  -> { advance(); BreakStmt }
            at(TokenType.CONTINUE) -> { advance(); ContinueStmt }
            else -> parseExprOrAssignStmt()
        }
    }

    private fun parseVarDeclStmt(mutable: Boolean): VarDeclStmt {
        advance()   // skip val/var
        val name = expectIdent()
        val type = if (at(TokenType.COLON)) { advance(); skipNL(); parseTypeRef() } else null
        val init = if (at(TokenType.EQ)) { advance(); skipNL(); parseExpr() } else null
        return VarDeclStmt(name, type, init, mutable)
    }

    private fun parseExprOrAssignStmt(): Stmt {
        val expr = parseExpr()
        return if (at(TokenType.EQ) || at(TokenType.PLUS_EQ) || at(TokenType.MINUS_EQ) ||
            at(TokenType.STAR_EQ) || at(TokenType.SLASH_EQ) || at(TokenType.PERCENT_EQ)) {
            val op = advance().value; skipNL()
            val value = parseExpr()
            AssignStmt(expr, op, value)
        } else {
            ExprStmt(expr)
        }
    }

    // ── for ──────────────────────────────────────────────────────────

    private fun parseForStmt(): ForStmt {
        expect(TokenType.FOR)
        expect(TokenType.LPAREN); nesting++; skipNL()
        val varName = expectIdent()
        expect(TokenType.IN); skipNL()
        val iter = parseExpr()
        expect(TokenType.RPAREN); nesting--; skipNL()
        val body = parseBlock()
        return ForStmt(varName, iter, body)
    }

    // ── while / do-while ─────────────────────────────────────────────

    private fun parseWhileStmt(): WhileStmt {
        expect(TokenType.WHILE)
        expect(TokenType.LPAREN); nesting++; skipNL()
        val cond = parseExpr()
        expect(TokenType.RPAREN); nesting--; skipNL()
        return WhileStmt(cond, parseBlock())
    }

    private fun parseDoWhileStmt(): DoWhileStmt {
        expect(TokenType.DO); skipNL()
        val body = parseBlock(); skipNL()
        expect(TokenType.WHILE)
        expect(TokenType.LPAREN); nesting++; skipNL()
        val cond = parseExpr()
        expect(TokenType.RPAREN); nesting--
        return DoWhileStmt(body, cond)
    }

    // ═══════════════════════════ Expressions (Pratt) ═════════════════

    fun parseExpr(minPrec: Int = 0): Expr {
        var left = parsePrefixExpr()
        while (true) {
            left = parsePostfixChain(left)
            skipNL()
            val prec = binaryPrec()
            if (prec < 0 || prec < minPrec) break

            when (cur().type) {
                // ── elvis ?: (right-assoc) ──
                TokenType.QUESTION_COLON -> {
                    advance(); skipNL()
                    val right = parseExpr(prec)      // same prec → right-assoc
                    left = ElvisExpr(left, right)
                }
                // ── is / !is ──
                TokenType.IS -> {
                    advance(); skipNL()
                    left = IsCheckExpr(left, parseTypeRef(), negated = false)
                }
                TokenType.EXCL -> {
                    // !in  or  !is  — peek ahead
                    if (peek().type == TokenType.IS) {
                        advance(); advance(); skipNL()
                        left = IsCheckExpr(left, parseTypeRef(), negated = true)
                    } else if (peek().type == TokenType.IN) {
                        advance(); advance(); skipNL()
                        left = BinExpr(left, "!in", parseExpr(prec + 1))
                    } else break
                }
                TokenType.IN -> {
                    advance(); skipNL()
                    left = BinExpr(left, "in", parseExpr(prec + 1))
                }
                TokenType.AS -> {
                    advance(); skipNL()
                    left = CastExpr(left, parseTypeRef())
                }
                // ── infix identifiers: until, downTo, step ──
                TokenType.IDENT -> {
                    val name = cur().value
                    if (name !in INFIX_IDS) break
                    advance(); skipNL()
                    left = BinExpr(left, name, parseExpr(prec + 1))
                }
                // ── standard binary ops ──
                else -> {
                    val op = advance(); skipNL()
                    val rightPrec = prec + 1       // left-assoc
                    left = BinExpr(left, op.value, parseExpr(rightPrec))
                }
            }
        }
        return left
    }

    // ── Prefix unary ─────────────────────────────────────────────────

    private fun parsePrefixExpr(): Expr {
        return when {
            at(TokenType.MINUS) || at(TokenType.EXCL) || at(TokenType.PLUS_PLUS) || at(TokenType.MINUS_MINUS) -> {
                // guard: EXCL followed by IN/IS is NOT a prefix — fall through
                if (at(TokenType.EXCL) && (peek().type == TokenType.IN || peek().type == TokenType.IS)) {
                    parsePrimary()
                } else {
                    val op = advance().value; skipNL()
                    PrefixExpr(op, parsePrefixExpr())
                }
            }
            at(TokenType.PLUS) -> { advance(); skipNL(); parsePrefixExpr() }   // unary + is no-op
            else -> parsePrimary()
        }
    }

    // ── Postfix chain: . ?. () [] ++ -- !! ───────────────────────────

    private fun parsePostfixChain(start: Expr): Expr {
        var e = start
        loop@ while (true) {
            e = when {
                at(TokenType.DOT) -> {
                    advance(); skipNL()
                    DotExpr(e, expectIdent())
                }
                at(TokenType.QUESTION_DOT) -> {
                    advance(); skipNL()
                    SafeDotExpr(e, expectIdent())
                }
                at(TokenType.LPAREN) -> {
                    advance(); nesting++; skipNL()
                    val args = parseArgList()
                    expect(TokenType.RPAREN); nesting--
                    CallExpr(e, args)
                }
                at(TokenType.LBRACKET) -> {
                    advance(); nesting++; skipNL()
                    val idx = parseExpr()
                    expect(TokenType.RBRACKET); nesting--
                    IndexExpr(e, idx)
                }
                at(TokenType.EXCL_EXCL) -> { advance(); NotNullExpr(e) }
                at(TokenType.PLUS_PLUS)  -> { advance(); PostfixExpr(e, "++") }
                at(TokenType.MINUS_MINUS) -> { advance(); PostfixExpr(e, "--") }
                else -> break@loop
            }
        }
        return e
    }

    private fun parseArgList(): List<Arg> {
        val list = mutableListOf<Arg>()
        skipNL()
        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            // Try named arg:  name = expr
            val name = if (at(TokenType.IDENT) && peek().type == TokenType.EQ) {
                val n = advance().value; advance(); n      // consume ident and =
            } else null
            skipNL()
            list += Arg(name, parseExpr())
            if (at(TokenType.COMMA)) { advance(); skipNL() } else break
        }
        skipNL()
        return list
    }

    // ── Primary ──────────────────────────────────────────────────────

    private fun parsePrimary(): Expr {
        skipNL()
        return when {
            at(TokenType.INT_LIT)    -> IntLit(advance().value.toLong())
            at(TokenType.LONG_LIT)   -> LongLit(advance().value.removeSuffix("L").toLong())
            at(TokenType.FLOAT_LIT)  -> FloatLit(advance().value.removeSuffix("f").removeSuffix("F").toDouble())
            at(TokenType.DOUBLE_LIT) -> DoubleLit(advance().value.toDouble())
            at(TokenType.CHAR_LIT)   -> CharLit(advance().value[0])
            at(TokenType.TRUE)       -> { advance(); BoolLit(true) }
            at(TokenType.FALSE)      -> { advance(); BoolLit(false) }
            at(TokenType.NULL)       -> { advance(); NullLit }
            at(TokenType.THIS)       -> { advance(); ThisExpr }
            at(TokenType.STRING_LIT) -> StrLit(advance().value)
            at(TokenType.STR_TMPL_START) -> parseStringTemplate()
            at(TokenType.IDENT)      -> NameExpr(advance().value)
            at(TokenType.IF)         -> parseIfExpr()
            at(TokenType.WHEN)       -> parseWhenExpr()
            at(TokenType.LPAREN)     -> { advance(); nesting++; skipNL(); val e = parseExpr(); skipNL(); expect(TokenType.RPAREN); nesting--; e }
            else -> error("Expected expression, got ${cur()}")
        }
    }

    // ── String template ──────────────────────────────────────────────

    private fun parseStringTemplate(): StrTemplateExpr {
        expect(TokenType.STR_TMPL_START)
        val parts = mutableListOf<StrPart>()
        while (!at(TokenType.STR_TMPL_END) && !at(TokenType.EOF)) {
            when {
                at(TokenType.STR_TMPL_PART) -> parts += LitPart(advance().value)
                at(TokenType.TMPL_REF)      -> parts += ExprPart(NameExpr(advance().value))
                at(TokenType.TMPL_EXPR_START) -> {
                    advance()    // skip ${
                    parts += ExprPart(parseExpr())
                    expect(TokenType.TMPL_EXPR_END)
                }
                else -> break
            }
        }
        expect(TokenType.STR_TMPL_END)
        return StrTemplateExpr(parts)
    }

    // ── if (expression / statement) ──────────────────────────────────

    private fun parseIfExpr(): IfExpr {
        expect(TokenType.IF)
        expect(TokenType.LPAREN); nesting++; skipNL()
        val cond = parseExpr()
        expect(TokenType.RPAREN); nesting--; skipNL()
        val thenBlock = if (at(TokenType.LBRACE)) parseBlock() else Block(listOf(parseSingleStmtOrExpr()))
        skipNL()
        val elseBlock = if (at(TokenType.ELSE)) {
            advance(); skipNL()
            if (at(TokenType.IF)) Block(listOf(ExprStmt(parseIfExpr())))
            else if (at(TokenType.LBRACE)) parseBlock()
            else Block(listOf(parseSingleStmtOrExpr()))
        } else null
        return IfExpr(cond, thenBlock, elseBlock)
    }

    /** Parse a single statement when braces are omitted (e.g. `if (c) return x`). */
    private fun parseSingleStmtOrExpr(): Stmt {
        return when {
            at(TokenType.RETURN)   -> { advance(); val v = if (atExprStart()) parseExpr() else null; ReturnStmt(v) }
            at(TokenType.BREAK)    -> { advance(); BreakStmt }
            at(TokenType.CONTINUE) -> { advance(); ContinueStmt }
            else -> ExprStmt(parseExpr())
        }
    }

    // ── when (expression / statement) ────────────────────────────────

    private fun parseWhenExpr(): WhenExpr {
        expect(TokenType.WHEN); skipNL()
        val subject = if (at(TokenType.LPAREN)) {
            advance(); nesting++; skipNL()
            val s = parseExpr()
            expect(TokenType.RPAREN); nesting--; skipNL()
            s
        } else null
        expect(TokenType.LBRACE); nesting++; skipNL()
        val branches = mutableListOf<WhenBranch>()
        while (!at(TokenType.RBRACE) && !at(TokenType.EOF)) {
            skipNL(); if (at(TokenType.RBRACE)) break
            branches += parseWhenBranch()
            skipTerminator(); skipNL()
        }
        expect(TokenType.RBRACE); nesting--
        return WhenExpr(subject, branches)
    }

    private fun parseWhenBranch(): WhenBranch {
        val conds: List<WhenCond>? = if (at(TokenType.ELSE)) { advance(); null }
        else {
            val list = mutableListOf<WhenCond>()
            list += parseWhenCond()
            while (at(TokenType.COMMA)) { advance(); skipNL(); list += parseWhenCond() }
            list
        }
        expect(TokenType.ARROW); skipNL()
        val body = if (at(TokenType.LBRACE)) parseBlock() else Block(listOf(ExprStmt(parseExpr())))
        return WhenBranch(conds, body)
    }

    private fun parseWhenCond(): WhenCond {
        skipNL()
        return when {
            at(TokenType.IS) -> { advance(); skipNL(); IsCond(parseTypeRef()) }
            at(TokenType.EXCL) && peek().type == TokenType.IS -> { advance(); advance(); skipNL(); IsCond(parseTypeRef(), negated = true) }
            at(TokenType.IN) -> { advance(); skipNL(); InCond(parseExpr(PREC_NAMED + 1)) }
            at(TokenType.EXCL) && peek().type == TokenType.IN -> { advance(); advance(); skipNL(); InCond(parseExpr(PREC_NAMED + 1), negated = true) }
            else -> ExprCond(parseExpr())
        }
    }

    // ═══════════════════════════ Type references ══════════════════════

    private fun parseTypeRef(): TypeRef {
        val name = parseQualifiedName()
        val typeArgs = if (at(TokenType.LT)) {
            advance(); nesting++; skipNL()
            val args = mutableListOf(parseTypeRef())
            while (at(TokenType.COMMA)) { advance(); skipNL(); args += parseTypeRef() }
            expect(TokenType.GT); nesting--
            args
        } else emptyList()
        val nullable = if (at(TokenType.QUESTION)) { advance(); true } else false
        return TypeRef(name, nullable, typeArgs)
    }

    // ═══════════════════════════ Precedence table ════════════════════

    companion object {
        val INFIX_IDS = setOf("until", "downTo", "step")
        // Levels — higher binds tighter
        const val PREC_DISJUNCTION  = 1   // ||
        const val PREC_CONJUNCTION  = 2   // &&
        const val PREC_EQUALITY     = 3   // == !=
        const val PREC_COMPARISON   = 4   // < > <= >=
        const val PREC_NAMED        = 5   // in  !in  is  !is
        const val PREC_ELVIS        = 6   // ?:
        const val PREC_INFIX_FN     = 7   // until  downTo  step
        const val PREC_RANGE        = 8   // ..
        const val PREC_ADDITIVE     = 9   // + -
        const val PREC_MULTIPLICATIVE = 10 // * / %
        const val PREC_AS           = 11  // as
    }

    private fun binaryPrec(): Int = when (cur().type) {
        TokenType.PIPE_PIPE     -> PREC_DISJUNCTION
        TokenType.AMP_AMP       -> PREC_CONJUNCTION
        TokenType.EQ_EQ, TokenType.EXCL_EQ -> PREC_EQUALITY
        TokenType.LT, TokenType.GT, TokenType.LT_EQ, TokenType.GT_EQ -> PREC_COMPARISON
        TokenType.IN            -> PREC_NAMED
        TokenType.IS            -> PREC_NAMED
        TokenType.EXCL          -> if (peek().type == TokenType.IN || peek().type == TokenType.IS) PREC_NAMED else -1
        TokenType.QUESTION_COLON -> PREC_ELVIS
        TokenType.IDENT         -> if (cur().value in INFIX_IDS) PREC_INFIX_FN else -1
        TokenType.DOT_DOT       -> PREC_RANGE
        TokenType.PLUS, TokenType.MINUS -> PREC_ADDITIVE
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> PREC_MULTIPLICATIVE
        TokenType.AS            -> PREC_AS
        else -> -1
    }

    // ═══════════════════════════ Helpers ══════════════════════════════

    private fun cur(): Token = tokens[pos]
    private fun peek(): Token = if (pos + 1 < tokens.size) tokens[pos + 1] else tokens.last()
    private fun at(type: TokenType): Boolean = cur().type == type
    private fun advance(): Token = tokens[pos].also { pos++ }

    private fun expect(type: TokenType): Token {
        if (!at(type)) error("Expected $type but got ${cur()}")
        return advance()
    }

    private fun expectIdent(): String {
        if (!at(TokenType.IDENT)) error("Expected identifier but got ${cur()}")
        return advance().value
    }

    private fun parseQualifiedName(): String {
        val sb = StringBuilder(expectIdent())
        while (at(TokenType.DOT) && peek().type == TokenType.IDENT) {
            advance(); sb.append('.').append(advance().value)
        }
        return sb.toString()
    }

    /** Skip newlines (and semicolons) — significant only when nesting==0, but we
     *  always allow skipping them explicitly.  */
    private fun skipNL() {
        while (at(TokenType.NEWLINE) || at(TokenType.SEMICOLON)) advance()
    }

    private fun skipTerminator() {
        // consume at least one newline/semicolon if present (but don't require it)
        while (at(TokenType.NEWLINE) || at(TokenType.SEMICOLON)) advance()
    }

    /** True when the current token could be the start of an expression. */
    private fun atExprStart(): Boolean = when (cur().type) {
        TokenType.INT_LIT, TokenType.LONG_LIT, TokenType.FLOAT_LIT, TokenType.DOUBLE_LIT,
        TokenType.STRING_LIT, TokenType.CHAR_LIT, TokenType.STR_TMPL_START,
        TokenType.TRUE, TokenType.FALSE, TokenType.NULL, TokenType.THIS,
        TokenType.IDENT, TokenType.LPAREN, TokenType.IF, TokenType.WHEN,
        TokenType.MINUS, TokenType.EXCL, TokenType.PLUS_PLUS, TokenType.MINUS_MINUS -> true
        else -> false
    }
}

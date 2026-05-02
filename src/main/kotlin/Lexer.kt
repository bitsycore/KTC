package com.bitsycore

class Lexer(private val src: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    private val tokens = mutableListOf<Token>()

    // ── Mode stack for string template handling ──────────────────────
    private enum class Mode { NORMAL, STRING, TMPL_EXPR }
    private val modes = ArrayDeque<Mode>().apply { addLast(Mode.NORMAL) }
    private var tmplBraceDepth = 0
    private var inTemplateLiteral = false   // has current string emitted STR_TMPL_START?

    // ── Public entry point ───────────────────────────────────────────

    fun tokenize(): List<Token> {
        while (pos < src.length) {
            when (modes.last()) {
                Mode.NORMAL    -> lexNormal()
                Mode.STRING    -> lexStringContent()
                Mode.TMPL_EXPR -> lexTemplateExpr()
            }
        }
        emit(TokenType.EOF, "")
        return tokens
    }

    // ── Normal mode ──────────────────────────────────────────────────

    private fun lexNormal() {
        skipSpaces()
        if (pos >= src.length) return
        val c = cur()
        when {
            c == '\n' || c == '\r' -> lexNewline()
            c == '/' && pos + 1 < src.length && src[pos + 1] == '/' -> skipLineComment()
            c == '/' && pos + 1 < src.length && src[pos + 1] == '*' -> skipBlockComment()
            c == '"'  -> startString()
            c == '\'' -> lexChar()
            c.isDigit() -> lexNumber()
            c.isLetter() || c == '_' -> lexIdent()
            else -> lexPunct()
        }
    }

    // ── Template expression mode (same as normal but tracks } depth) ─

    private fun lexTemplateExpr() {
        skipSpaces()
        if (pos >= src.length) return
        val c = cur()
        if (c == '}' && tmplBraceDepth == 0) {
            advance()
            emit(TokenType.TMPL_EXPR_END, "}")
            modes.removeLast()          // back to STRING
            return
        }
        if (c == '{') tmplBraceDepth++
        if (c == '}') tmplBraceDepth--
        lexNormal()
    }

    // ── String content mode ──────────────────────────────────────────

    private fun startString() {
        advance()                       // skip opening "
        inTemplateLiteral = false
        modes.addLast(Mode.STRING)
    }

    private fun lexStringContent() {
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = cur()
            when {
                c == '"' -> {
                    advance()
                    if (inTemplateLiteral) {
                        if (sb.isNotEmpty()) emit(TokenType.STR_TMPL_PART, sb.toString())
                        emit(TokenType.STR_TMPL_END, "")
                    } else {
                        emit(TokenType.STRING_LIT, sb.toString())
                    }
                    modes.removeLast()
                    return
                }
                c == '\\' -> sb.append(readEscape())
                c == '$' && pos + 1 < src.length && (src[pos + 1].isLetterOrDigitOrUnderscore()) -> {
                    ensureTmplStarted(sb)
                    advance()                   // skip $
                    val name = readIdentRaw()
                    emit(TokenType.TMPL_REF, name)
                }
                c == '$' && pos + 1 < src.length && src[pos + 1] == '{' -> {
                    ensureTmplStarted(sb)
                    advance(); advance()        // skip ${
                    emit(TokenType.TMPL_EXPR_START, "\${")
                    tmplBraceDepth = 0
                    modes.addLast(Mode.TMPL_EXPR)
                    return                      // hand control to lexTemplateExpr
                }
                c == '\n' -> {
                    sb.append(c); advance(); line++; col = 1
                }
                else -> { sb.append(c); advance() }
            }
        }
        error("Unterminated string at line $line")
    }

    private fun ensureTmplStarted(sb: StringBuilder) {
        if (!inTemplateLiteral) {
            inTemplateLiteral = true
            emit(TokenType.STR_TMPL_START, "")
        }
        if (sb.isNotEmpty()) {
            emit(TokenType.STR_TMPL_PART, sb.toString())
            sb.clear()
        }
    }

    // ── Character literal ────────────────────────────────────────────

    private fun lexChar() {
        advance()                           // skip opening '
        val ch = if (cur() == '\\') readEscape() else { val c = cur(); advance(); c }
        if (pos < src.length && cur() == '\'') advance() else error("Unterminated char at line $line")
        emit(TokenType.CHAR_LIT, ch.toString())
    }

    // ── Number literal ───────────────────────────────────────────────

    private fun lexNumber() {
        val start = pos
        var isLong = false
        var isFloat = false
        var isDouble = false

        while (pos < src.length && (cur().isDigit() || cur() == '_')) advance()

        if (pos < src.length && cur() == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) {
            advance()   // skip .
            while (pos < src.length && (cur().isDigit() || cur() == '_')) advance()
            isDouble = true
        }
        if (pos < src.length && (cur() == 'e' || cur() == 'E')) {
            advance()
            if (pos < src.length && (cur() == '+' || cur() == '-')) advance()
            while (pos < src.length && cur().isDigit()) advance()
            isDouble = true
        }
        if (pos < src.length && (cur() == 'f' || cur() == 'F')) { isFloat = true; isDouble = false; advance() }
        else if (pos < src.length && (cur() == 'L')) { isLong = true; isDouble = false; advance() }

        val raw = src.substring(start, pos).replace("_", "")
        val type = when {
            isFloat  -> TokenType.FLOAT_LIT
            isDouble -> TokenType.DOUBLE_LIT
            isLong   -> TokenType.LONG_LIT
            else     -> TokenType.INT_LIT
        }
        emit(type, raw)
    }

    // ── Identifier / keyword ─────────────────────────────────────────

    private fun lexIdent() {
        val name = readIdentRaw()
        val type = KEYWORDS[name] ?: TokenType.IDENT
        emit(type, name)
    }

    private fun readIdentRaw(): String {
        val start = pos
        while (pos < src.length && (cur().isLetterOrDigit() || cur() == '_')) advance()
        return src.substring(start, pos)
    }

    // ── Punctuation / operators ──────────────────────────────────────

    private fun lexPunct() {
        val c = cur()
        val nc = if (pos + 1 < src.length) src[pos + 1] else '\u0000'
        val nnc = if (pos + 2 < src.length) src[pos + 2] else '\u0000'
        when {
            c == '(' -> { advance(); emit(TokenType.LPAREN, "(") }
            c == ')' -> { advance(); emit(TokenType.RPAREN, ")") }
            c == '{' -> { advance(); emit(TokenType.LBRACE, "{") }
            c == '}' -> { advance(); emit(TokenType.RBRACE, "}") }
            c == '[' -> { advance(); emit(TokenType.LBRACKET, "[") }
            c == ']' -> { advance(); emit(TokenType.RBRACKET, "]") }
            c == ',' -> { advance(); emit(TokenType.COMMA, ",") }
            c == ':' && nc == ':' -> { advance(); advance(); emit(TokenType.COLON_COLON, "::") }
            c == ':' -> { advance(); emit(TokenType.COLON, ":") }
            c == ';' -> { advance(); emit(TokenType.SEMICOLON, ";") }
            c == '.' && nc == '.' && nnc == '<' -> { advance(); advance(); advance(); emit(TokenType.DOT_DOT, "..<") }
            c == '.' && nc == '.' -> { advance(); advance(); emit(TokenType.DOT_DOT, "..") }
            c == '.' -> { advance(); emit(TokenType.DOT, ".") }
            c == '+' && nc == '+' -> { advance(); advance(); emit(TokenType.PLUS_PLUS, "++") }
            c == '+' && nc == '=' -> { advance(); advance(); emit(TokenType.PLUS_EQ, "+=") }
            c == '+' -> { advance(); emit(TokenType.PLUS, "+") }
            c == '-' && nc == '-' -> { advance(); advance(); emit(TokenType.MINUS_MINUS, "--") }
            c == '-' && nc == '>' -> { advance(); advance(); emit(TokenType.ARROW, "->") }
            c == '-' && nc == '=' -> { advance(); advance(); emit(TokenType.MINUS_EQ, "-=") }
            c == '-' -> { advance(); emit(TokenType.MINUS, "-") }
            c == '*' && nc == '=' -> { advance(); advance(); emit(TokenType.STAR_EQ, "*=") }
            c == '*' -> { advance(); emit(TokenType.STAR, "*") }
            c == '/' && nc == '=' -> { advance(); advance(); emit(TokenType.SLASH_EQ, "/=") }
            c == '/' -> { advance(); emit(TokenType.SLASH, "/") }
            c == '%' && nc == '=' -> { advance(); advance(); emit(TokenType.PERCENT_EQ, "%=") }
            c == '%' -> { advance(); emit(TokenType.PERCENT, "%") }
            c == '=' && nc == '=' -> { advance(); advance(); emit(TokenType.EQ_EQ, "==") }
            c == '=' -> { advance(); emit(TokenType.EQ, "=") }
            c == '!' && nc == '=' -> { advance(); advance(); emit(TokenType.EXCL_EQ, "!=") }
            c == '!' && nc == '!' -> { advance(); advance(); emit(TokenType.EXCL_EXCL, "!!") }
            c == '!' -> { advance(); emit(TokenType.EXCL, "!") }
            c == '<' && nc == '=' -> { advance(); advance(); emit(TokenType.LT_EQ, "<=") }
            c == '<' -> { advance(); emit(TokenType.LT, "<") }
            c == '>' && nc == '=' -> { advance(); advance(); emit(TokenType.GT_EQ, ">=") }
            c == '>' -> { advance(); emit(TokenType.GT, ">") }
            c == '&' && nc == '&' -> { advance(); advance(); emit(TokenType.AMP_AMP, "&&") }
            c == '|' && nc == '|' -> { advance(); advance(); emit(TokenType.PIPE_PIPE, "||") }
            c == '?' && nc == '.' -> { advance(); advance(); emit(TokenType.QUESTION_DOT, "?.") }
            c == '?' && nc == ':' -> { advance(); advance(); emit(TokenType.QUESTION_COLON, "?:") }
            c == '?' -> { advance(); emit(TokenType.QUESTION, "?") }
            c == '@' -> { advance(); emit(TokenType.AT, "@") }
            else -> error("Unexpected character '$c' at line $line col $col")
        }
    }

    // ── Newlines ─────────────────────────────────────────────────────

    private fun lexNewline() {
        if (cur() == '\r' && pos + 1 < src.length && src[pos + 1] == '\n') advance()
        advance()
        emit(TokenType.NEWLINE, "\\n")
        line++; col = 1
    }

    // ── Comments ─────────────────────────────────────────────────────

    private fun skipLineComment() {
        while (pos < src.length && cur() != '\n') advance()
    }

    private fun skipBlockComment() {
        advance(); advance()                // skip /*
        var depth = 1
        while (pos < src.length && depth > 0) {
            if (cur() == '/' && pos + 1 < src.length && src[pos + 1] == '*') { advance(); advance(); depth++ }
            else if (cur() == '*' && pos + 1 < src.length && src[pos + 1] == '/') { advance(); advance(); depth-- }
            else { if (cur() == '\n') { line++; col = 1 }; advance() }
        }
    }

    // ── Escape sequences ─────────────────────────────────────────────

    private fun readEscape(): Char {
        advance()   // skip backslash
        val c = cur(); advance()
        return when (c) {
            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
            '\\' -> '\\'; '\'' -> '\''; '"' -> '"'; '$' -> '$'; '0' -> '\u0000'
            else -> c
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun cur(): Char = src[pos]
    private fun advance() { pos++; col++ }
    private fun skipSpaces() { while (pos < src.length && cur().let { it == ' ' || it == '\t' }) advance() }
    private fun emit(type: TokenType, value: String) { tokens.add(Token(type, value, line, col)) }

    private fun Char.isLetterOrDigitOrUnderscore(): Boolean = isLetter() || this == '_'
}

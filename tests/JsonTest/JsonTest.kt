package JsonTest

val TOK_LBRACE: Int = 0
val TOK_RBRACE: Int = 1
val TOK_LBRACK: Int = 2
val TOK_RBRACK: Int = 3
val TOK_COLON: Int = 4
val TOK_COMMA: Int = 5
val TOK_STR: Int = 6
val TOK_NUM: Int = 7
val TOK_TRUE: Int = 8
val TOK_FALSE: Int = 9
val TOK_NULL: Int = 10
val TOK_EOF: Int = 11

fun isDigit(ch: Char): Boolean {
    return ch >= '0' && ch <= '9'
}

fun lexJson(input: String): ArrayList<Int> {
    val tokens = ArrayList<Int>(256)
    var i = 0
    val len = input.length

    while (i < len) {
        val ch = input[i]
        when (ch) {
            ' ', '\n', '\r', '\t' -> {
                i = i + 1
            }

            '{' -> {
                tokens.add(TOK_LBRACE); tokens.add(i); tokens.add(1); tokens.add(i); i = i + 1
            }

            '}' -> {
                tokens.add(TOK_RBRACE); tokens.add(i); tokens.add(1); tokens.add(i); i = i + 1
            }

            '[' -> {
                tokens.add(TOK_LBRACK); tokens.add(i); tokens.add(1); tokens.add(i); i = i + 1
            }

            ']' -> {
                tokens.add(TOK_RBRACK); tokens.add(i); tokens.add(1); tokens.add(i); i = i + 1
            }

            ':' -> {
                tokens.add(TOK_COLON); tokens.add(i); tokens.add(1); tokens.add(i); i = i + 1
            }

            ',' -> {
                tokens.add(TOK_COMMA); tokens.add(i); tokens.add(1); tokens.add(i); i = i + 1
            }

            '"' -> {
                val start = i
                i = i + 1
                while (i < len && input[i] != '"') {
                    if (input[i] == '\\') {
                        i = i + 1
                    }
                    i = i + 1
                }
                i = i + 1
                tokens.add(TOK_STR)
                tokens.add(start + 1)
                tokens.add(i - start - 2)
                tokens.add(start)
            }

            '-' -> {
                val start = i
                i = i + 1
                while (i < len && isDigit(input[i])) {
                    i = i + 1
                }
                if (i < len && input[i] == '.') {
                    i = i + 1
                    while (i < len && isDigit(input[i])) {
                        i = i + 1
                    }
                }
                tokens.add(TOK_NUM)
                tokens.add(start)
                tokens.add(i - start)
                tokens.add(start)
            }

            't' -> {
                tokens.add(TOK_TRUE); tokens.add(i); tokens.add(4); tokens.add(i); i = i + 4
            }

            'f' -> {
                tokens.add(TOK_FALSE); tokens.add(i); tokens.add(5); tokens.add(i); i = i + 5
            }

            'n' -> {
                tokens.add(TOK_NULL); tokens.add(i); tokens.add(4); tokens.add(i); i = i + 4
            }

            else -> {
                if (isDigit(ch)) {
                    val start = i
                    while (i < len && isDigit(input[i])) {
                        i = i + 1
                    }
                    if (i < len && input[i] == '.') {
                        i = i + 1
                        while (i < len && isDigit(input[i])) {
                            i = i + 1
                        }
                    }
                    tokens.add(TOK_NUM)
                    tokens.add(start)
                    tokens.add(i - start)
                    tokens.add(start)
                } else {
                    c.printf("Unexpected character at position %d\n", i)
                    c.exit(1)
                }
            }
        }
    }
    tokens.add(TOK_EOF)
    tokens.add(len)
    tokens.add(0)
    tokens.add(len)
    return tokens
}

fun main(args: Array<String>) {
    val input = "{\"name\": \"Test\", \"ok\": true, \"count\": 42}"

    println("=== Input ===")
    println(input)

    val tokens = lexJson(input)
    defer { tokens.dispose() }
    val tokenCount = tokens.size / 4

    println("=== Tokens ===")
    c.printf("Token count: %d\n", tokenCount)

    for (i in 0 until tokenCount) {
        val kind = tokens.get(i * 4)
        val start = tokens.get(i * 4 + 1)
        val len = tokens.get(i * 4 + 2)
        val text = input.substring(start, start + len)
        c.printf("  [%d] kind=%d text=\"%.*s\"\n", i, kind, text.length, text.ptr)
    }

    println("")
    println("=== Done ===")
}

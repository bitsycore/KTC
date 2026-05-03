package JsonParserTest

// ═══════════════════════════════════════════════════════════════════════
// JSON Parser — Showcase for KotlinToC transpiler
//
// Features demonstrated:
//   - Generic classes (MutableList<T> with monomorphization)
//   - Data classes (Token)
//   - String character indexing (str[i])
//   - String methods (substring, length)
//   - When expressions (subject-based dispatch)
//   - Recursive-descent parsing
//   - Heap allocation (HeapAlloc, HeapArrayResize, HeapFree)
//   - Defer for cleanup
//   - C interop (c.printf, c.exit)
//   - For/while loops, ..<  operator
//   - Helper functions, default parameters
//   - Nullable types and !! assertion
// ═══════════════════════════════════════════════════════════════════════

// ── Generic MutableList ──────────────────────────────────────────────

class MutableList<T>(capacity: Int) {
    var size: Int = 0
    var buf: Heap<Array<T>> = HeapAlloc<Array<T>>(capacity)!!

    fun add(value: T) {
        if (size >= buf.size) {
            val newCap = buf.size * 2
            buf = HeapArrayResize<Array<T>>(buf, newCap)!!
        }
        buf[size] = value
        size = size + 1
    }

    fun get(index: Int): T {
        return buf[index]
    }

    fun set(index: Int, value: T) {
        buf[index] = value
    }

    fun dispose() {
        HeapFree(buf)
    }
}

// ── Token type constants ─────────────────────────────────────────────

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

// ── JSON node kind constants ─────────────────────────────────────────

val JSON_STRING: Int = 0
val JSON_NUMBER: Int = 1
val JSON_BOOL: Int = 2
val JSON_NULL: Int = 3
val JSON_ARRAY: Int = 4
val JSON_OBJECT: Int = 5

// ── Lexer ────────────────────────────────────────────────────────────

fun isDigit(ch: Char): Boolean {
    return ch >= '0' && ch <= '9'
}

data class Lexer(val input: String, val len: Int)

fun lexJson(input: String): MutableList<Int> {
    val tokens = MutableList<Int>(256)
    var i = 0
    val len = input.length

    while (i < len) {
        val ch = input[i]

        when (ch) {
            ' ', '\n', '\r', '\t' -> {
                i = i + 1
            }
            '{' -> {
                tokens.add(TOK_LBRACE); tokens.add(i); tokens.add(1); tokens.add(i)
                i = i + 1
            }
            '}' -> {
                tokens.add(TOK_RBRACE); tokens.add(i); tokens.add(1); tokens.add(i)
                i = i + 1
            }
            '[' -> {
                tokens.add(TOK_LBRACK); tokens.add(i); tokens.add(1); tokens.add(i)
                i = i + 1
            }
            ']' -> {
                tokens.add(TOK_RBRACK); tokens.add(i); tokens.add(1); tokens.add(i)
                i = i + 1
            }
            ':' -> {
                tokens.add(TOK_COLON); tokens.add(i); tokens.add(1); tokens.add(i)
                i = i + 1
            }
            ',' -> {
                tokens.add(TOK_COMMA); tokens.add(i); tokens.add(1); tokens.add(i)
                i = i + 1
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
                tokens.add(TOK_STR); tokens.add(start + 1); tokens.add(i - start - 2); tokens.add(start)
            }
            '-' -> {
                val start = i
                i = i + 1
                while (i < len && isDigit(input[i])) { i = i + 1 }
                if (i < len && input[i] == '.') {
                    i = i + 1
                    while (i < len && isDigit(input[i])) { i = i + 1 }
                }
                tokens.add(TOK_NUM); tokens.add(start); tokens.add(i - start); tokens.add(start)
            }
            't' -> {
                tokens.add(TOK_TRUE); tokens.add(i); tokens.add(4); tokens.add(i)
                i = i + 4
            }
            'f' -> {
                tokens.add(TOK_FALSE); tokens.add(i); tokens.add(5); tokens.add(i)
                i = i + 5
            }
            'n' -> {
                tokens.add(TOK_NULL); tokens.add(i); tokens.add(4); tokens.add(i)
                i = i + 4
            }
            else -> {
                if (isDigit(ch)) {
                    val start = i
                    while (i < len && isDigit(input[i])) { i = i + 1 }
                    if (i < len && input[i] == '.') {
                        i = i + 1
                        while (i < len && isDigit(input[i])) { i = i + 1 }
                    }
                    tokens.add(TOK_NUM); tokens.add(start); tokens.add(i - start); tokens.add(start)
                } else {
                    c.printf("Unexpected character at position %d\n", i)
                    c.exit(1)
                }
            }
        }
    }

    // EOF token
    tokens.add(TOK_EOF); tokens.add(len); tokens.add(0); tokens.add(len)
    return tokens
}

// ── Token accessors ──────────────────────────────────────────────────

fun tokKind(toks: MutableList<Int>, idx: Int): Int { return toks.get(idx * 4) }
fun tokStart(toks: MutableList<Int>, idx: Int): Int { return toks.get(idx * 4 + 1) }
fun tokLen(toks: MutableList<Int>, idx: Int): Int { return toks.get(idx * 4 + 2) }

// ── Recursive-descent parser ─────────────────────────────────────────
// Output: flat int list describing the JSON tree
//   STRING: [0, textStart, textLen]
//   NUMBER: [1, textStart, textLen]
//   BOOL:   [2, value (1=true, 0=false)]
//   NULL:   [3]
//   ARRAY:  [4, count, ...children inline...]
//   OBJECT: [5, count, ...key then value alternating...]

fun parseValue(input: String, toks: MutableList<Int>, pos: IntArray, out: MutableList<Int>): Boolean {
    val kind = tokKind(toks, pos[0])

    when (kind) {
        6 -> {
            out.add(JSON_STRING); out.add(tokStart(toks, pos[0])); out.add(tokLen(toks, pos[0]))
            pos[0] = pos[0] + 1
            return true
        }
        7 -> {
            out.add(JSON_NUMBER); out.add(tokStart(toks, pos[0])); out.add(tokLen(toks, pos[0]))
            pos[0] = pos[0] + 1
            return true
        }
        8 -> {
            out.add(JSON_BOOL); out.add(1)
            pos[0] = pos[0] + 1
            return true
        }
        9 -> {
            out.add(JSON_BOOL); out.add(0)
            pos[0] = pos[0] + 1
            return true
        }
        10 -> {
            out.add(JSON_NULL)
            pos[0] = pos[0] + 1
            return true
        }
        2 -> { return parseArray(input, toks, pos, out) }
        0 -> { return parseObject(input, toks, pos, out) }
        else -> {
            c.printf("Unexpected token kind %d at position %d\n", kind, pos[0])
            return false
        }
    }
}

fun parseArray(input: String, toks: MutableList<Int>, pos: IntArray, out: MutableList<Int>): Boolean {
    pos[0] = pos[0] + 1  // skip LBRACK

    val headerIdx = out.size
    out.add(JSON_ARRAY); out.add(0)

    var count = 0
    if (tokKind(toks, pos[0]) != TOK_RBRACK) {
        val ok = parseValue(input, toks, pos, out)
        if (!ok) { return false }
        count = count + 1

        while (tokKind(toks, pos[0]) == TOK_COMMA) {
            pos[0] = pos[0] + 1
            val ok2 = parseValue(input, toks, pos, out)
            if (!ok2) { return false }
            count = count + 1
        }
    }

    if (tokKind(toks, pos[0]) != TOK_RBRACK) {
        c.printf("Expected ] at token %d\n", pos[0])
        return false
    }
    pos[0] = pos[0] + 1
    out.set(headerIdx + 1, count)
    return true
}

fun parseKeyValue(input: String, toks: MutableList<Int>, pos: IntArray, out: MutableList<Int>): Boolean {
    if (tokKind(toks, pos[0]) != TOK_STR) {
        c.printf("Expected string key at token %d\n", pos[0])
        return false
    }
    out.add(JSON_STRING); out.add(tokStart(toks, pos[0])); out.add(tokLen(toks, pos[0]))
    pos[0] = pos[0] + 1

    if (tokKind(toks, pos[0]) != TOK_COLON) {
        c.printf("Expected : at token %d\n", pos[0])
        return false
    }
    pos[0] = pos[0] + 1

    return parseValue(input, toks, pos, out)
}

fun parseObject(input: String, toks: MutableList<Int>, pos: IntArray, out: MutableList<Int>): Boolean {
    pos[0] = pos[0] + 1  // skip LBRACE

    val headerIdx = out.size
    out.add(JSON_OBJECT); out.add(0)

    var count = 0
    if (tokKind(toks, pos[0]) != TOK_RBRACE) {
        val ok = parseKeyValue(input, toks, pos, out)
        if (!ok) { return false }
        count = count + 1

        while (tokKind(toks, pos[0]) == TOK_COMMA) {
            pos[0] = pos[0] + 1
            val ok2 = parseKeyValue(input, toks, pos, out)
            if (!ok2) { return false }
            count = count + 1
        }
    }

    if (tokKind(toks, pos[0]) != TOK_RBRACE) {
        c.printf("Expected } at token %d\n", pos[0])
        return false
    }
    pos[0] = pos[0] + 1
    out.set(headerIdx + 1, count)
    return true
}

// ── Pretty printer ───────────────────────────────────────────────────

fun printIndent(depth: Int) {
    for (i in 0 until depth) {
        c.printf("  ")
    }
}

fun printStr(input: String, out: MutableList<Int>, idx: IntArray) {
    val start = out.get(idx[0] + 1)
    val len = out.get(idx[0] + 2)
    val s = input.substring(start, start + len)
    c.printf("\"%.*s\"", s.length, s.ptr)
    idx[0] = idx[0] + 3
}

fun printJsonValue(input: String, out: MutableList<Int>, idx: IntArray, depth: Int) {
    val kind = out.get(idx[0])

    when (kind) {
        0 -> {
            printStr(input, out, idx)
        }
        1 -> {
            val start = out.get(idx[0] + 1)
            val len = out.get(idx[0] + 2)
            val s = input.substring(start, start + len)
            c.printf("%.*s", s.length, s.ptr)
            idx[0] = idx[0] + 3
        }
        2 -> {
            if (out.get(idx[0] + 1) == 1) {
                c.printf("true")
            } else {
                c.printf("false")
            }
            idx[0] = idx[0] + 2
        }
        3 -> {
            c.printf("null")
            idx[0] = idx[0] + 1
        }
        4 -> {
            val count = out.get(idx[0] + 1)
            idx[0] = idx[0] + 2
            if (count == 0) {
                c.printf("[]")
            } else {
                c.printf("[\n")
                for (i in 0 until count) {
                    printIndent(depth + 1)
                    printJsonValue(input, out, idx, depth + 1)
                    if (i < count - 1) {
                        c.printf(",")
                    }
                    c.printf("\n")
                }
                printIndent(depth)
                c.printf("]")
            }
        }
        5 -> {
            val count = out.get(idx[0] + 1)
            idx[0] = idx[0] + 2
            if (count == 0) {
                c.printf("{}")
            } else {
                c.printf("{\n")
                for (i in 0 until count) {
                    printIndent(depth + 1)
                    printStr(input, out, idx)
                    c.printf(": ")
                    printJsonValue(input, out, idx, depth + 1)
                    if (i < count - 1) {
                        c.printf(",")
                    }
                    c.printf("\n")
                }
                printIndent(depth)
                c.printf("}")
            }
        }
    }
}

// ── Main ─────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    val input = "{\"name\": \"KotlinToC\", \"version\": 1.0, \"features\": [\"enums\", \"generics\", \"nullable\"], \"active\": true, \"meta\": null}"

    println("=== Input ===")
    println(input)
    println("")

    // Lex
    val tokens = lexJson(input)
    defer tokens.dispose()
    val tokenCount = tokens.size / 4

    println("=== Tokens ===")
    c.printf("Token count: %d\n", tokenCount)

    for (i in 0 until tokenCount) {
        val kind = tokKind(tokens, i)
        val start = tokStart(tokens, i)
        val len = tokLen(tokens, i)
        val text = input.substring(start, start + len)
        c.printf("  [%d] kind=%d text=\"%.*s\"\n", i, kind, text.length, text.ptr)
    }
    println("")

    // Parse
    println("=== Parsed JSON (pretty) ===")
    val output = MutableList<Int>(256)
    defer output.dispose()
    val pos = IntArray(1)
    val oi = IntArray(1)
    pos[0] = 0
    oi[0] = 0
    val ok = parseValue(input, tokens, pos, output)

    if (ok) {
        val idx = IntArray(1)
        idx[0] = 0
        printJsonValue(input, output, idx, 0)
        c.printf("\n")
    } else {
        println("Parse failed!")
    }

    println("")
    println("=== Done ===")
}

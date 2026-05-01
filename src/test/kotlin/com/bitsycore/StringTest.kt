package com.bitsycore

import kotlin.test.Test

/**
 * Tests for string templates and string operations.
 */
class StringTest : TranspilerTestBase() {

    // ── Simple string template ───────────────────────────────────────

    @Test fun simpleTemplate() {
        val r = transpileMain("""
            val name = "World"
            println("Hello, ${'$'}name!")
        """)
        r.sourceContains("printf(\"Hello, %.*s!\\n\"")
    }

    // ── Expression in template ───────────────────────────────────────

    @Test fun expressionTemplate() {
        val r = transpileMain("""
            val a = 10
            val b = 20
            println("sum = ${'$'}{a + b}")
        """)
        r.sourceContains("(a + b)")
    }

    // ── String template as value ─────────────────────────────────────

    @Test fun templateAsValue() {
        val r = transpileMain("""
            val x = 42
            val s = "value=${'$'}x"
            println(s)
        """)
        // Template assigned to val → uses snprintf or similar
        r.sourceContains("kt_StrBuf")
    }

    // ── Data class in template ───────────────────────────────────────

    @Test fun dataClassInTemplate() {
        val r = transpile("""
            package test.Main
            data class Vec2(val x: Float, val y: Float)
            fun main(args: Array<String>) {
                val v = Vec2(3.0f, 4.0f)
                println("v=${'$'}v")
            }
        """)
        r.sourceContains("test_Main_Vec2_toString")
    }

    // ── String length ────────────────────────────────────────────────

    @Test fun stringLength() {
        val r = transpileMain("""
            val s = "hello"
            println(s.length)
        """)
        r.sourceContains("s.len")
    }

    // ── String toInt ─────────────────────────────────────────────────

    @Test fun stringToInt() {
        val r = transpileMain("""
            val s = "42"
            val n = s.toInt()
        """)
        r.sourceContains("kt_str_toInt(s)")
    }

    // ── String toDouble ──────────────────────────────────────────────

    @Test fun stringToDouble() {
        val r = transpileMain("""
            val s = "3.14"
            val d = s.toDouble()
        """)
        r.sourceContains("kt_str_toDouble(s)")
    }

    // ── String comparison ────────────────────────────────────────────

    @Test fun stringEquals() {
        val r = transpileMain("""
            val a = "hello"
            val b = "world"
            println(a == b)
        """)
        r.sourceContains("kt_string_eq")
    }

    @Test fun stringLessThan() {
        val r = transpileMain("""
            val a = "apple"
            val b = "banana"
            println(a < b)
        """)
        r.sourceContains("kt_string_cmp")
    }

    // ── String concatenation ─────────────────────────────────────────

    @Test fun stringConcat() {
        val r = transpileMain("""
            val a = "hello"
            val b = " world"
            val c = a + b
        """)
        r.sourceContains("kt_string_cat")
        // Verify buffer declaration and 4-arg call: (buf, sizeof(buf), a, b)
        r.sourceMatches(Regex("""char \$\w+\[512\];"""))
        r.sourceMatches(Regex("""kt_string_cat\(\$\w+, sizeof\(\$\w+\), a, b\)"""))
    }

    // ── toIntOrNull ──────────────────────────────────────────────────

    @Test fun toIntOrNull_valid() {
        val r = transpileMain("""
            val s = "42"
            val n = s.toIntOrNull()
        """)
        r.sourceContains("kt_str_toIntOrNull")
        r.sourceContains("n${'$'}has")
    }

    @Test fun toIntOrNull_usedWithElvis() {
        val r = transpileMain("""
            val s = "abc"
            val n = s.toIntOrNull() ?: 0
        """)
        r.sourceContains("kt_str_toIntOrNull")
    }

    // ── toLongOrNull ─────────────────────────────────────────────────

    @Test fun toLongOrNull_valid() {
        val r = transpileMain("""
            val s = "999"
            val n = s.toLongOrNull()
        """)
        r.sourceContains("kt_str_toLongOrNull")
        r.sourceContains("n${'$'}has")
    }

    // ── toDoubleOrNull ───────────────────────────────────────────────

    @Test fun toDoubleOrNull_valid() {
        val r = transpileMain("""
            val s = "3.14"
            val d = s.toDoubleOrNull()
        """)
        r.sourceContains("kt_str_toDoubleOrNull")
        r.sourceContains("d${'$'}has")
    }

    // ── toFloatOrNull ────────────────────────────────────────────────

    @Test fun toFloatOrNull_valid() {
        val r = transpileMain("""
            val s = "1.5"
            val f = s.toFloatOrNull()
        """)
        r.sourceContains("kt_str_toDoubleOrNull")
        r.sourceContains("(float)")
        r.sourceContains("f${'$'}has")
    }

    // ── toIntOrNull with null check ──────────────────────────────────

    @Test fun toIntOrNull_withNullCheck() {
        val r = transpileMain("""
            val s = "42"
            val n = s.toIntOrNull()
            if (n != null) {
                println(n)
            }
        """)
        r.sourceContains("n${'$'}has")
        r.sourceContains("kt_str_toIntOrNull")
    }

    // ── String character indexing ───────────────────────────────────

    @Test fun stringCharIndex() {
        val r = transpileMain("""
            val s = "hello"
            val ch: Char = s[0]
        """)
        r.sourceContains("s.ptr[0]")
    }

    @Test fun stringCharIndexVariable() {
        val r = transpileMain("""
            val s = "hello"
            val i = 2
            val ch = s[i]
        """)
        r.sourceContains("s.ptr[i]")
    }

    @Test fun stringCharIndexInExpression() {
        val r = transpileMain("""
            val s = "abc"
            val isA = s[0] == 'a'
        """)
        r.sourceContains("s.ptr[0]")
    }

    // ── String.substring ─────────────────────────────────────────────

    @Test fun substringTwoArgs() {
        val r = transpileMain("""
            val s = "hello world"
            val sub = s.substring(0, 5)
        """)
        r.sourceContains("kt_string_substring(s, 0, 5)")
    }

    @Test fun substringOneArg() {
        val r = transpileMain("""
            val s = "hello"
            val tail = s.substring(2)
        """)
        r.sourceContains("kt_string_substring(s, 2, s.len)")
    }

    // ── String.startsWith / endsWith ─────────────────────────────────

    @Test fun stringStartsWith() {
        val r = transpileMain("""
            val s = "hello"
            val ok = s.startsWith("hel")
        """)
        r.sourceContains("memcmp(s.ptr,")
    }

    @Test fun stringEndsWith() {
        val r = transpileMain("""
            val s = "hello"
            val ok = s.endsWith("llo")
        """)
        r.sourceContains("memcmp(s.ptr + s.len -")
    }

    // ── String.contains / indexOf ────────────────────────────────────

    @Test fun stringContains() {
        val r = transpileMain("""
            val s = "hello world"
            val found = s.contains("world")
        """)
        r.sourceContains("memcmp(s.ptr +")
    }

    @Test fun stringIndexOf() {
        val r = transpileMain("""
            val s = "hello world"
            val idx = s.indexOf("world")
        """)
        r.sourceContains("memcmp(s.ptr +")
    }

    // ── String.isEmpty / isNotEmpty ──────────────────────────────────

    @Test fun stringIsEmpty() {
        val r = transpileMain("""
            val s = "hello"
            val empty = s.isEmpty()
        """)
        r.sourceContains("(s.len == 0)")
    }

    @Test fun stringIsNotEmpty() {
        val r = transpileMain("""
            val s = "hello"
            val notEmpty = s.isNotEmpty()
        """)
        r.sourceContains("(s.len > 0)")
    }
}

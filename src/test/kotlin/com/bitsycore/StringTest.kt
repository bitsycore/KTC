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
    }
}

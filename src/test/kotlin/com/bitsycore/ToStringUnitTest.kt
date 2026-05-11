package com.bitsycore

import kotlin.test.Test
import kotlin.test.assertFalse

class ToStringUnitTest : TranspilerTestBase() {

    @Test fun `class default toString includes name and hex hash`() {
        val r = transpileMain(
            decls = "class Foo(val x: Int)",
            body = "val f = Foo(42)\nprintln(f)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("Foo_hashCode")
        r.sourceContains("%s@%x")
    }

    @Test fun `data class uses generated toString not default`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int, val y: Int)",
            body = "val p = Point(1, 2)\nprintln(p)"
        )
        r.sourceContains("Point_toString")
    }

    @Test fun `enum uses names array not default toString`() {
        val r = transpileMain(
            decls = "enum class Color { RED, GREEN, BLUE }",
            body = "val c = Color.RED\nprintln(c)"
        )
        r.sourceContains("Color_names")
    }

    @Test fun `object default toString`() {
        val r = transpileMain(
            decls = "object Logger",
            body = "println(Logger)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("%s@%x")
    }

    @Test fun `interface default toString`() {
        val r = transpileMain(
            decls = """
                interface Shape { fun area(): Float }
                class Circle(private var r: Float) : Shape {
                    override fun area(): Float = r * r * 3.14f
                }
            """.trimIndent(),
            body = "val s: Shape = Circle(1f)\nprintln(s)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("@")
    }

    @Test fun `primitive int toString`() {
        val r = transpileMain("val x = 42\nprintln(x)")
        r.sourceContains("printf")
    }

    @Test fun `string toString returns itself`() {
        val r = transpileMain("val s = \"hello\"\nprintln(s)")
        r.sourceContains("printf")
    }

    // ── StringBuffer toString ─────────────────────────────────────

    @Test fun `StringBuffer constructor with null buffer`() {
        val r = transpileMain("val sb = StringBuffer(null, 0)")
        r.sourceContains("(ktc_StrBuf){NULL")
        r.sourceContains(", 0, 0}")
    }

    @Test fun `StringBuffer constructor with array pointer derives capacity`() {
        val r = transpileMain("""
            val chars = CharArray(256)
            val sb = StringBuffer(chars.ptr(), 0)
        """.trimIndent())
        r.sourceContains("(ktc_StrBuf){")
        r.sourceContains("\$len")
    }

    @Test fun `StringBuffer field buffer maps to ptr`() {
        val r = transpileMain("""
            val sb = StringBuffer(null, 0)
            val p = sb.buffer
        """.trimIndent())
        r.sourceContains(".ptr")
    }

    @Test fun `StringBuffer field len maps to len`() {
        val r = transpileMain("""
            val sb = StringBuffer(null, 0)
            val l = sb.len
        """.trimIndent())
        r.sourceContains(".len")
    }

    @Test fun `data class toString with StringBuffer single pass`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int, val y: Int)",
            body = """
                val p = Point(1, 2)
                val sb = StringBuffer(null, 0)
                val s = p.toString(sb)
            """.trimIndent()
        )
        r.sourceContains("Point_toString")
        r.sourceContains("ktc_sb_to_string")
        val sbPattern = Regex("ktc_StrBuf \\w+_sb = \\{NULL, 0, 0\\};")
        assertFalse(sbPattern.containsMatchIn(r.source), "Should not contain two-pass StrBuf init pattern")
    }

    @Test fun `int toString with StringBuffer uses sb_append`() {
        val r = transpileMain("""
            val sb = StringBuffer(null, 0)
            val s = 42.toString(sb)
        """.trimIndent())
        r.sourceContains("ktc_sb_append_int")
        r.sourceContains("ktc_sb_to_string")
    }

    @Test fun `toString returns String when called via StringBuffer`() {
        val r = transpileMain(
            decls = "data class Vec2(val x: Float, val y: Float)",
            body = """
                val v = Vec2(1f, 2f)
                val sb = StringBuffer(null, 0)
                val result: String = v.toString(sb)
            """.trimIndent()
        )
        r.sourceContains("Vec2_toString")
        r.sourceContains("ktc_sb_to_string")
    }

    @Test fun `counting mode StringBuffer toString`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int)",
            body = """
                val p = Point(42)
                val sb = StringBuffer(null, 0)
                p.toString(sb)
            """.trimIndent()
        )
        r.sourceContains("Point_toString")
        // Should call toString only once (single pass, counting via NULL ptr)
    }

    @Test fun `counting mode then write pattern with StringBuffer`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int, val y: Int)",
            body = """
                val p = Point(1, 2)
                val sb = StringBuffer(null, 0)
                p.toString(sb)
                val buf = CharArray(sb.len + 1)
                val sb2 = StringBuffer(buf.ptr(), 0)
                p.toString(sb2)
            """.trimIndent()
        )
        // First toString: counting mode (NULL buffer)
        r.sourceContains("(ktc_StrBuf){NULL, 0, 0}")
        // Second toString: write mode with derived capacity
        r.sourceContains("buf\$len")
        r.sourceContains("Point_toString")
    }
}

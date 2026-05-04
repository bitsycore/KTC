package com.bitsycore

import kotlin.test.Test

/**
 * Tests for enum classes.
 */
class EnumUnitTest : TranspilerTestBase() {

    private val colorDecl = "enum class Color { RED, GREEN, BLUE }"

    // ── Typedef ──────────────────────────────────────────────────────

    @Test fun enumTypedef() {
        val r = transpileMain("val c = Color.RED", decls = colorDecl)
        r.headerContains("typedef enum {")
        r.headerContains("test_Main_Color_RED")
        r.headerContains("test_Main_Color_GREEN")
        r.headerContains("test_Main_Color_BLUE")
        r.headerContains("} test_Main_Color;")
    }

    // ── Enum value usage ─────────────────────────────────────────────

    @Test fun enumValueAccess() {
        val r = transpileMain("val c = Color.RED\nprintln(c)", decls = colorDecl)
        r.sourceContains("test_Main_Color c = test_Main_Color_RED;")
    }

    // ── When on enum ─────────────────────────────────────────────────

    @Test fun whenOnEnum() {
        val r = transpileMain("""
            val c = Color.RED
            when (c) {
                Color.RED -> println("red")
                Color.GREEN -> println("green")
                Color.BLUE -> println("blue")
            }
        """, decls = colorDecl)
        r.sourceContains("test_Main_Color_RED")
        r.sourceContains("test_Main_Color_GREEN")
        r.sourceContains("test_Main_Color_BLUE")
    }

    // ── Enum not confused with nullable ──────────────────────────────

    @Test fun enumAccessNotNullError() {
        // Enum access via dot should NOT trigger nullable receiver error
        val r = transpileMain("val c = Color.RED", decls = colorDecl)
        r.sourceContains("test_Main_Color_RED")
    }

    // ── enumValues ────────────────────────────────────────────────────

    @Test fun enumValues() {
        val r = transpileMain("""
            val arr = enumValues<Color>()
            val first = arr[0]
        """, decls = colorDecl)
        r.sourceContains("test_Main_Color_values")
        r.sourceContains("test_Main_Color_RED")
    }

    @Test fun enumValuesExtern() {
        val r = transpile("""
            package test.Main
            enum class Color { RED, GREEN, BLUE }
            fun main(args: Array<String>) {
                val arr = enumValues<Color>()
                println(arr[0])
            }
        """)
        r.headerContains("extern const test_Main_Color test_Main_Color_values[3];")
        r.headerContains("extern const int32_t test_Main_Color_values\$len;")
        r.sourceContains("test_Main_Color_values[]")
        r.sourceContains("test_Main_Color_values\$len")
    }

    @Test fun enumValuesSize() {
        val r = transpileMain("""
            val sz = enumValues<Color>().size
            println(sz)
        """, decls = colorDecl)
        r.sourceContains("\$len")
    }

    // ── enumValueOf ───────────────────────────────────────────────────

    @Test fun enumValueOf() {
        val r = transpileMain("""
            val c = enumValueOf<Color>("GREEN")
            println(c)
        """, decls = colorDecl)
        r.sourceContains("test_Main_Color_valueOf")
        r.sourceContains("ktc_string_eq")
        r.sourceContains("ktc_str(\"GREEN\")")
    }

    @Test fun enumValueOfHeader() {
        val r = transpile("""
            package test.Main
            enum class Color { RED, GREEN, BLUE }
            fun main(args: Array<String>) {
                val c = enumValueOf<Color>("RED")
                println(c)
            }
        """)
        r.headerContains("test_Main_Color test_Main_Color_valueOf(ktc_String name);")
        r.headerContains("extern const test_Main_Color test_Main_Color_values[3];")
    }

    // ── .name / .ordinal on enum values ───────────────────────────────

    @Test fun enumDotName() {
        val r = transpileMain("""
            val c = Color.GREEN
            val n: String = c.name
        """, decls = colorDecl)
        r.sourceContains("_names[")
        r.sourceContains("test_Main_Color_names")
    }

    @Test fun enumDotOrdinal() {
        val r = transpileMain("""
            val c = Color.BLUE
            val o: Int = c.ordinal
        """, decls = colorDecl)
        r.sourceContains("test_Main_Color_BLUE")
    }

    @Test fun enumDotNameDirect() {
        val r = transpileMain("""
            val n: String = Color.RED.name
        """, decls = colorDecl)
        r.sourceContains("_names[")
    }
}

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
}

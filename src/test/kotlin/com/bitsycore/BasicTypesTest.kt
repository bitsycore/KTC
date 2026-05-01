package com.bitsycore

import kotlin.test.Test

/**
 * Tests for basic types, literals, and variable declarations.
 */
class BasicTypesTest : TranspilerTestBase() {

    // ── Int ──────────────────────────────────────────────────────────

    @Test fun intLiteral() {
        val r = transpileMain("val x: Int = 42\nprintln(x)")
        r.sourceContains("int32_t x = 42;")
    }

    @Test fun intArithmetic() {
        val r = transpileMain("val a = 10\nval b = 20\nval c = a + b\nprintln(c)")
        r.sourceContains("int32_t c = (a + b);")
    }

    @Test fun intNegative() {
        val r = transpileMain("val x = -5\nprintln(x)")
        r.sourceContains("int32_t x = (-5);")
    }

    // ── Long ─────────────────────────────────────────────────────────

    @Test fun longLiteral() {
        val r = transpileMain("val x: Long = 100L\nprintln(x)")
        r.sourceContains("int64_t x = 100LL;")
    }

    // ── Float ────────────────────────────────────────────────────────

    @Test fun floatLiteral() {
        val r = transpileMain("val x = 3.14f\nprintln(x)")
        r.sourceContains("float x = 3.14f;")
    }

    // ── Double ───────────────────────────────────────────────────────

    @Test fun doubleLiteral() {
        val r = transpileMain("val x = 3.14\nprintln(x)")
        r.sourceContains("double x = 3.14;")
    }

    // ── Boolean ──────────────────────────────────────────────────────

    @Test fun boolTrue() {
        val r = transpileMain("val x = true\nprintln(x)")
        r.sourceContains("bool x = true;")
    }

    @Test fun boolFalse() {
        val r = transpileMain("val x = false\nprintln(x)")
        r.sourceContains("bool x = false;")
    }

    // ── Char ─────────────────────────────────────────────────────────

    @Test fun charLiteral() {
        val r = transpileMain("val c = 'A'\nprintln(c)")
        r.sourceContains("char c = 'A';")
    }

    // ── String ───────────────────────────────────────────────────────

    @Test fun stringLiteral() {
        val r = transpileMain("val s = \"hello\"\nprintln(s)")
        r.sourceContains("kt_String s = kt_str(\"hello\")")
    }

    @Test fun stringLength() {
        val r = transpileMain("val s = \"hello\"\nprintln(s.length)")
        r.sourceContains(".len")
    }

    // ── Val vs Var ───────────────────────────────────────────────────

    @Test fun valDeclaration() {
        val r = transpileMain("val x = 10")
        r.sourceContains("int32_t x = 10;")
    }

    @Test fun varDeclaration() {
        val r = transpileMain("var x = 10\nx = 20")
        r.sourceContains("int32_t x = 10;")
        r.sourceContains("x = 20;")
    }

    @Test fun varReassignment() {
        val r = transpileMain("var x = 10\nx += 5")
        r.sourceContains("x += 5;")
    }

    // ── Type conversions ─────────────────────────────────────────────

    @Test fun toFloat() {
        val r = transpileMain("val x = 42\nval f = x.toFloat()")
        r.sourceContains("(float)(x)")
    }

    @Test fun toLong() {
        val r = transpileMain("val x = 42\nval l = x.toLong()")
        r.sourceContains("(int64_t)(x)")
    }

    @Test fun toInt() {
        val r = transpileMain("val f = 3.14\nval i = f.toInt()")
        r.sourceContains("(int32_t)(f)")
    }

    @Test fun toDouble() {
        val r = transpileMain("val x = 42\nval d = x.toDouble()")
        r.sourceContains("(double)(x)")
    }

    @Test fun toByte() {
        val r = transpileMain("val x = 65\nval b = x.toByte()")
        r.sourceContains("(int8_t)(x)")
    }

    @Test fun toChar() {
        val r = transpileMain("val x = 65\nval c = x.toChar()")
        r.sourceContains("(char)(x)")
    }

    // ── println for each type ────────────────────────────────────────

    @Test fun printlnInt() {
        val r = transpileMain("println(42)")
        r.sourceContains("PRId32")
    }

    @Test fun printlnString() {
        val r = transpileMain("""println("hello")""")
        r.sourceContains("%.*s")
    }

    @Test fun printlnBool() {
        val r = transpileMain("println(true)")
        r.sourceContains("\"true\"")
    }

    @Test fun printlnFloat() {
        val r = transpileMain("println(3.14f)")
        r.sourceContains("%f")
    }

    @Test fun printlnEmpty() {
        val r = transpileMain("println()")
        r.sourceContains("printf(\"\\n\")")
    }
}

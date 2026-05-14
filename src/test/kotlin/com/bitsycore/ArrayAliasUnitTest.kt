package com.bitsycore.ktc

import kotlin.test.Test

/*
Tests for all type-specific array constructors and factory functions:
  XxxArray(size)      → stack allocation via alloca
  xxxArrayOf(v1, vn)  → stack compound literal
Verified for all built-in types including unsigned variants.
*/
class ArrayAliasUnitTest : TranspilerTestBase() {

    // ── Signed integer arrays ────────────────────────────────────────

    @Test fun byteArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = byteArrayOf(10, 20)", decls = "")
        v.sourceContains("ktc_Byte a[] = {10, 20};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    @Test fun shortArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = shortArrayOf(10, 20)", decls = "")
        v.sourceContains("ktc_Short a[] = {10, 20};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    @Test fun intArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = intArrayOf(10, 20)", decls = "")
        v.sourceContains("ktc_Int a[] = {10, 20};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    @Test fun longArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = longArrayOf(10L, 20L)", decls = "")
        v.sourceContains("ktc_Long a[] = {10LL, 20LL};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    // ── Float / Double ───────────────────────────────────────────────

    @Test fun floatArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = floatArrayOf(1.5f, 2.5f)", decls = "")
        v.sourceContains("ktc_Float a[] = {1.5f, 2.5f};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    @Test fun doubleArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = doubleArrayOf(1.1, 2.2)", decls = "")
        v.sourceContains("ktc_Double a[] = {1.1, 2.2};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    // ── Boolean / Char ───────────────────────────────────────────────

    @Test fun booleanArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = booleanArrayOf(true, false)", decls = "")
        v.sourceContains("ktc_Bool a[] = {true, false};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    @Test fun charArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = charArrayOf('a', 'b')", decls = "")
        v.sourceContains("ktc_Char a[] = {'a', 'b'};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    // ── Unsigned integer arrays ──────────────────────────────────────

    @Test fun ubyteArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = ubyteArrayOf(10u, 20u)", decls = "")
        v.sourceContains("ktc_UByte a[] = {10U, 20U};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    @Test fun ushortArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = ushortArrayOf(10u, 20u)", decls = "")
        v.sourceContains("ktc_UShort a[] = {10U, 20U};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    @Test fun uintArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = uintArrayOf(10u, 20u)", decls = "")
        v.sourceContains("ktc_UInt a[] = {10U, 20U};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    @Test fun ulongArrayOfEmitsCompoundLiteral() {
        val v = transpileMain("val a = ulongArrayOf(10UL, 20UL)", decls = "")
        v.sourceContains("ktc_ULong a[] = {10ULL, 20ULL};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    // ── Generic arrayOf<T> ───────────────────────────────────────────

    @Test fun arrayOfStringEmitsCompoundLiteral() {
        val v = transpileMain("val a = arrayOf(\"hello\", \"world\")", decls = "")
        v.sourceContains("ktc_String a[] = {ktc_str(\"hello\"), ktc_str(\"world\")};")
        v.sourceContains("ktc_Int a\$len = 2;")
    }

    // ── Type inference ───────────────────────────────────────────────

    @Test fun byteArrayInfersByteArrayType() {
        val v = transpileMain("val a = byteArrayOf(1)", decls = "")
        v.sourceContains("ktc_Byte a[]")
    }

    @Test fun ubyteArrayInfersUByteArrayType() {
        val v = transpileMain("val a = ubyteArrayOf(1u)", decls = "")
        v.sourceContains("ktc_UByte a[]")
    }

    @Test fun ushortArrayInfersUShortArrayType() {
        val v = transpileMain("val a = ushortArrayOf(1u)", decls = "")
        v.sourceContains("ktc_UShort a[]")
    }

    @Test fun uintArrayInfersUIntArrayType() {
        val v = transpileMain("val a = uintArrayOf(1u)", decls = "")
        v.sourceContains("ktc_UInt a[]")
    }

    @Test fun ulongArrayInfersULongArrayType() {
        val v = transpileMain("val a = ulongArrayOf(1UL)", decls = "")
        v.sourceContains("ktc_ULong a[]")
    }
}

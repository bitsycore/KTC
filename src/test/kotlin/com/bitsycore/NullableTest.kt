package com.bitsycore

import kotlin.test.Test

/**
 * Tests for nullable types, null safety enforcement, safe calls,
 * elvis operator, not-null assertion.
 */
class NullableTest : TranspilerTestBase() {

    // ── Nullable variable declaration ────────────────────────────────

    @Test fun nullableIntWithValue() {
        val r = transpileMain("var x: Int? = 42")
        r.sourceContains("int32_t x = 42;")
        r.sourceContains("bool x\$has = true;")
    }

    @Test fun nullableIntNull() {
        val r = transpileMain("var x: Int? = null")
        r.sourceContains("bool x\$has = false;")
    }

    @Test fun nullableStringWithValue() {
        val r = transpileMain("""var s: String? = "hello"""")
        r.sourceContains("kt_String s = kt_str(\"hello\")")
        r.sourceContains("bool s\$has = true;")
    }

    @Test fun nullableStringNull() {
        val r = transpileMain("var s: String? = null")
        r.sourceContains("bool s\$has = false;")
    }

    // ── Nullable return (out-pointer convention) ─────────────────────

    @Test fun nullableReturnSignature() {
        val r = transpile("""
            package test.Main
            fun findValue(flag: Boolean): Int? {
                if (flag) return 42
                return null
            }
            fun main(args: Array<String>) {
                val a = findValue(true)
            }
        """)
        // Return type is bool, last param is Int* $out
        r.sourceContains("bool test_Main_findValue(bool flag, int32_t* \$out)")
    }

    @Test fun nullableReturnNull() {
        val r = transpile("""
            package test.Main
            fun findValue(): Int? { return null }
            fun main(args: Array<String>) { val a = findValue() }
        """)
        r.sourceContains("return false;")
    }

    @Test fun nullableReturnValue() {
        val r = transpile("""
            package test.Main
            fun findValue(): Int? { return 42 }
            fun main(args: Array<String>) { val a = findValue() }
        """)
        r.sourceContains("*\$out = 42;")
        r.sourceContains("return true;")
    }

    @Test fun nullableReturnCallSite() {
        val r = transpile("""
            package test.Main
            fun findValue(): Int? { return 42 }
            fun main(args: Array<String>) {
                val a = findValue()
                println(a!!)
            }
        """)
        // Caller gets value + $has
        r.sourceContains("int32_t a;")
        r.sourceContains("bool a\$has = test_Main_findValue(&a);")
    }

    // ── Null comparison ──────────────────────────────────────────────

    @Test fun nullComparisonEquals() {
        val r = transpileMain("var x: Int? = null\nif (x == null) println(0)")
        r.sourceContains("!x\$has")
    }

    @Test fun nullComparisonNotEquals() {
        val r = transpileMain("var x: Int? = 42\nif (x != null) println(x!!)")
        r.sourceContains("x\$has")
    }

    // ── Elvis operator ───────────────────────────────────────────────

    @Test fun elvisOperator() {
        val r = transpileMain("var x: Int? = null\nval y = x ?: 99")
        r.sourceContains("x\$has")
        r.sourceContains("99")
    }

    // ── Not-null assertion ───────────────────────────────────────────

    @Test fun notNullAssertion() {
        val r = transpileMain("var x: Int? = 42\nprintln(x!!)")
        r.sourceContains("x")  // !! just unwraps the value
    }

    // ── Safe call on method ──────────────────────────────────────────

    @Test fun safeCallOnExtension() {
        val r = transpile("""
            package test.Main
            fun String.show() { println(this) }
            fun findStr(): String? { return null }
            fun main(args: Array<String>) {
                val s = findStr()
                s?.show()
            }
        """)
        // Safe call: if (s$has) { String_show(s); }
        r.sourceContains("s\$has")
        r.sourceContains("test_Main_String_show(s)")
    }

    // ── Null safety enforcement ──────────────────────────────────────

    @Test fun dotOnNullableReceiverErrors() {
        transpileMainExpectError(
            body = "val s = findStr()\ns.length",
            expectedMsg = "Only safe",
            decls = "fun findStr(): String? { return null }"
        )
    }

    // ── Assign null to nullable var ──────────────────────────────────

    @Test fun assignNullToNullableVar() {
        val r = transpileMain("var x: Int? = 42\nx = null")
        r.sourceContains("x\$has = false;")
    }

    @Test fun assignValueToNullableVar() {
        val r = transpileMain("var x: Int? = null\nx = 10")
        r.sourceContains("x = 10;")
        r.sourceContains("x\$has = true;")
    }

    // ── Passing null to nullable param ───────────────────────────────

    @Test fun passNullToNullableParam() {
        val r = transpile("""
            package test.Main
            fun show(value: Int?) {
                if (value != null) println(value!!)
            }
            fun main(args: Array<String>) {
                show(null)
            }
        """)
        // Passing null literal → pass 0 for value, false for $has
        r.sourceMatches(Regex("test_Main_show\\(0.*false\\)"))
    }

    @Test fun passValueToNullableParam() {
        val r = transpile("""
            package test.Main
            fun show(value: Int?) {
                if (value != null) println(value!!)
            }
            fun main(args: Array<String>) {
                show(99)
            }
        """)
        // Passing non-null literal → pass value, true for $has
        r.sourceMatches(Regex("test_Main_show\\(99.*true\\)"))
    }
}

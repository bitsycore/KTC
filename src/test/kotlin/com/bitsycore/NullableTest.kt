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
        r.sourceContains("ktc_Int32_Optional x = (ktc_Int32_Optional){SOME, 42};")
    }

    @Test fun nullableIntNull() {
        val r = transpileMain("var x: Int? = null")
        r.sourceContains("ktc_Int32_Optional x = (ktc_Int32_Optional){NONE};")
    }

    @Test fun nullableStringWithValue() {
        val r = transpileMain("""var s: String? = "hello"""")
        r.sourceContains("ktc_String_Optional s = (ktc_String_Optional){SOME, ktc_str(\"hello\")};")
    }

    @Test fun nullableStringNull() {
        val r = transpileMain("var s: String? = null")
        r.sourceContains("ktc_String_Optional s = (ktc_String_Optional){NONE};")
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
        // Return type is ktc_Int32_Optional
        r.sourceContains("ktc_Int32_Optional test_Main_findValue(bool flag)")
    }

    @Test fun nullableReturnNull() {
        val r = transpile("""
            package test.Main
            fun findValue(): Int? { return null }
            fun main(args: Array<String>) { val a = findValue() }
        """)
        r.sourceContains("return (ktc_Int32_Optional){NONE};")
    }

    @Test fun nullableReturnValue() {
        val r = transpile("""
            package test.Main
            fun findValue(): Int? { return 42 }
            fun main(args: Array<String>) { val a = findValue() }
        """)
        r.sourceContains("return (ktc_Int32_Optional){SOME, 42};")
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
        // Caller gets Optional directly
        r.sourceContains("ktc_Int32_Optional a = test_Main_findValue();")
        r.sourceContains("a.value")
    }

    // ── Null comparison ──────────────────────────────────────────────

    @Test fun nullComparisonEquals() {
        val r = transpileMain("var x: Int? = null\nif (x == null) println(0)")
        r.sourceContains("x.tag == NONE")
    }

    @Test fun nullComparisonNotEquals() {
        val r = transpileMain("var x: Int? = 42\nif (x != null) println(x!!)")
        r.sourceContains("x.tag == SOME")
    }

    // ── Elvis operator ───────────────────────────────────────────────

    @Test fun elvisOperator() {
        val r = transpileMain("var x: Int? = null\nval y = x ?: 99")
        r.sourceContains("x.tag == SOME")
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
        // Safe call: if (s.tag == SOME) { String_show(s.value); }
        r.sourceContains("s.tag == SOME")
        r.sourceContains("s.value")
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
        r.sourceContains("x = (ktc_Int32_Optional){NONE};")
    }

    @Test fun assignValueToNullableVar() {
        val r = transpileMain("var x: Int? = null\nx = 10")
        r.sourceContains("x = (ktc_Int32_Optional){SOME, 10};")
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
        // Passing null literal → ktc_Int32_Optional{NONE}
        r.sourceContains("test_Main_show((ktc_Int32_Optional){NONE})")
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
        // Passing non-null literal → ktc_Int32_Optional{SOME, 99}
        r.sourceContains("test_Main_show((ktc_Int32_Optional){SOME, 99})")
    }

    // ── Printing nullable values ─────────────────────────────────────

    @Test fun printlnNullableInt() {
        val r = transpileMain("""
            var x: Int? = 42
            println(x)
        """)
        r.sourceContains("x.tag == SOME")
        r.sourceContains("printf(\"null")
    }

    @Test fun printlnNullableString() {
        val r = transpileMain("""
            var s: String? = null
            println(s)
        """)
        r.sourceContains("s.tag == SOME")
        r.sourceContains("printf(\"null")
    }

    @Test fun nullableInStringTemplate() {
        val r = transpileMain("""
            var x: Int? = null
            println("value=${'$'}x")
        """)
        r.sourceContains("ktc_sb_append_cstr")
        r.sourceContains("\"null\"")
    }
}

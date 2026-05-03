package com.bitsycore

import kotlin.test.Test

/**
 * Tests for smart casts: guard pattern, if-then narrowing, if-else narrowing,
 * val vs var exclusion.
 */
class SmartCastTest : TranspilerTestBase() {

    private val nullableFun = """
        fun findValue(flag: Boolean): Int? {
            if (flag) return 42
            return null
        }
        fun findStr(flag: Boolean): String? {
            if (flag) return "hello"
            return null
        }
    """

    // ── Guard pattern: if (x == null) return ─────────────────────────

    @Test fun guardSmartCastAllowsDotCall() {
        val r = transpile("""
            package test.Main
            fun String.show() { println(this) }
            fun findStr(): String? { return "hello" }
            fun main(args: Array<String>) {
                val s = findStr()
                if (s == null) return
                s.show()
            }
        """)
        // After guard, s is smart-cast to String — s.value unwraps Optional
        r.sourceContains("test_Main_String_show(s.value)")
    }

    @Test fun guardSmartCastWithBreak() {
        val r = transpile("""
            package test.Main
            $nullableFun
            fun main(args: Array<String>) {
                for (i in 0..5) {
                    val x = findValue(i > 2)
                    if (x == null) break
                    println(x)
                }
            }
        """)
        // After guard with break, x should be smart-cast — printed via .value
        r.sourceContains("x.value")
    }

    @Test fun guardSmartCastWithContinue() {
        val r = transpile("""
            package test.Main
            $nullableFun
            fun main(args: Array<String>) {
                for (i in 0..5) {
                    val x = findValue(i > 2)
                    if (x == null) continue
                    println(x)
                }
            }
        """)
        r.sourceNotContains("Only safe")
    }

    // ── If-then narrowing: if (x != null) { x.method() } ────────────

    @Test fun ifThenSmartCast() {
        val r = transpile("""
            package test.Main
            fun String.show() { println(this) }
            fun findStr(): String? { return "hello" }
            fun main(args: Array<String>) {
                val s = findStr()
                if (s != null) {
                    s.show()
                }
            }
        """)
        r.sourceContains("test_Main_String_show(s.value)")
    }

    // ── If-else narrowing: if (x == null) { ... } else { x.use() } ──

    @Test fun ifElseSmartCast() {
        val r = transpile("""
            package test.Main
            $nullableFun
            fun main(args: Array<String>) {
                val x = findValue(true)
                if (x == null) {
                    println("null")
                } else {
                    println(x)
                }
            }
        """)
        // In else branch, x is smart-cast — should print x.value
        r.sourceContains("x.value")
    }

    // ── Var NOT smart-cast ───────────────────────────────────────────

    @Test fun varNotSmartCastInGuard() {
        // var should NOT be smart-cast even with guard pattern
        transpileExpectError("""
            package test.Main
            fun String.show() { println(this) }
            fun findStr(): String? { return "hello" }
            fun main(args: Array<String>) {
                var s = findStr()
                if (s == null) return
                s.show()
            }
        """, "Only safe")
    }

    @Test fun varNotSmartCastInIfThen() {
        transpileExpectError("""
            package test.Main
            fun String.show() { println(this) }
            fun findStr(): String? { return "hello" }
            fun main(args: Array<String>) {
                var s = findStr()
                if (s != null) {
                    s.show()
                }
            }
        """, "Only safe")
    }

    // ── Val IS smart-cast ────────────────────────────────────────────

    @Test fun valSmartCastWorks() {
        // val should be smart-cast
        val r = transpile("""
            package test.Main
            fun String.show() { println(this) }
            fun findStr(): String? { return "hello" }
            fun main(args: Array<String>) {
                val s = findStr()
                if (s == null) return
                s.show()
            }
        """)
        r.sourceContains("test_Main_String_show(s.value)")
    }

    // ── Smart cast on this in nullable receiver ──────────────────────

    @Test fun thisSmartCastInNullableReceiver() {
        val r = transpile("""
            package test.Main
            fun String?.safe() {
                if (this != null) println(this)
            }
            fun main(args: Array<String>) {
                val s: String? = "hello"
                s.safe()
            }
        """)
        // Inside the if, this should be smart-cast to String → $self.value
        r.sourceContains("(\$self.value).len")
        r.sourceContains("(\$self.value).ptr")
    }

    // ── No smart cast without guard/if ───────────────────────────────

    @Test fun noSmartCastWithoutCheck() {
        transpileExpectError("""
            package test.Main
            fun String.show() { println(this) }
            fun findStr(): String? { return "hello" }
            fun main(args: Array<String>) {
                val s = findStr()
                s.show()
            }
        """, "Only safe")
    }
}

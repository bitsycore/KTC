package com.bitsycore

import kotlin.test.Test

/**
 * Tests for intrinsic Pair<A,B> type, vararg parameters, and spread operator.
 */
class PairVarargTest : TranspilerTestBase() {

    // ── Pair: to infix ──────────────────────────────────────────────

    @Test fun pairToInfix() {
        val r = transpileMain("""
            val p = 1 to "hello"
            println(p.first)
        """)
        r.headerContains("ktc_Pair_Int_String")
        r.sourceContains(".first")
    }

    @Test fun pairToInfixTypes() {
        val r = transpileMain("""
            val p = 10 to 20
            println(p.second)
        """)
        r.headerContains("ktc_Pair_Int_Int")
        r.sourceContains(".second")
    }

    // ── Pair: constructor ───────────────────────────────────────────

    @Test fun pairConstructorExplicitTypes() {
        val r = transpileMain("""
            val p = Pair<Int, Int>(3, 4)
            println(p.first)
        """)
        r.headerContains("ktc_Pair_Int_Int")
    }

    @Test fun pairConstructorInferred() {
        val r = transpileMain("""
            val p = Pair(true, 42)
            println(p.second)
        """)
        r.headerContains("ktc_Pair_Boolean_Int")
    }

    // ── Pair: typedef struct emitted ────────────────────────────────

    @Test fun pairTypedefEmitted() {
        val r = transpileMain("""
            val p = 1 to 2
        """)
        r.headerContains("typedef struct { int32_t first; int32_t second; } ktc_Pair_Int_Int;")
    }

    // ── Pair: as function parameter / return ────────────────────────

    @Test fun pairAsParam() {
        val r = transpile("""
            package test.Main
            fun printPair(p: Pair<Int, Int>) {
                println(p.first)
            }
            fun main(args: Array<String>) {
                printPair(1 to 2)
            }
        """)
        r.headerContains("ktc_Pair_Int_Int")
        r.sourceContains("p.first")
    }

    // ── vararg: basic ───────────────────────────────────────────────

    @Test fun varargBasic() {
        val r = transpile("""
            package test.Main
            fun sum(vararg nums: Int): Int {
                var total = 0
                for (n in nums) {
                    total = total + n
                }
                return total
            }
            fun main(args: Array<String>) {
                println(sum(1, 2, 3))
            }
        """)
        // Function signature should have pointer + len
        r.headerContains("int32_t* nums")
        r.headerContains("int32_t nums\$len")
        // Call site should pack args into array
        r.sourceContains("int32_t")
        r.sourceContains("{1, 2, 3}")
    }

    @Test fun varargEmpty() {
        val r = transpile("""
            package test.Main
            fun count(vararg items: Int): Int {
                return items.size
            }
            fun main(args: Array<String>) {
                println(count())
            }
        """)
        // Empty vararg should pass NULL, 0
        r.sourceContains("NULL")
        r.sourceContains(", 0)")
    }

    @Test fun varargSize() {
        val r = transpile("""
            package test.Main
            fun count(vararg items: Int): Int {
                return items.size
            }
            fun main(args: Array<String>) {
                println(count(10, 20))
            }
        """)
        // .size on vararg should use $len
        r.sourceContains("items\$len")
    }

    // ── spread: pass array to vararg ────────────────────────────────

    @Test fun spreadArray() {
        val r = transpile("""
            package test.Main
            fun sum(vararg nums: Int): Int {
                var total = 0
                for (n in nums) {
                    total = total + n
                }
                return total
            }
            fun main(args: Array<String>) {
                val arr = intArrayOf(10, 20, 30)
                println(sum(*arr))
            }
        """)
        // Spread should pass arr, arr$len directly (no compound literal)
        r.sourceContains("arr, arr\$len")
    }

    // ── vararg with preceding params ────────────────────────────────

    @Test fun varargWithPrefix() {
        val r = transpile("""
            package test.Main
            fun log(tag: Int, vararg msgs: Int): Int {
                return tag
            }
            fun main(args: Array<String>) {
                println(log(42, 1, 2, 3))
            }
        """)
        r.headerContains("int32_t tag")
        r.headerContains("int32_t* msgs")
        r.headerContains("int32_t msgs\$len")
    }
}

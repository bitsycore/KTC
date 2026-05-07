package com.bitsycore

import kotlin.test.Test

class OperatorOverloadUnitTest : TranspilerTestBase() {

    @Test fun operatorGetIndex() {
        val r = transpile("""
            package test.Main
            class IntMap(val arr: IntArray) {
                operator fun get(index: Int): Int = arr[index]
            }
            fun main(args: Array<String>) {
                val m = IntMap(intArrayOf(10, 20, 30))
                val v = m[1]
            }
        """)
        r.sourceContains("IntMap_get(&m, 1)")
    }

    @Test fun operatorSetIndex() {
        val r = transpile("""
            package test.Main
            class IntMap(var arr: IntArray) {
                operator fun set(index: Int, value: Int) {
                    arr[index] = value
                }
            }
            fun main(args: Array<String>) {
                val m = IntMap(intArrayOf(10, 20, 30))
                m[1] = 99
            }
        """)
        r.sourceContains("IntMap_set(&m, 1, 99)")
    }

    @Test fun operatorContains() {
        val r = transpile("""
            package test.Main
            class Range2(val lo: Int, val hi: Int) {
                operator fun contains(x: Int): Boolean = x >= lo && x <= hi
            }
            fun main(args: Array<String>) {
                val r = Range2(1, 10)
                val ok = 5 in r
            }
        """)
        r.sourceContains("Range2_contains(&r, 5)")
    }

    @Test fun operatorContainsNotIn() {
        val r = transpile("""
            package test.Main
            class Range2(val lo: Int, val hi: Int) {
                operator fun contains(x: Int): Boolean = x >= lo && x <= hi
            }
            fun main(args: Array<String>) {
                val r = Range2(1, 10)
                val ok = 15 !in r
            }
        """)
        r.sourceContains("!Range2_contains(&r, 15)")
    }

    @Test fun operatorGetOnInterface() {
        val r = transpile("""
            package test.Main
            interface Indexed {
                operator fun get(index: Int): Int
            }
            class IntList(val arr: IntArray) : Indexed {
                override fun get(index: Int): Int = arr[index]
            }
            fun main(args: Array<String>) {
                val lst: Indexed = IntList(intArrayOf(1, 2, 3))
                val v = lst[0]
            }
        """)
        r.sourceContains("lst.vt->get((void*)&lst, 0)")
    }

    @Test fun operatorSetOnInterface() {
        val r = transpile("""
            package test.Main
            interface MutableIndexed {
                operator fun set(index: Int, value: Int)
            }
            class IntList(var arr: IntArray) : MutableIndexed {
                override fun set(index: Int, value: Int) { arr[index] = value }
            }
            fun main(args: Array<String>) {
                val lst: MutableIndexed = IntList(intArrayOf(1, 2, 3))
                lst[0] = 99
            }
        """)
        r.sourceContains("lst.vt->set((void*)&lst, 0, 99)")
    }

    @Test fun operatorIterator() {
        val r = transpile("""
            package test.Main
            class IntRange2(val lo: Int, val hi: Int) {
                operator fun iterator(): Iterator<Int> = TODO()
            }
            fun main(args: Array<String>) {
                val r = IntRange2(1, 10)
                for (x in r) { }
            }
        """)
        r.sourceContains("IntRange2_iterator")
    }

    @Test fun operatorIteratorWithStdlib() {
        val r = transpileMainWithStdlib("""
            val list = mutableListOf(1, 2, 3)
            var sum = 0
            for (x in list) { sum += x }
        """)
        r.sourceContains("ArrayList_iterator")
    }

    @Test fun operatorContainsMap() {
        val r = transpileMainWithStdlib("""
            val map = mutableMapOf("a" to 1, "b" to 2)
            val has = "a" in map
        """)
        r.sourceContains("containsKey")
    }

    @Test fun operatorGetMap() {
        val r = transpileMainWithStdlib("""
            val map = mutableMapOf("a" to 1, "b" to 2)
            val v = map["a"]
        """)
        r.sourceContains("HashMap_get")
    }

    @Test fun operatorSetMap() {
        val r = transpileMainWithStdlib("""
            val map = mutableMapOf("a" to 1, "b" to 2)
            map["a"] = 99
        """)
        r.sourceContains("HashMap_set")
    }
}

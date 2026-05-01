package com.bitsycore

import kotlin.test.Test

/**
 * Tests for arrays (arrayOf, intArrayOf) and ArrayList.
 */
class ArrayTest : TranspilerTestBase() {

    // ── intArrayOf ───────────────────────────────────────────────────

    @Test fun intArrayOf() {
        val r = transpileMain("""
            val arr = intArrayOf(10, 20, 30)
            println(arr[0])
        """)
        r.sourceContains("int32_t arr[] = {10, 20, 30};")
        r.sourceContains("arr[0]")
    }

    @Test fun intArraySize() {
        val r = transpileMain("""
            val arr = intArrayOf(10, 20, 30)
            println(arr.size)
        """)
        r.sourceContains("arr\$len")
    }

    // ── arrayOf with data class ──────────────────────────────────────

    @Test fun arrayOfDataClass() {
        val r = transpile("""
            package test.Main
            data class Vec2(val x: Float, val y: Float)
            fun main(args: Array<String>) {
                val pts = arrayOf(Vec2(1.0f, 2.0f), Vec2(3.0f, 4.0f))
                println(pts.size)
            }
        """)
        r.sourceContains("test_Main_Vec2 pts[] =")
    }

    @Test fun arrayOfStrings() {
        val r = transpileMain("""
            val names = arrayOf("Alice", "Bob")
            println(names[0])
        """)
        r.sourceContains("kt_String names[] =")
    }

    // ── For over array ───────────────────────────────────────────────

    @Test fun forOverIntArray() {
        val r = transpileMain("""
            val arr = intArrayOf(1, 2, 3)
            for (x in arr) { println(x) }
        """)
        r.sourceContains("arr\$len")
    }

    @Test fun forOverStringArray() {
        val r = transpileMain("""
            val names = arrayOf("A", "B", "C")
            for (name in names) { println(name) }
        """)
        r.sourceContains("names\$len")
    }

    // ── Array index write ────────────────────────────────────────────

    @Test fun arrayIndexWrite() {
        val r = transpileMain("""
            val arr = intArrayOf(10, 20, 30)
            arr[1] = 99
        """)
        r.sourceContains("arr[1] = 99;")
    }

    // ── ArrayList ────────────────────────────────────────────────────

    @Test fun intArrayList() {
        val r = transpileMain("""
            val nums = IntArrayList()
            nums.add(10)
            nums.add(20)
            println(nums.size)
            println(nums[0])
        """)
        r.headerContains("typedef struct")
        r.sourceContains("_add(")
    }

    @Test fun arrayListRemoveAt() {
        val r = transpileMain("""
            val nums = IntArrayList()
            nums.add(10)
            nums.removeAt(0)
        """)
        r.sourceContains("_removeAt(")
    }

    @Test fun arrayListFree() {
        val r = transpileMain("""
            val nums = IntArrayList()
            nums.free()
        """)
        r.sourceContains("_free(")
    }

    // ── mutableListOf ────────────────────────────────────────────────

    @Test fun mutableListOfStrings() {
        val r = transpileMain("""
            val names = mutableListOf("hello", "world")
            names.add("!")
            println(names.size)
        """)
        r.sourceContains("_add(")
    }

    // ── For over ArrayList ───────────────────────────────────────────

    @Test fun forOverArrayList() {
        val r = transpileMain("""
            val nums = IntArrayList()
            nums.add(1)
            nums.add(2)
            for (n in nums) { println(n) }
            nums.free()
        """)
        r.sourceContains("for (")
    }
}

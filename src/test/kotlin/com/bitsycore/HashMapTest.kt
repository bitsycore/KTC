package com.bitsycore

import kotlin.test.Test

/**
 * Tests for HashMap: declaration, put, get, remove, containsKey, index operators,
 * mapOf, mutableMapOf.
 */
class HashMapTest : TranspilerTestBase() {

    // ── HashMap declaration ──────────────────────────────────────────

    @Test fun hashMapIntInt() {
        val r = transpileMain("""
            var scores = HashMap<Int, Int>()
            scores.put(1, 100)
            println(scores.get(1))
            scores.free()
        """)
        r.sourceContains("kt_MapInfo")
        r.sourceContains("kt_map_put")
        r.sourceContains("kt_map_find")
    }

    @Test fun hashMapStringInt() {
        val r = transpileMain("""
            var wc = HashMap<String, Int>()
            wc.put("hello", 1)
            println(wc.get("hello"))
            wc.free()
        """)
        r.sourceContains("kt_MapInfo")
    }

    // ── Index operators ──────────────────────────────────────────────

    @Test fun hashMapIndexGet() {
        val r = transpileMain("""
            var scores = HashMap<Int, Int>()
            scores.put(1, 100)
            println(scores[1])
            scores.free()
        """)
        r.sourceContains("kt_map_find")
    }

    @Test fun hashMapIndexSet() {
        val r = transpileMain("""
            var scores = HashMap<Int, Int>()
            scores[1] = 100
            scores.free()
        """)
        r.sourceContains("kt_map_put")
    }

    // ── containsKey ──────────────────────────────────────────────────

    @Test fun hashMapContainsKey() {
        val r = transpileMain("""
            var scores = HashMap<Int, Int>()
            scores.put(1, 100)
            println(scores.containsKey(1))
            scores.free()
        """)
        r.sourceContains("kt_map_find")
    }

    // ── remove ───────────────────────────────────────────────────────

    @Test fun hashMapRemove() {
        val r = transpileMain("""
            var scores = HashMap<Int, Int>()
            scores.put(1, 100)
            scores.remove(1)
            scores.free()
        """)
        r.sourceContains("kt_map_remove")
    }

    // ── size ─────────────────────────────────────────────────────────

    @Test fun hashMapSize() {
        val r = transpileMain("""
            var scores = HashMap<Int, Int>()
            scores.put(1, 100)
            println(scores.size)
            scores.free()
        """)
        r.sourceContains(".len")
    }

    // ── mapOf sugar ──────────────────────────────────────────────────

    @Test fun mapOfSugar() {
        val r = transpileMain("""
            val colors = mapOf("red" to 1, "green" to 2, "blue" to 3)
            println(colors["red"])
        """)
        r.sourceContains("kt_map_put")
        r.sourceContains("kt_map_find")
    }

    // ── mutableMapOf sugar ───────────────────────────────────────────

    @Test fun mutableMapOfSugar() {
        val r = transpileMain("""
            var items = mutableMapOf(10 to 100, 20 to 200)
            items[30] = 300
            println(items.size)
            items.free()
        """)
        r.sourceContains("kt_map_put")
    }
}

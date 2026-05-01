package com.bitsycore

import kotlin.test.Test

/**
 * Tests for Heap<T> (heap-allocated objects), malloc, free, typed pointers.
 */
class HeapTest : TranspilerTestBase() {

    private val vec2Decl = "data class Vec2(val x: Float, val y: Float)"

    // ── malloc<Class> → heap constructor ─────────────────────────────

    @Test fun mallocClass() {
        val r = transpileMain("val p = malloc<Vec2>(10.0f, 20.0f)", decls = vec2Decl)
        r.sourceContains("test_Main_Vec2_new(10.0f, 20.0f)")
    }

    // ── Heap field access ────────────────────────────────────────────

    @Test fun heapFieldRead() {
        val r = transpileMain(
            "val p = malloc<Vec2>(10.0f, 20.0f)\nprintln(p.x)",
            decls = vec2Decl
        )
        r.sourceContains("p->x")
    }

    @Test fun heapFieldWrite() {
        val r = transpileMain(
            "val p = malloc<Vec2>(10.0f, 20.0f)\np.x = 99.0f",
            decls = vec2Decl
        )
        r.sourceContains("p->x = 99.0f;")
    }

    // ── .value() → dereference ───────────────────────────────────────

    @Test fun heapValue() {
        val r = transpileMain(
            "val p = malloc<Vec2>(10.0f, 20.0f)\nval v = p.value()",
            decls = vec2Decl
        )
        r.sourceContains("(*p)")
    }

    // ── .set() → update ──────────────────────────────────────────────

    @Test fun heapSet() {
        val r = transpileMain(
            "val p = malloc<Vec2>(10.0f, 20.0f)\np.set(Vec2(1.0f, 2.0f))",
            decls = vec2Decl
        )
        r.sourceContains("*p =")
    }

    // ── .toHeap() → stack to heap ────────────────────────────────────

    @Test fun stackToHeap() {
        val r = transpileMain(
            "val v = Vec2(5.0f, 6.0f)\nval hp = v.toHeap()",
            decls = vec2Decl
        )
        r.sourceContains("test_Main_Vec2_toHeap(v)")
    }

    // ── free ─────────────────────────────────────────────────────────

    @Test fun freeHeapPointer() {
        val r = transpileMain(
            "val p = malloc<Vec2>(10.0f, 20.0f)\nfree(p)",
            decls = vec2Decl
        )
        r.sourceContains("free(p)")
    }

    // ── Typed raw pointer ────────────────────────────────────────────

    @Test fun typedPointerMalloc() {
        val r = transpileMain("val ints = malloc<Int>(5)")
        r.sourceContains("(int32_t*)malloc(sizeof(int32_t) *")
    }

    @Test fun typedPointerIndexRead() {
        val r = transpileMain("val ints = malloc<Int>(5)\nprintln(ints[2])")
        r.sourceContains("ints[2]")
    }

    @Test fun typedPointerIndexWrite() {
        val r = transpileMain("val ints = malloc<Int>(5)\nints[0] = 42")
        r.sourceContains("ints[0] = 42;")
    }

    // ── Raw malloc ───────────────────────────────────────────────────

    @Test fun rawMalloc() {
        val r = transpileMain("val buf = malloc(1024)")
        r.sourceContains("malloc((size_t)(1024))")
    }

    @Test fun rawRealloc() {
        val r = transpileMain("val buf = malloc(1024)\nval buf2 = realloc(buf, 2048)")
        r.sourceContains("realloc(buf, (size_t)(2048))")
    }

    // ── Heap<T>? — pointer nullable ──────────────────────────────────

    @Test fun heapPtrNullable() {
        val r = transpileMain(
            "var q: Heap<Vec2>? = malloc<Vec2>(3.0f, 4.0f)\nq = null",
            decls = vec2Decl
        )
        // Heap<T>? uses NULL for null
        r.sourceContains("NULL")
    }

    @Test fun heapPtrNullCheck() {
        val r = transpileMain("""
            var q: Heap<Vec2>? = malloc<Vec2>(3.0f, 4.0f)
            if (q != null) {
                println(q!!.x)
            }
        """, decls = vec2Decl)
        r.sourceContains("q != NULL")
    }
}

package com.bitsycore

import org.junit.jupiter.api.assertThrows
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
            "val p = malloc<Vec2>(10.0f, 20.0f)!!\nprintln(p.x)",
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

    // ── .value() → Value<T> (same pointer, no copy) ────────────────

    @Test fun heapValue() {
        val r = transpileMain(
            "val p = malloc<Vec2>(10.0f, 20.0f)!!\nval v = p.value()",
            decls = vec2Decl
        )
        // .value() returns same pointer (Value<T>), NOT a dereference copy
        r.sourceNotContains("(*p)")
        r.sourceContains("= p;") // v = p (same pointer)
    }

    // ── .deref() → stack copy ────────────────────────────────────────

    @Test fun heapDeref() {
        val r = transpileMain(
            "val p = malloc<Vec2>(10.0f, 20.0f)!!\nval v = p.deref()",
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
        val r = transpileMain("val ints = malloc<Int>(5)!!\nprintln(ints[2])")
        r.sourceContains("ints[2]")
    }

    @Test fun typedPointerIndexWrite() {
        val r = transpileMain("val ints = malloc<Int>(5)!!\nints[0] = 42")
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

    @Test fun mallocReturnsNullable() {
        // malloc without !! should be nullable — accessing .x should error
        val ex = assertThrows<IllegalStateException> {
            transpileMain("val p = malloc<Vec2>(10.0f, 20.0f)\nprintln(p.x)", decls = vec2Decl)
        }
        assert(ex.message!!.contains("safe"))
    }

    @Test fun mallocNullCheckSmartCast() {
        // After null check, smart cast should allow access
        val r = transpileMain("""
            val p = malloc<Vec2>(10.0f, 20.0f)
            if (p == null) return
            println(p.x)
        """, decls = vec2Decl)
        r.sourceContains("p == NULL")
        r.sourceContains("p->x")
    }

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

    // ── Heap .toPtr() ────────────────────────────────────────────────

    @Test fun heapToPtr() {
        val r = transpileMain(
            "val h = malloc<Vec2>(1.0f, 2.0f)!!\nval p = h.toPtr()",
            decls = vec2Decl
        )
        r.sourceContains("\$heap = true;")
    }

    // ══════════════════════════════════════════════════════════════════
    // Ptr<T> tests
    // ══════════════════════════════════════════════════════════════════

    // ── Ptr from stack (.toPtr()) ────────────────────────────────────

    @Test fun stackToPtr() {
        val r = transpileMain(
            "val v = Vec2(1.0f, 2.0f)\nval p = v.toPtr()",
            decls = vec2Decl
        )
        r.sourceContains("&v")
        r.sourceContains("\$heap = false;")
    }

    // ── Ptr field access (auto-deref) ────────────────────────────────

    @Test fun ptrFieldAccess() {
        val r = transpileMain(
            "val v = Vec2(5.0f, 6.0f)\nval p = v.toPtr()\nprintln(p.x)",
            decls = vec2Decl
        )
        r.sourceContains("p->x")
    }

    // ── Ptr.isHeap() ─────────────────────────────────────────────────

    @Test fun ptrIsHeap() {
        val r = transpileMain(
            "val v = Vec2(1.0f, 2.0f)\nval p = v.toPtr()\nprintln(p.isHeap())",
            decls = vec2Decl
        )
        r.sourceContains("p\$heap")
    }

    // ── Ptr.asHeap() → nullable ──────────────────────────────────────

    @Test fun ptrAsHeap() {
        val r = transpileMain("""
            val v = Vec2(1.0f, 2.0f)
            val p = v.toPtr()
            val h = p.asHeap()
        """, decls = vec2Decl)
        r.sourceContains("p\$heap")
    }

    // ── Ptr.value() → Value<T> ───────────────────────────────────────

    @Test fun ptrValue() {
        val r = transpileMain(
            "val v = Vec2(1.0f, 2.0f)\nval p = v.toPtr()\nval vr = p.value()",
            decls = vec2Decl
        )
        // value() returns same pointer
        r.sourceNotContains("(*p)")
    }

    // ── Ptr.deref() → stack copy ─────────────────────────────────────

    @Test fun ptrDeref() {
        val r = transpileMain(
            "val v = Vec2(1.0f, 2.0f)\nval p = v.toPtr()\nval copy = p.deref()",
            decls = vec2Decl
        )
        r.sourceContains("(*")
    }

    // ── Ptr.set() ────────────────────────────────────────────────────

    @Test fun ptrSet() {
        val r = transpileMain(
            "val v = Vec2(1.0f, 2.0f)\nval p = v.toPtr()\np.set(Vec2(3.0f, 4.0f))",
            decls = vec2Decl
        )
        r.sourceContains("*")
    }

    // ══════════════════════════════════════════════════════════════════
    // Value<T> tests
    // ══════════════════════════════════════════════════════════════════

    // ── Value<T> from .value() — transparent field access ────────────

    @Test fun valueFieldAccess() {
        val r = transpileMain(
            "val h = malloc<Vec2>(10.0f, 20.0f)!!\nval v = h.value()\nprintln(v.x)",
            decls = vec2Decl
        )
        r.sourceContains("v->x")
    }

    // ── Value<T> field write ─────────────────────────────────────────

    @Test fun valueFieldWrite() {
        val r = transpileMain(
            "val h = malloc<Vec2>(10.0f, 20.0f)!!\nval v = h.value()\nv.x = 99.0f",
            decls = vec2Decl
        )
        r.sourceContains("v->x = 99.0f;")
    }

    // ── Value<T>.deref() → stack copy ────────────────────────────────

    @Test fun valueDeref() {
        val r = transpileMain(
            "val h = malloc<Vec2>(10.0f, 20.0f)!!\nval v = h.value()\nval copy = v.deref()",
            decls = vec2Decl
        )
        r.sourceContains("(*v)")
    }

    // ── Value<T> method call — transparent delegation ────────────────

    @Test fun valueMethodCall() {
        val r = transpile("""
            package test.Main
            class Counter(var count: Int) {
                fun inc() { count = count + 1 }
            }
            fun main(args: Array<String>) {
                val h = malloc<Counter>(0)!!
                val v = h.value()
                v.inc()
            }
        """)
        r.sourceContains("test_Main_Counter_inc(v)")
    }

    // ── Explicit Value<T> type annotation ────────────────────────────

    @Test fun explicitValueType() {
        val r = transpileMain(
            "val h = malloc<Vec2>(1.0f, 2.0f)!!\nval v: Value<Vec2> = h.value()\nprintln(v.x)",
            decls = vec2Decl
        )
        r.sourceContains("v->x")
    }

    // ── malloc<Array<T>>(n) → typed array allocation ─────────────────

    @Test fun mallocArrayInt() {
        val r = transpileMain("val buf = malloc<Array<Int>>(10)")
        r.sourceContains("(int32_t*)malloc(sizeof(int32_t) * (size_t)(10))")
    }

    @Test fun mallocArrayFloat() {
        val r = transpileMain("val buf = malloc<Array<Float>>(5)")
        r.sourceContains("(float*)malloc(sizeof(float) * (size_t)(5))")
    }

    @Test fun mallocArrayLong() {
        val r = transpileMain("val buf = malloc<Array<Long>>(3)")
        r.sourceContains("(int64_t*)malloc(sizeof(int64_t) * (size_t)(3))")
    }

    // ── malloc<T>() with no args → single element allocation ─────────

    @Test fun mallocSingleInt() {
        val r = transpileMain("val p = malloc<Int>()")
        r.sourceContains("(int32_t*)malloc(sizeof(int32_t))")
    }

    @Test fun mallocSingleFloat() {
        val r = transpileMain("val p = malloc<Float>()")
        r.sourceContains("(float*)malloc(sizeof(float))")
    }

    // ── realloc<Array<T>>(ptr, n) → typed array realloc ──────────────

    @Test fun reallocArrayInt() {
        val r = transpileMain("val buf = malloc<Array<Int>>(10)\nval buf2 = realloc<Array<Int>>(buf, 20)")
        r.sourceContains("(int32_t*)realloc(buf, sizeof(int32_t) * (size_t)(20))")
    }

    // ── calloc<Array<T>>(n) → typed array calloc ─────────────────────

    @Test fun callocArrayInt() {
        val r = transpileMain("val buf = calloc<Array<Int>>(10)")
        r.sourceContains("(int32_t*)calloc((size_t)(10), sizeof(int32_t))")
    }

    // ── Pointer<Array<T>> type resolution ────────────────────────────

    @Test fun pointerArrayIntType() {
        val r = transpileMain("var buf: Pointer<Array<Int>> = malloc<Array<Int>>(10)")
        r.sourceContains("int32_t* buf")
        r.sourceContains("(int32_t*)malloc(sizeof(int32_t) * (size_t)(10))")
    }

    @Test fun pointerArrayIntIndexRead() {
        val r = transpileMain("val buf: Pointer<Array<Int>> = malloc<Array<Int>>(10)\nprintln(buf[0])")
        r.sourceContains("buf[0]")
    }

    @Test fun pointerArrayIntIndexWrite() {
        val r = transpileMain("var buf: Pointer<Array<Int>> = malloc<Array<Int>>(10)\nbuf[0] = 42")
        r.sourceContains("buf[0] = 42;")
    }

    // ── Body prop with initializer referencing ctor param ────────────

    @Test fun bodyPropInitFromCtorParam() {
        val decl = """
            class Buf(var capacity: Int) {
                var buf: Pointer<Array<Int>> = malloc<Array<Int>>(capacity)
            }
        """
        val r = transpileMain("val b = Buf(16)", decls = decl)
        // struct field: int32_t* buf
        r.headerContains("int32_t* buf;")
        // _create initializes body prop from ctor param
        r.sourceContains("(int32_t*)malloc(sizeof(int32_t) * (size_t)(capacity))")
    }

    @Test fun bodyPropInitConstant() {
        val decl = """
            class Counter(var name: String) {
                var count: Int = 0
            }
        """
        val r = transpileMain("val c = Counter(\"hello\")", decls = decl)
        r.sourceContains("\$self.count = 0;")
    }
}

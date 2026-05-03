package com.bitsycore

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * Tests for Heap<T> (heap-allocated objects), Ptr<T>, Value<T>, HeapAlloc, HeapFree.
 */
class HeapTest : TranspilerTestBase() {

    private val vec2Decl = "data class Vec2(val x: Float, val y: Float)"

    // ── HeapAlloc<Class> → heap constructor ─────────────────────────────

    @Test fun heapAllocClass() {
        val r = transpileMain("val p = HeapAlloc<Vec2>(10.0f, 20.0f)", decls = vec2Decl)
        r.sourceContains("test_Main_Vec2_new(10.0f, 20.0f)")
    }

    // ── Heap<T> field access (auto-deref through pointer) ──────────

    @Test fun heapFieldRead() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!\nprintln(p.x)",
            decls = vec2Decl
        )
        r.sourceContains("p->x")
    }

    @Test fun heapFieldWrite() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!\np.x = 99.0f",
            decls = vec2Decl
        )
        r.sourceContains("p->x = 99.0f;")
    }

    // ── .value() → Value<T> (same pointer, no copy) ────────────────

    @Test fun heapValue() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!\nval v = p.value()",
            decls = vec2Decl
        )
        // .value() returns same pointer (Value<T>), NOT a dereference copy
        r.sourceNotContains("(*p)")
        r.sourceContains("= p;") // v = p (same pointer)
    }

    // ── .deref() → stack copy ────────────────────────────────────────

    @Test fun heapDeref() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!\nval v = p.deref()",
            decls = vec2Decl
        )
        r.sourceContains("(*p)")
    }

    // ── .set() → update ──────────────────────────────────────────────

    @Test fun heapSet() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)\np.set(Vec2(1.0f, 2.0f))",
            decls = vec2Decl
        )
        r.sourceContains("*p =")
    }

    // ── .toHeap() → stack to heap (inlined) ─────────────────────────

    @Test fun stackToHeap() {
        val r = transpileMain(
            "val v = Vec2(5.0f, 6.0f)\nval hp = v.toHeap()",
            decls = vec2Decl
        )
        r.sourceContains("malloc(sizeof(test_Main_Vec2))")
        r.sourceContains("*$") // struct copy: if ($t) *$t = v;
    }

    // ── HeapFree ─────────────────────────────────────────────────────

    @Test fun freeHeapPointer() {
        val r = transpileMain(
            "val p = HeapAlloc<Vec2>(10.0f, 20.0f)\nHeapFree(p)",
            decls = vec2Decl
        )
        r.sourceContains("free(p)")
    }

    // ── Typed raw pointer ────────────────────────────────────────────

    @Test fun typedPointerHeapAlloc() {
        val r = transpileMain("val ints = HeapAlloc<Int>(5)")
        r.sourceContains("(ktc_Int*)malloc(sizeof(ktc_Int) *")
    }

    @Test fun typedPointerIndexRead() {
        val r = transpileMain("val ints = HeapAlloc<Int>(5)!!\nprintln(ints[2])")
        r.sourceContains("ints[2]")
    }

    @Test fun typedPointerIndexWrite() {
        val r = transpileMain("val ints = HeapAlloc<Int>(5)!!\nints[0] = 42")
        r.sourceContains("ints[0] = 42;")
    }

    // ── Raw HeapAlloc ───────────────────────────────────────────────────

    @Test fun rawHeapAlloc() {
        val r = transpileMain("val buf = HeapAlloc(1024)")
        r.sourceContains("malloc((size_t)(1024))")
    }

    @Test fun rawHeapArrayResize() {
        val r = transpileMain("val buf = HeapAlloc(1024)\nval buf2 = HeapArrayResize(buf, 2048)")
        r.sourceContains("realloc(buf, (size_t)(2048))")
    }

    // ── Heap<T>? — pointer nullable ──────────────────────────────────

    @Test fun heapAllocReturnsNullable() {
        // HeapAlloc without !! should be nullable — accessing .x should error
        val ex = assertThrows<IllegalStateException> {
            transpileMain("val p = HeapAlloc<Vec2>(10.0f, 20.0f)\nprintln(p.x)", decls = vec2Decl)
        }
        assert(ex.message!!.contains("safe"))
    }

    @Test fun heapAllocNullCheckSmartCast() {
        // After null check, smart cast should allow access
        val r = transpileMain("""
            val p = HeapAlloc<Vec2>(10.0f, 20.0f)
            if (p == null) return
            println(p.x)
        """, decls = vec2Decl)
        r.sourceContains("p == NULL")
        r.sourceContains("p->x")
    }

    @Test fun notNullAssertionEmitsCrash() {
        // !! on HeapAlloc should emit NullPointerException check
        val r = transpileMain("val p = HeapAlloc<Vec2>(10.0f, 20.0f)!!", decls = vec2Decl)
        r.sourceContains("NullPointerException")
        r.sourceContains("exit(1)")
    }

    @Test fun notNullAssertionOnVariable() {
        // !! on nullable variable should emit check
        val r = transpileMain("""
            var p: Heap<Vec2>? = HeapAlloc<Vec2>(1.0f, 2.0f)
            val q = p!!
        """, decls = vec2Decl)
        r.sourceContains("NullPointerException")
    }

    @Test fun heapPtrNullable() {
        val r = transpileMain(
            "var q: Heap<Vec2>? = HeapAlloc<Vec2>(3.0f, 4.0f)\nq = null",
            decls = vec2Decl
        )
        // Heap<T>? uses NULL for null
        r.sourceContains("NULL")
    }

    @Test fun heapPtrNullCheck() {
        val r = transpileMain("""
            var q: Heap<Vec2>? = HeapAlloc<Vec2>(3.0f, 4.0f)
            if (q != null) {
                println(q!!.x)
            }
        """, decls = vec2Decl)
        r.sourceContains("q != NULL")
    }

    // ── Heap .toPtr() ────────────────────────────────────────────────

    @Test fun heapToPtr() {
        val r = transpileMain(
            "val h = HeapAlloc<Vec2>(1.0f, 2.0f)!!\nval p = h.toPtr()",
            decls = vec2Decl
        )
        // toPtr() is identity — same pointer, just changes type
        r.sourceContains("= h;")
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
    }

    // ── Ptr field access (auto-deref) ──────────────────────────────

    @Test fun ptrFieldAccess() {
        val r = transpileMain(
            "val v = Vec2(5.0f, 6.0f)\nval p = v.toPtr()\nprintln(p.x)",
            decls = vec2Decl
        )
        r.sourceContains("p->x")
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

    // ── Ptr field access through .value() ────────────────────────────

    @Test fun ptrValueFieldAccess() {
        val r = transpileMain(
            "val v = Vec2(5.0f, 6.0f)\nval p = v.toPtr()\nprintln(p.value().x)",
            decls = vec2Decl
        )
        r.sourceContains("->x")
    }

    // ══════════════════════════════════════════════════════════════════
    // Value<T> tests
    // ══════════════════════════════════════════════════════════════════

    // ── Value<T> from .value() — transparent field access ────────────

    @Test fun valueFieldAccess() {
        val r = transpileMain(
            "val h = HeapAlloc<Vec2>(10.0f, 20.0f)!!\nval v = h.value()\nprintln(v.x)",
            decls = vec2Decl
        )
        r.sourceContains("v->x")
    }

    // ── Value<T> field write ─────────────────────────────────────────

    @Test fun valueFieldWrite() {
        val r = transpileMain(
            "val h = HeapAlloc<Vec2>(10.0f, 20.0f)!!\nval v = h.value()\nv.x = 99.0f",
            decls = vec2Decl
        )
        r.sourceContains("v->x = 99.0f;")
    }

    // ── Value<T>.deref() → stack copy ────────────────────────────────

    @Test fun valueDeref() {
        val r = transpileMain(
            "val h = HeapAlloc<Vec2>(10.0f, 20.0f)!!\nval v = h.value()\nval copy = v.deref()",
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
                val h = HeapAlloc<Counter>(0)!!
                val v = h.value()
                v.inc()
            }
        """)
        r.sourceContains("test_Main_Counter_inc(v)")
    }

    // ── Explicit Value<T> type annotation ────────────────────────────

    @Test fun explicitValueType() {
        val r = transpileMain(
            "val h = HeapAlloc<Vec2>(1.0f, 2.0f)!!\nval v: Value<Vec2> = h.value()\nprintln(v.x)",
            decls = vec2Decl
        )
        r.sourceContains("v->x")
    }

    // ── HeapAlloc<Array<T>>(n) → typed array allocation ─────────────────

    @Test fun heapAllocArrayInt() {
        val r = transpileMain("val buf = HeapAlloc<Array<Int>>(10)")
        r.sourceContains("(ktc_Int*)malloc(sizeof(ktc_Int) * (size_t)(10))")
    }

    @Test fun heapAllocArrayFloat() {
        val r = transpileMain("val buf = HeapAlloc<Array<Float>>(5)")
        r.sourceContains("(ktc_Float*)malloc(sizeof(ktc_Float) * (size_t)(5))")
    }

    @Test fun heapAllocArrayLong() {
        val r = transpileMain("val buf = HeapAlloc<Array<Long>>(3)")
        r.sourceContains("(ktc_Long*)malloc(sizeof(ktc_Long) * (size_t)(3))")
    }

    // ── HeapAlloc<T>() with no args → single element allocation ─────────

    @Test fun heapAllocSingleInt() {
        val r = transpileMain("val p = HeapAlloc<Int>()")
        r.sourceContains("(ktc_Int*)malloc(sizeof(ktc_Int))")
    }

    @Test fun heapAllocSingleFloat() {
        val r = transpileMain("val p = HeapAlloc<Float>()")
        r.sourceContains("(ktc_Float*)malloc(sizeof(ktc_Float))")
    }

    // ── HeapArrayResize<Array<T>>(ptr, n) → typed array realloc ──────────────

    @Test fun heapArrayResizeInt() {
        val r = transpileMain("val buf = HeapAlloc<Array<Int>>(10)\nval buf2 = HeapArrayResize<Array<Int>>(buf, 20)")
        r.sourceContains("(ktc_Int*)realloc(buf, sizeof(ktc_Int) * (size_t)(20))")
    }

    // ── HeapArrayZero<Array<T>>(n) → typed array calloc ─────────────────────

    @Test fun heapZeroArrayInt() {
        val r = transpileMain("val buf = HeapArrayZero<Array<Int>>(10)")
        r.sourceContains("(ktc_Int*)calloc((size_t)(10), sizeof(ktc_Int))")
    }

    // ── Body prop with initializer referencing ctor param ────────────

    @Test fun bodyPropInitFromCtorParam() {
        val decl = """
            class Buf(var capacity: Int) {
                var buf: Heap<Array<Int>> = HeapAlloc<Array<Int>>(capacity)
            }
        """
        val r = transpileMain("val b = Buf(16)", decls = decl)
        // struct field: ktc_Int* buf
        r.headerContains("ktc_Int* buf;")
        // _create initializes body prop from ctor param
        r.sourceContains("(ktc_Int*)malloc(sizeof(ktc_Int) * (size_t)(capacity))")
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

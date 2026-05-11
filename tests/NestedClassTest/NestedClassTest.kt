package test

class Outer {
    class Inner(val x: Int)
    class Helper(val name: String)
}

data class Vec2(val x: Float, val y: Float) {
    class NestedPair(val a: Int, val b: Int)
}

class A {
    class B {
        class C(val v: Int)
    }
}

fun main() {
    var ok = true

    // Simple nested
    val inner = Outer.Inner(42)
    if (inner.x != 42) { c.printf("FAIL: simple nested\n"); ok = false }

    // Nested with String
    val h = Outer.Helper("test")
    if (h.name != "test") { c.printf("FAIL: nested string\n"); ok = false }

    // Nested in data class
    val np = Vec2.NestedPair(1, 2)
    if (np.a != 1 || np.b != 2) { c.printf("FAIL: data class nested\n"); ok = false }

    // Deeply nested
    val deep = A.B.C(99)
    if (deep.v != 99) { c.printf("FAIL: deeply nested\n"); ok = false }

    if (ok) {
        c.printf("NestedClassTest: PASSED\n")
    } else {
        c.printf("NestedClassTest: FAILED\n")
        c.exit(1)
    }
}

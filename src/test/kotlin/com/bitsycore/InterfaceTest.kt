package com.bitsycore

import kotlin.test.Test

/**
 * Tests for interfaces: declaration, implementation, virtual dispatch.
 */
class InterfaceTest : TranspilerTestBase() {

    private val shapeDecls = """
        interface Shape {
            fun area(): Float
            fun describe(): String
        }
        class Circle(val radius: Float) : Shape {
            override fun area(): Float = 3.14f * radius * radius
            override fun describe(): String = "Circle"
        }
        class Square(val side: Float) : Shape {
            override fun area(): Float = side * side
            override fun describe(): String = "Square"
        }
    """

    // ── Interface vtable ─────────────────────────────────────────────

    @Test fun interfaceVtableStruct() {
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls)
        r.headerContains("test_Main_Shape_vt")
    }

    @Test fun interfaceFatPointerStruct() {
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls)
        // Fat pointer: { void* obj, const VT* vt }
        r.headerContains("void* obj;")
        r.headerContains("test_Main_Shape_vt*")
    }

    // ── Interface dispatch ───────────────────────────────────────────

    @Test fun interfaceMethodCall() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            fun printShape(s: Shape) {
                println(s.area())
            }
            fun main(args: Array<String>) {
                val c = Circle(5.0f)
                printShape(c)
            }
        """)
        // Virtual dispatch: s.vt->area(s.obj)
        r.sourceContains("s.vt->area(s.obj)")
    }

    // ── Auto-wrap class → interface ──────────────────────────────────

    @Test fun autoWrapClassToInterface() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            fun printShape(s: Shape) {
                println(s.area())
            }
            fun main(args: Array<String>) {
                val c = Circle(5.0f)
                printShape(c)
            }
        """)
        // Circle passed to Shape param → wrapped with vtable
        r.sourceContains("test_Main_Circle_as_Shape")
    }

    // ── Multiple implementations ─────────────────────────────────────

    @Test fun multipleImplementations() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            fun printShape(s: Shape) {
                println(s.describe())
            }
            fun main(args: Array<String>) {
                printShape(Circle(5.0f))
                printShape(Square(3.0f))
            }
        """)
        r.sourceContains("test_Main_Circle_as_Shape")
        r.sourceContains("test_Main_Square_as_Shape")
    }
}

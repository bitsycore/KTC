package com.bitsycore

import kotlin.test.Test

/**
 * Tests for interfaces: declaration, implementation, virtual dispatch.
 */
class InterfaceUnitTest : TranspilerTestBase() {

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
        // Tagged union: uses .data.ClassName for 2+ implementors, plain field for 1
        r.headerContains("union {")
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
        // Virtual dispatch: s.vt->area((void*)&s)
        r.sourceContains("s.vt->area((void*)&s)")
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

    // ── Type ID system ────────────────────────────────────────────────

    @Test fun typeIdDefineInHeader() {
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls)
        r.headerContains("#define test_Main_Circle_TYPE_ID")
        r.headerContains("#define test_Main_Square_TYPE_ID")
        r.headerContains("#define test_Main_Shape_TYPE_ID")
    }

    @Test fun typeIdFieldInStruct() {
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls)
        r.headerContains("ktc_Int __type_id;")
    }

    @Test fun typeIdInConstructor() {
        // Classes with all ctor props use compound literal: return (Circle){TYPE_ID, radius};
        val r = transpileMain("val c = Circle(5.0f)", decls = shapeDecls) 
        r.sourceContains("return (test_Main_Circle){")
        r.sourceContains("test_Main_Circle_TYPE_ID")
    }

    @Test fun isCheckClass() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            class Box(val w: Float)
            fun main(args: Array<String>) {
                val c = Circle(5.0f)
                if (c is Circle) println("yes")
            }
        """)
        r.sourceContains("c.__type_id == test_Main_Circle_TYPE_ID")
    }

    @Test fun isCheckInterface() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            fun main(args: Array<String>) {
                val s: Shape = Circle(5.0f)
                if (s is Shape) println("yes")
            }
        """)
        r.sourceContains("__type_id == test_Main_Circle_TYPE_ID ||")
    }

    @Test fun negatedIsCheck() {
        val r = transpile("""
            package test.Main
            $shapeDecls
            class Box(val w: Float)
            fun main(args: Array<String>) {
                val b = Box(3.0f)
                if (b !is Circle) println("no")
            }
        """)
        r.sourceContains("!(b.__type_id == test_Main_Circle_TYPE_ID)")
    }

    // ── Override enforcement ──────────────────────────────────────────

    @Test fun validOverridePasses() {
        val decls = """
            interface Foo {
                fun bar(): Int
            }
            class Impl : Foo {
                override fun bar(): Int = 42
            }
        """.trimIndent()
        val r = transpileMain("val f = Impl()", decls = decls)
        r.sourceContains("Impl_bar")
    }

    @Test fun missingOverrideKeywordError() {
        val decls = """
            interface Foo {
                fun bar(): Int
            }
            class Impl : Foo {
                fun bar(): Int = 42
            }
        """.trimIndent()
        transpileMainExpectError("val f = Impl()", "must be marked 'override'", decls = decls)
    }

    @Test fun missingInterfaceMethodError() {
        val decls = """
            interface Foo {
                fun bar(): Int
                fun baz(): String
            }
            class Impl : Foo {
                override fun bar(): Int = 42
            }
        """.trimIndent()
        transpileMainExpectError("val f = Impl()", "must implement 'baz'", decls = decls)
    }

    @Test fun bogusOverrideError() {
        val decls = """
            class Impl {
                override fun bar(): Int = 42
            }
        """.trimIndent()
        transpileMainExpectError("val f = Impl()", "does not override any interface method", decls = decls)
    }

    @Test fun overrideMethodNotInInterface() {
        val decls = """
            interface Foo {
                fun bar(): Int
            }
            class Impl : Foo {
                override fun bar(): Int = 42
                override fun baz(): String = "hello"
            }
        """.trimIndent()
        transpileMainExpectError("val f = Impl()", "does not override any interface method", decls = decls)
    }
}

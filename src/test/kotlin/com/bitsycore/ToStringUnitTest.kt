package com.bitsycore

import kotlin.test.Test

class ToStringUnitTest : TranspilerTestBase() {

    @Test fun `class default toString includes name and hex hash`() {
        val r = transpileMain(
            decls = "class Foo(val x: Int)",
            body = "val f = Foo(42)\nprintln(f)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("Foo_hashCode")
        r.sourceContains("%s@%x")
    }

    @Test fun `data class uses generated toString not default`() {
        val r = transpileMain(
            decls = "data class Point(val x: Int, val y: Int)",
            body = "val p = Point(1, 2)\nprintln(p)"
        )
        r.sourceContains("Point_toString")
    }

    @Test fun `enum uses names array not default toString`() {
        val r = transpileMain(
            decls = "enum class Color { RED, GREEN, BLUE }",
            body = "val c = Color.RED\nprintln(c)"
        )
        r.sourceContains("Color_names")
    }

    @Test fun `object default toString`() {
        val r = transpileMain(
            decls = "object Logger",
            body = "println(Logger)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("%s@%x")
    }

    @Test fun `interface default toString`() {
        val r = transpileMain(
            decls = """
                interface Shape { fun area(): Float }
                class Circle(private var r: Float) : Shape {
                    override fun area(): Float = r * r * 3.14f
                }
            """.trimIndent(),
            body = "val s: Shape = Circle(1f)\nprintln(s)"
        )
        r.sourceContains("snprintf")
        r.sourceContains("@")
    }

    @Test fun `primitive int toString`() {
        val r = transpileMain("val x = 42\nprintln(x)")
        r.sourceContains("printf")
    }

    @Test fun `string toString returns itself`() {
        val r = transpileMain("val s = \"hello\"\nprintln(s)")
        r.sourceContains("printf")
    }
}

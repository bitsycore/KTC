package com.bitsycore

import kotlin.test.Test

/**
 * Tests for type checks (`is`, `!is`) and casts (`as`, `as?`).
 * Covers class type checks, interface type checks, interface casts,
 * and non-interface C-style casts.
 */
class CastUnitTest : TranspilerTestBase() {

    @Test fun isCheckOnClass() {
        val r = transpile("""
            package test.Main
            class Shape
            class Circle(val r: Float)
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val ok = c is Circle
            }
        """)
        r.sourceContains("__type_id == test_Main_Circle_TYPE_ID")
    }

    @Test fun isCheckNegated() {
        val r = transpile("""
            package test.Main
            class Shape
            class Circle(val r: Float)
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val ok = c !is Circle
            }
        """)
        r.sourceContains("!(c.__type_id == test_Main_Circle_TYPE_ID)")
    }

    @Test fun isCheckOnInterface() {
        val r = transpile("""
            package test.Main
            interface Drawable
            class Circle(val r: Float) : Drawable
            class Square(val s: Float) : Drawable
            fun main(args: Array<String>) {
                val c: Drawable = Circle(1.0f)
                val ok = c is Circle
            }
        """)
        // Interface is-check enumerates implementing classes
        r.sourceContains("c.__type_id == test_Main_Circle_TYPE_ID")
    }

    @Test fun isCheckOnInterfaceMultipleImpls() {
        val r = transpile("""
            package test.Main
            interface Drawable
            class Circle(val r: Float) : Drawable
            class Square(val s: Float) : Drawable
            fun main(args: Array<String>) {
                val c: Drawable = Circle(1.0f)
                val ok = c is Drawable
            }
        """)
        r.sourceContains("__type_id == test_Main_Circle_TYPE_ID || c.__type_id == test_Main_Square_TYPE_ID")
    }

    @Test fun asCastNonInterface() {
        val r = transpile("""
            package test.Main
            class Shape
            class Circle(val r: Float)
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val s = c as Shape
            }
        """)
        // Non-interface cast → C-style cast
        r.sourceContains("(test_Main_Shape)(c)")
    }

    @Test fun asCastToInterface() {
        val r = transpile("""
            package test.Main
            interface Drawable {
                fun draw(): Unit
            }
            class Circle(val r: Float) : Drawable {
                override fun draw() {}
            }
            fun main(args: Array<String>) {
                val c = Circle(1.0f)
                val d = c as Drawable
            }
        """)
        r.sourceContains("test_Main_Circle_as_Drawable")
    }

    @Test fun safeCastAsQuestionNotYetImpl() {
        notYetImpl("as? safe cast is not implemented yet")
    }

    @Test fun isCheckInWhen() {
        notYetImpl("is-check in when-expression is not implemented yet")
        val r = transpile("""
            package test
            class Shape
            class Circle(val r: Float)
            class Square(val s: Float)
            fun main(args: Array<String>) {
                val s: Shape = Circle(1.0f)
                val res = when (s) {
                    is Circle -> 1
                    is Square -> 2
                    else -> 0
                }
            }
        """)
        r.sourceContains("__type_id == test_Circle_TYPE_ID")
        r.sourceContains("__type_id == test_Square_TYPE_ID")
    }
}

package com.bitsycore

import kotlin.test.Test

/**
 * Tests for extension functions: on primitives, classes, nullable receivers.
 */
class ExtensionFunctionTest : TranspilerTestBase() {

    // ── Extension on primitive type ──────────────────────────────────

    @Test fun extensionOnInt() {
        val r = transpile("""
            package test.Main
            fun Int.isEven(): Boolean = this % 2 == 0
            fun main(args: Array<String>) {
                val n = 42
                println(n.isEven())
            }
        """)
        r.sourceContains("bool test_Main_Int_isEven(int32_t ${'$'}self)")
        r.sourceContains("test_Main_Int_isEven(n)")
    }

    // ── Extension on String ──────────────────────────────────────────

    @Test fun extensionOnString() {
        val r = transpile("""
            package test.Main
            fun String.shout() {
                println(this)
            }
            fun main(args: Array<String>) {
                val s = "hello"
                s.shout()
            }
        """)
        r.sourceContains("void test_Main_String_shout(kt_String ${'$'}self)")
        r.sourceContains("test_Main_String_shout(s)")
    }

    // ── Extension on data class ──────────────────────────────────────

    @Test fun extensionOnClass() {
        val r = transpile("""
            package test.Main
            data class Vec2(val x: Float, val y: Float)
            fun Vec2.lengthSquared(): Float = x * x + y * y
            fun main(args: Array<String>) {
                val v = Vec2(3.0f, 4.0f)
                println(v.lengthSquared())
            }
        """)
        r.sourceContains("float test_Main_Vec2_lengthSquared(test_Main_Vec2 ${'$'}self)")
        r.sourceContains("test_Main_Vec2_lengthSquared(v)")
    }

    // ── Extension with parameters ────────────────────────────────────

    @Test fun extensionWithParams() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
            }
            fun Player.heal(amount: Int) {
                health += amount
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                p.heal(10)
            }
        """)
        r.sourceContains("void test_Main_Player_heal(test_Main_Player ${'$'}self, int32_t amount)")
    }

    // ── Nullable receiver extension ──────────────────────────────────

    @Test fun nullableReceiverSignature() {
        val r = transpile("""
            package test.Main
            fun String?.printSafe() {
                if (this != null) println(this)
            }
            fun main(args: Array<String>) {
                val s: String? = "hello"
                s.printSafe()
            }
        """)
        // Should pass ktc_String_Optional $self
        r.sourceContains("void test_Main_String_printSafe(ktc_String_Optional \$self)")
    }

    @Test fun nullableReceiverCallPassesHas() {
        val r = transpile("""
            package test.Main
            fun String?.printSafe() {
                if (this != null) println(this)
            }
            fun nullMaybe(): String? {
                return null
            }
            fun main(args: Array<String>) {
                val s = nullMaybe()
                s.printSafe()
            }
        """)
        // Call site should pass s (Optional directly)
        r.sourceContains("test_Main_String_printSafe(s)")
    }

    @Test fun nullableReceiverBodyChecksHas() {
        val r = transpile("""
            package test.Main
            fun String?.printSafe() {
                if (this != null) println(this)
            }
            fun main(args: Array<String>) {
                val s: String? = null
                s.printSafe()
            }
        """)
        // Inside the function, `this != null` should check $self.tag == SOME
        r.sourceContains("\$self.tag == SOME")
    }

    @Test fun nullableReceiverOnClassType() {
        val r = transpile("""
            package test.Main
            data class Vec2(val x: Float, val y: Float)
            fun Vec2?.printSafe() {
                if (this != null) println(this)
            }
            fun maybeVec(): Vec2? {
                return null
            }
            fun main(args: Array<String>) {
                val v = maybeVec()
                v.printSafe()
            }
        """)
        // Class type: $self is by-value Optional
        r.sourceContains("test_Main_Vec2_Optional \$self")
        r.sourceContains("test_Main_Vec2_printSafe(v)")
    }

    @Test fun nullableReceiverNoErrorOnDotCall() {
        // Calling a nullable-receiver extension with `.` should NOT error
        val r = transpile("""
            package test.Main
            fun String?.safe() {}
            fun nullStr(): String? { return null }
            fun main(args: Array<String>) {
                val s = nullStr()
                s.safe()
            }
        """)
        r.sourceContains("test_Main_String_safe(s)")
    }

    @Test fun dotCallOnNullableWithoutNullableReceiverErrors() {
        // Calling a non-nullable-receiver extension with `.` on nullable SHOULD error
        transpileExpectError("""
            package test.Main
            fun String.notSafe() {}
            fun nullStr(): String? { return null }
            fun main(args: Array<String>) {
                val s = nullStr()
                s.notSafe()
            }
        """, "Only safe")
    }
}

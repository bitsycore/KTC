package com.bitsycore

import kotlin.test.Test

/**
 * Tests for classes with body properties, methods, constructors.
 */
class ClassTest : TranspilerTestBase() {

    // ── Class with ctor params and body props ────────────────────────

    @Test fun classWithBodyProps() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
                var score: Int = 0
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                println(p.health)
            }
        """)
        r.headerContains("ktc_String name;")
        r.headerContains("int32_t health;")
        r.headerContains("int32_t score;")
    }

    @Test fun classConstructorInit() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
            }
        """)
        // Constructor should set ctor params + body prop defaults
        r.sourceContains("test_Main_Player_create")
        r.sourceContains("health = 100")
    }

    // ── Class methods ────────────────────────────────────────────────

    @Test fun classMethod() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
                fun takeDamage(amount: Int) {
                    health -= amount
                }
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                p.takeDamage(30)
            }
        """)
        r.sourceContains("void test_Main_Player_takeDamage(test_Main_Player* ${'$'}self, int32_t amount)")
    }

    @Test fun classMethodExprBody() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
                fun isAlive(): Boolean = health > 0
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                println(p.isAlive())
            }
        """)
        r.sourceContains("bool test_Main_Player_isAlive(test_Main_Player* ${'$'}self)")
    }

    // ── Class with only ctor params (var) ────────────────────────────

    @Test fun classVarCtorParam() {
        val r = transpile("""
            package test.Main
            class Counter(var count: Int) {
                fun increment() { count++ }
                fun get(): Int = count
            }
            fun main(args: Array<String>) {
                val c = Counter(0)
                c.increment()
                println(c.get())
            }
        """)
        r.headerContains("int32_t count;")
        r.sourceContains("test_Main_Counter_increment")
        r.sourceContains("test_Main_Counter_get")
    }

    // ── Property access via dot ──────────────────────────────────────

    @Test fun propertyRead() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                println(p.health)
            }
        """)
        r.sourceContains("p.health")
    }

    @Test fun propertyWrite() {
        val r = transpile("""
            package test.Main
            class Player(val name: String) {
                var health: Int = 100
            }
            fun main(args: Array<String>) {
                val p = Player("Alice")
                p.health = 50
            }
        """)
        r.sourceContains("p.health = 50;")
    }
}

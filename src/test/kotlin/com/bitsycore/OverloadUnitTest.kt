package com.bitsycore

import kotlin.test.Test

/*
Unit tests for method overloading with WithType1_Type2 naming.
*/
class OverloadUnitTest : TranspilerTestBase() {

    // ── Object overloads ───────────────────────────────────────────

    @Test fun objectOverloadNoArgGetsNoArg() {
        val v = transpileMain("", """
            object O {
                fun greet(): String = "hi"
                fun greet(name: String): String = name
            }
        """)
        v.headerContains("O_greetNoArg")
    }

    @Test fun objectOverloadWithTypeSuffix() {
        val v = transpileMain("", """
            object O {
                fun greet(): String = "hi"
                fun greet(name: String): String = name
            }
        """)
        v.headerContains("O_greetWithString")
    }

    @Test fun objectOverloadMultiParam() {
        val v = transpileMain("", """
            object O {
                fun add(x: Int, y: Int): Int = x + y
                fun add(x: Double, y: Double): Double = x + y
            }
        """)
        v.headerContains("O_addWithInt_Int")
        v.headerContains("O_addWithDouble_Double")
    }

    @Test fun objectOverloadThreeVariants() {
        val v = transpileMain("", """
            object O {
                fun inc() {}
                fun inc(by: Int) {}
                fun inc(by: Int, times: Int) {}
            }
        """)
        v.headerContains("O_incNoArg")
        v.headerContains("O_incWithInt")
        v.headerContains("O_incWithInt_Int")
    }

    @Test fun objectSingleMethodNoSuffix() {
        val v = transpileMain("", """
            object O {
                fun only(): Int = 42
            }
        """)
        v.headerContains("test_Main_O_only")
    }

    // ── Class overloads ────────────────────────────────────────────

    @Test fun classOverloadTwoVariants() {
        val v = transpileMain("", """
            class C() {
                fun log(msg: String) {}
                fun log(code: Int) {}
            }
        """)
        v.headerContains("C_logWithString")
        v.headerContains("C_logWithInt")
    }

    @Test fun classOverloadTypeMatching() {
        val v = transpileMain("val x = C().add(2.5, 3.5)", """
            class C() {
                fun add(x: Int, y: Int): Int = 0
                fun add(x: Double, y: Double): Double = 0.0
            }
        """)
        v.sourceContains("test_Main_C_addWithDouble_Double")
    }

    @Test fun classSingleMethodNoSuffix() {
        val v = transpileMain("", """
            class C() {
                fun only(): Int = 42
            }
        """)
        v.headerContains("test_Main_C_only")
    }

    // ── Private overloads ──────────────────────────────────────────

    @Test fun privateOverloadPRIVPrefix() {
        val v = transpileMain("", """
            object O {
                private fun helper() {}
                private fun helper(x: Int) {}
            }
        """)
        v.sourceContains("O_PRIV_helperNoArg")
        v.sourceContains("O_PRIV_helperWithInt")
    }

    // ── Top-level overloads ────────────────────────────────────────

    @Test fun topLevelOverload() {
        val v = transpile("""
            package test
            fun doIt() {}
            fun doIt(x: Int) {}
        """)
        // Top-level overloads should also get suffixes
        v.headerContains("test_doItNoArg")
        v.headerContains("test_doItWithInt")
    }
}

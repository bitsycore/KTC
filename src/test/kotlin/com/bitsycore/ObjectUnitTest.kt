package com.bitsycore

import kotlin.test.Test

/**
 * Tests for object declarations (singletons).
 */
class ObjectUnitTest : TranspilerTestBase() {

    private val configDecl = """
        object Config {
            val maxRetries: Int = 3
            var debug: Boolean = false
        }
    """

    // ── Object struct ────────────────────────────────────────────────

    @Test fun objectStructTypedef() {
        val r = transpileMain("println(Config.maxRetries)", decls = configDecl)
        r.headerContains("typedef struct {")
        r.headerContains("test_Main_Config_t")
        r.headerContains("extern test_Main_Config_t test_Main_Config;")
    }

    // ── Object field access ──────────────────────────────────────────

    @Test fun objectFieldRead() {
        val r = transpileMain("println(Config.maxRetries)", decls = configDecl)
        r.sourceContains("test_Main_Config.maxRetries")
    }

    @Test fun objectFieldWrite() {
        val r = transpileMain("Config.debug = true", decls = configDecl)
        r.sourceContains("test_Main_Config.debug = true;")
    }

    // ── Object initialization ────────────────────────────────────────

    @Test fun objectInitialization() {
        val r = transpileMain("println(Config.maxRetries)", decls = configDecl)
        r.sourceContains("test_Main_Config_t test_Main_Config = {")
    }

    // ── Object not confused with nullable ────────────────────────────

    @Test fun objectAccessNotNullError() {
        // Object access via dot should NOT trigger nullable receiver error
        val r = transpileMain("Config.debug = true", decls = configDecl)
        r.sourceContains("test_Main_Config.debug")
    }
}

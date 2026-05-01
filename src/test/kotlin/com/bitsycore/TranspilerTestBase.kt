package com.bitsycore

import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Base class for KotlinToC transpiler tests.
 *
 * Provides helpers to transpile Kotlin snippets and assert on the generated C code.
 * Each test feeds a self-contained Kotlin source through Lexer → Parser → CCodeGen
 * and checks the .c / .h output for expected patterns.
 */
open class TranspilerTestBase {

    data class TranspileResult(
        val header: String,
        val source: String,
        val pkg: String
    ) {
        /** Combined header + source for full-text searches. */
        val all get() = "$header\n$source"
    }

    // ── Core transpile helper ────────────────────────────────────────

    /**
     * Transpile a Kotlin source string to C.
     * [src] should be a complete Kotlin file (with package, declarations, etc.).
     * If no package is given, one is auto-generated.
     */
    protected fun transpile(src: String): TranspileResult {
        val source = src.trimIndent()
        val tokens = Lexer(source).tokenize()
        val ast = Parser(tokens).parseFile()
        val allAsts = listOf(ast)
        val output = CCodeGen(ast, allAsts, source.lines()).generate()
        return TranspileResult(
            header = output.header,
            source = output.source,
            pkg = ast.pkg ?: "test"
        )
    }

    /**
     * Shorthand: wrap body in a test package + main function, transpile.
     * [body] is placed inside `fun main(args: Array<String>) { ... }`.
     * [decls] are placed at top-level (before main).
     */
    protected fun transpileMain(body: String, decls: String = "", pkg: String = "test.Main"): TranspileResult {
        val src = buildString {
            appendLine("package $pkg")
            appendLine()
            if (decls.isNotBlank()) {
                appendLine(decls.trimIndent())
                appendLine()
            }
            appendLine("fun main(args: Array<String>) {")
            for (line in body.trimIndent().lines()) {
                appendLine("    $line")
            }
            appendLine("}")
        }
        return transpile(src)
    }

    // ── Assertion helpers ────────────────────────────────────────────

    /** Assert the C source contains [text] (substring match). */
    protected fun TranspileResult.sourceContains(text: String, message: String? = null) {
        assertTrue(
            source.contains(text),
            message ?: "Expected C source to contain:\n  «$text»\n\nActual source:\n$source"
        )
    }

    /** Assert the C header contains [text]. */
    protected fun TranspileResult.headerContains(text: String, message: String? = null) {
        assertTrue(
            header.contains(text),
            message ?: "Expected C header to contain:\n  «$text»\n\nActual header:\n$header"
        )
    }

    /** Assert the C source does NOT contain [text]. */
    protected fun TranspileResult.sourceNotContains(text: String, message: String? = null) {
        assertTrue(
            !source.contains(text),
            message ?: "Expected C source NOT to contain:\n  «$text»\n\nActual source:\n$source"
        )
    }

    /** Assert the C source matches [regex]. */
    protected fun TranspileResult.sourceMatches(regex: Regex, message: String? = null) {
        assertTrue(
            regex.containsMatchIn(source),
            message ?: "Expected C source to match:\n  «${regex.pattern}»\n\nActual source:\n$source"
        )
    }

    /** Assert the C header matches [regex]. */
    protected fun TranspileResult.headerMatches(regex: Regex, message: String? = null) {
        assertTrue(
            regex.containsMatchIn(header),
            message ?: "Expected C header to match:\n  «${regex.pattern}»\n\nActual header:\n$header"
        )
    }

    /** Assert transpilation fails with an error containing [expectedMsg]. */
    protected fun transpileExpectError(src: String, expectedMsg: String) {
        try {
            transpile(src.trimIndent())
            fail("Expected transpilation to fail with error containing '$expectedMsg', but it succeeded")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains(expectedMsg) == true,
                "Expected error containing '$expectedMsg', got: ${e.message}"
            )
        }
    }

    /** Assert transpilation of a main-body snippet fails. */
    protected fun transpileMainExpectError(body: String, expectedMsg: String, decls: String = "", pkg: String = "test.Main") {
        val src = buildString {
            appendLine("package $pkg")
            appendLine()
            if (decls.isNotBlank()) {
                appendLine(decls.trimIndent())
                appendLine()
            }
            appendLine("fun main(args: Array<String>) {")
            for (line in body.trimIndent().lines()) {
                appendLine("    $line")
            }
            appendLine("}")
        }
        transpileExpectError(src, expectedMsg)
    }
}

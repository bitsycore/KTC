package com.bitsycore

import org.intellij.lang.annotations.Language
import kotlin.test.assertEquals
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
        override fun toString(): String {
            return "$header\n$source"
        }
    }

    // ── Core transpile helper ────────────────────────────────────────

    /**
     * Transpile a Kotlin source string to C.
     * [src] should be a complete Kotlin file (with package, declarations, etc.).
     * If no package is given, one is auto-generated.
     */
    protected fun transpile(@Language("kotlin") src: String): TranspileResult {
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

    /*
    Loads and parses all stdlib .kt files by scanning the /stdlib/ resource directory.
    Returns a list of ASTs ready to be passed as allAsts to CCodeGen.
    */
    protected fun loadStdlibAsts(): List<KtFile> {
        val cls = this.javaClass
        val stdlibDir = cls.getResource("/stdlib") ?: cls.getResource("/stdlib/") ?: return emptyList()
        val names = when (stdlibDir.protocol) {
            "jar" -> {
                val conn = stdlibDir.openConnection() as java.net.JarURLConnection
                conn.jarFile.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("stdlib/") && it.name.endsWith(".kt") }
                    .map { it.name.removePrefix("stdlib/") }
                    .toList()
            }
            "file" -> java.io.File(stdlibDir.toURI()).listFiles()
                ?.filter { it.name.endsWith(".kt") }
                ?.map { it.name } ?: emptyList()
            else -> emptyList()
        }
        return names.sorted().mapNotNull { name ->
            val res = cls.getResourceAsStream("/stdlib/$name") ?: return@mapNotNull null
            val src = res.bufferedReader().readText()
            val tokens = Lexer(src).tokenize()
            Parser(tokens).parseFile().copy(sourceFile = name)
        }
    }

    /*
    Transpiles [src] with the full stdlib included in the allAsts context,
    so stdlib types (Random, ArrayList, etc.) are visible to the codegen.
    The generated output is for the primary file only.
    */
    protected fun transpileWithStdlib(src: String): TranspileResult {
        val vSource = src.trimIndent()
        val vTokens = Lexer(vSource).tokenize()
        val vAst = Parser(vTokens).parseFile()
        val vAllAsts = loadStdlibAsts() + vAst
        val vOutput = CCodeGen(vAst, vAllAsts, vSource.lines()).generate()
        return TranspileResult(
            header = vOutput.header,
            source = vOutput.source,
            pkg = vAst.pkg ?: "test"
        )
    }

    /*
    Transpiles a single stdlib .kt file (by filename, e.g. "Random.kt") as the
    primary output, with all other stdlib files as cross-file context.
    Use this to verify declarations emitted by the stdlib itself.
    */
    protected fun transpileStdlibFile(vFileName: String): TranspileResult {
        val vRes = this.javaClass.getResourceAsStream("/stdlib/$vFileName")
            ?: error("stdlib file not found: $vFileName")
        val vSource = vRes.bufferedReader().readText()
        val vTokens = Lexer(vSource).tokenize()
        val vAst = Parser(vTokens).parseFile().copy(sourceFile = vFileName)
        val vAllAsts = loadStdlibAsts()
        val vOutput = CCodeGen(vAst, vAllAsts, vSource.lines()).generate()
        return TranspileResult(
            header = vOutput.header,
            source = vOutput.source,
            pkg = vAst.pkg ?: "ktc_std"
        )
    }

    /*
    Shorthand: wrap body in a test package + main, transpile with stdlib context.
    */
    protected fun transpileMainWithStdlib(
        body: String,
        decls: String = "",
        pkg: String = "test.Main"
    ): TranspileResult {
        val vSrc = buildString {
            appendLine("package $pkg")
            appendLine()
            if (decls.isNotBlank()) {
                appendLine(decls.trimIndent())
                appendLine()
            }
            appendLine("fun main(args: Array<String>) {")
            for (vLine in body.trimIndent().lines()) {
                appendLine("    $vLine")
            }
            appendLine("}")
        }
        return transpileWithStdlib(vSrc)
    }

    /**
     * Shorthand: wrap body in a test package + main function, transpile.
     * [body] is placed inside `fun main(args: Array<String>) { ... }`.
     * [decls] are placed at top-level (before main).
     */
    protected fun transpileMain(@Language("kotlin") body: String,@Language("kotlin") decls: String = "", pkg: String = "test.Main"): TranspileResult {
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

    protected fun TranspileResult.sourceContainsXTime(text: String, time: Int = 1, message: String? = null) {
        var count = 0
        var pos = source.indexOf(text)
        while (pos >= 0) { count++; pos = source.indexOf(text, pos + 1) }
        assertEquals(
            time, count,
            message ?: "Expected C source to contain «$text» exactly once, but found $count times.\n\nActual source:\n$source"
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

    /**
     * Mark a test as "not yet implemented" — skipped via JUnit5 assumption
     * instead of failing. Use for valid Kotlin syntax that the transpiler
     * doesn't support yet.
     */
    protected fun notYetImpl(reason: String = "not yet implemented") {
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "NOT YET IMPL: $reason")
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

package com.bitsycore

import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: ktc <file.kt> [-o <output_dir>]")
        System.err.println("  Transpiles a Kotlin subset file to C11.")
        System.exit(1)
    }

    val inputPath = args[0]
    val outputDir = if (args.size >= 3 && args[1] == "-o") args[2] else "."
    val inputFile = File(inputPath)

    if (!inputFile.exists()) {
        System.err.println("Error: file not found: $inputPath")
        System.exit(1)
    }

    val source = inputFile.readText()

    // ── Lex ──────────────────────────────────────────────────────────
    val tokens: List<Token>
    try {
        tokens = Lexer(source).tokenize()
    } catch (e: Exception) {
        System.err.println("Lexer error: ${e.message}")
        System.exit(1)
        return
    }

    // ── Parse ────────────────────────────────────────────────────────
    val ast: KtFile
    try {
        ast = Parser(tokens).parseFile()
    } catch (e: Exception) {
        System.err.println("Parser error: ${e.message}")
        System.exit(1)
        return
    }

    // ── Generate C ───────────────────────────────────────────────────
    val output: CCodeGen.COutput
    try {
        output = CCodeGen(ast).generate()
    } catch (e: Exception) {
        System.err.println("CodeGen error: ${e.message}")
        System.exit(1)
        return
    }

    // ── Determine output file names from package ─────────────────────
    val baseName = ast.pkg?.replace('.', '_') ?: inputFile.nameWithoutExtension
    val outDir = File(outputDir)
    outDir.mkdirs()

    val headerFile = File(outDir, "$baseName.h")
    val sourceFile = File(outDir, "$baseName.c")

    headerFile.writeText(output.header)
    sourceFile.writeText(output.source)

    // ── Copy runtime if not present ──────────────────────────────────
    val runtimeDst = File(outDir, "ktc_runtime.h")
    if (!runtimeDst.exists()) {
        val runtimeSrc = object {}.javaClass.getResourceAsStream("/ktc_runtime.h")
        if (runtimeSrc != null) {
            runtimeDst.writeText(runtimeSrc.bufferedReader().readText())
            println("  wrote ${runtimeDst.path}")
        } else {
            System.err.println("Warning: ktc_runtime.h not found in resources, copy it manually.")
        }
    }

    println("  wrote ${headerFile.path}")
    println("  wrote ${sourceFile.path}")
    println("Done. Compile with:  cc -std=c11 -o ${baseName} ${sourceFile.name}")
}

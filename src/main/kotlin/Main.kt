package com.bitsycore

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: ktc <file.kt...> [-o <output_dir>] [--mem-track]")
        System.err.println("  Transpiles Kotlin subset files to C11.")
        System.err.println("  --mem-track  Enable allocation tracking (alloc/free counts + leak report)")
        exitProcess(1)
    }

    // Parse args: collect .kt files and flags
    val inputPaths = mutableListOf<String>()
    var outputDir = "."
    var memTrack = false
    var i = 0
    while (i < args.size) {
        if (args[i] == "-o" && i + 1 < args.size) {
            outputDir = args[i + 1]
            i += 2
        } else if (args[i] == "--mem-track") {
            memTrack = true
            i++
        } else {
            inputPaths += args[i]
            i++
        }
    }

    if (inputPaths.isEmpty()) {
        System.err.println("Error: no input files specified")
        exitProcess(1)
    }

    // Resolve input files
    val inputFiles = mutableListOf<File>()
    for (path in inputPaths) {
        val f = File(path)
        if (f.exists()) {
            inputFiles += f
        } else {
            System.err.println("Error: file not found: $path")
            exitProcess(1)
        }
    }

    // ── Lex & Parse all files ────────────────────────────────────────
    data class ParsedSource(val file: File, val ast: KtFile, val sourceLines: List<String>)
    val parsedFiles = mutableListOf<ParsedSource>()
    for (inputFile in inputFiles) {
        val source = inputFile.readText()
        val tokens: List<Token>
        try {
            tokens = Lexer(source).tokenize()
        } catch (e: Exception) {
            System.err.println("Lexer error in ${inputFile.name}: ${e.message}")
            exitProcess(1)
        }
        val ast: KtFile
        try {
            ast = Parser(tokens).parseFile()
        } catch (e: Exception) {
            System.err.println("Parser error in ${inputFile.name}: ${e.message}")
            exitProcess(1)
        }
        parsedFiles += ParsedSource(inputFile, ast, source.lines())
    }

    // ── Group files by package ───────────────────────────────────────
    // Files with the same package are merged into a single output unit.
    // Files with different packages produce separate .c/.h outputs.
    val byPackage = parsedFiles.groupBy { it.ast.pkg ?: it.file.nameWithoutExtension }

    val outDir = File(outputDir)
    outDir.mkdirs()

    val allAsts = parsedFiles.map { it.ast }
    val allOutputNames = mutableListOf<String>()

    for ((pkg, group) in byPackage) {
        // Merge all files in the same package into one KtFile
        val mergedImports = group.flatMap { it.ast.imports }.distinct()
        val mergedDecls = group.flatMap { it.ast.decls }
        val mergedFile = KtFile(group.first().ast.pkg, mergedImports, mergedDecls)
        val mergedSourceLines = group.flatMap { it.sourceLines }

        val output: CCodeGen.COutput
        try {
            val srcName = if (group.size == 1) group.first().file.name else "$pkg.kt"
            output = CCodeGen(mergedFile, allAsts, mergedSourceLines, memTrack = memTrack, sourceFileName = srcName).generate()
        } catch (e: Exception) {
            System.err.println("CodeGen error in package '$pkg': ${e.message}")
            exitProcess(1)
        }

        val baseName = mergedFile.pkg?.replace('.', '_') ?: pkg
        val headerFile = File(outDir, "$baseName.h")
        val sourceFile = File(outDir, "$baseName.c")
        headerFile.writeText(output.header)
        sourceFile.writeText(output.source)
        allOutputNames += baseName
        println("  wrote ${headerFile.path}")
        println("  wrote ${sourceFile.path}")
    }

    // ── Copy runtime (always overwrite to keep in sync) ──────────────
    val runtimeDst = File(outDir, "ktc_runtime.h")
    val runtimeSrc = object {}.javaClass.getResourceAsStream("/ktc_runtime.h")
    if (runtimeSrc != null) {
        runtimeDst.writeText(runtimeSrc.bufferedReader().readText())
    } else {
        System.err.println("Warning: ktc_runtime.h not found in resources, copy it manually.")
    }

    // Print compile command
    val sourceNames = allOutputNames.joinToString(" ") { "$it.c" }
    val firstBase = allOutputNames.first()
    println("Done. Compile with:  cc -std=c11 -o $firstBase $sourceNames")
}

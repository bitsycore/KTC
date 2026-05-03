package com.bitsycore

import kotlin.test.Test

/**
 * Regression test for the JSON parser showcase (tests/JsonParser/JsonParser.kt).
 *
 * Transpiles the full JsonParser.kt source and asserts on key patterns that
 * previously caused gcc compilation failures:
 *   - Top-level val constants must be prefixed (JsonParserTest_TOK_LBRACE, not TOK_LBRACE)
 *   - IntArray(n) must produce non-const pointers (mutable array contents)
 *   - MutableList<Int> params are passed by value (struct copy)
 *   - Array $len companions must be emitted for val assignments from array constructors
 */
class JsonParserTest : TranspilerTestBase() {

    private fun transpileJsonParser(): TranspileResult {
        val src = java.io.File("tests/JsonParserTest/JsonParserTest.kt").readText()
        return transpile(src)
    }

    // ── Top-level val constants get package prefix ───────────────────

    @Test fun topLevelConstantsDefinedWithPrefix() {
        val r = transpileJsonParser()
        r.sourceContains("const ktc_Int JsonParserTest_TOK_LBRACE = 0;")
        r.sourceContains("const ktc_Int JsonParserTest_TOK_EOF = 11;")
        r.sourceContains("const ktc_Int JsonParserTest_JSON_STRING = 0;")
        r.sourceContains("const ktc_Int JsonParserTest_JSON_OBJECT = 5;")
    }

    @Test fun topLevelConstantsReferencedWithPrefix() {
        val r = transpileJsonParser()
        // References inside function bodies must use the prefixed name
        r.sourceContains("JsonParserTest_TOK_LBRACE)")
        r.sourceContains("JsonParserTest_TOK_RBRACK)")
        r.sourceContains("JsonParserTest_JSON_STRING)")
        r.sourceContains("JsonParserTest_JSON_ARRAY)")
        // Must NOT contain bare unprefixed references (except in comments/strings)
        r.sourceNotContains(", TOK_LBRACE)")
        r.sourceNotContains(", JSON_STRING)")
    }

    @Test fun topLevelConstantsExternInHeader() {
        val r = transpileJsonParser()
        r.headerContains("extern const ktc_Int JsonParserTest_TOK_LBRACE;")
        r.headerContains("extern const ktc_Int JsonParserTest_JSON_OBJECT;")
    }

    // ── IntArray(n) produces mutable (non-const) pointers ───────────

    @Test fun intArrayNotConst() {
        val r = transpileJsonParser()
        // val pos = IntArray(1) should produce "ktc_Int* pos" not "const ktc_Int* pos"
        r.sourceContains("ktc_Int* pos =")
        r.sourceNotContains("const ktc_Int* pos")
    }

    @Test fun intArrayLenCompanionEmitted() {
        val r = transpileJsonParser()
        // The $len companion must be emitted for the named variable, not just the temp
        r.sourceContains("pos\$len =")
        r.sourceContains("idx\$len =")
    }

    // ── MutableList<Int> params passed by pointer ────────────────────

    @Test fun mutableListParamsByPointer() {
        val r = transpileJsonParser()
        // Function signatures now pass class types by value (struct copy)
        r.sourceContains("JsonParserTest_MutableList_Int toks")
        r.sourceContains("JsonParserTest_MutableList_Int out)")
    }

    @Test fun mutableListParamsByPointerInHeader() {
        val r = transpileJsonParser()
        r.headerContains("JsonParserTest_MutableList_Int toks")
        r.headerContains("JsonParserTest_MutableList_Int out)")
    }

    @Test fun mutableListMethodCallsUsePointerDirectly() {
        val r = transpileJsonParser()
        // Params are by-value, so method calls use & to get pointer for $self
        r.sourceContains("JsonParserTest_MutableList_Int_add(&out, JsonParserTest_JSON_STRING)")
        r.sourceContains("JsonParserTest_MutableList_Int_get(&out,")
        r.sourceContains("JsonParserTest_MutableList_Int_get(&toks,")
    }

    @Test fun mutableListLocalStructUsesAddressOf() {
        val r = transpileJsonParser()
        // Local stack struct (tokens in lexJson) uses & for method calls
        r.sourceContains("JsonParserTest_MutableList_Int_add(&tokens,")
    }

    @Test fun mutableListLocalPassedByAddressToFunctions() {
        val r = transpileJsonParser()
        // In main, local class structs are passed by value (struct copy)
        // IntArray params are wrapped in ktc_ArrayTrampoline for pass-by-value semantics
        r.sourceContains("tokens, (ktc_ArrayTrampoline)")
        r.sourceContains("output)")
    }

    // ── Heap pointer field access uses -> ────────────────────────────

    @Test fun pointerParamFieldAccessUsesArrow() {
        val r = transpileJsonParser()
        // out.size (by-value struct), not out->size
        r.sourceContains("out.size")
    }

    // ── Generic class monomorphization ───────────────────────────────

    @Test fun mutableListIntStructGenerated() {
        val r = transpileJsonParser()
        r.headerContains("typedef struct JsonParserTest_MutableList_Int JsonParserTest_MutableList_Int;")
        r.headerContains("struct JsonParserTest_MutableList_Int {")
        r.headerContains("ktc_Int size;")
        r.headerContains("ktc_Int* buf;")
    }

    @Test fun mutableListIntMethodsGenerated() {
        val r = transpileJsonParser()
        r.sourceContains("void JsonParserTest_MutableList_Int_add(JsonParserTest_MutableList_Int* \$self, ktc_Int value)")
        r.sourceContains("ktc_Int JsonParserTest_MutableList_Int_get(JsonParserTest_MutableList_Int* \$self, ktc_Int index)")
        r.sourceContains("void JsonParserTest_MutableList_Int_set(JsonParserTest_MutableList_Int* \$self, ktc_Int index, ktc_Int value)")
        r.sourceContains("void JsonParserTest_MutableList_Int_dispose(JsonParserTest_MutableList_Int* \$self)")
    }

    // ── Data class Lexer ─────────────────────────────────────────────

    @Test fun lexerDataClass() {
        val r = transpileJsonParser()
        r.headerContains("} JsonParserTest_Lexer;")
        r.sourceContains("JsonParserTest_Lexer_create(ktc_String input, ktc_Int len)")
        r.sourceContains("JsonParserTest_Lexer_equals(JsonParserTest_Lexer a, JsonParserTest_Lexer b)")
    }

    // ── C interop ────────────────────────────────────────────────────

    @Test fun cPrintfPassthrough() {
        val r = transpileJsonParser()
        // c.printf calls become raw printf
        r.sourceContains("printf(\"Unexpected character at position %d\\n\", i)")
        r.sourceContains("printf(\"Token count: %d\\n\", tokenCount)")
    }

    @Test fun cExitPassthrough() {
        val r = transpileJsonParser()
        r.sourceContains("exit(1)")
    }

    // ── Defer cleanup ────────────────────────────────────────────────

    @Test fun deferDispose() {
        val r = transpileJsonParser()
        // defer tokens.dispose() and defer output.dispose() emitted at end of main
        r.sourceContains("JsonParserTest_MutableList_Int_dispose(&output)")
        r.sourceContains("JsonParserTest_MutableList_Int_dispose(&tokens)")
    }
}

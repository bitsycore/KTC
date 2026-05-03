package com.bitsycore

import kotlin.test.Test

/*
Tests for the stdlib Random object transpilation.

Call-site tests use transpileMainWithStdlib so the ktc.Random object is in scope.
Declaration tests use transpileStdlibFile to inspect the ktc package output directly.
*/
class RandomUnitTest : TranspilerTestBase() {

	// ── Struct / extern declarations (in ktc package output) ─────────

	@Test fun randomStructDeclaredInHeader() {
		val vResult = transpileStdlibFile("Random.kt")
		vResult.headerContains("ktc_std_Random_t")
	}

	@Test fun randomExternDeclaredInHeader() {
		val vResult = transpileStdlibFile("Random.kt")
		vResult.headerContains("extern ktc_std_Random_t ktc_std_Random;")
	}

	@Test fun randomNextIntDeclaredInHeader() {
		val vResult = transpileStdlibFile("Random.kt")
		vResult.headerContains("ktc_std_Random_nextInt(")
	}

	@Test fun randomNextFloatDeclaredInHeader() {
		val vResult = transpileStdlibFile("Random.kt")
		vResult.headerContains("ktc_std_Random_nextFloat(")
	}

	@Test fun randomNextDoubleDeclaredInHeader() {
		val vResult = transpileStdlibFile("Random.kt")
		vResult.headerContains("ktc_std_Random_nextDouble(")
	}

	@Test fun randomNextBooleanDeclaredInHeader() {
		val vResult = transpileStdlibFile("Random.kt")
		vResult.headerContains("ktc_std_Random_nextBoolean(")
	}

	// ── Init body (srand in ensure_init, in ktc package source) ──────

	@Test fun randomInitCallsSrand() {
		val vResult = transpileStdlibFile("Random.kt")
		vResult.sourceContains("srand(")
	}

	@Test fun randomInitUsesTime() {
		val vResult = transpileStdlibFile("Random.kt")
		vResult.sourceContains("time(")
	}

	// ── Call-site generation (in user package source) ─────────────────

	@Test fun nextIntNoArgFillsDefaultZero() {
		val vResult = transpileMainWithStdlib("val n = Random.nextInt()")
		// Default param 0 must be filled in; ensures ensure_init is also called
		vResult.sourceContains("ktc_std_Random_nextInt(0)")
	}

	@Test fun nextIntWithUntilPassesThrough() {
		val vResult = transpileMainWithStdlib("val n = Random.nextInt(100)")
		vResult.sourceContains("ktc_std_Random_nextInt(100)")
	}

	@Test fun nextIntBetweenGeneratesCorrectCall() {
		val vResult = transpileMainWithStdlib("val n = Random.nextIntBetween(10, 50)")
		vResult.sourceContains("ktc_std_Random_nextIntBetween(10, 50)")
	}

	@Test fun nextLongNoArgFillsDefaultZero() {
		val vResult = transpileMainWithStdlib("val n = Random.nextLong()")
		// Long literal 0L → 0LL in C
		vResult.sourceContains("ktc_std_Random_nextLong(0LL)")
	}

	@Test fun nextLongWithUntilPassesThrough() {
		val vResult = transpileMainWithStdlib("val n = Random.nextLong(1000L)")
		vResult.sourceContains("ktc_std_Random_nextLong(1000LL)")
	}

	@Test fun nextFloatGeneratesCall() {
		val vResult = transpileMainWithStdlib("val f = Random.nextFloat()")
		vResult.sourceContains("ktc_std_Random_nextFloat()")
	}

	@Test fun nextDoubleGeneratesCall() {
		val vResult = transpileMainWithStdlib("val d = Random.nextDouble()")
		vResult.sourceContains("ktc_std_Random_nextDouble()")
	}

	@Test fun nextBooleanGeneratesCall() {
		val vResult = transpileMainWithStdlib("val b = Random.nextBoolean()")
		vResult.sourceContains("ktc_std_Random_nextBoolean()")
	}

	@Test fun randomEnsureInitCalledInsideMethod() {
		// $ensure_init is injected at the top of each method body in the ktc source
		val vResult = transpileStdlibFile("Random.kt")
		vResult.sourceContains("ktc_std_Random_\$ensure_init()")
	}
}

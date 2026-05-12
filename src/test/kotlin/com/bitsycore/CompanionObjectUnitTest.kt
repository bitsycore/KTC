package com.bitsycore

import kotlin.test.Test

/*
Tests for companion object declarations.
Companion objects are treated like named objects scoped inside their class,
accessed via ClassName.member syntax.
*/
class CompanionObjectUnitTest : TranspilerTestBase() {

	private val kClassWithCompanion = """
		class Foo {
			val x: Int
			companion object {
				val kDefault: Int = 42
				var count: Int = 0
				fun create(): Int = kDefault
			}
		}
	"""

	// ── Companion struct ─────────────────────────────────────────────

	@Test fun companionStructTypedef() {
		val vResult = transpileMain("println(Foo.kDefault)", decls = kClassWithCompanion)
		vResult.headerContains("test_Main_Foo\$Companion_t")
	}

	@Test fun companionGlobalInstance() {
		val vResult = transpileMain("println(Foo.kDefault)", decls = kClassWithCompanion)
		vResult.sourceContains("test_Main_Foo\$Companion_t test_Main_Foo\$Companion = {0};")
	}

	@Test fun companionExternDecl() {
		val vResult = transpileMain("println(Foo.kDefault)", decls = kClassWithCompanion)
		vResult.headerContains("extern test_Main_Foo\$Companion_t test_Main_Foo\$Companion;")
	}

	// ── Companion field access ───────────────────────────────────────

	@Test fun companionFieldRead() {
		val vResult = transpileMain("println(Foo.kDefault)", decls = kClassWithCompanion)
		vResult.sourceContains("test_Main_Foo\$Companion.kDefault")
	}

	@Test fun companionFieldWrite() {
		val vResult = transpileMain("Foo.count = 5", decls = kClassWithCompanion)
		vResult.sourceContains("test_Main_Foo\$Companion.count = 5;")
	}

	// ── Companion method call ────────────────────────────────────────

	@Test fun companionMethodCall() {
		val vResult = transpileMain("val n = Foo.create()", decls = kClassWithCompanion)
		vResult.sourceContains("test_Main_Foo\$Companion_create()")
	}

	// ── Lazy init guard ──────────────────────────────────────────────

	@Test fun companionEnsureInitCalledOnFieldRead() {
		val vResult = transpileMain("println(Foo.kDefault)", decls = kClassWithCompanion)
		vResult.sourceContains("test_Main_Foo\$Companion_\$ensure_init()")
	}

	@Test fun companionEnsureInitCalledOnFieldWrite() {
		val vResult = transpileMain("Foo.count = 1", decls = kClassWithCompanion)
		vResult.sourceContains("test_Main_Foo\$Companion_\$ensure_init()")
	}
}

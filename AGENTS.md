# AGENTS.md

This file provides context for AI coding agents working on the KotlinToC project.

## Project Overview

KotlinToC is a source-to-source transpiler that converts a subset of Kotlin into portable C11 code. It targets zero-runtime, stack-first allocation with no garbage collector. The output is standard C11 that compiles with GCC, Clang, or MSVC.

## Architecture

The transpiler is a single-pass pipeline:

```
Kotlin source → Lexer → Parser → AST → CCodeGen → .c/.h files
```

All stages are in `src/main/kotlin/`:

| File          | Role                                                                                                                                                         |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Lexer.kt`    | Tokenizer. Converts source text to token stream.                                                                                                             |
| `Token.kt`    | Token enum and type definitions.                                                                                                                             |
| `Parser.kt`   | Recursive-descent parser. Produces AST from tokens.                                                                                                          |
| `Ast.kt`      | AST node definitions (Decl, Stmt, Expr, TypeRef).                                                                                                            |
| `CCodeGen.kt` | The bulk of the project (~6400 lines). All C code generation, generic monomorphization, type resolution, interface vtable emission, operator dispatch, etc.  |
| `Main.kt`     | CLI entry point. Loads stdlib, lexes/parses inputs, groups by package, invokes CCodeGen, writes output.                                                      |

### CCodeGen Internal Structure

CCodeGen emits section comments in the generated `.c` file:
```c
// ══ data class Vec2 (PointerTest.kt) ══
// ══ fun passArrayPtr(@Ptr Array<Int>) (PointerTest.kt) ══
// ══ private fun findSlot(key: K): Int ══
// ══ class HashMap<Int, String> (Map.kt) ══
```

### Type System

**Value semantics:** Unlike Kotlin, all types in KotlinToC are by value by default. A class instance is a C struct held directly on the stack; assignment copies the struct. `@Ptr T` is the only way to introduce pointer/reference semantics.

Types are tracked as strings internally with suffix markers:
- `T` — value type (stack)
- `T*` — pointer type (`@Ptr` annotation)
- `T?` — value-nullable (uses Optional struct)
- `T*?` — nullable pointer (uses NULL)

The `@Ptr` annotation on a TypeRef adds `*` suffix. There is no `Ptr<T>` generic wrapper, no `@Heap`, `@Value`, `^`, `&`, or `#` marker.

**Primitive types:** `Int`, `Long`, `Float`, `Double`, `Boolean`, `Char`, `String`, `UByte`, `UShort`, `UInt`, `ULong`. All have Optional and hash support.

### Pointer Semantics

Only `@Ptr T` exists as a pointer annotation. `Ptr<T>` generic wrapper has been removed.

- `p.x` on `@Ptr Vec2` → `p->x` (auto-deref on member access)
- `v.ptr()` → `&v` (take address of a plain value, yields `@Ptr T`)
- `p.value()` → `(*p)` (dereference to a stack copy of `T`)

### Array Types

- `Array<T>` — dynamic-size stack array, represented as `ktc_ArrayTrampoline`. Cannot be returned from a function.
- `@Size(N) Array<T>` — fixed-size stack array with size known at compile time. Passed and returned as a raw C pointer (out-parameter ABI). Can be returned from functions.

### Nullable Pattern

- **Value types** (`Vec2?`): Optional struct with `tag` (`ktc_SOME`/`ktc_NONE`) and `value`
- **Pointer types** (`@Ptr Vec2?`): nullable pointer, NULL for null
- **Arrays** (`Array<Int>?`): `ktc_ArrayTrampoline` with `data == NULL`

### Inline Functions & Lambdas

- `inline fun` functions are expanded at the call site; no C function is emitted for them.
- Lambda expressions (`{ params -> body }` or trailing `{ }`) are **only valid as arguments to `inline` functions**. There is no closure support — lambdas cannot be stored in variables or passed to non-inline functions.
- Inside an inline expansion, lambda call sites are themselves expanded inline.
- `::funRef` (function pointer references) are separate from lambdas and work with regular functions, producing a raw C function pointer.

### Private Visibility

`private` keyword supported on:
- **Fields**: `PRIV_` prefix on C struct field, auto-resolved in class methods. External code sees un-prefixed name (C compilation error).
- **Methods**: `PRIV_` prefix on C function name, forward declaration in `.c` only (not `.h`).

```kotlin
class Foo {
    private var count: Int = 0
    private fun helper(): Int = count
}
// → struct { ktc_Int PRIV_count; };
// → ktc_Int Foo_PRIV_helper(Foo* $self);
```

### Stdlib

The stdlib lives in `src/main/resources/stdlib/` as Kotlin source files transpiled alongside user code. Uses `@Ptr` annotation for heap-allocated arrays.

Stdlib files belong to `package ktc.std` and get the `ktc_std_` C prefix.

## Testing

### Unit Tests (~330 tests)

Located in `src/test/kotlin/com/bitsycore/` with `*UnitTest.kt` suffix. JUnit 5 tests that feed Kotlin source strings through the transpiler pipeline and assert on generated C code. They do NOT compile or execute the C output.

Run with: `./gradlew test`

Test base class `TranspilerTestBase` provides:
- `transpile(src)` → `TranspileResult(header, source, pkg)`
- `transpileMain(body, decls)` — wraps body in a main function with optional supporting decls
- `sourceContains()`, `headerContains()`, `sourceMatches()` — assertion helpers
- `transpileExpectError(src, msg)` — negative test helper

### Integration Tests

Each subdirectory under `tests/` is one integration test. All `.kt` files in the directory are transpiled, compiled, and executed together.

```
tests/
  HashMapTest/        — HashMap/ArrayList operators, for-in iteration
  JsonParserTest/     — MutableList, safe-call extensions
  LambdaInlineTest/   — Inline functions, lambda parameters, array .let()
  ListTest/           — List, ArrayList, ListIterator, newArray
  MultiFileTest/      — Multi-file/multi-package test
  PairVarargTest/     — Pair intrinsic, to infix, vararg, spread
  PointerTest/        — @Ptr Array<T>, nullable pointers, vec, data classes
  UberTest/           — Comprehensive: classes, data classes, generics, interfaces, defer, @Ptr, HeapAlloc
```

Run scripts: `run_tests.ps1` (Windows) / `run_tests.sh` (Unix)
```
.\run_tests.ps1                    # Run all
.\run_tests.ps1 -Skip unit         # Skip unit
.\run_tests.ps1 -Run HashMapTest   # Single test
.\run_tests.ps1 -MemTrack -Ast     # Transpiler flags
.\run_tests.ps1 -CCArgs "-j14 -O2" # C compiler flags
.\run_tests.ps1 -Compiler clang    # Override C compiler
.\run_tests.ps1 -BuildJar          # Force fat JAR build (default: gradlew run)
```

## Build

```bash
./gradlew jar       # Build fat JAR
./gradlew test      # Run unit tests
.\run_tests.ps1     # Run all tests (Windows)
./run_tests.sh      # Run all tests (Unix)
```

Requires JDK 21+ and a C11 compiler (GCC, Clang, or MSVC) on PATH.

## Key Conventions

- `@Ptr T` annotation for pointer types (not `Ptr<T>`)
- `$self` is the receiver parameter name for methods
- Mangled generic names use `_` separator: `HashMap_Int_String`
- Package prefix uses `_` separator: `package game.Main` → `game_Main_`
- `operator` keyword required for dispatch
- `defer` is a non-standard extension for RAII-style cleanup
- Strings are non-owning slices (`ktc_String { const char* ptr; int32_t len; }`)
- `private` keyword supported on fields and methods
- Private methods get `PRIV_` prefix and `.c`-only forward declaration
- Private fields get `PRIV_` prefix on struct; access within class auto-resolved

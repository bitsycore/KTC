# KotlinToC

A transpiler that converts a subset of Kotlin to portable C11. Stack-first allocation, no garbage collector, no runtime dependencies beyond the C standard library.

## Quick Start

```bash
# Build the transpiler
./gradlew jar

# Transpile a Kotlin file (or use gradle run directly):
./gradlew run --args="myfile.kt"

# Or via fat JAR:
java -jar build/libs/KotlinToC-1.0-SNAPSHOT.jar myfile.kt

# Compile the generated C
cc -std=c11 -o myfile ktc_std.c myfile.c
```

## Usage

```
java -jar KotlinToC.jar <file.kt...> [-o <output_dir>] [--mem-track] [--ast]
```

| Flag           | Description                                             |
|----------------|---------------------------------------------------------|
| `<file.kt...>` | One or more Kotlin source files                         |
| `-o <dir>`     | Output directory for generated `.c`/`.h` (default: `.`) |
| `--mem-track`  | Enable allocation tracking with leak reporting at exit  |
| `--ast`        | Print AST for debugging                                 |

## Supported Language Features

### Types & Primitives

- `Int`, `Long`, `Float`, `Double`, `Boolean`, `Char`, `String`
- `Array<T>` (stack-allocated), `Array<T?>` (optional elements)
- `@Ptr T` annotation for pointer types
- Nullable types with `?` suffix: value-nullable uses Optional, pointer-nullable uses NULL
- Safe calls `?.`, elvis `?:`, not-null assertion `!!`
- Smart casts after `is` / `!is` checks (for `val` bindings only)

### Memory Model

| Kotlin Type | C Representation      | Semantics                       |
|-------------|-----------------------|---------------------------------|
| `T`         | `T` (value)           | Stack-allocated, by value       |
| `@Ptr T`    | `T*`                  | Pointer, `p.x` → `p->x`         |
| `@Ptr T?`   | `T*` (nullable)       | Nullable pointer, NULL for null |
| `Array<T>`  | `ktc_ArrayTrampoline` | Stack array, pass-by-value      |

- `HeapAlloc<T>(args)` / `HeapArrayZero<T>(n)` / `HeapArrayResize<T>(ptr, n)` for heap allocation
- `HeapFree(ptr)` releases heap memory
- `Array<T>(size)` uses stack allocation; `HeapAlloc<Array<T>>(size)` for heap arrays

### Functions

- Top-level functions with full signatures: `fun name(@Ptr param: Type?): Ret`
- Default parameter values
- Extension functions (including generic and star-projection receivers)
- Generic functions (monomorphized at call sites)
- `vararg` parameters with spread operator `*`
- Function pointers via `::`

### Classes & Data Classes

- Constructor properties (`val`/`var` in primary constructor)
- Body properties with initializers
- `private` fields and methods
- Methods (including `override`)
- `data class` with auto-generated `toString()`, `equals()`
- Generic classes (monomorphized per unique type argument combination)

### Interfaces

- Method declarations and `val` property declarations
- Multiple interface implementation
- Vtable-based dynamic dispatch via fat pointers (`{void* obj, vtable* vt}`)

### Other

- Enums, singleton objects, `when`, `for` loops, `defer`
- Operator overloading (`operator fun get/set/contains/iterator`)
- C interop via `c.` prefix: `c.printf(...)`, `c.memcpy(...)`
- String methods: `length`, `substring()`, `startsWith()`, `endsWith()`, `contains()`

## Standard Library (`package ktc.std`)

| Type                           | Description                                 |
|--------------------------------|---------------------------------------------|
| `Disposable`                   | Interface with `dispose()` for cleanup      |
| `List<T>` / `MutableList<T>`   | Read-only and mutable list interfaces       |
| `ArrayList<T>`                 | Growable array-backed list implementation   |
| `ListIterator<T>`              | Iterator for list types                     |
| `Map<K,V>` / `MutableMap<K,V>` | Read-only and mutable map interfaces        |
| `HashMap<K,V>`                 | Open-addressing hash map implementation     |
| `MapIterator<K,V>`             | Iterator for map types (yields `Pair<K,V>`) |

Convenience functions: `listOf(...)`, `mutableListOf(...)`, `mapOf(...)`, `mutableMapOf(...)`.

## Project Structure

```
src/
  main/kotlin/
    Main.kt         # CLI entry point
    Lexer.kt         # Tokenizer
    Token.kt         # Token type definitions
    Parser.kt        # Recursive-descent parser
    Ast.kt           # AST node definitions
    CCodeGen.kt      # C code generator (~6000 lines)
  main/resources/
    ktc_intrinsic.h  # C runtime intrinsics
    ktc_intrinsic.c  # C runtime implementations
    stdlib/          # Kotlin stdlib (transpiled alongside user code)
      Collections.kt
      Map.kt
      ...
  test/kotlin/com/bitsycore/
    *UnitTest.kt     # ~330 unit tests
tests/
  HashMapTest/       # Integration tests (each is a directory with .kt files)
  JsonParserTest/
  ListTest/
  MultiFileTest/
  PairVararg/
  PointerTest/
  UberTest/
```

## Building & Testing

**Prerequisites:** JDK 21+, a C11 compiler (GCC, Clang, or MSVC).

```bash
# Build the transpiler
./gradlew jar

# Run unit tests
./gradlew test

# Run all tests (unit + integration)
.\run_tests.ps1            # Windows
./run_tests.sh             # Unix

# Skip unit tests
.\run_tests.ps1 -Skip unit

# Run single integration test
.\run_tests.ps1 -Run PointerTest

# With flags
.\run_tests.ps1 -MemTrack -Ast -CCArgs "-j14 -O2" -Compiler clang
```

## License

This project is proprietary. All rights reserved.

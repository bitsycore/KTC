# KotlinToC

A transpiler that converts a subset of Kotlin to portable C11. Stack-first allocation, no garbage collector, no runtime dependencies beyond the C standard library.

## Quick Start

```bash
# Build the transpiler
./gradlew jar

# Transpile a Kotlin file
java -jar build/libs/KotlinToC-1.0-SNAPSHOT.jar myfile.kt

# Compile the generated C
cc -std=c11 -o myfile ktc_std.c myfile.c
```

## Usage

```
ktc <file.kt...> [-o <output_dir>] [--mem-track]
```

| Flag           | Description                                             |
|----------------|---------------------------------------------------------|
| `<file.kt...>` | One or more Kotlin source files                         |
| `-o <dir>`     | Output directory for generated `.c`/`.h` (default: `.`) |
| `--mem-track`  | Enable allocation tracking with leak reporting at exit  |

Multiple files sharing the same `package` are merged into a single C output unit. Different packages produce separate `.c`/`.h` pairs.

### Multi-file Example

```bash
java -jar build/libs/KotlinToC-1.0-SNAPSHOT.jar game_main.kt game_vec3.kt math.kt
# Outputs: game.c game.h math.c math.h ktc_std.c ktc_std.h ktc_intrinsic.h
cc -std=c11 -o game ktc_std.c math.c game.c
```

## Supported Language Features

### Types & Primitives

- `Int`, `Long`, `Float`, `Double`, `Boolean`, `Char`, `String`
- `Array<T>` (stack-allocated via `alloca`)
- Nullable types with `?` suffix, safe calls `?.`, elvis `?:`, not-null assertion `!!`
- Smart casts after `is` / `!is` checks (for `val` bindings only)

### Memory Model

| Kotlin Type | C Representation | Semantics                            |
|-------------|------------------|--------------------------------------|
| `T`         | `T` (value)      | Stack-allocated, passed by value     |
| `Heap<T>`   | `T*`             | Heap-allocated pointer               |
| `Ptr<T>`    | `T*`             | Raw pointer (no ownership)           |
| `Value<T>`  | `T` (auto-deref) | Zero-cost wrapper, auto-dereferences |

- `HeapAlloc<T>(...)` / `HeapArrayZero<T>(...)` / `HeapArrayResize<T>(...)` return `Heap<T>?` (nullable)
- `HeapFree(ptr)` releases heap memory
- `Array<T>(size)` uses stack allocation; `HeapAlloc<Array<T>>(size)` for heap arrays

### Functions

- Top-level and nested functions
- Default parameter values
- Extension functions (including generic and star-projection receivers)
- Generic functions (monomorphized at call sites)
- `vararg` parameters with spread operator `*`
- Function pointers and lambda-style references via `::`

### Classes & Data Classes

- Constructor properties (`val`/`var` in primary constructor)
- Body properties with initializers
- Init blocks
- Methods (including `override`)
- `data class` with auto-generated `toString()`
- Generic classes (monomorphized per unique type argument combination)

### Interfaces

- Method declarations and `val` property declarations
- Single and multiple interface implementation
- Generic interfaces (monomorphized)
- Vtable-based dynamic dispatch via fat pointers (`{void* obj, vtable* vt}`)
- Transitive super-interface support

### Enums

- Simple enums with named entries
- Mapped to C `enum` with `Int` underlying type

### Object Declarations

- Singleton objects with properties and methods
- Mapped to C global instances

### Control Flow

- `if`/`else` (expressions and statements)
- `when` with `is`, `in`, and expression branches
- `for (i in 0 until n)`, `for (i in 0..n)`, `for (i in a downTo b)`
- `for (item in collection)` via `operator fun iterator()` / `hasNext()` / `next()`
- `while`, `do..while`, `break`, `continue`
- `defer` (RAII-like cleanup, runs at scope exit)

### Operators

Operator overloading via `operator fun` keyword:

| Kotlin           | Dispatches to                                                         |
|------------------|-----------------------------------------------------------------------|
| `obj[i]`         | `operator fun get(index)`                                             |
| `obj[i] = v`     | `operator fun set(index, value)`                                      |
| `x in obj`       | `operator fun contains(value)`                                        |
| `for (x in obj)` | `operator fun iterator()` returning a class with `hasNext()`/`next()` |

### Generics

- Class and function generics via **monomorphization** (compile-time specialization)
- `MyList<Int>` becomes `MyList_Int` struct with type-specific methods
- Generic interfaces monomorphized the same way
- Star-projection extension functions (e.g., `fun <T> List<T>.size()`)
- Intrinsic `Pair<A, B>` with `to` infix constructor

### C Interop

Call C standard library functions directly via the `c.` package prefix:

```kotlin
c.printf("Hello %s\n", name.ptr)
c.memcpy(dst, src, size)
c.strlen(s.ptr)
```

### String Operations

Strings are represented as `ktc_String { const char* ptr; int32_t len; }` (non-owning slice).

Built-in methods: `length`, `substring()`, `startsWith()`, `endsWith()`, `contains()`, `indexOf()`, `isEmpty()`, `isNotEmpty()`, character indexing via `[]`.

String templates: `"Hello, ${name}!"` with arbitrary expressions.

## Standard Library (`package ktc`)

The transpiler ships a small stdlib written in Kotlin that gets transpiled alongside user code. It provides:

| Type                           | Description                                 |
|--------------------------------|---------------------------------------------|
| `Disposable`                   | Interface with `dispose()` for cleanup      |
| `Hashable`                     | Interface with `hashCode()` for hashing     |
| `List<T>` / `MutableList<T>`   | Read-only and mutable list interfaces       |
| `ArrayList<T>`                 | Growable array-backed list implementation   |
| `ListIterator<T>`              | Iterator for list types                     |
| `Map<K,V>` / `MutableMap<K,V>` | Read-only and mutable map interfaces        |
| `HashMap<K,V>`                 | Open-addressing hash map implementation     |
| `MapIterator<K,V>`             | Iterator for map types (yields `Pair<K,V>`) |

Convenience functions: `listOf(...)`, `mutableListOf(...)`, `mapOf(...)`, `mutableMapOf(...)`.

All collection types implement `Disposable` and must be cleaned up via `dispose()` (or `defer obj.dispose()`).

## Memory Tracking

Pass `--mem-track` to enable allocation tracking. The generated C code will intercept `HeapAlloc`/`HeapArrayZero`/`HeapArrayResize`/`HeapFree` calls (mapped to C `malloc`/`calloc`/`realloc`/`free`) and print a report at program exit:

```
══════ KTC Memory Report ══════
Total allocations : 42
Total frees       : 42
Leaked blocks     : 0
```

Each allocation is attributed to the Kotlin source file and line that caused it.

## Project Structure

```
src/
  main/
    kotlin/
      Main.kt          # CLI entry point and file orchestration
      Lexer.kt          # Tokenizer
      Token.kt          # Token type definitions
      Parser.kt         # Kotlin subset parser (tokens -> AST)
      Ast.kt            # AST node definitions
      CCodeGen.kt       # C11 code generator (AST -> .c/.h)
    resources/
      ktc_intrinsic.h   # C runtime intrinsics (alloca, string, mem-track)
      stdlib/
        index.txt        # Stdlib manifest
        Disposable.kt    # Disposable interface
        Hashable.kt      # Hashable interface
        Collections.kt   # List, MutableList, ArrayList, ListIterator
        Map.kt           # Map, MutableMap, HashMap, MapIterator
  test/
    kotlin/com/bitsycore/
      *.kt               # 285 unit tests (transpile-and-assert on generated C)
tests/
  single/                # Single-file integration tests
    HashMapTest.kt       # HashMap/ArrayList operators, for-in iteration
    TestProject.kt       # Classes, data classes, generics, collections
    PairVararg.kt        # Pair intrinsic, vararg, spread operator
    JsonParser.kt        # Full JSON lexer + recursive-descent parser
    game.kt              # Comprehensive feature showcase (658 lines)
  multi/                 # Multi-file / multi-package integration tests
    game_main.kt
    game_vec3.kt
    math.kt
```

## Building & Testing

**Prerequisites:** JDK 25+, a C11 compiler (GCC, Clang, or MSVC).

```bash
# Build the transpiler JAR
./gradlew jar

# Run all 285 unit tests
./gradlew test

# Run integration tests (transpile + compile + execute)
# Windows:
.\run_tests.ps1

# Unix/macOS:
./run_tests.sh
```

### Running a Single File

Use `-Run` (Windows) or `--run` (Unix) to transpile, compile, and run a Kotlin file with step-by-step output:

```bash
# Windows
.\run_tests.ps1 -Run tests\single\HashMapTest.kt

# Unix/macOS
./run_tests.sh --run tests/single/HashMapTest.kt

# Multiple files (comma-separated)
.\run_tests.ps1 -Run "game_main.kt,game_vec3.kt,math.kt"
```

This shows each stage (transpile, compile, run) with the exact commands, full output, and a summary of generated files.

### Other Options

```bash
# Skip unit tests, only run integration tests
.\run_tests.ps1 -Skip unit         # Windows
./run_tests.sh --skip-unit          # Unix

# Only run single-file or multi-file integration tests
.\run_tests.ps1 -Only single       # Windows
./run_tests.sh --only single        # Unix
```

## Pipeline Overview

```
Kotlin source (.kt)
    │
    ▼
  Lexer          tokenize into token stream
    │
    ▼
  Parser         parse into AST (KtFile, Decl, Stmt, Expr)
    │
    ▼
  CCodeGen       generate C11 code
    │  ├─ collect declarations (classes, interfaces, enums, functions)
    │  ├─ scan for generic instantiations
    │  ├─ monomorphize generics (classes, interfaces, functions)
    │  ├─ emit struct typedefs, interface vtables, function prototypes (.h)
    │  └─ emit implementations, vtable instances, main() (.c)
    │
    ▼
  Output: name.h + name.c + ktc_std.h + ktc_std.c + ktc_intrinsic.h
    │
    ▼
  C compiler (cc -std=c11) → native executable
```

## License

This project is proprietary. All rights reserved.

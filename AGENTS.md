# AGENTS.md

This file provides context for AI coding agents working on the KotlinToC project.

---

## 1. Project Overview

KotlinToC is a source-to-source transpiler that converts a subset of Kotlin into portable C11 code. It targets **zero-runtime, stack-first allocation** with no garbage collector. The output is standard C11 that compiles with GCC, Clang, or MSVC.

```
Kotlin source → Lexer → Parser → AST → CCodeGen → .c/.h files
```

---

## 2. Source Layout

All transpiler stages are in `src/main/kotlin/`:

| File                         | Role                                          |
|------------------------------|-----------------------------------------------|
| `Token.kt`                   | Token enum and type definitions               |
| `Lexer.kt`                   | Tokenizer: source text → token stream         |
| `Parser.kt`                  | Recursive-descent parser: tokens → AST        |
| `Ast.kt`                     | AST node definitions (Decl, Stmt, Expr, TypeRef) |
| `Main.kt`                    | CLI entry point                               |

### 2.1 CCodeGen Module Map

The C code generator is split across 8 files, all in package `com.bitsycore`. Every function is an extension function on `CCodeGen`; state is `internal` so all files share it.

| File                      | Lines | Role                                                          |
|---------------------------|-------|---------------------------------------------------------------|
| `CCodeGen.kt`             | 1043  | **Orchestrator** — state, `collectDecls()`, `generate()`, `dumpSemantics()` |
| `CCodeGenStructures.kt`   |   60  | Data classes: `BodyProp`, `ClassInfo`, `EnumInfo`, `IfaceInfo`, etc. |
| `CCodeGenScan.kt`         |  789  | **Pre-scanning** — discover all generic class/function instantiations |
| `CCodeGenEmit.kt`         | 1497  | **Declaration emission** — structs, vtables, functions, interfaces |
| `CCodeGenStmts.kt`        | 1351  | **Statement codegen** — var/if/for/when/return/inline expansion |
| `CCodeGenExpr.kt`         | 2124  | **Expression codegen** — genExpr → calls, dots, bins, toString |
| `CCodeGenInfer.kt`        |  460  | **Type inference** — inferExprType and helpers |
| `CCodeGenCTypes.kt`       |  574  | **C type mapping** — resolveTypeName, cTypeStr, printf helpers |

### 2.2 Dependency Graph

```
CCodeGenCTypes ──────────────────────────┐   (leaf module, no deps)
                                         │
CCodeGenInfer ─── depends on ── CCodeGenCTypes
                                         │
CCodeGenScan ─── depends on ─── CCodeGenInfer, CCodeGenCTypes
                                         │
CCodeGenExpr ─── depends on ─── CCodeGenStmts, CCodeGenInfer, CCodeGenCTypes
                                         │
CCodeGenStmts ─── depends on ─── CCodeGenExpr, CCodeGenInfer, CCodeGenCTypes
                                         │
CCodeGenEmit ─── depends on ─── CCodeGenExpr, CCodeGenStmts, CCodeGenInfer, CCodeGenCTypes
                                         │
CCodeGen ─────── orchestrates all modules above
```

### 2.3 Pipeline Phases (orchestrated by `generate()`)

```
1. collectDecls()                          → populate symbol tables
2. scanForClassArrayTypes()                → discover class types in Array<T>
3. scanForGenericInstantiations()         → find MyList<Int>, HashMap<K,V> etc.
4. materializeGenericInstantiations()     → create concrete ClassInfo per instantiation
5. scanForGenericFunCalls()               → discover generic function call sites
6. scanGenericFunBodiesForInstantiations()       → transitive discovery (fixpoint)
7. scanGenericClassMethodBodiesForInstantiations() → from materialized classes (fixpoint)
8. computeGenericFunConcreteReturns()     → interface return → concrete class
9. Emit declarations                      → structs, vtables, functions, methods
10. Output assembly                       → .h + .c strings
```

---

## 3. Generated C Output

CCodeGen emits section comments in the generated `.c` file:

```c
// ══ data class Vec2 (PointerTest.kt) ══
// ══ fun passArrayPtr(@Ptr Array<Int>) (PointerTest.kt) ══
// ══ private fun findSlot(key: K): Int ══
// ══ class HashMap<Int, String> (Map.kt) ══
```

## 4. Type System

### 4.1 Value Semantics

Unlike Kotlin, **all types are by value by default**. A class instance is a C struct held directly on the stack; assignment copies the struct. `@Ptr T` is the only way to introduce pointer/reference semantics.

Types are tracked internally as strings with suffix markers:

| Suffix  | Meaning                  | C Representation       |
|---------|--------------------------|------------------------|
| `T`     | Value type (stack)       | `T` (struct)           |
| `T*`    | Pointer type (`@Ptr`)    | `T*`                   |
| `T?`    | Value-nullable           | `ktc_T_Optional`       |
| `T*?`   | Nullable pointer         | `T*` (NULL for null)   |

There is no `Ptr<T>` generic wrapper, no `@Heap`, `@Value`, `^`, `&`, or `#` marker.

### 4.2 Primitive Types

`Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `Boolean`, `Char`, `String`, `UByte`, `UShort`, `UInt`, `ULong`. All primitives have `ktc_T_Optional` and `ktc_hash_*` support.

### 4.3 Pointer Semantics

Only `@Ptr T` exists as a pointer annotation.

| Kotlin            | C                 | Notes                           |
|-------------------|-------------------|----------------------------------|
| `p.x` on `@Ptr T` | `p->x`            | Auto-deref on member access     |
| `v.ptr()`          | `&v`              | Take address, yields `@Ptr T`   |
| `p.value()`        | `(*p)`            | Dereference to stack copy       |

### 4.4 Array Types

| Kotlin              | C Representation      | Constraints                          |
|---------------------|-----------------------|--------------------------------------|
| `Array<T>`          | `ktc_ArrayTrampoline` | Stack array, **cannot be returned**  |
| `@Size(N) Array<T>` | `T[N]` (out-pointer)  | Fixed-size, **can be returned**      |
| `@Ptr Array<T>`     | `T*` (heap pointer)   | Heap-allocated, + `$len` variable    |

- `Array<T>` is passed/received as `ktc_ArrayTrampoline { .size, .data }`.
- Parameters are trampolined: copied via `alloca`+`memcpy` on entry.
- `@Size(N) Array<T>` is passed and returned as a raw C pointer via out-parameter ABI.

#### Heap-Allocated Arrays (safe to return)

| Kotlin                       | C Representation                 | Notes                            |
|------------------------------|----------------------------------|----------------------------------|
| `heapArrayOf<T>(e1, e2, ...)`| `T* = malloc(sizeof(T) * n)`     | Like `arrayOf` but on heap       |
| `HeapAlloc<Array<T>>(n)`     | `T* = malloc(sizeof(T) * n)`     | Uninitialized (zero-initialized) |
| `HeapArrayZero<Array<T>>(n)` | `T* = calloc(n, sizeof(T))`      | Zero-initialized                 |
| `HeapArrayResize<Array<T>>(p, n)` | `T* = realloc(p, n)`       | Reallocates                       |

`heapArrayOf<T>(v1, v2, ...)` is the heap equivalent of `arrayOf<T>(v1, v2, ...)`. It
allocates a heap array, initializes each element from the given values, and returns a
`@Ptr` (nullable pointer). The result carries an accompanying `$len` variable (the
number of elements). It is safe to return from functions.

### 4.5 Nullable Pattern

| Kotlin Type      | C Pattern                                    |
|------------------|-----------------------------------------------|
| `Vec2?`          | `ktc_Vec2_Optional { tag, value }`            |
| `@Ptr Vec2?`     | `Vec2*` (NULL for null)                       |
| `Array<Int>?`    | `ktc_ArrayTrampoline` with `data == NULL`     |

---

## 5. Language Feature Implementation

### 5.1 Inline Functions & Lambdas

- `inline fun` — body expanded at call site; **no C function emitted**.
- Lambdas `{ params -> body }` — **only valid as arguments to `inline` functions**. No closures.
- Inside an inline expansion, lambda call sites are themselves expanded inline.
- `::funRef` — function pointer references; produces raw C function pointer.

### 5.2 Private Visibility

`private` keyword supported on fields and methods:

```kotlin
class Foo {
    private var count: Int = 0
    private fun helper(): Int = count
}
// → struct { ktc_Int PRIV_count; };
// → ktc_Int Foo_PRIV_helper(Foo* $self);
// PRIV_ prefix is C-enforced: external code sees un-prefixed name → compilation error.
// Private methods: forward declaration only in .c (not .h).
```

### 5.3 Generics (Monomorphization)

- Generic classes (`class Box<T>`) and functions (`fun <T> id(x: T)`) are stored as templates.
- Concrete instantiations (`Box<Int>`) are discovered by scanning all type references and call sites.
- Each concrete instantiation is materialized as a distinct C type (`Box_Int` in C).
- Fixpoint iteration ensures transitive instantiations are found (e.g. `HashMap<Int,String>` creates `MapIterator<Int,String>`).
- Package prefix: `package game.Main` → `game_Main_`
- Mangled generics: `HashMap_Int_String`

### 5.4 Interface Vtables

Interfaces use **fat pointers** (`{void* obj; const vtable* vt;}`):

```c
typedef struct Drawable_vt {
    void (*draw)(void* self);
    ktc_Float (*area)(void* self);
} Drawable_vt;

typedef struct Drawable {
    void* obj;
    const Drawable_vt* vt;
} Drawable;
```

- Each class generates a static `const` vtable instance per implemented interface.
- `ClassName_as_IfaceName(ClassName* $self)` wrapping function converts class → interface fat pointer.
- Interface dispatch: `d.vt->method((void*)&d, args)`.
- Parent interface vtables are inherited transitively.

### 5.5 Operator Dispatch

`operator` keyword is required for:
- `get`/`set` — `a[i]`, `a[i] = v`
- `contains`/`containsKey` — `x in collection`
- `iterator` — `for (item in collection)`
- `hashCode` — requires `override` (implicitly overrides default)

### 5.6 `defer`

Non-standard RAII extension. LIFO execution at function end or return:

```kotlin
fun example() {
    defer { println("cleanup") }
    // ...body...
} // "cleanup" printed here
```

### 5.7 `c.*` Interop

Direct C function/constant access via `c.` prefix: `c.printf(...)`, `c.memcpy(...)`, `c.EXIT_SUCCESS`. String literals are passed as raw C strings.

### 5.8 Pair / Triple / Tuple Intrinsics

`Pair<A,B>`, `Triple<A,B,C>`, `Tuple<T...>` are built-in compound types (unless a user-defined class shadows them). Emitted as dedicated C structs (`ktc_Pair_Int_String`, etc.) on first use.

---

## 6. Stdlib

Located in `src/main/resources/stdlib/` as Kotlin source files. Transpiled alongside user code. All stdlib files belong to `package ktc.std` → `ktc_std_` C prefix.

---

## 7. Testing

### 7.1 Unit Tests (~330 tests)

Located in `src/test/kotlin/com/bitsycore/` with `*UnitTest.kt` suffix. JUnit 5 tests feed Kotlin source strings through the transpiler pipeline and assert on generated C code — they do **not** compile or execute the C output.

```bash
./gradlew test
```

Test base class `TranspilerTestBase` provides:
- `transpile(src)` → `TranspileResult(header, source, pkg)`
- `transpileMain(body, decls)` — wraps body in a main function
- `sourceContains()`, `headerContains()`, `sourceMatches()` — assertion helpers
- `transpileExpectError(src, msg)` — negative test helper

### 7.2 Integration Tests

Each subdirectory under `tests/` is one integration test. All `.kt` files in the directory are transpiled, compiled with a C11 compiler, and executed together.

```
tests/
  HashMapTest/        — HashMap/ArrayList operators, for-in iteration
  JsonParserTest/     — MutableList, safe-call extensions
  LambdaInlineTest/   — Inline functions, lambda parameters, array .let()
  ListTest/           — List, ArrayList, ListIterator, newArray
  MultiFileTest/      — Multi-file/multi-package test
  PairVarargTest/     — Pair intrinsic, to infix, vararg, spread
  PointerTest/        — @Ptr Array<T>, nullable pointers, vec, data classes
  UberTest/           — Comprehensive: classes, generics, interfaces, defer, @Ptr, HeapAlloc
```

```bash
./run_tests.sh                     # Run all (Unix)
.\run_tests.ps1                    # Run all (Windows)
./run_tests.sh --run HashMapTest   # Single test
.\run_tests.ps1 -Skip unit         # Integration only
.\run_tests.ps1 -MemTrack -Ast     # With transpiler flags
.\run_tests.ps1 -Compiler clang    # Override C compiler
```

---

## 8. Build

```bash
./gradlew jar       # Fat JAR
./gradlew test      # Unit tests
```

Requires JDK 21+ and a C11 compiler (GCC, Clang, or MSVC) on PATH.

---

## 9. Key Conventions

- `@Ptr T` annotation for pointer types (not `Ptr<T>`)
- `$self` is the receiver parameter name for methods
- `operator` keyword required for dispatch
- `defer` is a non-standard RAII extension
- Strings are non-owning slices: `ktc_String { const char* ptr; int32_t len; }`
- `private` fields get `PRIV_` prefix on struct; private methods get `PRIV_` on function name
- The `inlineCounter` produces unique temp vars (`$0`, `$1`, ...) and goto labels (`$end_ir_0`, ...)
- `preStmts` is a hoist buffer: C statements emitted before the current statement (for temp allocations, toString buffers, etc.)
- `trampolinedParams` tracks array params that have been copied to `local$` stack copies via alloca+memcpy

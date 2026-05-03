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

| File          | Role                                                                                                                                                        | Lines (approx) |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------|
| `Lexer.kt`    | Tokenizer. Converts source text to token stream.                                                                                                            |
| `Token.kt`    | Token enum and type definitions.                                                                                                                            |
| `Parser.kt`   | Recursive-descent parser. Produces AST from tokens.                                                                                                         |
| `Ast.kt`      | AST node definitions (Decl, Stmt, Expr, TypeRef).                                                                                                           |
| `CCodeGen.kt` | The bulk of the project (~5000 lines). All C code generation, generic monomorphization, type resolution, interface vtable emission, operator dispatch, etc. |
| `Main.kt`     | CLI entry point. Loads stdlib, lexes/parses inputs, groups by package, invokes CCodeGen, writes output.                                                     |

### CCodeGen Internal Structure

CCodeGen is a large class with these major subsystems (in execution order):

1. **Declaration collection** (`collectDecl`) — registers classes, interfaces, enums, objects into lookup maps
2. **Generic scanning** — discovers concrete generic instantiations from type annotations and call sites
3. **Monomorphization** — materializes concrete types (`MyList<Int>` → `MyList_Int` struct)
4. **Emission** — emits C structs, function prototypes (.h) and implementations (.c)

Key internal data structures:
- `classes: Map<String, ClassInfo>` — all known classes (including monomorphized)
- `interfaces: Map<String, IfaceInfo>` — all known interfaces
- `genericInstantiations: Map<String, Set<List<String>>>` — tracks which type arg combinations exist
- `genericClassDecls: Map<String, ClassDecl>` — template AST for generic classes
- `typeSubst: Map<String, String>` — active type parameter substitution during monomorphized emission
- `genericTypeBindings: Map<String, Map<String, String>>` — stored substitution maps per mangled name

### Type System Internals

Types are tracked as strings internally with suffix markers:
- `T` — value type (stack)
- `T*` — Heap pointer
- `T^` — Ptr (raw pointer)
- `T&` — Value wrapper (auto-deref, zero-cost)
- `T?` — nullable (uses out-pointer return pattern or `$has` companion)
- `T#` — nullable Heap pointer

The `resolveTypeName()` function resolves `TypeRef` AST nodes to these internal type strings, applying `typeSubst` for generics.

### Nullable Pattern

Functions returning nullable types use the **out-pointer pattern**:
```c
// Kotlin: fun get(key: Int): String?
// C: bool HashMap_Int_String_get(HashMap_Int_String* $self, int32_t key, ktc_String* $out);
// Returns true if value exists, writes value through $out pointer
```

`return null` → `return false;`  
`return value` → `*$out = value; return true;`

### Interface / Vtable Pattern

Interfaces are fat pointers: `{ void* obj, const Vtable* vt }`. Each class implementing an interface gets a static vtable instance and an `_as_InterfaceName()` wrapping function.

### Generics / Monomorphization

Generics are resolved entirely at transpile time. Each unique type argument combination produces a separate C struct and set of functions. The scanning happens in multiple passes to reach a fixpoint:

1. `scanForGenericInstantiations()` — scan AST type annotations
2. `materializeGenericInstantiations()` — create concrete ClassInfo entries
3. `scanForGenericFunCalls()` — scan function call sites
4. `scanGenericFunBodiesForInstantiations()` — scan generic function bodies with substitution
5. `scanGenericClassMethodBodiesForInstantiations()` — scan generic class method bodies
6. Re-materialize until fixpoint

### Array Handling

`Heap<Array<T>>` fields and parameters always have a companion `$len` field/param in C. When passing array-typed values, both the pointer and `$len` must be forwarded. This is handled by:
- `expandCallArgs()` for function/constructor calls
- `expandCtorParams()` for parameter declarations
- `emitBodyPropLenIfArray()` for body property initialization

## Stdlib

The stdlib lives in `src/main/resources/stdlib/` as Kotlin source files that are transpiled alongside user code. It is loaded automatically by `Main.kt` via the `index.txt` manifest.

Stdlib files belong to `package ktc` and get the `ktc_` C prefix. The stdlib provides:
- `Disposable` — cleanup interface
- `Hashable` — hashing interface
- `Collections.kt` — `List<T>`, `MutableList<T>`, `ArrayList<T>`, `ListIterator<T>`
- `Map.kt` — `Map<K,V>`, `MutableMap<K,V>`, `HashMap<K,V>`, `MapIterator<K,V>`

Generic stdlib types are monomorphized just like user types. `ArrayList<Int>` becomes `ktc_ArrayList_Int`.

## Testing

### Unit Tests (285 tests)

Located in `src/test/kotlin/com/bitsycore/`. These are JUnit 5 tests that feed Kotlin source strings through the transpiler pipeline and assert on the generated C code. They do NOT compile or execute the C output.

Run with: `./gradlew test`

Test base class `TranspilerTestBase` provides:
- `transpile(src)` → `TranspileResult(header, source, pkg)`
- `transpileMain(body)` — wraps body in a main function
- `sourceContains()`, `headerContains()`, `sourceMatches()` — assertion helpers
- `transpileExpectError(src, msg)` — negative test helper

### Integration Tests

Each subdirectory under `tests/` is one integration test. All `.kt` files in the directory are transpiled together. **Adding a new test = creating a new directory** with `.kt` files in it.

```
tests/
  HashMapTest/HashMapTest.kt      — HashMap/ArrayList operators, for-in iteration
  TestProject/TestProject.kt      — Classes, data classes, generics, Value<T>, collections
  PairVararg/PairVararg.kt        — Pair intrinsic, to infix, vararg, spread
  JsonParser/JsonParser.kt        — Full JSON lexer + recursive-descent parser (end-to-end)
  game/game.kt                    — Data classes, enums, interfaces, defer, nullable, smart casts
  multi-game/{game_main,game_vec3,math}.kt — Multi-file/multi-package test
```

Run with `run_tests.ps1` (Windows) or `run_tests.sh` (Unix):
```bash
.\run_tests.ps1                    # Run all (unit + integration)
.\run_tests.ps1 -Skip unit         # Skip unit tests
.\run_tests.ps1 -Run HashMapTest   # Run a single test by directory name

./run_tests.sh                     # Run all (unit + integration)
./run_tests.sh --skip-unit         # Skip unit tests
./run_tests.sh --run HashMapTest   # Run a single test by directory name
```

## Common Development Tasks

### Adding a new language feature

1. Add token types in `Token.kt` if needed
2. Add AST nodes in `Ast.kt`
3. Update `Parser.kt` to parse the new syntax
4. Update `CCodeGen.kt` to emit C code for the new nodes
5. Add unit tests in `src/test/kotlin/com/bitsycore/`
6. Add or update an integration test directory in `tests/`

### Adding a new stdlib type

1. Create or edit a `.kt` file in `src/main/resources/stdlib/`
2. If it's a new file, add it to `src/main/resources/stdlib/index.txt`
3. If it's generic, ensure the monomorphization scanner discovers it (check `scanGenericClassMethodBodiesForInstantiations` and related functions)
4. Add integration test coverage

### Debugging monomorphization issues

If you get "Generic class 'X' not materialized":
1. Check that the type is discovered by one of the scanning passes
2. The scanning order is: type annotations → function calls → generic function bodies → generic class method bodies
3. Use `scanTypeRefWithSubst()` to trace type resolution
4. Check that `typeSubst` is set correctly during emission

### Debugging C compilation errors

1. Check declaration ordering in the generated `.h` — forward declarations must precede usage
2. Check that `$len` companions are forwarded for `Heap<Array<T>>` params
3. Check that interface vtable structs include all methods from super-interfaces

## Build

```bash
# Build fat JAR
./gradlew jar
# Output: build/libs/KotlinToC-1.0-SNAPSHOT.jar

# Run unit tests
./gradlew test

# Run integration tests
# Windows: .\run_tests.ps1
# Unix: ./run_tests.sh
```

Requires JDK 25+ and a C11 compiler (GCC, Clang, or MSVC) on PATH.

## Key Conventions

- Class-type function parameters are always passed by pointer in C
- `$self` is the receiver parameter name for methods
- Mangled generic names use `_` separator: `HashMap_Int_String`
- Package prefix uses `_` separator: `package game.Main` → `game_Main_`
- The `operator` keyword is required for dispatch (matches Kotlin semantics)
- `defer` is a non-standard extension (not in real Kotlin) for RAII-style cleanup
- Strings are non-owning slices (`ktc_String { const char* ptr; int32_t len; }`)
- All generated files include `ktc_intrinsic.h` for runtime primitives

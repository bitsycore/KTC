# Kotlin.toC()

A transpiler that converts a subset of Kotlin to portable C11. Stack-first allocation, pass by value, no garbage collector, no runtime dependencies beyond the C standard library.

---

## Quick Start

```bash
./gradlew jar                              # build
./gradlew run --args="myfile.kt"           # transpile
cc -std=c11 -o myfile ktc_intrinsic.c ktc_std.c myfile.c  # compile
./myfile                                    # run
```

Or via fat JAR:

```bash
java -jar build/libs/KotlinToC-1.0-SNAPSHOT.jar myfile.kt
```

---

## Usage

```
java -jar KotlinToC.jar <file.kt...> [-o <output_dir>] [--mem-track] [--ast] [--dump-semantics]
```

| Flag             | Description                                      |
|------------------|--------------------------------------------------|
| `<file.kt...>`   | One or more Kotlin source files                  |
| `-o <dir>`       | Output directory for `.c`/`.h` (default: `.`)    |
| `--mem-track`    | Enable allocation tracking with leak report      |
| `--ast`          | Print parsed AST for debugging                   |
| `--dump-semantics`| Print symbol table analysis                     |

---

## Language Reference

This section describes the supported Kotlin subset and its C mapping. Every feature is shown with a **Kotlin → C example**.

### Primitives

| Kotlin    | C type       | Kotlin → C example                          |
|-----------|-------------|---------------------------------------------|
| `Byte`    | `ktc_Byte` (`int8_t`) | `val x: Byte = 1` → `ktc_Byte x = 1` |
| `Short`   | `ktc_Short` (`int16_t`) | `val x: Short = 1` → `ktc_Short x = 1` |
| `Int`     | `ktc_Int` (`int32_t`) | `val x: Int = 1` → `ktc_Int x = 1` |
| `Long`    | `ktc_Long` (`int64_t`) | `val x: Long = 1L` → `ktc_Long x = 1LL` |
| `Float`   | `ktc_Float` (`float`) | `val x = 1.0f` → `ktc_Float x = 1.0f` |
| `Double`  | `ktc_Double` (`double`) | `val x = 1.0` → `ktc_Double x = 1.0` |
| `Boolean` | `ktc_Bool` (`bool`) | `val x = true` → `ktc_Bool x = true` |
| `Char`    | `ktc_Char` (`char`) | `val x = 'a'` → `ktc_Char x = 'a'` |
| `UByte`   | `ktc_UByte` (`uint8_t`) | `val x: UByte = 1u` → `ktc_UByte x = 1` |
| `UShort`  | `ktc_UShort` (`uint16_t`) | `val x: UShort = 1u` → `ktc_UShort x = 1` |
| `UInt`    | `ktc_UInt` (`uint32_t`) | `val x: UInt = 1u` → `ktc_UInt x = 1u` |
| `ULong`   | `ktc_ULong` (`uint64_t`) | `val x: ULong = 1uL` → `ktc_ULong x = 1UL` |
| `String`  | `ktc_String` | `val s = "hi"` → `ktc_String s = ktc_str("hi")` |
| `Unit`    | `void` | Return type only |

The `String` type is a non-owning slice: `{ const char* ptr; int32_t len; }`.

---

### Memory Model — Value Semantics

**All types are by value by default.** A class instance is a C struct held on the stack — assignment copies the struct. There is no garbage collector.

| Kotlin              | C                    | Semantics                          |
|---------------------|----------------------|------------------------------------|
| `T`                 | `T`                  | Stack-allocated, copied on assign  |
| `@Ptr T`            | `T*`                 | Pointer to T                       |
| `@Ptr T?`           | `T*` (nullable)      | NULL for null                      |

Pointer operations:

| Kotlin            | C          |
|-------------------|------------|
| `v.ptr()`         | `&v`       |
| `p.value()`       | `*p`       |
| `p.x` (on @Ptr)   | `p->x`     |

---

### Nullable Types

| Kotlin        | C                                | Null sentinel    |
|---------------|----------------------------------|-------------------|
| `Int?`        | `ktc_Int_Optional { tag, value }`| `tag == ktc_NONE` |
| `@Ptr T?`     | `T*`                             | `NULL`            |
| `Array<T>?`   | `ktc_ArrayTrampoline`            | `data == NULL`    |

```kotlin
// value-nullable
fun maybe(): Int? = if (cond) 42 else null
// → ktc_Int_Optional → {ktc_SOME, 42} or {ktc_NONE}

// pointer-nullable
var p: @Ptr Vec2? = null
// → Vec2* p = NULL;
```

Null-safety operators:

| Kotlin    | C                                                    |
|-----------|------------------------------------------------------|
| `a?.b`    | `if (a$has) { a.b }` or `a != NULL ? a->b`           |
| `a ?: b`  | `a.tag == ktc_SOME ? a.value : (b)`                  |
| `a!!`     | `if (!a) { fprintf(stderr, "NPE"); exit(1); }`       |

Smart casts: `if (x != null) { /* x narrowed to non-null */ }` — the val must be `val` (not `var`).

---

### Arrays

| Kotlin              | C                            | Notes                            |
|---------------------|------------------------------|----------------------------------|
| `Array<T>`          | `ktc_ArrayTrampoline` + `$len`| Stack-allocated, cannot return   |
| `Array<T?>`         | `ktc_T_Optional*` + `$len`  | Each element wrapped in Optional |
| `@Size(N) Array<T>` | `T[N]` (out-pointer)         | Fixed size, can return from fun  |

```kotlin
val xs = arrayOf(1, 2, 3)        // → ktc_Int xs[] = {1, 2, 3};  const int32_t xs$len = 3;
val ys = IntArray(10)             // → ktc_Int* ys = alloca(...); memset(ys, 0, ...);
val zs = Array<Vec2>(5)           // → ktc_alloca(sizeof(game_Vec2) * 5); memset(zs, 0, ...);

// Iteration
for (x in xs) { ... }            // → for(ktc_Int $i=0; $i < xs$len; $i++) { ktc_Int x = xs[$i]; }
xs.size                           // → xs$len
```

Heap arrays:

```kotlin
val hp = HeapAlloc<Array<Int>>(100)     // → (ktc_Int*)malloc(sizeof(ktc_Int) * 100)
val hz = HeapArrayZero<Array<Vec2>>(50) // → calloc(50, sizeof(game_Vec2))
hp[3] = 99                              // direct indexing
HeapFree(hp)                             // → free(hp)
```

---

### Functions

```kotlin
fun add(a: Int, b: Int): Int = a + b
// → ktc_Int add(ktc_Int a, ktc_Int b) { return a + b; }

fun main() { println("hi") }
// → int main(void) { ... return 0; }

// default parameters
fun greet(name: String = "world") = "Hello $name"
// → filled at call site

// vararg
fun sum(vararg xs: Int): Int { ... }
// → ktc_Int sum(ktc_Int* xs, ktc_Int xs$len) { ... }
sum(1, 2, 3)                          // → ktc_Int $t[] = {1, 2, 3}; sum($t, 3)
sum(*existingArray)                    // → sum(existingArray, existingArray$len)
```

---

### Extension Functions

```kotlin
fun Int.squared(): Int = this * this
// → ktc_Int ktc_squared(ktc_Int $self) { return $self * $self; }

fun String?.isNullOrEmpty(): Boolean = this == null || isEmpty()
// nullable receiver: $self is passed as Optional struct
```

---

### Classes & Data Classes

```kotlin
class Vec2(val x: Float, val y: Float) {
    var tag: Int = 0
    fun length(): Float = sqrt(x * x + y * y)
}
```

```c
typedef struct {
    ktc_Int __type_id;
    ktc_Float x;           // val ctor prop
    ktc_Float y;           // val ctor prop
    ktc_Int tag;           // var body prop
} game_Vec2;

game_Vec2 game_Vec2_primaryConstructor(ktc_Float x, ktc_Float y);
ktc_Float game_Vec2_length(game_Vec2* $self);
```

Data classes get auto-generated `toString()`, `equals()`, and `hashCode()`:

```kotlin
data class Point(val x: Int, val y: Int)
// → Point_toString, Point_equals, Point_hashCode emitted automatically
```

---

### Interfaces & Polymorphism

```kotlin
interface Drawable {
    fun draw()
    val area: Float
}

class Circle(val r: Float) : Drawable {
    override fun draw() { ... }
    override val area get() = 3.14f * r * r
}
```

Uses **fat pointer** dispatch:

```c
// vtable
typedef struct Drawable_vt {
    void (*draw)(void* self);
    ktc_Float (*area)(void* self);
    void (*dispose)(void* self);
} Drawable_vt;

// fat pointer
typedef struct Drawable {
    void* obj;
    const Drawable_vt* vt;
} Drawable;

// use
Drawable d = Circle_as_Drawable(&circle);
d.vt->draw((void*)&d);
```

---

### Generics (Monomorphized)

Generic classes and functions are instantiated per concrete type combination:

```kotlin
class Box<T>(val item: T)
// template — no C code emitted

val ib = Box<Int>(42)       // → Box_Int emitted with ktc_Int item
val sb = Box<String>("hi")  // → Box_String emitted with ktc_String item

fun <T> identity(x: T): T = x
identity(42)                // → identity_Int(42)
identity("hi")              // → identity_String(ktc_str("hi"))
```

Package-prefixed and mangled: `package com.game` + `Box<Int>` → `com_game_Box_Int`.

---

### Inline Functions & Lambdas

```kotlin
inline fun measure(body: () -> Unit) {
    val start = now()
    body()
    println("took ${now() - start}ms")
}

measure { doWork() }
// → body is expanded inline at the call site — no C function emitted
```

Lambdas are only valid as arguments to `inline` functions. No closures — they cannot be stored in variables.

Function pointers via `::` produce raw C function pointers:

```kotlin
val fn = ::someFunction      // → void(*fn)(void) = someFunction
```

---

### Control Flow

| Kotlin          | C equivalent                                      |
|-----------------|---------------------------------------------------|
| `if/else`       | `if/else` (expression form → ternary or temp)     |
| `when`          | `if/else if/else` chain or nested ternary         |
| `for (i in a..b)`  | `for (ktc_Int i = a; i <= b; i++)`            |
| `for (i in a until b)` | `for (ktc_Int i = a; i < b; i++)`         |
| `for (i in a downTo b)` | `for (ktc_Int i = a; i >= b; i--)`      |
| `for (e in list)` | Iterator-based: `it = list.iterator(); while(hasNext) { e = next() }` |
| `while` / `do-while` | `while` / `do-while`                           |
| `break` / `continue` | `break` / `continue`                         |
| `return`         | `return` (with defer unwinding)                  |
| `in` / `!in`     | `operator contains()` dispatch                   |

---

### Other Features

| Feature            | Description                                              |
|--------------------|----------------------------------------------------------|
| `defer { ... }`    | RAII-style LIFO cleanup blocks                          |
| `operator get/set` | `a[i]` → `ClassName_get(&a, i)`                         |
| `operator contains`| `x in coll` → `ClassName_contains(&coll, x)`            |
| `operator iterator`| `for (x in obj)` → `iterator()` + `hasNext()` + `next()` |
| `c.printf(...)`    | Direct C function call, string args passed raw          |
| `c.memcpy(...)`    | Direct C standard library call                          |
| `private`          | Fields: `PRIV_` prefix; Methods: `.c`-only forward decl  |
| `enum class`       | C `typedef enum`, with `.name`/`.ordinal` support       |
| `object`           | Singleton with lazy `$ensure_init()`                    |
| `companion object` | Nested object treated as companion                      |

---

### Pair / Triple / Tuple

Built-in compound types (unless shadowed by a user class):

```kotlin
val p = Pair(1, "hi")            // → (ktc_Pair_Int_String){1, ktc_str("hi")}
p.first                           // → p.first
p.second                          // → p.second
val kv = "key" to 42              // → infix `to` → Pair literal

val t = Triple(1, 2.0, "three")  // → (ktc_Triple_Int_Double_String){1, 2.0, ktc_str("three")}
```

---

## Standard Library (`package ktc.std`)

Transpiled alongside user code. Provides:

| Type                           | Description                                 |
|--------------------------------|---------------------------------------------|
| `List<T>` / `MutableList<T>`   | Read-only and mutable list interfaces       |
| `ArrayList<T>`                 | Growable array-backed list                  |
| `ListIterator<T>`              | Iterator for list types                     |
| `Map<K,V>` / `MutableMap<K,V>` | Read-only and mutable map interfaces        |
| `HashMap<K,V>`                 | Open-addressing hash map                    |
| `MapIterator<K,V>`             | Map iterator (yields `Pair<K,V>`)           |

Convenience: `listOf(...)`, `mutableListOf(...)`, `mapOf(...)`, `mutableMapOf(...)`.

---

## Project Structure

```
src/
  main/kotlin/
    Main.kt                 — CLI entry point
    Lexer.kt / Token.kt     — Tokenizer
    Parser.kt / Ast.kt      — Recursive-descent parser + AST
    CCodeGen.kt             — C code generator orchestrator
    CCodeGenStructures.kt    — Data classes
    CCodeGenScan.kt         — Generic instantiation discovery
    CCodeGenEmit.kt         — Declaration emission
    CCodeGenStmts.kt        — Statement codegen
    CCodeGenExpr.kt         — Expression codegen
    CCodeGenInfer.kt        — Type inference
    CCodeGenCTypes.kt       — C type mapping + printf
  main/resources/
    ktc_intrinsic.h/.c      — C runtime
    stdlib/                 — Kotlin stdlib
  test/                     — ~330 unit tests
tests/                      — Integration tests (compile+run)
```

---

## Building & Testing

**Requirements:** JDK 21+, C11 compiler (GCC / Clang / MSVC)

```bash
./gradlew jar                # Build fat JAR
./gradlew test               # Unit tests (~330)
./run_tests.sh               # All tests (unit + integration)
./run_tests.sh --run HashMapTest  # Single integration test
```

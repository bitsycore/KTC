# KotlinToC — Language Subset Specification

KotlinToC (ktc) is a strict **subset of Kotlin** that transpiles to readable **C11**.
Every `.kt` file accepted by ktc is also valid Kotlin — you can run it on the JVM
with Kotlin's own stdlib, or transpile it to C with ktc's runtime.

## Design Principles

| Principle            | Description                                                                          |
|----------------------|--------------------------------------------------------------------------------------|
| **Kotlin in, C out** | You write regular Kotlin. You get readable C11.                                      |
| **Zero runtime**     | No GC, no reference counting, no hidden allocations.                                 |
| **Stack by default** | Everything lives on the stack unless you explicitly use an Arena.                    |
| **Own stdlib**       | Familiar Kotlin functions (`println`, `intArrayOf`, …) backed by a C runtime header. |
| **No lambdas**       | Function literals, closures, and higher-order functions are excluded.                |
| **No suspend**       | Coroutines and suspend functions are excluded.                                       |
| **Value semantics**  | Classes are C structs passed by value. No reference identity.                        |

---

## Packages & Namespacing

Every `.kt` file may declare a `package`. All dots in the package name are
replaced with underscores to form the C symbol prefix.

> **Recommendation:** Use a **single short word** as your package name
> (e.g. `package game`). Multi-segment names like `com.bitsycore.game` work
> but produce verbose C prefixes (`com_bitsycore_game_`). Keep it short.

| Kotlin                       | C prefix              | Notes                              |
|------------------------------|-----------------------|------------------------------------|
| *(no package)*               | *(none)*              | Bare names, `main()` emitted as-is |
| `package game`               | `game_`               | Recommended style                  |
| `package com.bitsycore.game` | `com_bitsycore_game_` | Works, but verbose                 |

### How symbols are prefixed

```kotlin
package game

data class Vec2(val x: Float, val y: Float)   // → game_Vec2
fun dot(a: Vec2, b: Vec2): Float = ...         // → game_dot
enum class Dir { UP, DOWN, LEFT, RIGHT }       // → game_Dir, game_Dir_UP …
object Config { val fps: Int = 60 }            // → game_Config, game_Config.fps
```

```c
// game.h
#pragma once
#include "ktc_runtime.h"

typedef struct { float x; float y; } game_Vec2;
float game_dot(game_Vec2 a, game_Vec2 b);

typedef enum { game_Dir_UP, game_Dir_DOWN, game_Dir_LEFT, game_Dir_RIGHT } game_Dir;

typedef struct { int32_t fps; } game_Config_t;
extern game_Config_t game_Config;
```

### Imports

Importing another ktc package emits a `#include`. The full package path
has dots replaced by `/` for the include path, and by `_` for the symbol prefix:

```kotlin
import math       // → #include "math/math.h"      prefix: math_
import gfx.core   // → #include "gfx_core/gfx_core.h"  prefix: gfx_core_
```

When referencing imported symbols, the package prefix is already baked into
the C name, so `math.lerp(a, b, t)` becomes `math_lerp(a, b, t)`.

### `main` function

`fun main()` always emits `int main(void)` regardless of package prefix.
It is the only symbol that is never prefixed.

---

## Supported Types

### Primitives

| Kotlin    | C         | Size    |
|-----------|-----------|---------|
| `Int`     | `int32_t` | 4 bytes |
| `Long`    | `int64_t` | 8 bytes |
| `Float`   | `float`   | 4 bytes |
| `Double`  | `double`  | 8 bytes |
| `Boolean` | `bool`    | 1 byte  |
| `Char`    | `char`    | 1 byte  |
| `Unit`    | `void`    | —       |

### String

`String` maps to `kt_String`, a pointer + length pair (slices).
String literals are zero-cost (`const char*` to static storage). Dynamic strings
use stack buffers or arena allocation.

```kotlin
// kotlin
val name: String = "Alice"
val greeting = "Hello, $name!"
```

```c
// c
kt_String name = kt_str("Alice");
char _buf1[256];
int _len1 = snprintf(_buf1, 256, "Hello, %.*s!", (int)name.len, name.ptr);
kt_String greeting = (kt_String){_buf1, _len1};
```

### Nullable Types

`T?` maps to a struct with a value + presence flag. Only supported for
primitives and class types.

```kotlin
// kotlin
val x: Int? = null
val y: Int? = 42
println(y ?: 0)
```

```c
// c
kt_Nullable_Int x = KT_NULL_VAL(kt_Nullable_Int);
kt_Nullable_Int y = KT_SOME(kt_Nullable_Int, 42);
printf("%" PRId32 "\n", y.has ? y.val : 0);
```

### Arrays

Fixed-size, stack-allocated. The array struct carries a pointer + length.

| Kotlin         | C                                              |
|----------------|------------------------------------------------|
| `IntArray`     | `kt_IntArray` (`int32_t* ptr` + `int32_t len`) |
| `LongArray`    | `kt_LongArray`                                 |
| `FloatArray`   | `kt_FloatArray`                                |
| `DoubleArray`  | `kt_DoubleArray`                               |
| `BooleanArray` | `kt_BooleanArray`                              |
| `CharArray`    | `kt_CharArray`                                 |

```kotlin
// kotlin
val arr = intArrayOf(1, 2, 3)
println(arr[0])
println(arr.size)
```

```c
// c
int32_t _arr_data[] = {1, 2, 3};
kt_IntArray arr = {_arr_data, 3};
printf("%" PRId32 "\n", arr.ptr[0]);
printf("%" PRId32 "\n", arr.len);
```

---

## Supported Declarations

### Functions

Regular functions with typed parameters, optional return type, block or expression body.
Default parameter values are filled in by the transpiler at call sites.

```kotlin
// kotlin
fun add(a: Int, b: Int): Int {
    return a + b
}

fun double(x: Int): Int = x * 2

fun greet(name: String, greeting: String = "Hello") {
    println("$greeting, $name!")
}
```

```c
// c
int32_t add(int32_t a, int32_t b) {
    return a + b;
}

int32_t double_(int32_t x) {
    return x * 2;
}

void greet(kt_String name, kt_String greeting) {
    printf("%.*s, %.*s!\n", (int)greeting.len, greeting.ptr, (int)name.len, name.ptr);
}

// call site: greet(kt_str("World"), kt_str("Hello"));  // default filled in
```

### Classes

Classes map to C structs. Constructor parameters with `val`/`var` become struct fields.
Methods become `ClassName_method(ClassName* self, ...)` functions.

```kotlin
// kotlin
class Counter(var count: Int) {
    fun increment() {
        count++
    }

    fun get(): Int = count
}

fun main() {
    val c = Counter(0)
    c.increment()
    println(c.get())
}
```

```c
// c
typedef struct { int32_t count; } Counter;

Counter Counter_create(int32_t count) {
    return (Counter){count};
}

void Counter_increment(Counter* self) {
    self->count++;
}

int32_t Counter_get(Counter* self) {
    return self->count;
}

int main(void) {
    Counter c = Counter_create(0);
    Counter_increment(&c);
    printf("%" PRId32 "\n", Counter_get(&c));
    return 0;
}
```

### Data Classes

Like classes, but the transpiler auto-generates `equals`, `toString`, and `copy`.

```kotlin
// kotlin
data class Point(val x: Int, val y: Int)

fun main() {
    val p = Point(3, 4)
    println(p)
    println(p == Point(3, 4))
}
```

```c
// c
typedef struct { int32_t x; int32_t y; } Point;

Point Point_create(int32_t x, int32_t y) {
    return (Point){x, y};
}

bool Point_equals(Point a, Point b) {
    return a.x == b.x && a.y == b.y;
}

kt_String Point_toString(Point self, char* buf, int bufsz) {
    int n = snprintf(buf, bufsz, "Point(x=%" PRId32 ", y=%" PRId32 ")", self.x, self.y);
    return (kt_String){buf, n};
}

int main(void) {
    Point p = Point_create(3, 4);
    char _buf1[256];
    kt_String _s1 = Point_toString(p, _buf1, 256);
    printf("%.*s\n", (int)_s1.len, _s1.ptr);
    printf("%s\n", Point_equals(p, Point_create(3, 4)) ? "true" : "false");
    return 0;
}
```

### Enum Classes

Enum classes map to C enums. No associated data or methods (plain enums only).

```kotlin
// kotlin
enum class Color { RED, GREEN, BLUE }

fun main() {
    val c = Color.RED
    when (c) {
        Color.RED -> println("Red")
        Color.GREEN -> println("Green")
        Color.BLUE -> println("Blue")
    }
}
```

```c
// c
typedef enum { Color_RED, Color_GREEN, Color_BLUE } Color;

int main(void) {
    Color c = Color_RED;
    if (c == Color_RED) { printf("Red\n"); }
    else if (c == Color_GREEN) { printf("Green\n"); }
    else if (c == Color_BLUE) { printf("Blue\n"); }
    return 0;
}
```

### Object Declarations (Singletons)

Object declarations map to a global struct instance.

```kotlin
// kotlin
object Config {
    val maxRetries: Int = 3
    var debug: Boolean = false
}

fun main() {
    Config.debug = true
    println(Config.maxRetries)
}
```

```c
// c
typedef struct { int32_t maxRetries; bool debug; } Config_t;
Config_t Config = {3, false};

int main(void) {
    Config.debug = true;
    printf("%" PRId32 "\n", Config.maxRetries);
    return 0;
}
```

---

## Supported Statements

### Variable Declarations

```kotlin
val x: Int = 5       // immutable, explicit type
var y = 10           // mutable, inferred type
val s = "hello"      // String literal
```

```c
const int32_t x = 5;
int32_t y = 10;
kt_String s = kt_str("hello");
```

### Assignment

```kotlin
y = 20
y += 5
arr[0] = 42
```

```c
y = 20;
y += 5;
arr.ptr[0] = 42;
```

### If / Else

Both as statement and expression.

```kotlin
// statement
if (x > 0) {
    println("positive")
} else {
    println("non-positive")
}

// expression
val abs = if (x >= 0) x else -x
```

```c
if (x > 0) {
    printf("positive\n");
} else {
    printf("non-positive\n");
}

int32_t abs = (x >= 0) ? x : -x;
```

### When

Both as statement and expression, with or without subject.

```kotlin
when (x) {
    1 -> println("one")
    2, 3 -> println("two or three")
    in 4..10 -> println("4 to 10")
    else -> println("other")
}

val desc = when {
    x < 0 -> "negative"
    x == 0 -> "zero"
    else -> "positive"
}
```

```c
if (x == 1) { printf("one\n"); }
else if (x == 2 || x == 3) { printf("two or three\n"); }
else if (x >= 4 && x <= 10) { printf("4 to 10\n"); }
else { printf("other\n"); }

const char* _d;
if (x < 0) { _d = "negative"; }
else if (x == 0) { _d = "zero"; }
else { _d = "positive"; }
kt_String desc = kt_str(_d);  // simplified
```

### For Loops

Three forms: inclusive range (`..`), exclusive range (`until`), and downward (`downTo`).
Plus array iteration.

```kotlin
for (i in 0..4) { println(i) }         // 0,1,2,3,4
for (i in 0 until 4) { println(i) }    // 0,1,2,3
for (i in 4 downTo 0) { println(i) }   // 4,3,2,1,0

for (v in arr) { println(v) }          // array iteration
```

```c
for (int32_t i = 0; i <= 4; i++) { printf("%" PRId32 "\n", i); }
for (int32_t i = 0; i < 4; i++) { printf("%" PRId32 "\n", i); }
for (int32_t i = 4; i >= 0; i--) { printf("%" PRId32 "\n", i); }

for (int32_t _i = 0; _i < arr.len; _i++) {
    int32_t v = arr.ptr[_i];
    printf("%" PRId32 "\n", v);
}
```

### While / Do-While

```kotlin
while (x > 0) { x-- }
do { x++ } while (x < 10)
```

```c
while (x > 0) { x--; }
do { x++; } while (x < 10);
```

### Return / Break / Continue

Direct mapping to C.

---

## Supported Expressions

### Operators

| Category        | Operators                      |
|-----------------|--------------------------------|
| Arithmetic      | `+` `-` `*` `/` `%`            |
| Comparison      | `==` `!=` `<` `>` `<=` `>=`    |
| Logical         | `&&` `\|\|` `!`                |
| Assignment      | `=` `+=` `-=` `*=` `/=` `%=`   |
| Increment       | `++` `--` (prefix and postfix) |
| Range           | `..`                           |
| Elvis           | `?:`                           |
| Not-null assert | `!!`                           |
| Safe call       | `?.`                           |
| Type check      | `is` `!is`                     |
| Type cast       | `as`                           |
| Infix           | `until` `downTo` `step`        |

### String Templates

`$name` and `${expr}` are supported. Transpiled to `snprintf` with
format specifiers chosen by the inferred type of each part.

```kotlin
val name = "World"
val n = 42
println("Hello, $name! n=${n + 1}")
```

```c
kt_String name = kt_str("World");
int32_t n = 42;
printf("Hello, %.*s! n=%" PRId32 "\n", (int)name.len, name.ptr, (int32_t)(n + 1));
```

### Function Calls

```kotlin
add(1, 2)
greet("Alice")
greet(name = "Bob", greeting = "Hi")  // named args reordered at compile time
```

### Constructor Calls

```kotlin
val p = Point(1, 2)   // → Point_create(1, 2)
```

### Property & Method Access

```kotlin
p.x                   // → p.x
c.increment()         // → Counter_increment(&c)
arr.size              // → arr.len
arr[i]                // → arr.ptr[i]
```

### Safe Calls & Elvis

```kotlin
val len = name?.length ?: 0
val v = nullable!!
```

---

## Arena Allocator

When stack allocation is insufficient (unknown-size collections, long-lived
strings), use the arena from the runtime:

```kotlin
// Conceptual usage (stdlib provides wrappers)
// Arena lives on the stack, sub-allocations are bump-pointer
```

```c
kt_Arena arena;
kt_arena_init(&arena);
char* buf = (char*)kt_arena_alloc(&arena, 1024);
// ... use buf ...
kt_arena_reset(&arena);  // free everything at once
```

---

## What Is NOT Supported

| Feature                           | Reason                                              |
|-----------------------------------|-----------------------------------------------------|
| Lambdas / closures                | Requires capture semantics and heap allocation      |
| `suspend` / coroutines            | Requires a runtime scheduler                        |
| Generics                          | Complexity; built-in arrays cover the main use case |
| Interfaces                        | Requires vtable dispatch; may be added later        |
| Inheritance / `open` / `abstract` | Requires vtable; classes are final value types      |
| Extension functions               | Syntactic sugar that complicates method resolution  |
| Companion objects                 | Use top-level functions or `object` instead         |
| `try` / `catch` / `finally`       | No exception model in C; use return codes           |
| Operator overloading              | Complexity                                          |
| Destructuring declarations        | Complexity                                          |
| Sealed classes                    | Requires discriminated unions; may be added later   |
| Type aliases                      | Not needed for MVP                                  |
| Annotations                       | Not applicable to C output                          |
| `when` with `is` smart casts      | No runtime type info                                |
| Nested / inner classes            | Complexity                                          |
| Property getters / setters        | Complexity                                          |
| `init` with complex logic         | Basic `init` blocks are supported                   |

---

## Semantic Differences from JVM Kotlin

| Behavior                          | JVM Kotlin                       | KotlinToC                         |
|-----------------------------------|----------------------------------|-----------------------------------|
| Class identity                    | Reference type (heap)            | Value type (stack)                |
| `val obj = Foo(); val copy = obj` | Same object (aliased)            | Independent copy                  |
| `===` (referential equality)      | Compares references              | Not supported; use `==`           |
| String interning                  | JVM pools literals               | Static `const char*`, no pooling  |
| Integer overflow                  | Wraps silently                   | Wraps silently (same)             |
| `null` for objects                | Null reference                   | `Nullable` struct with `has` flag |
| Array bounds                      | `ArrayIndexOutOfBoundsException` | Undefined behavior (C)            |
| Stack overflow                    | `StackOverflowError`             | Segfault or undefined             |

---

## Full Example

```kotlin
data class Vec2(val x: Float, val y: Float)

fun dot(a: Vec2, b: Vec2): Float = a.x * b.x + a.y * b.y

fun main() {
    val a = Vec2(1.0f, 2.0f)
    val b = Vec2(3.0f, 4.0f)
    val d = dot(a, b)
    println("dot = $d")

    val nums = intArrayOf(10, 20, 30)
    var sum = 0
    for (n in nums) {
        sum += n
    }
    println("sum = $sum")
}
```

Transpiles to:

```c
#include "ktc_runtime.h"

typedef struct { float x; float y; } Vec2;

Vec2 Vec2_create(float x, float y) {
    return (Vec2){x, y};
}

bool Vec2_equals(Vec2 a, Vec2 b) {
    return a.x == b.x && a.y == b.y;
}

kt_String Vec2_toString(Vec2 self, char* buf, int bufsz) {
    int n = snprintf(buf, bufsz, "Vec2(x=%f, y=%f)", self.x, self.y);
    return (kt_String){buf, n};
}

float dot(Vec2 a, Vec2 b) {
    return a.x * b.x + a.y * b.y;
}

int main(void) {
    Vec2 a = Vec2_create(1.0f, 2.0f);
    Vec2 b = Vec2_create(3.0f, 4.0f);
    float d = dot(a, b);
    printf("dot = %f\n", d);

    int32_t _nums_data[] = {10, 20, 30};
    kt_IntArray nums = {_nums_data, 3};
    int32_t sum = 0;
    for (int32_t _i = 0; _i < nums.len; _i++) {
        int32_t n = nums.ptr[_i];
        sum += n;
    }
    printf("sum = %" PRId32 "\n", sum);
    return 0;
}
```

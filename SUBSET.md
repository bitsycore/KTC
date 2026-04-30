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

### Extension Functions

Extension functions map to free functions with the receiver as the first parameter.
Like Go methods on types — you can add behaviour to any type without modifying it.

- **Class receiver** → pointer parameter (`ClassName* self`), can mutate via `self->`
- **Primitive receiver** → value parameter (`int32_t self`), `this` maps to `self`

```kotlin
// kotlin
data class Vec2(val x: Float, val y: Float)

fun Vec2.lengthSquared(): Float = x * x + y * y    // accesses fields directly

fun Int.isEven(): Boolean = this % 2 == 0           // this = value

class Player(val name: String) {
    var health: Int = 100
}

fun Player.heal(amount: Int) {                       // mutates receiver
    health += amount
}
```

```c
// c — extension functions become TypeName_method(self, ...)
float game_Vec2_lengthSquared(game_Vec2* self) {
    return (self->x * self->x) + (self->y * self->y);
}

bool game_Int_isEven(int32_t self) {
    return (self % 2) == 0;
}

void game_Player_heal(game_Player* self, int32_t amount) {
    self->health += amount;
}

// call sites:
game_Vec2_lengthSquared(&v);   // v.lengthSquared()
game_Int_isEven(n);            // n.isEven()
game_Player_heal(&player, 10); // player.heal(10)
```

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

`String` maps to `kt_String`, a pointer + length pair (like Go/Solod slices).
String literals are zero-cost (`const char*` to static storage). Dynamic strings
use `kt_StrBuf` (a stack-backed string builder) or arena allocation.

```kotlin
// kotlin
val name: String = "Alice"
val greeting = "Hello, $name!"
```

```c
// c — simple template → direct printf for println, kt_StrBuf for value
kt_String name = kt_str("Alice");

// as value (kt_StrBuf, no GCC extensions):
char _t0[256];
kt_StrBuf _t0_sb = {_t0, 0, 256};
kt_sb_append_cstr(&_t0_sb, "Hello, ");
kt_sb_append_str(&_t0_sb, name);
kt_sb_append_char(&_t0_sb, '!');
kt_String greeting = kt_sb_to_string(&_t0_sb);

// for println → optimized to direct printf:
printf("Hello, %.*s!\n", (int)name.len, name.ptr);
```

#### kt_StrBuf — String Builder

`kt_StrBuf` is a stack-backed (or arena-backed) string builder. It replaces the
old fixed-buffer `snprintf` approach for `toString` and string templates.

```c
// Stack-backed (most common):
char buf[256];
kt_StrBuf sb = {buf, 0, 256};
kt_sb_append_cstr(&sb, "Hello ");
kt_sb_append_int(&sb, 42);
kt_String result = kt_sb_to_string(&sb);

// Arena-backed (for longer-lived strings):
kt_StrBuf sb = kt_sb_arena(&arena, 1024);
kt_sb_append_str(&sb, name);
kt_String result = kt_sb_to_string(&sb);
```

Key properties:
- **No GCC extensions** — all string building is pure C11 statements
- **Nested toString** — data class fields call their own `toString(sb)`, appending to the same buffer
- **Zero heap allocation** — everything stays on the stack or in an explicit arena

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

**Body properties** (fields declared in the class body with initializers) are included
in the struct and initialized in the constructor alongside constructor parameters.

```kotlin
// kotlin — constructor params + body properties
class Player(val name: String) {
    var health: Int = 100
    var score: Int = 0

    fun takeDamage(amount: Int) {
        health -= amount
    }

    fun isAlive(): Boolean = health > 0
}
```

```c
// c
typedef struct {
    kt_String name;
    int32_t health;
    int32_t score;
} Player;

Player Player_create(kt_String name) {
    Player self = {0};
    self.name = name;
    self.health = 100;
    self.score = 0;
    return self;
}

void Player_takeDamage(Player* self, int32_t amount) {
    self->health -= amount;
}

bool Player_isAlive(Player* self) {
    return self->health > 0;
}
```

**Nested class fields** — classes can have other classes as fields. The struct
simply contains the nested struct by value (stack, no pointers):

```kotlin
// kotlin
data class Vec2(val x: Float, val y: Float)
data class Rect(val origin: Vec2, val size: Vec2)
```

```c
// c
typedef struct { float x; float y; } Vec2;
typedef struct { Vec2 origin; Vec2 size; } Rect;

// equals calls nested equals
bool Rect_equals(Rect a, Rect b) {
    return Vec2_equals(a.origin, b.origin) && Vec2_equals(a.size, b.size);
}

// toString calls nested toString (same StrBuf — no extra buffers)
void Rect_toString(Rect self, kt_StrBuf* sb) {
    kt_sb_append_cstr(sb, "Rect(origin=");
    Vec2_toString(self.origin, sb);
    kt_sb_append_cstr(sb, ", size=");
    Vec2_toString(self.size, sb);
    kt_sb_append_char(sb, ')');
}
```

### Data Classes

Like classes, but the transpiler auto-generates `equals` and `toString`.
`toString` uses `kt_StrBuf` (a stack-backed string builder) — no fixed buffers,
supports nesting, and is compatible with arena allocation.

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

void Point_toString(Point self, kt_StrBuf* sb) {
    kt_sb_append_cstr(sb, "Point(x=");
    kt_sb_append_int(sb, self.x);
    kt_sb_append_cstr(sb, ", y=");
    kt_sb_append_int(sb, self.y);
    kt_sb_append_char(sb, ')');
}

int main(void) {
    Point p = Point_create(3, 4);
    char _buf[256];
    kt_StrBuf _sb = {_buf, 0, 256};
    Point_toString(p, &_sb);
    printf("%.*s\n", (int)_sb.len, _sb.ptr);
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
data class Rect(val origin: Vec2, val size: Vec2)

fun Vec2.lengthSquared(): Float = x * x + y * y

class Player(val name: String) {
    var health: Int = 100
    fun takeDamage(amount: Int) { health -= amount }
}

fun Player.heal(amount: Int) { health += amount }

fun main() {
    val r = Rect(Vec2(0.0f, 0.0f), Vec2(10.0f, 5.0f))
    println(r)
    println("Rect: $r")

    val v = Vec2(3.0f, 4.0f)
    println(v.lengthSquared())

    val p = Player("Alice")
    p.takeDamage(30)
    p.heal(10)
    println(p.health)
}
```

Transpiles to:

```c
#include "ktc_runtime.h"

typedef struct { float x; float y; } Vec2;
typedef struct { Vec2 origin; Vec2 size; } Rect;
typedef struct { kt_String name; int32_t health; } Player;

Vec2 Vec2_create(float x, float y) { return (Vec2){x, y}; }

bool Vec2_equals(Vec2 a, Vec2 b) {
    return a.x == b.x && a.y == b.y;
}

void Vec2_toString(Vec2 self, kt_StrBuf* sb) {
    kt_sb_append_cstr(sb, "Vec2(x=");
    kt_sb_append_double(sb, (double)self.x);
    kt_sb_append_cstr(sb, ", y=");
    kt_sb_append_double(sb, (double)self.y);
    kt_sb_append_char(sb, ')');
}

bool Rect_equals(Rect a, Rect b) {
    return Vec2_equals(a.origin, b.origin) && Vec2_equals(a.size, b.size);
}

void Rect_toString(Rect self, kt_StrBuf* sb) {
    kt_sb_append_cstr(sb, "Rect(origin=");
    Vec2_toString(self.origin, sb);     /* nested — same buffer! */
    kt_sb_append_cstr(sb, ", size=");
    Vec2_toString(self.size, sb);
    kt_sb_append_char(sb, ')');
}

Player Player_create(kt_String name) {
    Player self = {0};
    self.name = name;
    self.health = 100;
    return self;
}

float Vec2_lengthSquared(Vec2* self) {
    return (self->x * self->x) + (self->y * self->y);
}

void Player_heal(Player* self, int32_t amount) {
    self->health += amount;
}

int main(void) {
    Rect r = Rect_create(Vec2_create(0.0f, 0.0f), Vec2_create(10.0f, 5.0f));

    /* println(r) → StrBuf toString */
    char _t0[256];
    kt_StrBuf _t0_sb = {_t0, 0, 256};
    Rect_toString(r, &_t0_sb);
    printf("%.*s\n", (int)_t0_sb.len, _t0_sb.ptr);

    /* println("Rect: $r") → complex template via StrBuf */
    char _t1[256];
    kt_StrBuf _t1_sb = {_t1, 0, 256};
    kt_sb_append_cstr(&_t1_sb, "Rect: ");
    Rect_toString(r, &_t1_sb);
    printf("%.*s\n", (int)_t1_sb.len, _t1_sb.ptr);

    Vec2 v = Vec2_create(3.0f, 4.0f);
    printf("%f\n", Vec2_lengthSquared(&v));

    Player p = Player_create(kt_str("Alice"));
    Player_takeDamage(&p, 30);
    Player_heal(&p, 10);
    printf("%" PRId32 "\n", p.health);
    return 0;
}
```

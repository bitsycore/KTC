# ktc — Kotlin-to-C Language Reference

A strict subset of Kotlin that transpiles to readable C11. Stack-first, no GC, no hidden allocations.

**Kotlin/JVM compatibility is intentionally broken** — the C target is primary.

## Packages & Imports

```kotlin
package game
import ktc.*        // implicit runtime types
import math.*       // cross-package (wildcard only)
```

- Package dots become underscores in C: `package game` → symbol prefix `game_`
- Short single-word packages recommended
- Wildcard imports only (`.*`); no selective imports
- Multiple files can share the same package — they get merged into one `.c` / `.h` pair
- Cross-package references emit `#include "math.h"` automatically

## Types

### Primitives

| Kotlin    | C type      | Notes                                  |
|-----------|-------------|----------------------------------------|
| `Int`     | `int32_t`   |                                        |
| `Long`    | `int64_t`   | `123L` literal                         |
| `Float`   | `float`     | `1.0f` literal                         |
| `Double`  | `double`    | `1.0` literal                          |
| `Boolean` | `bool`      | `true` / `false`                       |
| `Char`    | `char`      | `'x'` literal                          |
| `String`  | `kt_String` | ptr + len pair, zero-cost for literals |
| `Unit`    | `void`      | Implicit return type                   |

### String

Strings are `kt_String { const char* ptr; int32_t len; }`. Literals point into static storage (zero-cost). Concatenation requires a buffer.

```kotlin
val s = "hello"
println(s.length)       // → s.len
val n = "42".toInt()    // → kt_str_toInt(...)
val d = "3.14".toDouble()
val l = "100".toLong()
```

String comparisons use `kt_string_cmp`:
```kotlin
val a = "apple"
val b = "banana"
println(a < b)   // true
println(a == b)   // false
```

### Numeric Conversions

```kotlin
val x = 65
val f = x.toFloat()    // → (float)x
val l = x.toLong()     // → (int64_t)x
val d = x.toDouble()   // → (double)x
```

## Variables

```kotlin
val x: Int = 10        // → const int32_t x = 10;
var y = 20             // → int32_t y = 20;  (type inferred)
```

- `val` → `const` for primitives and strings
- `val` on class types omits `const` so `&var` works for method calls
- `var` → mutable

## Functions

```kotlin
fun add(a: Int, b: Int): Int {
    return a + b
}

// Expression body
fun double(x: Int): Int = x * 2

// Default parameters
fun greet(name: String, greeting: String = "Hello") { ... }
```

- Package prefix applied: `fun add(...)` in `package game` → `game_add(...)`
- `fun main()` always emits `int main(void)` — no prefix

### main with args

```kotlin
fun main(args: Array<String>) {
    println(args.size)
    for (arg in args) { println(arg) }
}
```

Emits `int main(int argc, char** argv)` with argv→`kt_String*` conversion (skips argv[0]).

### Extension Functions

```kotlin
fun Vec2.lengthSquared(): Float = x * x + y * y
fun Int.isEven(): Boolean = this % 2 == 0
fun Player.heal(amount: Int) { health += amount }
```

- Class receiver → pointer: `float game_Vec2_lengthSquared(game_Vec2* $self)`
- Primitive receiver → value: `bool kt_Int_isEven(int32_t $self)`

### Function Pointers

```kotlin
fun addTwo(a: Int, b: Int): Int = a + b

val f: (Int, Int) -> Int = ::addTwo
println(f(3, 4))  // 7

fun applyOp(x: Int, y: Int, op: (Int, Int) -> Int): Int = op(x, y)
println(applyOp(5, 6, ::mulTwo))
```

- `::functionName` → C function name (with package prefix)
- `(T, T) -> R` → `R (*name)(T, T)` in C
- Can be stored in variables, passed as arguments, reassigned

## Classes

### Data Classes

```kotlin
data class Vec2(val x: Float, val y: Float)
data class Rect(val origin: Vec2, val size: Vec2)
```

- Emits C struct: `typedef struct { float x; float y; } game_Vec2;`
- Auto-generates `equals` (`==`) and `toString` (via `kt_StrBuf`)
- Nested structs stored by value

### Regular Classes

```kotlin
class Player(val name: String) {
    var health: Int = 100
    var score: Int = 0

    fun takeDamage(amount: Int) { health -= amount }
    fun isAlive(): Boolean = health > 0
}
```

- Constructor params + body properties → struct fields
- Methods → `void game_Player_takeDamage(game_Player* $self, int32_t amount)`
- `init` blocks supported — code runs at construction time

### Class with only ctor params

```kotlin
class Counter(var count: Int) {
    fun increment() { count++ }
    fun get(): Int = count
}
```

### Enum Classes

```kotlin
enum class Color { RED, GREEN, BLUE }
```

- Emits `typedef enum { game_Color_RED, ... } game_Color;`
- Use in `when`: `Color.RED -> ...`

### Objects (Singletons)

```kotlin
object Config {
    val maxRetries: Int = 3
    var debug: Boolean = false
}
```

- Emits static global variables: `static const int32_t game_Config_maxRetries = 3;`
- Access: `Config.debug = true`

## Control Flow

### if / else

```kotlin
if (x > 0) { ... } else { ... }

// Expression if (simple → ternary)
val max = if (a > b) a else b

// Expression if (multi-statement → temp var)
val result = if (a > 5) {
    val doubled = a * 2
    doubled + 1
} else {
    a - 1
}
```

### when

```kotlin
// Statement when (no subject)
when {
    x < 0 -> println("negative")
    x == 0 -> println("zero")
    else -> println("positive")
}

// Statement when (with subject)
when (color) {
    Color.RED -> println("Red!")
    Color.GREEN -> println("Green!")
    Color.BLUE -> println("Blue!")
}

// Expression when → nested ternary or temp var
val letter = when {
    grade >= 90 -> "A"
    grade >= 80 -> "B"
    else -> "F"
}
```

### for loops

```kotlin
for (i in 0..10) { ... }           // inclusive range: 0,1,...,10
for (i in 0 until 10) { ... }      // exclusive end: 0,1,...,9
for (i in 10 downTo 0) { ... }     // descending: 10,9,...,0
for (i in 0..10 step 2) { ... }    // with step: 0,2,4,6,8,10
for (i in 0 until 10 step 3) { ... }
for (i in 10 downTo 0 step 2) { ... }

// Iterate arrays
for (pt in points) { ... }

// Iterate ArrayList
for (n in nums) { ... }
```

### while / do-while

```kotlin
while (cond) { ... }
do { ... } while (cond)
```

### break / continue

```kotlin
for (i in 0..10) {
    if (i == 5) break
    if (i % 2 == 0) continue
    println(i)
}
```

## String Templates

```kotlin
println("Hello, $name!")
println("sum = ${a + b}")
println("Rect: $rect")          // calls toString on data classes
val info = "v=$v len2=${v.lengthSquared()}"
```

- Emits `kt_StrBuf` with append calls via `preStmts` pattern
- No GCC statement expressions

## Arrays

### Fixed Arrays

```kotlin
val arr = intArrayOf(10, 20, 30)     // → int32_t arr[] = {10, 20, 30};
val pts = arrayOf(Vec2(1.0f, 2.0f), Vec2(3.0f, 4.0f))

arr[0]          // direct indexing
arr.size        // → arr$len
```

- Inlined as separate vars: `T* arr` + `int32_t arr$len`
- Function params expand: `fun foo(arr: IntArray)` → `foo(int32_t* arr, int32_t arr$len)`

### ArrayList (Dynamic Arrays)

```kotlin
val nums = IntArrayList()
nums.add(10)
nums.add(20)
println(nums.size)      // .len
println(nums[0])        // _get
nums[1] = 99            // _set
nums.removeAt(0)
nums.clear()
nums.free()             // must free manually

// mutableListOf sugar
val names = mutableListOf("hello", "world")
names.add("!")
for (s in names) { println(s) }
names.free()
```

**Supported types:** `IntArrayList`, `LongArrayList`, `FloatArrayList`, `DoubleArrayList`, `BooleanArrayList`, `StringArrayList`, `<ClassName>ArrayList` (e.g. `Vec2ArrayList`)

**Methods:** `add`, `get` (via `[]`), `set` (via `[]=`), `removeAt`, `clear`, `free`, `size`

Codegen-driven: struct + methods emitted per element type on first use. Heap-backed (`malloc` / `realloc`).

### HashMap (Hash Tables)

```kotlin
// Int → Int map
var scores = IntIntHashMap()       // default capacity 16
scores.put(1, 100)
scores.put(2, 200)
println(scores.get(1))             // 100
println(scores.size)               // 2
println(scores.containsKey(2))     // true

// Index operators
scores[1] = 999                    // → _put
println(scores[1])                 // → _get (999)

// Remove
scores.remove(2)

// String keys
var wordCount = StringIntHashMap()
wordCount.put("hello", 1)
wordCount["hello"] = 42
println(wordCount["hello"])        // 42

// Cleanup
scores.free()
wordCount.free()
```

**Naming convention:** `<KeyType><ValueType>HashMap` — e.g. `IntIntHashMap`, `StringIntHashMap`, `IntFloatHashMap`

**Supported key types:** `Int`, `Long`, `Float`, `Double`, `Boolean`, `Char`, `String`
**Supported value types:** Any primitive or class type known to the transpiler.

**Methods:** `put`, `get` (also via `[]`), `containsKey`, `remove`, `clear`, `free`, `size`

**Constructor:** `IntIntHashMap()` (default cap 16) or `IntIntHashMap(cap)` with explicit initial capacity.

Codegen-driven: open-addressing hash table with linear probing, emitted per key:value type pair on first use. Grows at 50% load factor. String keys use FNV-1a hash. Heap-backed (`malloc` / `realloc`).

## Nullables

### Inline Representation

```kotlin
var x: Int? = 42      // → int32_t x = 42; bool x$has = true;
x = null              // → x$has = false;
```

- No wrapper structs — `T name` + `bool name$has` side by side
- Function params expand similarly: `fun foo(x: Int?)` → `foo(int32_t x, bool x$has)`

### Operators

```kotlin
x!!                   // assert non-null → just emit x (no runtime check)
x ?: 99               // elvis → x$has ? x : 99
```

### Null Checks

```kotlin
if (x != null) { ... }  // → if (x$has) { ... }
if (x == null) { ... }  // → if (!x$has) { ... }
```

### Nullable Returns

```kotlin
fun findValue(flag: Boolean): Int? {
    if (flag) return 42
    return null
}
```

Emits: `int32_t game_findValue(bool flag, bool* $has_out)` — caller passes `&x$has`.

### Smart Casts

```kotlin
var x: Int? = 42
if (x != null) {
    println(x)    // no !! needed — smart cast to non-null
}

if (x == null) {
    println("null")
} else {
    println(x)    // smart cast in else branch
}
```

## Memory Management

### Raw malloc/free

```kotlin
val buf = malloc(1024)          // → malloc(1024)
val buf2 = realloc(buf, 2048)  // → realloc(buf, 2048)
free(buf2)                      // → free(buf2)
```

### Typed Pointers

```kotlin
val ints = malloc<Int>(5)       // → (int32_t*)malloc(sizeof(int32_t) * 5)
ints[2] = 42                   // → ints[2] = 42;
println(ints[2])
free(ints)
```

### Heap Classes — `Heap<T>`

Three variants:

| Syntax     | Meaning                          | C representation       |
|------------|----------------------------------|------------------------|
| `Heap<T>`  | Always allocated, non-null       | `T* p`                 |
| `Heap<T?>` | Always allocated, value-nullable | `T* p` + `bool p$has`  |
| `Heap<T>?` | Pointer-nullable                 | `T* p` (NULL = absent) |

```kotlin
// Allocate on heap
val p = malloc<Vec2>(10.0, 20.0)   // → Vec2_new(10.0, 20.0)
println(p.x)                        // → p->x
p.x = 99.0                          // → p->x = 99.0

// Copy to stack
val v = p.value()                   // → *p

// Update entire value
p.set(Vec2(1.0, 2.0))              // → *p = (game_Vec2){1.0, 2.0}

// Data class copy (returns stack value)
val v2 = p.copy()                   // copy all fields
val v3 = p.copy(x = 77.0)          // copy with override

// Stack to heap
val sv = Vec2(5.0, 6.0)
val hp = sv.toHeap()                // → Vec2_toHeap(sv)

free(p)
free(hp)
```

Every class auto-generates `_new(args)` and `_toHeap(value)` functions.

## Defer

```kotlin
fun example() {
    val p = malloc<Vec2>(1.0, 2.0)
    defer free(p)                    // runs at function exit

    defer println("first registered, last to run")
    defer println("second registered, second-to-last to run")

    // Block form
    defer {
        println("cleanup A")
        println("cleanup B")
    }

    // Return values evaluated before deferred blocks run
    return p.x.toInt()
}
```

- Function-scoped, LIFO order (like Go)
- Return value is hoisted to a temp variable, then deferred blocks execute, then the temp is returned
- Deferred blocks emitted before every `return` in the function and at end of function

## println / print

```kotlin
println("hello")           // strings
println(42)                // int
println(3.14)              // double
println(true)              // bool
println(myDataClass)       // calls generated toString
println(heapPtr)           // dereferences, then toString
print(x)                   // no newline
```

## Multi-file Compilation

```
ktc math.kt game_vec3.kt game_main.kt -o out/
```

- Files grouped by package
- Same-package files merged into one `.c` / `.h`
- Cross-package references resolved via `symbolPrefix` map
- Each package gets its own output pair: `math.c` + `math.h`, `game.c` + `game.h`

## What's NOT Supported

- Lambdas / closures
- Generics (except `Heap<T>`, `Array<T>`, `ArrayList<T>`, `HashMap<K,V>`)
- Interfaces / inheritance / abstract classes
- Suspend / coroutines
- Try / catch / exceptions
- Destructuring declarations
- Companion objects
- Property getters/setters
- Annotations
- Visibility modifiers (everything is public)
- Operator overloading
- Type aliases
- Nested/inner classes
- Sealed classes
- Varargs

## C Output Conventions

- All generated identifiers use `$` prefix: `$self`, `$0`, `arr$len`, `x$has`
- Package prefix on all symbols: `game_Vec2`, `math_add`
- `fun main()` → `int main(void)` (no prefix)
- `val` on primitives/strings → `const`
- `val` on class types → no `const` (so `&` works)
- Data class `toString` uses `kt_StrBuf` (stack-backed buffer)
- String templates use `preStmts` pattern to avoid GCC statement expressions
- Expression `if`/`when` with blocks → hoisted temp variable

## Runtime Header

`ktc_runtime.h` is included in every generated file and provides:

- `kt_String` — string type (ptr + len)
- `kt_str(s)` — macro for string literals (zero-cost)
- `kt_string_eq` / `kt_string_cmp` — string comparison
- `kt_string_cat` — string concatenation into caller buffer
- `kt_StrBuf` — stack-backed string builder (`append_str`, `append_int`, `append_double`, etc.)
- `kt_Arena` — simple bump allocator (64 KiB default)
- Conversion: `kt_int_to_string`, `kt_long_to_string`, `kt_double_to_string`, `kt_bool_to_string`
- Parsing: `kt_str_toInt`, `kt_str_toLong`, `kt_str_toDouble`

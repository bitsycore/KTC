package All

// ── Data classes ─────────────────────────────────────────────────────
data class Vec2(val x: Float, val y: Float)
data class Rect(val origin: Vec2, val size: Vec2)

//// ── Class with body properties ───────────────────────────────────────
class Player(val name: String) {
    var health: Int = 100
    var score: Int = 0

    fun takeDamage(amount: Int) {
        health -= amount
    }

    fun isAlive(): Boolean = health > 0
}
//
//// ── Class with only ctor params ──────────────────────────────────────
class Counter(var count: Int) {
    fun increment() {
        count++
    }

    fun get(): Int = count
}

// ── Extension functions ──────────────────────────────────────────────
fun Vec2.lengthSquared(): Float = x * x + y * y
fun Int.isEven(): Boolean = this % 2 == 0
fun Player.heal(amount: Int) {
    health += amount
}

// ── Enum, object ─────────────────────────────────────────────────────
enum class Color { RED, GREEN, BLUE }

object Config {
    val maxRetries: Int = 3
    var debug: Boolean = false
}

// ── Helper functions ─────────────────────────────────────────────────
fun add(a: Int, b: Int): Int {
    return a + b
}

fun greet(name: String, greeting: String = "Hello") {
    println("$greeting, $name!")
}

fun fibonacci(n: Int): Int {
    if (n <= 1) return n
    var a = 0
    var b = 1
    for (i in 2..n) {
        val temp = a + b
        a = b
        b = temp
    }
    return b
}

fun describe(x: Int): String {
    return when {
        x < 0 -> "negative"
        x == 0 -> "zero"
        x < 10 -> "small"
        else -> "large"
    }
}

// ═══════════════════════ Test functions ═══════════════════════════════

fun testArgs(args: Array<String>) {
    println(args.size)
    for (arg in args) {
        println(arg)
    }
}

fun testDataClasses() {
    val origin = Vec2(0.0f, 0.0f)
    val size = Vec2(10.0f, 5.0f)
    val rect = Rect(origin, size)
    println(rect)
    val p = Vec2(1.0f, 2.0f)
    println(p)
}

fun testClassMethods() {
    val player = Player("Alice")
    println(player.health)
    player.takeDamage(30)
    println(player.health)
    println(player.isAlive())
    player.heal(10)
    println(player.health)

    val counter = Counter(0)
    counter.increment()
    counter.increment()
    println(counter.get())
}

fun testExtensions() {
    val v = Vec2(3.0f, 4.0f)
    println(v.lengthSquared())
    val n = 42
    println(n.isEven())
}

fun testEnumAndObject() {
    val color = Color.RED
    when (color) {
        Color.RED -> println("Red!")
        Color.GREEN -> println("Green!")
        Color.BLUE -> println("Blue!")
    }
    Config.debug = true
    println(Config.maxRetries)
}

fun testFunctions() {
    val result = add(10, 20)
    println(result)
    greet("World")
    greet("Kotlin", "Hi")
    println(fibonacci(10))
    println(describe(42))
}

fun testStringTemplates() {
    val v = Vec2(3.0f, 4.0f)
    val rect = Rect(Vec2(0.0f, 0.0f), Vec2(10.0f, 5.0f))
    println("Rect: $rect")
    val info = "v=$v len2=${v.lengthSquared()}"
    println(info)
}

fun testArrays() {
    val points = arrayOf(Vec2(1.0f, 2.0f), Vec2(3.0f, 4.0f), Vec2(5.0f, 6.0f))
    for (pt in points) {
        println(pt)
    }
    println(points.size)

    val names = arrayOf("Alice", "Bob", "Charlie")
    for (name in names) {
        println(name)
    }

    val arr = intArrayOf(10, 20, 30)
    var sum = 0
    for (i in 0 until arr.size) {
        sum += arr[i]
    }
    println("sum = $sum")

    for (item in arr) {
        print(item)
    }
    println()
}

fun testArrayList() {
    val nums = mutableListOf<Int>()
    nums.add(10)
    nums.add(20)
    nums.add(30)
    println(nums.size)
    println(nums[0])
    nums[1] = 99
    for (n in nums) {
        println(n)
    }
    nums.removeAt(0)
    println(nums.size)
    nums.dispose()

    val names = mutableListOf("hello", "world")
    println(names.size)
    names.add("!")
    for (s in names) {
        println(s)
    }
    names.dispose()
}

fun testMalloc() {
    val buf = HeapAlloc<Array<Int>>(8)
    if (buf == null) return
    for (i in 0 until 8) {
        buf[i] = i * 10
    }
    println(buf[0])
    println(buf[3])
    println(buf[7])
    HeapFree(buf)
}

fun testTypedPointer() {
    val ints = HeapAlloc<Array<Int>>(5)
    if (ints == null) return
    for (i in 0 until 5) {
        ints[i] = i * 10
    }
    println(ints[2])
    HeapFree(ints)
}

fun testNullable() {
    var x: Int? = 42
    println(x!!)
    x = null
    val y = x ?: 99
    println(y)

    var name: String? = "hello"
    println(name!!)
    name = null
    val fallback = name ?: "default"
    println(fallback)

    // null comparison
    var z: Int? = 10
    if (z != null) {
        println(z!!)
    }
    z = null
    if (z == null) {
        println("z is null")
    }
}

fun findValue(flag: Boolean): Int? {
    if (flag) {
        return 42
    }
    return null
}

fun showNullable(value: Int?) {
    if (value != null) {
        println(value!!)
    } else {
        println("none")
    }
}

fun testNullableReturn() {
    val a: Int? = findValue(true)
    println(a!!)
    val b: Int? = findValue(false)
    val c = b ?: -1
    println(c)
    if (b == null) {
        println("b is null")
    }
    // pass nullable to nullable param
    showNullable(a)
    showNullable(b)
    // pass literal null
    showNullable(null)
    // pass non-null literal to nullable param
    showNullable(99)
}

fun testStringOps() {
    val s = "hello"
    println(s.length)
    val num = "42"
    val n = num.toInt()
    println(n + 8)
    val pi = "3.14"
    val d = pi.toDouble()
    println(d)
    // numeric conversions
    val x = 65
    val f = x.toFloat()
    val l = x.toLong()
    println(f)
    println(l)
}

fun testExprIfWhen() {
    // simple expression if → ternary
    val a = 10
    val b = 20
    val max = if (a > b) a else b
    println(max)

    // multi-statement expression if → temp var
    val result = if (a > 5) {
        val doubled = a * 2
        doubled + 1
    } else {
        a - 1
    }
    println(result)

    // simple expression when → nested ternary
    val grade = 85
    val letter = when {
        grade >= 90 -> "A"
        grade >= 80 -> "B"
        grade >= 70 -> "C"
        else -> "F"
    }
    println(letter)

    // when with subject
    val color = 2
    val name = when (color) {
        1 -> "red"
        2 -> "green"
        3 -> "blue"
        else -> "unknown"
    }
    println(name)

    // multi-statement when branch → temp var
    val score = 95
    val msg = when {
        score >= 90 -> {
            val bonus = score - 90
            bonus + 100
        }
        else -> {
            0
        }
    }
    println(msg)
}

fun testSmartCast() {
    // Smart cast: x != null → use x directly without !!
    var x: Int? = 42
    if (x != null) {
        println(x)  // should work without !! — smart cast to Int
    }

    // Smart cast: x == null → else branch has non-null x
    var y: Int? = 99
    if (y == null) {
        println("y is null")
    } else {
        println(y)  // smart cast in else branch
    }

    // Null path
    var z: Int? = null
    if (z != null) {
        println(z)
    } else {
        println("z is null")
    }

    // Smart cast with string
    var s: String? = "hello"
    if (s != null) {
        println(s)
    }
}

fun testForStep() {
    // for with step on range
    var sum = 0
    for (i in 0..10 step 2) {
        sum = sum + i
    }
    println(sum) // 0+2+4+6+8+10 = 30

    // for with step on until
    var sum2 = 0
    for (i in 0 until 10 step 3) {
        sum2 = sum2 + i
    }
    println(sum2) // 0+3+6+9 = 18

    // for with step on downTo
    var sum3 = 0
    for (i in 10 downTo 0 step 2) {
        sum3 = sum3 + i
    }
    println(sum3) // 10+8+6+4+2+0 = 30
}

fun testStringCompare() {
    val a = "apple"
    val b = "banana"
    println(a < b)    // true
    println(a > b)    // false
    println(a <= b)   // true
    println(a >= b)   // false
    println(a == b)   // false
    println(a != b)   // true

    // same string
    val c = "apple"
    println(a == c)   // true
    println(a >= c)   // true
    println(a <= c)   // true
}

fun testHeap() {
    // Heap<T> — always allocated, non-null
    val p = HeapAlloc<Vec2>(10.0, 20.0)
    if (p == null) return
    println(p.x)       // -> access
    println(p.y)
    p.x = 99.0         // -> assignment
    println(p.x)
    println(p)          // println heap pointer (data class)

    // .value() — copy to stack
    val v = p.value()
    println(v)

    // .set() — update heap object
    p.set(Vec2(1.0, 2.0))
    println(p.x)
    println(p.y)

    // .copy() on data class (returns stack copy)
    val v2 = p.copy()
    println(v2)

    // .copy(field = val) on data class
    val v3 = p.copy(x = 77.0)
    println(v3)

    // .toHeap() — stack to heap
    val sv = Vec2(5.0, 6.0)
    val hp = sv.toHeap()
    println(hp.x)
    println(hp.y)
    HeapFree(hp)
    HeapFree(p)
}

fun testHeapNullable() {
    // Heap<T>? — pointer nullable
    var q : Heap<Vec2>? = HeapAlloc<Vec2>(3.0, 4.0)

    val b = q
    defer HeapFree(b)

    if (q == null) {
        return
    }
    q = null
    if (q == null) {
        println("q is null")
    }

    // Heap<T?> — value nullable, pointer always allocated
    var r: Heap<Vec2?> = HeapAlloc<Vec2>(7.0, 8.0)
    if (r == null) {
        println(r.x)
    }
    r = null
    if (r == null) {
        println("r value is null")
    }
    r?.set(Vec2(11.0, 12.0))
    if (r != null) {
        println(r.x)
    }
    HeapFree(r)
}

fun testSafeDotExpr() {
    // ── safe dot property access assigned to nullable-inferred variable ──
    var r: Heap<Vec2?> = HeapAlloc<Vec2>(5.0, 6.0)

    // r is non-null: val fx = r?.x  → inferred as Float?, should be 5.0
    val fx = r?.x
    if (fx != null) {
        println(fx)
    }

    // r is non-null: safe dot with elvis → non-nullable
    val fy = r?.y ?: -1.0
    println(fy)

    // ── safe method call assigned to nullable-inferred variable ──
    val flen = r?.lengthSquared()
    if (flen != null) {
        println(flen)
    }

    // safe method call with elvis
    val flen2 = r?.lengthSquared() ?: -1.0
    println(flen2)

    // ── now set r to null and repeat: all should take the null/elvis path ──
    r = null

    val nx = r?.x
    if (nx == null) {
        println("nx is null")
    }

    val ny = r?.y ?: -1.0
    println(ny)

    val nlen = r?.lengthSquared()
    if (nlen == null) {
        println("nlen is null")
    }

    val nlen2 = r?.lengthSquared() ?: -1.0
    println(nlen2)

    HeapFree(r)
}

fun addTwo(a: Int, b: Int): Int {
    return a + b
}

fun mulTwo(a: Int, b: Int): Int {
    return a * b
}

fun applyOp(x: Int, y: Int, op: (Int, Int) -> Int): Int {
    return op(x, y)
}

fun testFunPtr() {
    // Direct function pointer variable
    val f: (Int, Int) -> Int = ::addTwo
    println(f(3, 4))

    // Pass function reference as argument
    println(applyOp(5, 6, ::addTwo))
    println(applyOp(5, 6, ::mulTwo))

    // Reassign function pointer
    var g: (Int, Int) -> Int = ::addTwo
    println(g(10, 20))
    g = ::mulTwo
    println(g(10, 20))
}

fun deferredReturn(): Int {
    val p = HeapAlloc<Vec2>(1.0, 2.0)
    if (p == null) return -1
    defer HeapFree(p)
    p.x = 42.0
    return p.x.toInt()
}

fun testDefer() {
    // Basic: LIFO order
    defer println("defer 1")
    defer println("defer 2")
    println("body")

    // Block defer
    defer {
        println("defer block A")
        println("defer block B")
    }
    println("after defers registered")
}

fun testDeferReturn() {
    // defer runs before return, return value evaluated first
    val v = deferredReturn()
    println(v)
}

// ── Interfaces ───────────────────────────────────────────────────────
interface Shape {
    fun area(): Float
    fun describe(): String
}

class Circle(val radius: Float) : Shape {
    override fun area(): Float = 3.14159f * radius * radius
    override fun describe(): String = "Circle"
}

class Square(val side: Float) : Shape {
    override fun area(): Float = side * side
    override fun describe(): String = "Square"
}

fun printShape(s: Shape) {
    println(s.describe())
    println(s.area())
}

fun testInterface() {
    val c = Circle(5.0f)
    val s = Square(3.0f)

    // Call via interface
    val shape1: Shape = Circle(10.0f)
    println(shape1.area())
    println(shape1.describe())

    // Pass class → interface param (auto-wrap)
    printShape(c)
    printShape(s)
}

fun testCInterop() {
    // ── c.printf with format string (raw C string passthrough) ──
    c.printf("c.printf: %d + %d = %d\n", 10, 20, 30)

    // ── c.sprintf into a stack buffer ──
    val buf = Array<Char>(64)
    c.sprintf(buf, "formatted: %d", 42)
    c.printf("sprintf: %s\n", buf)

    // ── c.strlen on a raw C string literal ──
    c.printf("strlen: %d\n", c.strlen("hello"))

    // ── c.memset + c.memcpy ──
    val src = intArrayOf(10, 20, 30, 40)
    val dst = IntArray(4)
    c.memset(dst, 0, 4 * 4)
    c.memcpy(dst, src, 4 * 4)
    println(dst[0])
    println(dst[2])

    // ── c.abs ──
    val neg = -99
    println(c.abs(neg))

    // ── c.atoi on a raw C string literal ──
    val parsed = c.atoi("256")
    println(parsed)

    // ── c.NULL constant ──
    val p: Heap<Vec2>? = c.NULL
    if (p == null) {
        println("p is null")
    }

    // ── c.snprintf for safe formatted output ──
    val buf2 = Array<Char>(32)
    c.snprintf(buf2, 32, "val=%d", 777)
    c.printf("snprintf: %s\n", buf2)
}

fun testCRandom() {
    // Generate two random numbers
    val a = Random.nextInt()
    println("nextInt: $a")

    val b = Random.nextInt()
    println("nextInt: $b")

    val c = Random.nextInt(100)
    println("nextRange(100): $c")

    // Range-limited random: all in [0, 100)
    var allInRange = true
    for (i in 0 until 20) {
        val r = Random.nextInt(100)
        if (r < 0 || r >= 100) {
            allInRange = false
        }
    }
    println("All in range: $allInRange")

    val a2 = Random.nextInt()
    println("nextInt: $a2")
}

fun testArrayResult(recursion: Int = 10): @Size(5) IntArray {
    if (recursion > 0) {
        return testArrayResult(recursion - 1)
    } else {
        return arrayOf(recursion, 2, 3, 4, 5)
    }
}

fun testArrayUsage() {
    val array = testArrayResult()
    for (n in array) {
        println(n)
    }
}

fun vecByValue(vec: Vec2) {
    vec.x = 99.9f
}

fun testPassByValue() {
    val aVec = Vec2(5.0f, 2.0f)
    println(aVec)
    vecByValue(aVec)
    println(aVec)
}

fun main(args: Array<String>) {
    println("--- testArgs ---")
    testArgs(args)
    println("--- testDataClasses ---")
    testDataClasses()
    println("--- testClassMethods ---")
    testClassMethods()
    println("--- testExtensions ---")
    testExtensions()
    println("--- testEnumAndObject ---")
    testEnumAndObject()
    println("--- testFunctions ---")
    testFunctions()
    println("--- testStringTemplates ---")
    testStringTemplates()
    println("--- testArrays ---")
    testArrays()
    println("--- testArrayList ---")
    testArrayList()
    println("--- testMalloc ---")
    testMalloc()
    println("--- testTypedPointer ---")
    testTypedPointer()
    println("--- testNullable ---")
    testNullable()
    println("--- testNullableReturn ---")
    testNullableReturn()
    println("--- testStringOps ---")
    testStringOps()
    println("--- testExprIfWhen ---")
    testExprIfWhen()
    println("--- testSmartCast ---")
    testSmartCast()
    println("--- testForStep ---")
    testForStep()
    println("--- testStringCompare ---")
    testStringCompare()
    println("--- testHeap ---")
    testHeap()
    println("--- testHeapNullable ---")
    testHeapNullable()
    println("--- testSafeDotExpr ---")
    testSafeDotExpr()
    println("--- testFunPtr ---")
    testFunPtr()
    println("--- testDefer ---")
    testDefer()
    println("--- testDeferReturn ---")
    testDeferReturn()
    println("--- testInterface ---")
    testInterface()
    println("--- testCInterop ---")
    testCInterop()
    println("--- testCRandom ---")
    testCRandom()
    println("--- testArrayResult ---")
    testArrayUsage()
    println("--- testPassByValue ---")
    testPassByValue()
}

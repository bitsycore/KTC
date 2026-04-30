package game

import ktc.*

// ── Data classes ─────────────────────────────────────────────────────
data class Vec2(val x: Float, val y: Float)
data class Rect(val origin: Vec2, val size: Vec2)

// ── Class with body properties ───────────────────────────────────────
class Player(val name: String) {
    var health: Int = 100
    var score: Int = 0

    fun takeDamage(amount: Int) {
        health -= amount
    }

    fun isAlive(): Boolean = health > 0
}

// ── Class with only ctor params ──────────────────────────────────────
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
    val nums = IntArrayList()
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
    nums.free()

    val names = mutableListOf("hello", "world")
    println(names.size)
    names.add("!")
    for (s in names) {
        println(s)
    }
    names.free()
}

fun testMalloc() {
    val buf = malloc(1024)
    println(buf)
    val buf2 = realloc(buf, 2048)
    free(buf2)
}

fun testTypedPointer() {
    val ints = malloc<Int>(5)
    for (i in 0 until 5) {
        ints[i] = i * 10
    }
    println(ints[2])
    free(ints)
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
}

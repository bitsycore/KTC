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
}

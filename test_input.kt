package game

// ── Nested data classes ──────────────────────────────────────────────
data class Vec2(val x: Float, val y: Float)
data class Rect(val origin: Vec2, val size: Vec2)

// ── Class with body properties (directly initialized fields) ─────────
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

// ── Extension function on data class ─────────────────────────────────
fun Vec2.lengthSquared(): Float = x * x + y * y

// ── Extension function on primitive type ─────────────────────────────
fun Int.isEven(): Boolean = this % 2 == 0

// ── Extension function on class (mutates receiver) ───────────────────
fun Player.heal(amount: Int) {
    health += amount
}

// ── Enum, object, functions (original tests) ─────────────────────────
enum class Color { RED, GREEN, BLUE }

object Config {
    val maxRetries: Int = 3
    var debug: Boolean = false
}

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

fun main(args: Array<String>) {
    // ── Command-line arguments ──
    println(args.size)
    for (arg in args) {
        println(arg)
    }

    // ── Array<T> with class type ──
    val points = arrayOf(Vec2(1.0f, 2.0f), Vec2(3.0f, 4.0f), Vec2(5.0f, 6.0f))
    for (pt in points) {
        println(pt)
    }
    println(points.size)

    // ── Array<String> via arrayOf ──
    val names = arrayOf("Alice", "Bob", "Charlie")
    for (name in names) {
        println(name)
    }

    // ── Nested data classes ──
    val origin = Vec2(0.0f, 0.0f)
    val size = Vec2(10.0f, 5.0f)
    val rect = Rect(origin, size)
    println(rect)

    // ── Class with body properties ──
    val player = Player("Alice")
    println(player.health)
    player.takeDamage(30)
    println(player.health)
    println(player.isAlive())

    // ── Extension functions ──
    val v = Vec2(3.0f, 4.0f)
    println(v.lengthSquared())

    val n = 42
    println(n.isEven())

    player.heal(10)
    println(player.health)

    // ── String template with data class (complex template → StrBuf) ──
    println("Rect: $rect")
    println("Player health: ${player.health}")

    // ── String template as value ──
    val info = "v=$v len2=${v.lengthSquared()}"
    println(info)

    // ── Original tests ──
    val p = Vec2(1.0f, 2.0f)
    println(p)

    val counter = Counter(0)
    counter.increment()
    counter.increment()
    println(counter.get())

    val result = add(10, 20)
    println(result)

    greet("World")
    greet("Kotlin", "Hi")

    println(fibonacci(10))

    val desc = describe(42)
    println(desc)

    val color = Color.RED
    when (color) {
        Color.RED -> println("Red!")
        Color.GREEN -> println("Green!")
        Color.BLUE -> println("Blue!")
    }

    val nums = intArrayOf(10, 20, 30)
    var sum = 0
    for (i in 0 until nums.size) {
        sum += nums[i]
    }
    println("sum = $sum")

    for (item in nums) {
        print(item)
    }
    println()

    Config.debug = true
    println(Config.maxRetries)
}

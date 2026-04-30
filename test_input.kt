package game

data class Vec2(val x: Float, val y: Float)

class Counter(var count: Int) {
    fun increment() {
        count++
    }

    fun get(): Int = count
}

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

fun main() {
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

    for (v in nums) {
        print(v)
    }
    println()

    Config.debug = true
    println(Config.maxRetries)
}

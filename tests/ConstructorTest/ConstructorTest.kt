package ConstructorTest

data class Vec2(val x: Float, val y: Float) {
    // secondary: construct from single value (sets both x and y)
    constructor(s: Float) : this(s, s)
    // secondary: construct from ints
    constructor(x: Int, y: Int) : this(x.toFloat(), y.toFloat())

    constructor(x: Int, y: Int, z: Int) : this(x.toFloat() * y.toFloat(), y.toFloat() * z.toFloat())

    constructor() : this(0.0f, 0.0f) {
        println("Empty constructor")
    }
}

class Player(val name: String, var score: Int) {
    // secondary: just a name, default score
    constructor(name: String) : this(name, 0)
}

fun main() {
    val a = Vec2(1.0f, 2.0f)
    println("Vec2 primary: ${a.x}, ${a.y}")

    val b = Vec2(3.0f)
    println("Vec2 single: ${b.x}, ${b.y}")

    val c = Vec2(4, 5)
    println("Vec2 ints: ${c.x}, ${c.y}")

    val d = Vec2(4, 5, 6)
    println("Vec2 triple ints: ${d.x}, ${d.y}")

    val e = Vec2()
    println("Vec2 empty: ${e.x}, ${e.y}")

    val p1 = Player("Alice", 100)
    println("Player: ${p1.name}, ${p1.score}")

    val p2 = Player("Bob")
    println("Player default: ${p2.name}, ${p2.score}")

    println("done")
}

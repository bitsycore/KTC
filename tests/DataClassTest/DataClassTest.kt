package DataClassTest

data class Vec2(val x: Float, val y: Float)
data class Rect(val origin: Vec2, val size: Vec2)

fun main() {
    val v = Vec2(1.0f, 2.0f)
    println("Vec2(${v.x}, ${v.y})")
    println(v)

    val a = Vec2(1.0f, 2.0f)
    val b = Vec2(1.0f, 2.0f)
    val c = Vec2(3.0f, 4.0f)
    println("a == b = ${a == b}")
    println("a == c = ${a == c}")

    val r = Rect(Vec2(0.0f, 0.0f), Vec2(10.0f, 5.0f))
    println(r)

    var v2 = Vec2(1.0f, 2.0f)
    v2 = v2.copy(x = 5.0f)
    println("copy: $v2")

    println("done")
}

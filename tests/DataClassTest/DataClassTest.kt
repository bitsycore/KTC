package DataClassTest

data class Vec2(val x: Float, val y: Float)
data class Rect(val origin: Vec2, val size: Vec2)

fun main() {
    val v = Vec2(1.0f, 2.0f)
    println("Vec2(${v.x}, ${v.y})")
    if (v.x != 1.0f || v.y != 2.0f) error("FAIL field access")
    println(v)

    val a = Vec2(1.0f, 2.0f)
    val b = Vec2(1.0f, 2.0f)
    val c = Vec2(3.0f, 4.0f)
    if (a != b) error("FAIL a==b")
    if (a == c) error("FAIL a!=c")
    println("a == b = true")
    println("a == c = false")

    val r = Rect(Vec2(0.0f, 0.0f), Vec2(10.0f, 5.0f))
    if (r.origin.x != 0.0f || r.size.y != 5.0f) error("FAIL nested fields")
    println(r)

    var v2 = Vec2(1.0f, 2.0f)
    v2 = v2.copy(x = 5.0f)
    if (v2.x != 5.0f || v2.y != 2.0f) error("FAIL copy")
    println("copy: $v2")

    println("ALL OK")
}

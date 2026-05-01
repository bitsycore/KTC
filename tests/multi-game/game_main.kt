package game

import math.*

class Test {
    var a: Int = 5
}

fun main() {
    val a = malloc<Test>()
    if (a == null) return
    println(a)
    val pts = Point(1, 2)
    println(pts)
    
    val b = Vec3(4.0f, 5.0f, 6.0f)
    println(b)
    println(dot(b, b))
}

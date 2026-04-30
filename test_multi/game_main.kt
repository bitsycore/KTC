package game

import ktc.*
import math.*

class Test {
    var a: Int = 5
}

fun main() {
    val a = malloc<Test>(10)
    println(a)
    val pts = Point(1f, 2f)
    println(pts)
    
    val b = Vec3(4.0f, 5.0f, 6.0f)
    println(a)
    println(b)
    //println(dot(a, b))
}

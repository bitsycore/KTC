package game

import math.*

class Test {
    var a: Int = 5
}

fun main() {
    val a = HeapAlloc<Test>()
    if (a == null) return
    defer HeapFree(a)
    println(a)
    val pts = Point(1, 2)
    println(pts)
    
    val b = Vec3(4.0f, 5.0f, 6.0f)
    println(b)
    println(dot(b, b))
}
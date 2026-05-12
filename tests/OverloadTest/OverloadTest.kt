package OverloadTest

object Calc {
    fun add(x: Int, y: Int): Int = x + y
    fun add(x: Double, y: Double): Double = x + y
    fun add(x: Int): Int = x

    fun greet(): String = "hello"
    fun greet(name: String): String = "hello $name"
}

class Counter() {
    var n: Int = 0

    fun inc() { n = n + 1 }
    fun inc(by: Int) { n = n + by }
    fun inc(by: Int, times: Int) {
        for (i in 0 until times) { n = n + by }
    }
    fun count(): Int = n
}

fun main() {
    println("start")
    val a = Calc.add(2, 3)
    println("a=$a")
    val b = Calc.add(2.5, 3.5)
    println("b=$b")
    val single = Calc.add(42)
    val g1 = Calc.greet()
    val g2 = Calc.greet("World")

    if (a != 5) { c.exit(1) }
    if (b != 6.0) { c.exit(1) }
    if (single != 42) { c.exit(1) }
    if (g1 != "hello") { c.exit(1) }
    if (g2 != "hello World") { c.exit(1) }
    println("Object overloads OK")

    val ctr = Counter()
    ctr.inc()
    if (ctr.count() != 1) { c.exit(1) }
    ctr.inc(5)
    if (ctr.count() != 6) { c.exit(1) }
    ctr.inc(2, 3)
    if (ctr.count() != 12) { c.exit(1) }
    println("Class overloads OK")

    println("ALL OK")
}

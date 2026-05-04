package StringTemplateTest

data class Vec2(val x: Float, val y: Float)

fun main() {
    val name = "World"
    val age = 42

    println("Hello, $name!")
    println("Age: $age")

    // Expression in template
    println("Next year: ${age + 1}")

    // Data class toString in template
    val v = Vec2(3.0f, 4.0f)
    println("Position: $v")

    // Method call in template
    val s = "hello"
    println("Length of '$s' is ${s.length}")

    // Multiple values
    val a = 10
    val b = 20
    println("$a + $b = ${a + b}")

    println("done")
}

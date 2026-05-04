package ExtensionFunctionTest

class Box(var value: Int)

fun Box.double(): Int { return value * 2 }
fun Box.reset() { value = 0 }

fun main() {
    val b = Box(21)
    println("Box double: ${b.double()}")
    b.reset()
    println("after reset: ${b.double()}")

    println("done")
}

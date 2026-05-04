package ObjectTest

object Config {
    private var count: Int = 0

    fun next(): Int {
        count++
        return count
    }
}

object Greeter {
    fun greet(name: String) {
        println("Hello, $name!")
    }
}

fun main() {
    println("first: ${Config.next()}")
    println("second: ${Config.next()}")

    Greeter.greet("World")
    Greeter.greet("Kotlin")

    println("done")
}

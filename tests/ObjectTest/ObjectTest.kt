package ObjectTest

object Config {
    private var count: Int = 0

    fun next(): Int {
        count++
        return count
    }
}

object Config2 {
    private val buffer: @Ptr Array<Int> = HeapAlloc<Array<Int>>(128)

    override fun dispose() {
        println("AutoFreeing Config2")
        HeapFree(buffer)
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

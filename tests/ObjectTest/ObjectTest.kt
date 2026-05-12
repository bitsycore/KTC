package ObjectTest

object Config {
    private var count: Int = 0

    fun next(): Int {
        count++
        return count
    }
}

object Config2 {
    val buffer: @Ptr Array<Int> = HeapAlloc<Array<Int>>(128)

    override fun dispose() {
        println("AutoFreeing Config2")
        HeapFree(buffer)
    }

}
object Greeter {
    var greeterEnabled = true
    fun greet(name: String) {
        if (greeterEnabled)
            println("Hello, $name!")
    }
}

class TestCompanion {

    fun test() {
        println("Run test from instance")
    }

    companion object {
        fun testMe() {
            println("Run testMe from companion object")
        }
    }
}

fun main() {
    println("Value: ${Config2.buffer.size}")
    println("first: ${Config.next()}")
    println("second: ${Config.next()}")

    Greeter.greeterEnabled = false
    Greeter.greet("World")
    Greeter.greeterEnabled = true
    Greeter.greet("Kotlin")

    val test = TestCompanion()
    TestCompanion.testMe()
    test.test()

    println("done")
}

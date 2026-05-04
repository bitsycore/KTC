package GenericsTest

// Generic function
fun <T> firstOf(list: List<T>): T { return list[0] }

// Generic class
class Wrapper<T>(private var value: T) {
    fun get(): T { return value }
    fun set(v: T) { value = v }
}

// Pair + to infix
fun main() {
    // Generic function
    val list = mutableListOf(10, 20, 30)
    defer list.dispose()
    println("firstOf = ${firstOf(list)}")

    // Generic class
    val w = Wrapper<String>("hello")
    println("Wrapper: ${w.get()}")
    w.set("world")
    println("Wrapper after set: ${w.get()}")

    // Pair
    val p = 1 to "one"
    println("Pair: ${p.first} -> ${p.second}")

    println("done")
}

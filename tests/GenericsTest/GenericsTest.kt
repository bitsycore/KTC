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
    if (firstOf(list) != 10) error("FAIL firstOf")

    // Generic class
    val w = Wrapper<String>("hello")
    println("Wrapper: ${w.get()}")
    if (w.get() != "hello") error("FAIL Wrapper initial")
    w.set("world")
    println("Wrapper after set: ${w.get()}")
    if (w.get() != "world") error("FAIL Wrapper set")

    // Pair
    val p = 1 to "one"
    println("Pair: ${p.first} -> ${p.second}")
    if (p.first != 1 || p.second != "one") error("FAIL Pair")

    // Triple
    val t = Triple(10, "hello", true)
    println("Triple: ${t.first}, ${t.second}, ${t.third}")
    if (t.first != 10 || t.second != "hello" || t.third != true) error("FAIL Triple")

    // Triple with type args
    val t2 = Triple<Boolean, Int, String>(false, 42, "world")
    println("Triple2: ${t2.first}, ${t2.second}, ${t2.third}")
    if (t2.first != false || t2.second != 42 || t2.third != "world") error("FAIL Triple2")

    println("done")
}

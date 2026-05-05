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

    // Triple
    val t = Triple(10, "hello", true)
    println("Triple: ${t.first}, ${t.second}, ${t.third}")

    // Triple with type args
    val t2 = Triple<Boolean, Int, String>(false, 42, "world")
    println("Triple2: ${t2.first}, ${t2.second}, ${t2.third}")

    // Tuple
    val tu = Tuple(1, "two", 3.0f)
    println("Tuple: ${tu.component0}, ${tu.component1}, ${tu.component2}")

    // Tuple with type args
    val tu2 = Tuple<Int, Boolean>(99, true)
    println("Tuple2: ${tu2.component0}, ${tu2.component1}")

    // Tuple with generic type args
    val tu2 = Tuple(Wrapper("Hello"), Wrapper(true))
    println("Tuple2: ${tu2.component0}, ${tu2.component1}")

    println("done")
}

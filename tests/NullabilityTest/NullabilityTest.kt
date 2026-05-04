package NullabilityTest

fun maybeInt(n: Int): Int? {
    if (n > 0) {
        return n
    }
    return null
}

fun main() {
    // Basic null checks
    val x: Int? = null
    println("null == null = ${x == null}")
    val y: Int? = 42
    println("42 != null = ${y != null}")

    // Elvis operator
    val v = x ?: 99
    println("elvis: $v")

    // Nullable return
    val a = maybeInt(10)
    if (a != null) {
        println("maybeInt(10) = $a")
    }
    val b = maybeInt(0)
    if (b == null) {
        println("maybeInt(0) = null")
    }

    // Nullable string
    val s: String? = "hello"
    if (s != null) {
        println("length = ${s.length}")
    }

    println("done")
}

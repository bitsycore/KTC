package SmartCastTest

fun main() {
    // Null smart-cast on Int?
    val x: Int? = 42
    if (x != null) {
        println("doubled: ${x * 2}")
    }
    if (x == null) {
        println("is null")
    } else {
        println("value = $x")
    }

    // Null smart-cast on String?
    val s: String? = "hello"
    if (s != null) {
        println("length: ${s.length}")
    }

    // is type check on arrays
    val arr = intArrayOf(1, 2, 3)
    // TODO: 'is Array<Int>' not supported on dynamic arrays
    println("has items: ${arr.size > 0}")

    println("done")
}

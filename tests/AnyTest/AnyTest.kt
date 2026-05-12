package AnyTest

fun checkAny(item: @Ptr Any) {
    if (item is Int) {
        println("int: ${item as Int}")
    } else if (item is String) {
        println("str: ${item as String}")
    } else if (item is Boolean) {
        println("bool: ${item as Boolean}")
    } else {
        println("unknown")
    }
}

fun checkAny2(item: Any) {
    if (item is Int) {
        println("int: $item")
    } else if (item is String) {
        println("str: $item")
    } else if (item is Boolean) {
        println("bool: $item")
    } else {
        println("unknown")
    }
}

fun main() {
    checkAny(42)
    checkAny("hello")
    checkAny(true)

    checkAny2(42)
    checkAny2("hello")
    checkAny2(true)

    // Value-type Any checks
    val v: Any = 42
    if (v is Int) { println("v is Int") } else { error("FAIL v is Int") }
    if (v !is String) { println("v !is String") } else { error("FAIL v !is String") }

    println("ALL OK")
}

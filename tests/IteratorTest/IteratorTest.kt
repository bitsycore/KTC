package IteratorTest

fun main() {
    // Test ListIterator via mutableListOf
    val list = mutableListOf(1, 2, 3, 4, 5)
    defer list.dispose()

    println("--- List iterator ---")
    for (item in list) {
        println(item)
    }

    // Test ListIterator via listOf
    val fixedList = listOf(10, 20, 30)
    defer fixedList.dispose()

    println("--- Fixed list iterator ---")
    for (item in fixedList) {
        println(item)
    }

    // Test MapIterator via HashMap
    val map = HashMap<String, Int>(8)
    map["Alice"] = 30
    map["Bob"] = 25
    map["Charlie"] = 35

    println("--- Map iterator ---")
    for (entry in map) {
        println("${entry.first} = ${entry.second}")
    }
    map.dispose()

    // Test MapIterator via mutableMapOf
    val m2 = mutableMapOf(1 to "one", 2 to "two")
    defer m2.dispose()

    println("--- mutableMapOf iterator ---")
    for (entry in m2) {
        println("${entry.first} -> ${entry.second}")
    }

    // Test MapIterator via mapOf (read-only)
    val m3 = mapOf(100 to "hundred", 200 to "two hundred")
    defer m3.dispose()

    println("--- mapOf iterator ---")
    for (entry in m3) {
        println("${entry.first} -> ${entry.second}")
    }

    println("done")
}

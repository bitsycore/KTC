package HashMapTest

fun main() {
    // Setup
    val map = HashMap<Int, String>(16)

    // Test put via operator set
    map[1] = "hello"
    map[2] = "world"
    map[3] = "foo"

    println("get(1) = ${map[1]}")
    println("get(2) = ${map[2]}")

    // Test containsKey via `in` operator
    println("1 in map = ${1 in map}")
    println("99 in map = ${99 in map}")
    println("99 !in map = ${99 !in map}")

    // Test size
    println("size = ${map.size}")

    // Test overwrite
    map[1] = "replaced"
    println("get(1) after overwrite = ${map[1]}")
    println("size after overwrite = ${map.size}")

    // Test remove
    val removed = map.remove(2)
    println("remove(2) = $removed")
    println("2 in map after remove = ${2 in map}")
    println("size after remove = ${map.size}")

    // Test get on missing key
    val missing = map[999]
    if (missing == null) {
        println("get(999) = null")
    }

    // Test clear
    map.clear()
    println("size after clear = ${map.size}")
    println("isEmpty after clear = ${map.isEmpty()}")

    // Test many puts (triggers growth)
    for (i in 0 until 100) {
        map[i] = "val"
    }
    println("size after 100 puts = ${map.size}")
    println("50 in map = ${50 in map}")
    println("100 in map = ${100 in map}")

    // Test for-in iteration over map
    val iterMap = HashMap<Int, String>(8)
    iterMap[10] = "ten"
    iterMap[20] = "twenty"
    iterMap[30] = "thirty"
    println("iterating map:")
    for (entry in iterMap) {
        println("  ${entry.first} -> ${entry.second}")
    }
    iterMap.dispose()

    // Test mapOf
    val m1 = mapOf("A" to "Alpha", "B" to "Bravo")
    defer m1.dispose()
    println("mapOf keys:")
    for (entry in m1) {
        println("  ${entry.first} -> ${entry.second}")
    }

    // Test mutableMapOf
    val m2 = mutableMapOf("X" to "Xray", "Y" to "Yankee")
    defer m2.dispose()
    m2["Z"] = "Zulu"
    println("mutableMapOf after add:")
    for (entry in m2) {
        println("  ${entry.first} -> ${entry.second}")
    }

    map.dispose()
    println("done")
}

package com.bitsycore

inline fun repeat(times: Int, action: (Int) -> Unit) {
    var i = 0
    while (i < times) {
        action(i)
        i++
    }
}

inline fun transform(value: Int, op: (Int) -> Int): Int {
    return op(value)
}

inline fun applyTwo(a: Int, b: Int, combine: (Int, Int) -> Int): Int {
    return combine(a, b)
}

fun main() {

    val a = arrayOf(1, 2, 3)
    a.let { arr ->
        println("let:")
        for (i in 0..<arr.size) {
            print("$i, ")
        }
        println()
    }

    var a2 = a
    a2[0] = 5
    a2[1] = 6
    a2[2] = 7
    a2.let { arr ->
        println("let:")
        for (i in 0..<arr.size) {
            print("$i, ")
        }
        println()
    }

    val b : Array<Int>? = a
    b.let { arr ->
        println("let:")
        for (i in 0..<arr.size) {
            print("$i, ")
        }
        println()
    }

    val c : @Ptr Array<Int>? = a
    c.let { arr ->
        println("let:")
        for (i in 0..<arr.size) {
            print("$i, ")
        }
        println()
    }

    val d : Array<Int>? = null
    d?.let { arr ->
        println("let:")
        for (i in 0..<arr.size) {
            print("$i, ")
        }
        println()
    }

    // Basic lambda with index
    repeat(3) { i ->
        println(i)
    }

    // Lambda returning a value
    val doubled = transform(21) { x -> x * 2 }
    println(doubled)

    // Lambda with two parameters
    val sum = applyTwo(10, 32) { a, b -> a + b }
    println(sum)
}

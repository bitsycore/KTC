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

inline fun <T, R> T.let(block: (T) -> R): R {
    return block(this)
}

fun main() {

    val a = arrayOf(1, 2, 3)
    a.let { arr ->
        println("let:")
        for (i in 0..<a.size) {
            print("$i, ")
        }
        println()
    }

    // SHOULD BE PASSED AS COPY AS ITS A VALUE
    // AND NO NULL CHECK SEEM TO BE DONE TO KNOW IF ENTER IN THE SCOPE
    val b : Array<Int>? = a
    b.let { arr ->
        println("let:")
        for (i in 0..<a.size) {
            print("$i, ")
        }
        println()
    }

    // LEN IS BROKEN
    // AND NO NULL CHECK SEEM TO BE DONE TO KNOW IF ENTER IN THE SCOPE
    val c : Array<Int>? = null
    c?.let { arr ->
        println("let:")
        for (i in 0..<a.size) {
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

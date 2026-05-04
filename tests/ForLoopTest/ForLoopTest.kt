package ForLoopTest

fun testRangeInclusive() {
    println("--- 0..5 ---")
    for (i in 0..5) {
        println(i)
    }
}

fun testRangeUntil() {
    println("--- 0 until 5 ---")
    for (i in 0 until 5) {
        println(i)
    }
}

fun testDownTo() {
    println("--- 5 downTo 0 ---")
    for (i in 5 downTo 0) {
        println(i)
    }
}

fun testStep() {
    println("--- 0..10 step 2 ---")
    for (i in 0..10 step 2) {
        println(i)
    }
    println("--- 10 downTo 0 step 3 ---")
    for (i in 10 downTo 0 step 3) {
        println(i)
    }
}

fun testForInArray() {
    val arr = intArrayOf(100, 200, 300)
    for (x in arr) {
        println(x)
    }
}

fun main() {
    testRangeInclusive()
    testRangeUntil()
    testDownTo()
    testStep()
    testForInArray()
    println("done")
}

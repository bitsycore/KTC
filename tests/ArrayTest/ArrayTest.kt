package ArrayTest

fun main() {
    val arr = intArrayOf(10, 20, 30, 40, 50)
    println("size = ${arr.size}")
    for (i in 0 until arr.size) {
        println(arr[i])
    }
    arr[1] = 99
    println("after set: ${arr[1]}")

    val names = arrayOf("Alice", "Bob", "Charlie")
    for (name in names) {
        println(name)
    }

    println("done")
}

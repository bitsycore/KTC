package ArrayTest

fun main() {
    // intArrayOf
    val arr = intArrayOf(10, 20, 30, 40, 50)
    println("size = ${arr.size}")
    if (arr.size != 5) error("size should be 5")
    for (i in 0 until arr.size) {
        println(arr[i])
    }
    arr[1] = 99
    println("after set: ${arr[1]}")
    if (arr[1] != 99) error("arr[1] should be 99")

    // arrayOf<String>
    val names = arrayOf("Alice", "Bob", "Charlie")
    if (names.size != 3) error("size should be 3")
    for (name in names) {
        println("\"$name\" is ${name.length} characters long")
    }

    val farr = floatArrayOf(1.5f, 2.5f, 3.5f)
    val farr2 = Array<Float>(3)
    val farr3 = arrayOf(2f, 4f, 6f)

    val darr = doubleArrayOf(1.5, 2.5, 3.5)
    val darr2 = arrayOf(3.4, 2.2, 3.5)
    val darr3 = Array<Double>(3)
    if (farr.size != 3 || farr2.size != 3 || farr3.size != 3
        || darr.size != 3 || darr2.size != 3 || darr3.size != 3)
        error("size should be 3")

    val larr = longArrayOf(100L, 200L, 300L)
    val larr2 = arrayOf<Long>(100L, 200L, 300L)
    val larr3 = Array<Long>(3)
    if (larr.size != 3 || larr2.size != 3 || larr3.size != 3)
        error("size should be 3")

    val sarr = arrayOf<Short>(10, 20, 30)
    val sarr2 = Array<Short>(3)
    if (sarr.size != 3 || sarr2.size != 3)
        error("size should be 3")

    val barr = arrayOf<Byte>(10, 20, 30)
    val barr2 = Array<Byte>(3)
    if (barr.size != 3 || barr2.size != 3)
        error("size should be 3")

    println("done")
}

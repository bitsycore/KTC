package ArrayAliasTest

fun testByteArray() {
    val lit = byteArrayOf(10, 20, 30)
    println("byteArrayOf: ${lit[1]}")
    if (lit[1] != 20.toByte()) c.exit(1)
}

fun testShortArray() {
    val lit = shortArrayOf(10, 20, 30)
    println("shortArrayOf: ${lit[1]}")
    if (lit[1] != 20.toShort()) c.exit(1)
}

fun testIntArray() {
    val lit = intArrayOf(10, 20, 30)
    println("intArrayOf: ${lit[1]}")
    if (lit[1] != 20) c.exit(1)
}

fun testLongArray() {
    val lit = longArrayOf(10L, 20L, 30L)
    println("longArrayOf: ${lit[1]}")
    if (lit[1] != 20L) c.exit(1)
}

fun testFloatArray() {
    val lit = floatArrayOf(1.5f, 2.5f, 3.5f)
    println("floatArrayOf: ${lit[1]}")
    if (lit[1] != 2.5f) c.exit(1)
}

fun testDoubleArray() {
    val lit = doubleArrayOf(1.1, 2.2, 3.3)
    println("doubleArrayOf: ${lit[1]}")
    if (lit[1] != 2.2) c.exit(1)
}

fun testBooleanArray() {
    val lit = booleanArrayOf(true, false, true)
    println("booleanArrayOf: ${lit[1]}")
    if (lit[1]) c.exit(1)
}

fun testCharArray() {
    val lit = charArrayOf('a', 'b', 'c')
    println("charArrayOf: ${lit[1]}")
    if (lit[1] != 'b') c.exit(1)
}

fun testUByteArray() {
    val lit = ubyteArrayOf(10u, 20u, 30u)
    println("ubyteArrayOf: ${lit[1]}")
    if (lit[1] != 20.toUByte()) c.exit(1)
}

fun testUShortArray() {
    val lit = ushortArrayOf(10u, 20u, 30u)
    println("ushortArrayOf: ${lit[1]}")
    if (lit[1] != 20.toUShort()) c.exit(1)
}

fun testUIntArray() {
    val lit = uintArrayOf(10u, 20u, 30u)
    println("uintArrayOf: ${lit[1]}")
    if (lit[1] != 20u) c.exit(1)
}

fun testULongArray() {
    val lit = ulongArrayOf(10UL, 20UL, 30UL)
    println("ulongArrayOf: ${lit[1]}")
    if (lit[1] != 20UL) c.exit(1)
}

fun testStringArray() {
    val lit = arrayOf("hello", "world")
    println("arrayOf<String>: ${lit[0]}")
}

fun main() {
    testByteArray()
    testShortArray()
    testIntArray()
    testLongArray()
    testFloatArray()
    testDoubleArray()
    testBooleanArray()
    testCharArray()
    testUByteArray()
    testUShortArray()
    testUIntArray()
    testULongArray()
    testStringArray()
    println("ALL OK")
}

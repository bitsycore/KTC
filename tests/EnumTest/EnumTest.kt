package EnumTest

enum class Color { RED, GREEN, BLUE }

fun testEnumValues() {
    val values = enumValues<Color>()
    println(values.size)
    if (values.size != 3) error("FAIL enumValues size")
    for (i in 0 until values.size) {
        println(values[i])
    }
}

fun testEnumValueOf() {
    val c = enumValueOf<Color>("GREEN")
    println(c)
    if (c != Color.GREEN) error("FAIL enumValueOf")
}

fun testColorDotValues() {
    val values = Color.values()
    println(values.size)
    if (values.size != 3) error("FAIL Color.values")
    for (i in 0 until values.size) {
        println(values[i])
    }
}

fun testColorDotValueOf() {
    val c = Color.valueOf("BLUE")
    println(c)
    if (c != Color.BLUE) error("FAIL Color.valueOf")
}

fun testEnumName() {
    val c = Color.RED
    println(c.name)
    println(c.ordinal)
    if (c.ordinal != 0) error("FAIL ordinal")
}

fun testEnumWhen() {
    val values = enumValues<Color>()
    var value = 0
    repeat(values.size) {
        when (values[value]) {
            Color.RED -> println("Fire ! the color is RED")
            Color.GREEN -> println("Leafy ! the color is GREEN")
            Color.BLUE -> println("Sad ! the color is BLUE")
        }
        value++
    }
}

fun main(args: Array<String>) {
    testEnumValues()
    testEnumValueOf()
    testColorDotValues()
    testColorDotValueOf()
    testEnumName()
    testEnumWhen()
    println("ALL OK")
}

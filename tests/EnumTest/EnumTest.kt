package EnumTest

enum class Color { RED, GREEN, BLUE }

fun testEnumValues() {
    val values = enumValues<Color>()
    println(values.size)
    for (i in 0 until values.size) {
        println(values[i])
    }
}

fun testEnumValueOf() {
    val c = enumValueOf<Color>("GREEN")
    println(c)
}

fun testColorDotValues() {
    val values = Color.values()
    println(values.size)
    for (i in 0 until values.size) {
        println(values[i])
    }
}

fun testColorDotValueOf() {
    val c = Color.valueOf("BLUE")
    println(c)
}

fun testEnumName() {
    val c = Color.RED
    println(c.name)
    println(c.ordinal)
}

fun testEnumWhen() {
    val values = enumValues<Color>()
    var value = 0
    repeat(values.size) {
        when (values[value]) {
            Color.RED -> println("Fire ! the color is ${Color.RED}")
            Color.GREEN -> println("Leafy ! the color is ${Color.GREEN}")
            Color.BLUE -> println("Sad ! the color is ${Color.BLUE}")
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
}

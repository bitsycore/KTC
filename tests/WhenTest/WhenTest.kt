package WhenTest

enum class Color { RED, GREEN, BLUE }

fun testWhenExpr() {
    val x = 3
    val desc = when (x) {
        1 -> "one"
        2 -> "two"
        3 -> "three"
        else -> "many"
    }
    println(desc)
}

fun testWhenStatement() {
    val x = 2
    when (x) {
        1 -> println("is one")
        2 -> println("is two")
        else -> println("is other")
    }
}

fun testWhenCondition() {
    val x = 15
    when {
        x < 0 -> println("negative")
        x == 0 -> println("zero")
        x < 10 -> println("small")
        else -> println("large")
    }
}

fun testWhenEnum() {
    val color = Color.GREEN
    when (color) {
        Color.RED -> println("Red!")
        Color.GREEN -> println("Green!")
        Color.BLUE -> println("Blue!")
    }
}

fun main() {
    testWhenExpr()
    testWhenStatement()
    testWhenCondition()
    testWhenEnum()
    println("done")
}

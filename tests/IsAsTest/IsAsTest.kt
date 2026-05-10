package IsAsTest

interface Drawable {
    fun draw(): String
}

class Circle(val radius: Int) : Drawable {
    override fun draw(): String { return "Circle(r=$radius)" }
}

class Square(val side: Int) : Drawable {
    override fun draw(): String { return "Square(s=$side)" }
}

fun main() {
    // is smart-cast on nullable
    val y: String? = "test"
    if (y is String) {
        println("y is String: ${y.length}")
    }

    // Interface dispatch
    val c = Circle(5)
    val s = Square(3)
    println(c.draw())
    println(s.draw())

    println("done")
}

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
    // is on nullable String
    val y: String? = "test"
    // TODO: 'is' smart-cast on nullable doesn't work yet
    if (y != null) {
        println("y is String: ${y.length}")
    }

    // Interface dispatch
    val c = Circle(5)
    val s = Square(3)
    println(c.draw())
    println(s.draw())

    println("done")
}

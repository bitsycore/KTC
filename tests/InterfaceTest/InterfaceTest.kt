package InterfaceTest

interface Shape {
    fun area(): Float
    fun name(): String
}

class Circle(private var radius: Float) : Shape {
    override fun area(): Float { return 3.14159f * radius * radius }
    override fun name(): String { return "Circle" }
}

class Square(private var side: Float) : Shape {
    override fun area(): Float { return side * side }
    override fun name(): String { return "Square" }
}

interface Resource {
    fun dispose()
    fun label(): String
}

class File(private var name: String) : Resource {
    override fun dispose() { println("closing file $name") }
    override fun label(): String { return "File($name)" }
}

fun main() {
    val c = Circle(2.0f)
    val s = Square(3.0f)
    println("${c.name()}: ${c.area()}")
    println("${s.name()}: ${s.area()}")

    // Interface as parameter (direct, not nullable)
    val shape: Shape = c
    println("shape area: ${shape.area()}")

    // Disposable-like interface
    val f = File("data.txt")
    println(f.label())
    f.dispose()

    println("done")
}

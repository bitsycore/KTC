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

// TODO: This should return a Union of Circle and Square
// Right now it's allocated memory
fun shapeReturnerById(id: Int): Shape {
    if (id % 2 == 0)
        return Circle(1.0f)
    else
        return Square(1.0f)
}

fun main() {
    val c = Circle(2.0f)
    val s = Square(3.0f)

    println("${c.name()}: ${c.area()}")
    println("${s.name()}: ${s.area()}")

    val shape1 = shapeReturnerById(0)
    val shape2 = shapeReturnerById(1)

    println("${shape1.name()}: ${shape1.area()}")
    println("${shape2.name()}: ${shape2.area()}")

    // Interface as parameter (direct, not nullable)
    val shape: Shape = c
    println("shape area: ${shape.area()}")

    // Disposable-like interface
    val f = File("data.txt")
    println(f.label())
    f.dispose()

    println("done")
}

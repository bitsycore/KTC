package InterfaceTest

interface Shape {
    fun area(): Float
    fun name(): String
}

class Circle(private var radius: Float) : Shape {
    override fun area(): Float = 3.14159f * radius * radius
    override fun name(): String = "Circle"
}

class Square(private var side: Float) : Shape {
    override fun area(): Float = side * side
    override fun name(): String = "Square"
}

interface Resource {
    fun label(): String
}

class File(private var name: String) : Resource {
    override fun dispose() { println("closing file $name") }
    override fun label(): String = "File($name)"
}

// This should return a Union of Circle and Square
fun shapeReturnerById(id: Int): Shape {
    if (id % 2 == 0)
        return Circle(1.0f)
    else
        return Square(1.0f)
}

// This should return a Union of Circle and Square
fun shapeReturnerByIdInfer(id: Int): Shape {
    if (id % 2 == 0)
        return Circle(1.0f)
    else
        return Square(1.0f)
}

// if-expression in block body with return
fun shapeReturnerById2(id: Int): Shape {
    return if (id % 2 == 0)
        Circle(2.0f)
    else
        Square(2.0f)
}

// if-expression as expression body
fun shapeReturnerById3(id: Int): Shape = if (id % 2 == 0)
    Circle(3.0f)
else
    Square(3.0f)

// Infer Type
fun shapeReturnerById3Infer(id: Int) = if (id % 2 == 0)
    Circle(3.0f)
else
    Square(3.0f)

// when-expression as expression body
fun shapeReturnerById4(id: Int): Shape = when {
    id % 2 == 0 -> Circle(4.0f)
    else -> Square(4.0f)
}

// Infer Type
fun shapeReturnerById4Infer(id: Int) = when {
    id % 2 == 0 -> Circle(4.0f)
    else -> Square(4.0f)
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

    // Test if-expression in block body with return
    val shape3 = shapeReturnerById2(0)
    val shape4 = shapeReturnerById2(1)
    println("if-return: ${shape3.name()}: ${shape3.area()}")
    println("if-return: ${shape4.name()}: ${shape4.area()}")

    // Test if-expression as expression body
    val shape5 = shapeReturnerById3(0)
    val shape6 = shapeReturnerById3(1)
    println("if-expr: ${shape5.name()}: ${shape5.area()}")
    println("if-expr: ${shape6.name()}: ${shape6.area()}")

    // Test when-expression as expression body
    val shape7 = shapeReturnerById4(0)
    val shape8 = shapeReturnerById4(1)
    println("when-expr: ${shape7.name()}: ${shape7.area()}")
    println("when-expr: ${shape8.name()}: ${shape8.area()}")

    // Test inferred return type — if-expression
    val shape9 = shapeReturnerById3Infer(0)
    val shape10 = shapeReturnerById3Infer(1)
    println("if-infer: ${shape9.name()}: ${shape9.area()}")
    println("if-infer: ${shape10.name()}: ${shape10.area()}")

    // Test inferred return type — when-expression
    val shape11 = shapeReturnerById4Infer(0)
    val shape12 = shapeReturnerById4Infer(1)
    println("when-infer: ${shape11.name()}: ${shape11.area()}")
    println("when-infer: ${shape12.name()}: ${shape12.area()}")

    // Interface as parameter (direct, not nullable)
    val shape: Shape = c
    println("shape area: ${shape.area()}")

    // Disposable-like interface
    val f = File("data.txt")
    println(f.label())
    f.dispose()

    // dispose through interface (vtable dispatch)
    val r: Resource = f
    println("dispose via Resource interface:")
    r.dispose()

    // dispose on non-override class (no-op via macro)
    println("dispose on Circle (should be no-op):")
    c.dispose()

    println("done")
}

package HashTest

data class Vec2(val x: Float, val y: Float)
data class Point3D(val x: Float, val y: Float, val z: Float)
data class Player(val name: String, val score: Int)
class RegularClass(val id: Int)

fun main() {
    val a = Vec2(1.0f, 2.0f)
    val b = Vec2(1.0f, 2.0f)
    val c = Vec2(3.0f, 4.0f)

    println("same value -> same hash: ${a.hashCode() == b.hashCode()}")
    println("diff value -> diff hash: ${a.hashCode() != c.hashCode()}")

    val p1 = Point3D(1.0f, 2.0f, 3.0f)
    val p2 = Point3D(1.0f, 2.0f, 3.0f)
    println("Point3D same -> same hash: ${p1.hashCode() == p2.hashCode()}")

    val pl1 = Player("Alice", 100)
    val pl2 = Player("Alice", 100)
    val pl3 = Player("Bob", 100)
    println("Player same -> same hash: ${pl1.hashCode() == pl2.hashCode()}")
    println("Player diff -> diff hash: ${pl1.hashCode() != pl3.hashCode()}")

    val r1 = RegularClass(1)
    val r2 = RegularClass(2)
    println("RegularClass different because diff address: ${r1.hashCode() != r2.hashCode()}")

    println("done")
}

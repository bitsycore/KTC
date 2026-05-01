package TestProject.Main

data class Vec2f(val x: Float, val y: Float)

fun main(args: Array<String>) {

	val longe = "10000L".toLongOrNull()
	val longe2 = "Hello".toLongOrNull()

	println("longe = $longe, longe2 = $longe2")

	val a = nullIfTooBig("Hello world, this is big !")
	val b = nullIfTooBig("Hello")

	if (b == null) return

	a.printN()
	b.print()

	val c = nullIfTooBig2("Hello world, this is big !")
	val d = nullIfTooBig2("Hello")

	if (d == null) return

	c.printN()
	d.print()

	// Test if (x != null) { x.method() } smart cast
	val e = nullIfTooBig("Smart")
	if (e != null) {
		e.print()
	}

	// Test var should NOT get smart cast (use safe call)
	var f = nullIfTooBig("VarTest")
	if (f != null) {
		f?.print()
	}
}

fun nullIfTooBig(input: String): String? {
	if (input.length > 10) {
		return null 
	} else {
		return input
	}
}

fun nullIfTooBig2(input: String): Vec2f? {
	if (input.length > 10) {
		return null 
	} else {
		return Vec2f(input.length.toFloat(), input.length.toFloat())
	}
}

fun String.print() {
	println(this)
}

fun String?.printN() {
	if(this != null) println(this)
}

fun Vec2f.print() {
	println(this)
}

fun Vec2f?.printN() {
	if(this != null) println(this)
}

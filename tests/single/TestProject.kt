package TestProject.Main

data class Vec2f(val x: Float, val y: Float)

fun main(args: Array<String>) {

	println("Hello world !")

	var i = 0
	while(true) {
		println("Index is = $i")
		i++
		if (i > 30)
			break
	}

	val vec2f = Vec2f(10.0f, 20.0f)
	var tempVec2f = vec2f

	while(tempVec2f.x < 100.0f && tempVec2f.y < 100.0f) {
		println(tempVec2f)
		tempVec2f = tempVec2f.copy(x = tempVec2f.x + 10.0f, y = tempVec2f.y + 5.0f)
	}

	val a = nullIfTooBig("Hello world, this is big !")
	val b = nullIfTooBig("Hello")

	a?.print()
	b?.print()

	val c = nullIfTooBig2("Hello world, this is big !")
	val d = nullIfTooBig2("Hello")

	c.print()
	d.print()

	c?.print()
	d?.print()
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

fun Vec2f.print() {
	println(this)
}

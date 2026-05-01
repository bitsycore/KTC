package PairVararg.Main

fun sum(vararg nums: Int): Int {
	var total = 0
	for (n in nums) {
		total = total + n
	}
	return total
}

fun printPairs(vararg pairs: Pair<Int, Int>) {
	for (p in pairs) {
		c.printf("(%d, %d)\n", p.first, p.second)
	}
}

fun forward(vararg items: Int): Int {
	return sum(*items)
}

fun main(args: Array<String>) {
	// Pair via to operator
	val p1 = 1 to 2
	c.printf("p1: (%d, %d)\n", p1.first, p1.second)

	// Pair via constructor
	val p2 = Pair(10, 20)
	c.printf("p2: (%d, %d)\n", p2.first, p2.second)

	// vararg with values
	val s1 = sum(1, 2, 3, 4, 5)
	c.printf("sum(1..5) = %d\n", s1)

	// vararg empty
	val s2 = sum()
	c.printf("sum() = %d\n", s2)

	// spread operator
	val arr = intArrayOf(10, 20, 30)
	val s3 = sum(*arr)
	c.printf("sum(*arr) = %d\n", s3)

	// forward vararg via spread
	val s4 = forward(100, 200, 300)
	c.printf("forward(100,200,300) = %d\n", s4)

	// vararg of Pair
	printPairs(1 to 10, 2 to 20, 3 to 30)
}

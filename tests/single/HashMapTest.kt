fun main() {

	val testMap = mapOf("Helo" to "World")
	defer testMap.dispose()
	val testMap2 = mutableMapOf("Helo" to "World", "Another" to "One")
	defer testMap2.dispose()

	val testList = listOf(1,2,3,4,5)
	defer testList.dispose()
	val testList2 = mutableListOf("Hello", "World")
	defer testList2.dispose()

	val map = HashMap<Int, String>(16)

	// Test put and get
	map.put(1, "hello")
	map.put(2, "world")
	map.put(3, "foo")

	val v1 = map.get(1)
	if (v1 != null) {
		c.printf("get(1) = %.*s\n", v1.len, v1.ptr)
	}

	val v2 = map.get(2)
	if (v2 != null) {
		c.printf("get(2) = %.*s\n", v2.len, v2.ptr)
	}

	// Test containsKey
	c.printf("containsKey(1) = %d\n", map.containsKey(1))
	c.printf("containsKey(99) = %d\n", map.containsKey(99))

	// Test size
	c.printf("size = %d\n", map.size)

	// Test overwrite
	map.put(1, "replaced")
	val v1b = map.get(1)
	if (v1b != null) {
		c.printf("get(1) after overwrite = %.*s\n", v1b.len, v1b.ptr)
	}
	c.printf("size after overwrite = %d\n", map.size)

	// Test remove
	val removed = map.remove(2)
	c.printf("remove(2) = %d\n", removed)
	c.printf("containsKey(2) after remove = %d\n", map.containsKey(2))
	c.printf("size after remove = %d\n", map.size)

	// Test get on missing key
	val missing = map.get(999)
	if (missing == null) {
		c.printf("get(999) = null\n")
	}

	// Test clear
	map.clear()
	c.printf("size after clear = %d\n", map.size)
	c.printf("isEmpty after clear = %d\n", map.isEmpty())

	// Test growth (many entries)
	for (i in 0 until 100) {
		map.put(i, "val")
	}
	c.printf("size after 100 puts = %d\n", map.size)
	c.printf("containsKey(50) = %d\n", map.containsKey(50))
	c.printf("containsKey(100) = %d\n", map.containsKey(100))

	map.dispose()
	c.printf("done\n")
}

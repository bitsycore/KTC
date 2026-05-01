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

	// Test put via operator set: map[key] = value
	map[1] = "hello"
	map[2] = "world"
	map[3] = "foo"

	// Test get via operator get: map[key]
	val v1 = map[1]
	if (v1 != null) {
		c.printf("get(1) = %.*s\n", v1.len, v1.ptr)
	}

	val v2 = map[2]
	if (v2 != null) {
		c.printf("get(2) = %.*s\n", v2.len, v2.ptr)
	}

	// Test containsKey via `in` operator
	c.printf("1 in map = %d\n", 1 in map)
	c.printf("99 in map = %d\n", 99 in map)
	c.printf("99 !in map = %d\n", 99 !in map)

	// Test size
	c.printf("size = %d\n", map.size)

	// Test overwrite via operator set
	map[1] = "replaced"
	val v1b = map[1]
	if (v1b != null) {
		c.printf("get(1) after overwrite = %.*s\n", v1b.len, v1b.ptr)
	}
	c.printf("size after overwrite = %d\n", map.size)

	// Test remove
	val removed = map.remove(2)
	c.printf("remove(2) = %d\n", removed)
	c.printf("2 in map after remove = %d\n", 2 in map)
	c.printf("size after remove = %d\n", map.size)

	// Test get on missing key via operator
	val missing = map[999]
	if (missing == null) {
		c.printf("get(999) = null\n")
	}

	// Test clear
	map.clear()
	c.printf("size after clear = %d\n", map.size)
	c.printf("isEmpty after clear = %d\n", map.isEmpty())

	// Test growth (many entries)
	for (i in 0 until 100) {
		map[i] = "val"
	}
	c.printf("size after 100 puts = %d\n", map.size)
	c.printf("50 in map = %d\n", 50 in map)
	c.printf("100 in map = %d\n", 100 in map)

	// Test list operator get and contains
	val list = mutableListOf(10, 20, 30, 40, 50)
	defer list.dispose()
	c.printf("list[0] = %d\n", list[0])
	c.printf("list[2] = %d\n", list[2])
	c.printf("list[4] = %d\n", list[4])

	// operator set on list
	list[1] = 99
	c.printf("list[1] after set = %d\n", list[1])

	// in operator on list
	c.printf("30 in list = %d\n", 30 in list)
	c.printf("999 in list = %d\n", 999 in list)
	c.printf("999 !in list = %d\n", 999 !in list)

	map.dispose()
	c.printf("done\n")
}

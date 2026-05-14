package ListTest.Main

// Disposable, List<T>, MutableList<T>, ArrayList<T> come from ktc stdlib (auto-imported)

fun createMutableListInt(capacity: Int = 8): MutableList<Int> {
	return ArrayList<Int>(capacity)
}

fun <T> sizeOfList(list: List<T>): Int {
	return list.size
}

fun List<*>.sizeOf(): Int {
	return size
}

data class Vec2(val x: Int, val y: Int)

// ── main ─────────────────────────────────────────────────────────────

fun <T> newArray(size: Int = 100) : @Ptr Array<T> {
	return HeapAlloc<Array<T>>(size)!!
}

fun main(args: Array<String>) {

	val array = newArray<Int>(5)
	defer HeapFree(array)
	val array2 = newArray<Int>()
	defer HeapFree(array2)
	val array3 = newArray<Int>(180)
	defer HeapFree(array3)

	println("Sizeof array: ${array.size}")
	if (array.size != 5) error("FAIL array.size=${array.size}")
	println("Sizeof array2: ${array2.size}")
	if (array2.size != 100) error("FAIL array2.size=${array2.size}")
	println("Sizeof array3: ${array3.size}")
	if (array3.size != 180) error("FAIL array3.size=${array3.size}")

	val listVec = HeapAlloc<ArrayList<Vec2>>(8)
	if (listVec == null) return
	defer HeapFree(listVec)
	val v2 = listVec.value()
	defer {
		v2.dispose()
	}

	v2.add(Vec2(1,1))
	v2.add(Vec2(2,2))
	v2.add(Vec2(3,3))
	v2.add(Vec2(4,4))
	v2.add(Vec2(5,5))
	v2.add(Vec2(6,6))
	v2.add(Vec2(7,7))
	v2.add(Vec2(8,8))
	v2.add(Vec2(9,9))
	v2.add(Vec2(0,0))

	for(i in 0..<v2.size) {
		println("v2.get($i) = ${v2[i]}")
	}
	if (v2.size != 10) error("FAIL v2.size=${v2.size}")

	val list = HeapAlloc<ArrayList<Int>>(8)!!
	val v = list.value()

	v.add(10)
	v.add(20)
	v.add(30)
	v.add(40)
	v.add(50)

	println("size:")
	println(v.size)
	if (v.size != 5) error("FAIL v.size=${v.size}")

	println("sizeOfList")
	println(sizeOfList(v))

	println("MutableList.sizeOf")
	println(v.sizeOf())

	println("get(0), get(2):")
	println(v.get(0))
	println(v.get(2))

	v.set(1, 99)
	println("after set(1, 99), get(1):")
	println(v.get(1))
	if (v.get(1) != 99) error("FAIL get(1) after set")

	val removed = v.removeAt(0)
	println("removed:")
	println(removed)
	if (removed != 10) error("FAIL removed=$removed")
	println("size after remove:")
	println(v.size)
	if (v.size != 4) error("FAIL size after remove=${v.size}")

	println("contains 99:")
	println(v.contains(99))
	if (v.contains(99) == false) error("FAIL should contain 99")
	println("contains 777:")
	println(v.contains(777))
	if (v.contains(777)) error("FAIL should not contain 777")

	println("indexOf 50:")
	println(v.indexOf(50))
	if (v.indexOf(50) != 3) error("FAIL indexOf 50")

	v.clear()
	println("size after clear:")
	println(v.size)
	if (v.size != 0) error("FAIL size after clear=${v.size}")

	v.dispose()
	HeapFree(list)
}

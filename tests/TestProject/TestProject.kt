package TestProject.Main

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

fun <T> newArray(size: Int = 100) : Array<T> {
	return Array<T>(size)
}

fun main(args: Array<String>) {

	val array = newArray<Int>(5)
	val array2 = newArray<Int>()
	val array3 = newArray<Int>(180)

	println("Sizeof array: ${array.size}")
	println("Sizeof array2: ${array2.size}")
	println("Sizeof array3: ${array3.size}")

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

	val list = HeapAlloc<ArrayList<Int>>(8)!!
	val v = list.value()

	v.add(10)
	v.add(20)
	v.add(30)
	v.add(40)
	v.add(50)

	println("size:")
	println(v.size)

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

	val removed = v.removeAt(0)
	println("removed:")
	println(removed)
	println("size after remove:")
	println(v.size)

	println("contains 99:")
	println(v.contains(99))
	println("contains 777:")
	println(v.contains(777))

	println("indexOf 50:")
	println(v.indexOf(50))

	v.clear()
	println("size after clear:")
	println(v.size)

	v.dispose()
	HeapFree(list)
}

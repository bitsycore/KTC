package TestProject.Main

// ── MutableListInt ─ ArrayList<Int> built with malloc/realloc ────────

class MutableList<T>(capacity: Int) {

	var size: Int = 0
	var buf: Heap<Array<T>> = malloc<Array<T>>(capacity)!!

	fun add(value: T) {
		if (size >= buf.size) {
			val newSize = buf.size * 2
			buf = realloc<Array<T>>(buf, newSize)!!
		}
		buf[size] = value
		size = size + 1
	}

	fun get(index: Int): T {
		return buf[index]
	}

	fun set(index: Int, value: T) {
		buf[index] = value
	}

	fun removeAt(index: Int): T {
		val removed = buf[index]
		for (i in index until size - 1) {
			buf[i] = buf[i + 1]
		}
		size = size - 1
		return removed
	}

	fun contains(value: T): Boolean {
		for (i in 0 until size) {
			if (buf[i] == value) return true
		}
		return false
	}

	fun indexOf(value: T): Int {
		for (i in 0 until size) {
			if (buf[i] == value) return i
		}
		return -1
	}

	fun clear() {
		size = 0
	}

	fun dispose() {
		free(buf)
	}

}

fun createMutableListInt(capacity: Int = 8): MutableList<Int> {
	return MutableList<Int>(capacity)
}

fun <T> sizeOfList(list: MutableList<T>): Int {
	return list.size
}

fun MutableList<*>.sizeOf(): Int {
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

	val listVec = malloc<MutableList<Vec2>>(8)
	if (listVec == null) return
	defer free(listVec)
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
		println("v2.get($i) = ${v2.get(i)}")
	}

	val list = malloc<MutableList<Int>>(8)!!
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
	free(list)
}

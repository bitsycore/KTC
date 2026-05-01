package TestProject.Main

// ── MutableListInt ─ ArrayList<Int> built with malloc/realloc ────────

class MutableListInt(var capacity: Int) {

	var size: Int = 0
	var buf: Pointer<Array<Int>> = malloc<Array<Int>>(capacity)

	fun add(value: Int) {
		if (size >= capacity) {
			capacity = capacity * 2
			buf = realloc<Int>(buf, capacity)
		}
		buf[size] = value
		size = size + 1
	}

	fun get(index: Int): Int {
		return buf[index]
	}

	fun set(index: Int, value: Int) {
		buf[index] = value
	}

	fun removeAt(index: Int): Int {
		val removed = buf[index]
		for (i in index until size - 1) {
			buf[i] = buf[i + 1]
		}
		size = size - 1
		return removed
	}

	fun contains(value: Int): Boolean {
		for (i in 0 until size) {
			if (buf[i] == value) return true
		}
		return false
	}

	fun indexOf(value: Int): Int {
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

fun createMutableListInt(capacity: Int = 8): MutableListInt {
	return MutableListInt(capacity)
}

// ── main ─────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
	val list = malloc<MutableListInt>(8)
	val v = list.value()

	v.add(10)
	v.add(20)
	v.add(30)
	v.add(40)
	v.add(50)

	println("size:")
	println(v.size)

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

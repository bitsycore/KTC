package ktc

interface List<T> : Disposable {
	val size: Int
	fun get(index: Int): T
	fun contains(value: T): Boolean
	fun indexOf(value: T): Int
}

interface MutableList<T> : List<T> {
	fun add(value: T)
	fun set(index: Int, value: T)
	fun removeAt(index: Int): T
	fun clear()
}

class ArrayList<T>(capacity: Int) : MutableList<T> {

	override var size: Int = 0
	var buf: Heap<Array<T>> = malloc<Array<T>>(capacity)!!

	override fun add(value: T) {
		if (size >= buf.size) {
			val newSize = buf.size * 2
			buf = realloc<Array<T>>(buf, newSize)!!
		}
		buf[size] = value
		size = size + 1
	}

	override fun get(index: Int): T {
		return buf[index]
	}

	override fun set(index: Int, value: T) {
		buf[index] = value
	}

	override fun removeAt(index: Int): T {
		val removed = buf[index]
		for (i in index until size - 1) {
			buf[i] = buf[i + 1]
		}
		size = size - 1
		return removed
	}

	override fun contains(value: T): Boolean {
		for (i in 0 until size) {
			if (buf[i] == value) return true
		}
		return false
	}

	override fun indexOf(value: T): Int {
		for (i in 0 until size) {
			if (buf[i] == value) return i
		}
		return -1
	}

	override fun clear() {
		size = 0
	}

	override fun dispose() {
		free(buf)
	}

}

fun <T> listOf(vararg items: T): List<T> {
	val list = ArrayList<T>(items.size)
	for (item in items) {
		list.add(item)
	}
	return list
}

fun <T> mutableListOf(vararg items: T): MutableList<T> {
	val list = ArrayList<T>(items.size)
	for (item in items) {
		list.add(item)
	}
	return list
}
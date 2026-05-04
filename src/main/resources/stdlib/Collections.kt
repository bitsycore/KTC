package ktc.std

class ListIterator<T>(@Ptr val buf: Array<T>) : Iterator<T> {

	var idx: Int = 0

	operator fun hasNext(): Boolean {
		return idx < buf.size
	}

	operator fun next(): T {
		val v = buf[idx]
		idx = idx + 1
		return v
	}
}

interface List<T> : Disposable {
	val size: Int
	operator fun get(index: Int): T
	operator fun contains(value: T): Boolean
	fun indexOf(value: T): Int
	operator fun iterator(): ListIterator<T>
}

interface MutableList<T> : List<T> {
	fun add(value: T)
	operator fun set(index: Int, value: T)
	fun removeAt(index: Int): T
	fun clear()
}

class ArrayList<T>(capacity: Int) : MutableList<T> {

	private var buf: @Ptr Array<T> = HeapAlloc<Array<T>>(if (capacity > 0) capacity else 4)!!

	override var size: Int = 0
		private set

	override fun add(value: T) {
		if (size >= buf.size) {
			val newSize = if (buf.size > 0) buf.size * 2 else 4
			buf = HeapArrayResize<Array<T>>(buf, newSize)!!
		}
		buf[size] = value
		size = size + 1
	}

	override operator fun get(index: Int): T {
		return buf[index]
	}

	override operator fun set(index: Int, value: T) {
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

	override operator fun contains(value: T): Boolean {
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

	override operator fun iterator(): ListIterator<T> {
		return ListIterator<T>(buf)
	}

	override fun dispose() {
		HeapFree(buf)
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
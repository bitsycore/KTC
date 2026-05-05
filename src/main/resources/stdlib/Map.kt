package ktc.std

class MapIterator<K, V>(
	private val keys: @Ptr Array<K>,
	private val vals: @Ptr Array<V>,
	private val occ: @Ptr Array<Boolean>,
) : Iterator<Pair<K, V>> {

	private var idx: Int = 0

	operator fun hasNext(): Boolean {
		while (idx < keys.size) {
			if (occ[idx]) {
				return true
			}
			idx = idx + 1
		}
		return false
	}

	operator fun next(): Pair<K, V> {
		val k = keys[idx]
		val v = vals[idx]
		idx = idx + 1
		return k to v
	}

}

interface Map<K, V> {
	val size: Int
	operator fun get(key: K): V?
	operator fun containsKey(key: K): Boolean
	fun isEmpty(): Boolean
	operator fun iterator(): MapIterator<K, V>
}

interface MutableMap<K, V> : Map<K, V> {
	fun put(key: K, value: V)
	operator fun set(key: K, value: V)
	fun remove(key: K): Boolean
	fun clear()
}

class HashMap<K, V>(capacity: Int) : MutableMap<K, V> {

	override var size: Int = 0
		private set

	private var keys = HeapAlloc<Array<K>>(capacity)!!
	private var vals = HeapAlloc<Array<V>>(capacity)!!
	private var occ = HeapArrayZero<Array<Boolean>>(capacity)!!

	private fun findSlot(key: K): Int {
		var idx = key.hashCode() % keys.size
		if (idx < 0) {
			idx = idx + keys.size
		}
		while (occ[idx]) {
			if (keys[idx] == key) {
				return idx
			}
			idx = (idx + 1) % keys.size
		}
		return -1
	}

	override operator fun get(key: K): V? {
		val idx = this.findSlot(key)
		if (idx < 0) {
			return null
		}
		return vals[idx]
	}

	override operator fun containsKey(key: K): Boolean {
		return this.findSlot(key) >= 0
	}

	override fun isEmpty(): Boolean {
		return size == 0
	}

	override fun put(key: K, value: V) {
		if (size * 2 >= keys.size) {
			this.grow()
		}
		var idx = key.hashCode() % keys.size
		if (idx < 0) {
			idx = idx + keys.size
		}
		while (occ[idx]) {
			if (keys[idx] == key) {
				vals[idx] = value
				return
			}
			idx = (idx + 1) % keys.size
		}
		keys[idx] = key
		vals[idx] = value
		occ[idx] = true
		size = size + 1
	}

	override operator fun set(key: K, value: V) {
		this.put(key, value)
	}

	override fun remove(key: K): Boolean {
		val fi = this.findSlot(key)
		if (fi < 0) {
			return false
		}
		occ[fi] = false
		size = size - 1
		var j = (fi + 1) % keys.size
		while (occ[j]) {
			val rk = keys[j]
			val rv = vals[j]
			occ[j] = false
			size = size - 1
			this.put(rk, rv)
			j = (j + 1) % keys.size
		}
		return true
	}

	override fun clear() {
		for (i in 0 until keys.size) {
			occ[i] = false
		}
		size = 0
	}

	private fun grow() {
		val oldKeys = keys
		val oldVals = vals
		val oldOcc = occ
		val oldCap = keys.size
		val newCapacity = keys.size * 2
		keys = HeapAlloc<Array<K>>(newCapacity)!!
		vals = HeapAlloc<Array<V>>(newCapacity)!!
		occ = HeapArrayZero<Array<Boolean>>(newCapacity)!!
		size = 0
		for (i in 0 until oldCap) {
			if (oldOcc[i]) {
				this.put(oldKeys[i], oldVals[i])
			}
		}
		HeapFree(oldKeys)
		HeapFree(oldVals)
		HeapFree(oldOcc)
	}

	override operator fun iterator(): Iterator<Pair<K, V>> {
		return MapIterator<K, V>(keys, vals, occ)
	}

	override fun dispose() {
		HeapFree(keys)
		HeapFree(vals)
		HeapFree(occ)
	}

}

fun <K,V> mapOf(vararg pairs: Pair<K, V>): Map<K, V> {
	val map = HashMap<K, V>(pairs.size)
	for (p in pairs) {
		map.put(p.first, p.second)
	}
	return map
}

fun <K,V> mutableMapOf(vararg pairs: Pair<K, V>): MutableMap<K, V> {
	val notZero = if (pairs.size == 0) 8 else pairs.size * 2
	val map = HashMap<K, V>(notZero)
	for (p in pairs) {
		map.put(p.first, p.second)
	}
	return map
}
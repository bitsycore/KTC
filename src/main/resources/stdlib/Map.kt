package ktc.std

class MapIterator<K, V>(
	private val keys: @Ptr Array<K>,
	private val vals: @Ptr Array<V>,
	private val occ: @Ptr Array<Boolean>,
	private val cap: Int
) {

	private var idx: Int = 0

	operator fun hasNext(): Boolean {
		while (idx < cap) {
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

interface Map<K, V> : Disposable {
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

class HashMap<K, V>(private var capacity: Int) : MutableMap<K, V> {

	override var size: Int = 0

	private @Ptr var keys: Array<K> = HeapAlloc<Array<K>>(capacity)!!
	private @Ptr var vals: Array<V> = HeapAlloc<Array<V>>(capacity)!!
	private @Ptr var occ: Array<Boolean> = HeapArrayZero<Array<Boolean>>(capacity)!!

	private fun findSlot(key: K): Int {
		var idx = key.hashCode() % capacity
		if (idx < 0) {
			idx = idx + capacity
		}
		while (occ[idx]) {
			if (keys[idx] == key) {
				return idx
			}
			idx = (idx + 1) % capacity
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
		if (size * 2 >= capacity) {
			this.grow()
		}
		var idx = key.hashCode() % capacity
		if (idx < 0) {
			idx = idx + capacity
		}
		while (occ[idx]) {
			if (keys[idx] == key) {
				vals[idx] = value
				return
			}
			idx = (idx + 1) % capacity
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
		var j = (fi + 1) % capacity
		while (occ[j]) {
			val rk = keys[j]
			val rv = vals[j]
			occ[j] = false
			size = size - 1
			this.put(rk, rv)
			j = (j + 1) % capacity
		}
		return true
	}

	override fun clear() {
		for (i in 0 until capacity) {
			occ[i] = false
		}
		size = 0
	}

	private fun grow() {
		val oldKeys = keys
		val oldVals = vals
		val oldOcc = occ
		val oldCap = capacity
		capacity = capacity * 2
		keys = HeapAlloc<Array<K>>(capacity)!!
		vals = HeapAlloc<Array<V>>(capacity)!!
		occ = HeapArrayZero<Array<Boolean>>(capacity)!!
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

	override operator fun iterator(): MapIterator<K, V> {
		return MapIterator<K, V>(keys, vals, occ, capacity)
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
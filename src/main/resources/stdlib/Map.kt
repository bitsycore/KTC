package ktc

class MapIterator<K, V>(keys: Heap<Array<K>>, vals: Heap<Array<V>>, occ: Heap<Array<Boolean>>, cap: Int) {
	var idx: Int = 0
	val keys: Heap<Array<K>> = keys
	val vals: Heap<Array<V>> = vals
	val occ: Heap<Array<Boolean>> = occ
	val cap: Int = cap

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

class HashMap<K, V>(capacity: Int) : MutableMap<K, V> {

	override var size: Int = 0
	var cap: Int = capacity
	var keys: Heap<Array<K>> = HeapAlloc<Array<K>>(capacity)!!
	var vals: Heap<Array<V>> = HeapAlloc<Array<V>>(capacity)!!
	var occ: Heap<Array<Boolean>> = HeapArrayZero<Array<Boolean>>(capacity)!!

	fun findSlot(key: K): Int {
		var idx = key.hashCode() % cap
		if (idx < 0) {
			idx = idx + cap
		}
		while (occ[idx]) {
			if (keys[idx] == key) {
				return idx
			}
			idx = (idx + 1) % cap
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
		if (size * 2 >= cap) {
			this.grow()
		}
		var idx = key.hashCode() % cap
		if (idx < 0) {
			idx = idx + cap
		}
		while (occ[idx]) {
			if (keys[idx] == key) {
				vals[idx] = value
				return
			}
			idx = (idx + 1) % cap
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
		var j = (fi + 1) % cap
		while (occ[j]) {
			val rk = keys[j]
			val rv = vals[j]
			occ[j] = false
			size = size - 1
			this.put(rk, rv)
			j = (j + 1) % cap
		}
		return true
	}

	override fun clear() {
		for (i in 0 until cap) {
			occ[i] = false
		}
		size = 0
	}

	fun grow() {
		val oldKeys = keys
		val oldVals = vals
		val oldOcc = occ
		val oldCap = cap
		cap = cap * 2
		keys = HeapAlloc<Array<K>>(cap)!!
		vals = HeapAlloc<Array<V>>(cap)!!
		occ = HeapArrayZero<Array<Boolean>>(cap)!!
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
		return MapIterator<K, V>(keys, vals, occ, cap)
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
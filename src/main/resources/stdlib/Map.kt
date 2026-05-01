package ktc

interface Map<K, V> {
	val size: Int
	fun get(key: K): V?
	fun containsKey(key: K): Boolean
	fun isEmpty(): Boolean
}

interface MutableMap<K, V> : Map<K, V> {
	fun put(key: K, value: V)
	fun remove(key: K): Boolean
	fun clear()
}

class HashMap<K, V>(capacity: Int) : MutableMap<K, V>, Disposable {

	override var size: Int = 0
	var cap: Int = capacity
	var keys: Heap<Array<K>> = malloc<Array<K>>(capacity)!!
	var vals: Heap<Array<V>> = malloc<Array<V>>(capacity)!!
	var occ: Heap<Array<Boolean>> = calloc<Array<Boolean>>(capacity)!!

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

	override fun get(key: K): V? {
		val idx = this.findSlot(key)
		if (idx < 0) {
			return null
		}
		return vals[idx]
	}

	override fun containsKey(key: K): Boolean {
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
		keys = malloc<Array<K>>(cap)!!
		vals = malloc<Array<V>>(cap)!!
		occ = calloc<Array<Boolean>>(cap)!!
		size = 0
		for (i in 0 until oldCap) {
			if (oldOcc[i]) {
				this.put(oldKeys[i], oldVals[i])
			}
		}
		free(oldKeys)
		free(oldVals)
		free(oldOcc)
	}

	override fun dispose() {
		free(keys)
		free(vals)
		free(occ)
	}

}

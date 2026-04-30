@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

/**
 * ktc_stdlib.kt — Kotlin/JVM compatibility stubs for ktc built-in functions.
 *
 * Include this file in your Kotlin/JVM project so that the same source code
 * compiles on both JVM (via kotlinc) and native (via ktc → C).
 *
 * On JVM these are lightweight stubs; the real work happens in the generated C.
 */

// ── Pointer (maps to void* in C) ────────────────────────────────────

class Pointer(@JvmField val address: Long) {
    companion object {
        @JvmField val NULL = Pointer(0L)
    }
    override fun toString(): String = "0x${address.toString(16)}"
    override fun equals(other: Any?): Boolean = other is Pointer && address == other.address
    override fun hashCode(): Int = address.hashCode()
}

// ── malloc / calloc / realloc / free ─────────────────────────────────

private var _nextAddr: Long = 0x1000L

fun malloc(size: Int): Pointer {
    val addr = _nextAddr
    _nextAddr += size
    return Pointer(addr)
}

fun calloc(count: Int, size: Int): Pointer {
    val addr = _nextAddr
    _nextAddr += count * size
    return Pointer(addr)
}

fun realloc(ptr: Pointer, size: Int): Pointer {
    val addr = _nextAddr
    _nextAddr += size
    return Pointer(addr)
}

fun free(ptr: Pointer) { /* no-op on JVM — GC handles memory */ }

// ── ArrayList types (map to heap-allocated arrays in C) ──────────────

class IntArrayList(capacity: Int = 16) {
    private val backing = ArrayList<Int>(capacity)
    val size: Int get() = backing.size
    fun add(elem: Int) { backing.add(elem) }
    fun get(index: Int): Int = backing[index]
    operator fun set(index: Int, value: Int) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    operator fun iterator(): Iterator<Int> = backing.iterator()
}

class LongArrayList(capacity: Int = 16) {
    private val backing = ArrayList<Long>(capacity)
    val size: Int get() = backing.size
    fun add(elem: Long) { backing.add(elem) }
    fun get(index: Int): Long = backing[index]
    operator fun set(index: Int, value: Long) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    operator fun iterator(): Iterator<Long> = backing.iterator()
}

class FloatArrayList(capacity: Int = 16) {
    private val backing = ArrayList<Float>(capacity)
    val size: Int get() = backing.size
    fun add(elem: Float) { backing.add(elem) }
    fun get(index: Int): Float = backing[index]
    operator fun set(index: Int, value: Float) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    operator fun iterator(): Iterator<Float> = backing.iterator()
}

class DoubleArrayList(capacity: Int = 16) {
    private val backing = ArrayList<Double>(capacity)
    val size: Int get() = backing.size
    fun add(elem: Double) { backing.add(elem) }
    fun get(index: Int): Double = backing[index]
    operator fun set(index: Int, value: Double) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    operator fun iterator(): Iterator<Double> = backing.iterator()
}

class StringArrayList(capacity: Int = 16) {
    private val backing = ArrayList<String>(capacity)
    val size: Int get() = backing.size
    fun add(elem: String) { backing.add(elem) }
    fun get(index: Int): String = backing[index]
    operator fun set(index: Int, value: String) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    operator fun iterator(): Iterator<String> = backing.iterator()
}

// ── mutableListOf helpers ────────────────────────────────────────────

fun mutableListOf(vararg elements: Int): IntArrayList {
    val list = IntArrayList(elements.size.coerceAtLeast(4))
    for (e in elements) list.add(e)
    return list
}

fun mutableListOf(vararg elements: Float): FloatArrayList {
    val list = FloatArrayList(elements.size.coerceAtLeast(4))
    for (e in elements) list.add(e)
    return list
}

fun mutableListOf(vararg elements: Double): DoubleArrayList {
    val list = DoubleArrayList(elements.size.coerceAtLeast(4))
    for (e in elements) list.add(e)
    return list
}

fun mutableListOf(vararg elements: String): StringArrayList {
    val list = StringArrayList(elements.size.coerceAtLeast(4))
    for (e in elements) list.add(e)
    return list
}

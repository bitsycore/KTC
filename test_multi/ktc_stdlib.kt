@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate", "NOTHING_TO_INLINE")
package ktc

/**
 * ktc_stdlib.kt — Kotlin/JVM compatibility stubs for ktc built-in functions.
 *
 * Include this file in your Kotlin/JVM project so that the same source code
 * compiles on both JVM (via kotlinc) and native (via ktc → C).
 *
 * On JVM these are lightweight stubs; the real work happens in the generated C.
 */

// ── Pointer<T> (maps to T* in C) ────────────────────────────────────
// Untyped usage: Pointer<Unit> (or just Pointer() for void*)
// Typed usage:   Pointer<Int> wraps IntArray on JVM for indexing

class Pointer<T>(
    @JvmField val address: Long = 0L,
    @JvmField val _ints: IntArray? = null,
    @JvmField val _longs: LongArray? = null,
    @JvmField val _floats: FloatArray? = null,
    @JvmField val _doubles: DoubleArray? = null
) {
    companion object {
        @JvmField val NULL = Pointer<Unit>(0L)
    }

    // Int indexing
    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T = when {
        _ints != null    -> _ints[index] as T
        _longs != null   -> _longs[index] as T
        _floats != null  -> _floats[index] as T
        _doubles != null -> _doubles[index] as T
        else -> throw UnsupportedOperationException("Untyped pointer cannot be indexed")
    }

    operator fun set(index: Int, value: T) {
        when {
            _ints != null    -> _ints[index] = value as Int
            _longs != null   -> _longs[index] = value as Long
            _floats != null  -> _floats[index] = value as Float
            _doubles != null -> _doubles[index] = value as Double
            else -> throw UnsupportedOperationException("Untyped pointer cannot be indexed")
        }
    }

    override fun toString(): String = address.toString(16).padStart(16, '0')
    override fun equals(other: Any?): Boolean = other is Pointer<*> && address == other.address
    override fun hashCode(): Int = address.hashCode()
}

// ── malloc / calloc / realloc / free (untyped — returns Pointer<Unit>) ──

private var _nextAddr: Long = 0x1000L
@PublishedApi internal fun nextAddr(size: Int): Long { val a = _nextAddr; _nextAddr += size; return a }

@JvmName("mallocUntyped")
fun malloc(size: Int): Pointer<Unit> = Pointer(nextAddr(size))
fun calloc(count: Int, size: Int): Pointer<Unit> = Pointer(nextAddr(count * size))
fun realloc(ptr: Pointer<Unit>, size: Int): Pointer<Unit> = Pointer(nextAddr(size))
fun free(ptr: Pointer<*>) { /* no-op on JVM */ }

// ── Typed malloc<T>(n) — returns Pointer<T> with backing array ───────

inline fun <reified T> malloc(count: Int): Pointer<T> = when (T::class) {
    Int::class    -> Pointer<T>(nextAddr(count * 4), _ints = IntArray(count))
    Long::class   -> Pointer<T>(nextAddr(count * 8), _longs = LongArray(count))
    Float::class  -> Pointer<T>(nextAddr(count * 4), _floats = FloatArray(count))
    Double::class -> Pointer<T>(nextAddr(count * 8), _doubles = DoubleArray(count))
    else -> throw IllegalArgumentException("Unsupported malloc type: ${T::class}")
}

inline fun <reified T> calloc(count: Int): Pointer<T> = malloc<T>(count)

// ── ArrayList types (map to heap-allocated arrays in C) ──────────────

class IntArrayList(capacity: Int = 16) : Iterable<Int> {
    private val backing = ArrayList<Int>(capacity)
    val size: Int get() = backing.size
    fun add(elem: Int) { backing.add(elem) }
    operator fun get(index: Int): Int = backing[index]
    operator fun set(index: Int, value: Int) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    override operator fun iterator(): Iterator<Int> = backing.iterator()
}

class LongArrayList(capacity: Int = 16) : Iterable<Long> {
    private val backing = ArrayList<Long>(capacity)
    val size: Int get() = backing.size
    fun add(elem: Long) { backing.add(elem) }
    operator fun get(index: Int): Long = backing[index]
    operator fun set(index: Int, value: Long) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    override operator fun iterator(): Iterator<Long> = backing.iterator()
}

class FloatArrayList(capacity: Int = 16) : Iterable<Float> {
    private val backing = ArrayList<Float>(capacity)
    val size: Int get() = backing.size
    fun add(elem: Float) { backing.add(elem) }
    operator fun get(index: Int): Float = backing[index]
    operator fun set(index: Int, value: Float) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    override operator fun iterator(): Iterator<Float> = backing.iterator()
}

class DoubleArrayList(capacity: Int = 16) : Iterable<Double> {
    private val backing = ArrayList<Double>(capacity)
    val size: Int get() = backing.size
    fun add(elem: Double) { backing.add(elem) }
    operator fun get(index: Int): Double = backing[index]
    operator fun set(index: Int, value: Double) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    override operator fun iterator(): Iterator<Double> = backing.iterator()
}

class StringArrayList(capacity: Int = 16) : Iterable<String> {
    private val backing = ArrayList<String>(capacity)
    val size: Int get() = backing.size
    fun add(elem: String) { backing.add(elem) }
    operator fun get(index: Int): String = backing[index]
    operator fun set(index: Int, value: String) { backing[index] = value }
    fun removeAt(index: Int) { backing.removeAt(index) }
    fun clear() { backing.clear() }
    fun free() { /* no-op on JVM */ }
    override operator fun iterator(): Iterator<String> = backing.iterator()
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

@file:DocumentationOnly
package ktc.std

// ==================================================
// MARK: Arrays
// ==================================================

/**
Construct a stack array from a fixed list of values.
Usage: val arr = arrayOf(1, 2, 3)
*/
fun <T> arrayOf(vararg inElements: T): Array<T> = error("Transpiler intrinsic")

/**
Construct a stack array of n elements, all initialised to null.
Usage: val arr = arrayOfNulls<String>(n)
*/
fun <T> arrayOfNulls(inSize: Int): Array<T?> = error("Transpiler intrinsic")

/**
Construct a stack array of n elements, all zero-initialised.
Usage: val arr = Array<Int>(n)
*/
fun <T> Array(inSize: Int): Array<T> = error("Transpiler intrinsic")

/**
Construct a stack array of n elements, initialised by calling init(index) for each slot.
Usage: val arr = Array(n) { i -> i * 2 }
*/
fun <T> Array(inSize: Int, inInit: (Int) -> T): Array<T> = error("Transpiler intrinsic")

// ==================================================
// MARK: Primitive Arrays
// ==================================================

/**
Construct a stack array of n Int elements, all zero-initialised.
Usage: val arr = IntArray(n)
*/
fun IntArray(inSize: Int): Array<Int> = error("Transpiler intrinsic")

/**
Construct a stack array of n Int elements, initialised by calling init(index).
Usage: val arr = IntArray(n) { i -> i }
*/
fun IntArray(inSize: Int, inInit: (Int) -> Int): Array<Int> = error("Transpiler intrinsic")

/**
Construct a stack array of n Long elements, all zero-initialised.
*/
fun LongArray(inSize: Int): Array<Long> = error("Transpiler intrinsic")

/**
Construct a stack array of n Long elements, initialised by calling init(index).
*/
fun LongArray(inSize: Int, inInit: (Int) -> Long): Array<Long> = error("Transpiler intrinsic")

/**
Construct a stack array of n Float elements, all zero-initialised.
*/
fun FloatArray(inSize: Int): Array<Float> = error("Transpiler intrinsic")

/**
Construct a stack array of n Float elements, initialised by calling init(index).
*/
fun FloatArray(inSize: Int, inInit: (Int) -> Float): Array<Float> = error("Transpiler intrinsic")

/**
Construct a stack array of n Double elements, all zero-initialised.
*/
fun DoubleArray(inSize: Int): Array<Double> = error("Transpiler intrinsic")

/**
Construct a stack array of n Double elements, initialised by calling init(index).
*/
fun DoubleArray(inSize: Int, inInit: (Int) -> Double): Array<Double> = error("Transpiler intrinsic")

/**
Construct a stack array of n Byte elements, all zero-initialised.
*/
fun ByteArray(inSize: Int): Array<Byte> = error("Transpiler intrinsic")

/**
Construct a stack array of n Byte elements, initialised by calling init(index).
*/
fun ByteArray(inSize: Int, inInit: (Int) -> Byte): Array<Byte> = error("Transpiler intrinsic")

/**
Construct a stack array of n Char elements, all zero-initialised.
*/
fun CharArray(inSize: Int): Array<Char> = error("Transpiler intrinsic")

/**
Construct a stack array of n Char elements, initialised by calling init(index).
*/
fun CharArray(inSize: Int, inInit: (Int) -> Char): Array<Char> = error("Transpiler intrinsic")

/**
Construct a stack array of n Boolean elements, all zero-initialised.
*/
fun BooleanArray(inSize: Int): Array<Boolean> = error("Transpiler intrinsic")

/**
Construct a stack array of n Boolean elements, initialised by calling init(index).
*/
fun BooleanArray(inSize: Int, inInit: (Int) -> Boolean): Array<Boolean> = error("Transpiler intrinsic")

// ==================================================
// MARK: Primitive arrayOf variants
// ==================================================

/**
Construct a stack array from a fixed list of Int values.
Usage: val arr = intArrayOf(1, 2, 3)
*/
fun intArrayOf(vararg inElements: Int): Array<Int> = error("Transpiler intrinsic")

/**
Construct a stack array from a fixed list of Long values.
Usage: val arr = longArrayOf(1L, 2L, 3L)
*/
fun longArrayOf(vararg inElements: Long): Array<Long> = error("Transpiler intrinsic")

/**
Construct a stack array from a fixed list of Float values.
Usage: val arr = floatArrayOf(1f, 2f, 3f)
*/
fun floatArrayOf(vararg inElements: Float): Array<Float> = error("Transpiler intrinsic")

/**
Construct a stack array from a fixed list of Double values.
Usage: val arr = doubleArrayOf(1.0, 2.0, 3.0)
*/
fun doubleArrayOf(vararg inElements: Double): Array<Double> = error("Transpiler intrinsic")

/**
Construct a stack array from a fixed list of Byte values.
Usage: val arr = byteArrayOf(0x00, 0xFF)
*/
fun byteArrayOf(vararg inElements: Byte): Array<Byte> = error("Transpiler intrinsic")

/**
Construct a stack array from a fixed list of Char values.
Usage: val arr = charArrayOf('a', 'b', 'c')
*/
fun charArrayOf(vararg inElements: Char): Array<Char> = error("Transpiler intrinsic")

/**
Construct a stack array from a fixed list of Boolean values.
Usage: val arr = booleanArrayOf(true, false, true)
*/
fun booleanArrayOf(vararg inElements: Boolean): Array<Boolean> = error("Transpiler intrinsic")

// ==================================================
// MARK: RawArray
// ==================================================

/**
A raw C pointer to an array of T elements with no companion $len field.
RawArray<T> is always used together with @Ptr:
@Ptr RawArray<T>  →  T*

Unlike Array<T>, RawArray has no length tracking.  The caller is responsible
for keeping track of the element count.  Use this only when interfacing with
C APIs that expect a bare pointer or for optimizing class like HashMap implementation.

Heap-allocating a RawArray:
val vRaw: @Ptr RawArray<Byte> = HeapAlloc<RawArray<Byte>>(n)

Getting a raw pointer from a regular stack Array<T>:
val vArr = ByteArray(n)
val vRaw: @Ptr RawArray<Byte> = vArr.ptr()
 */
class RawArray<T>
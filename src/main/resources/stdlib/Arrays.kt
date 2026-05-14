@file:DocumentationOnly
package ktc.std

/**
Stack array construction intrinsics.
These functions are handled directly by the transpiler and emit
stack-allocated C arrays.  All arrays carry a companion $len field.
*/

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

// ── Primitive array constructors ─────────────────────────────────────────────

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

/**
A raw C pointer to an array of T elements with no companion $len field.
RawArray<T> is always used together with @Ptr:
@Ptr RawArray<T>  →  T*

Unlike Array<T>, RawArray has no length tracking.  The caller is responsible
for keeping track of the element count.  Use this only when interfacing with
C APIs that expect a bare pointer.

Heap-allocating a RawArray:
val vRaw: @Ptr RawArray<Byte> = HeapAlloc<RawArray<Byte>>(n)

Getting a raw pointer from a regular stack Array<T>:
val vArr = ByteArray(n)
val vRaw: @Ptr RawArray<Byte> = vArr.ptr()
 */
class RawArray<T>
@file:DocumentationOnly
package ktc.std

/**
Heap memory management intrinsics.
These functions are handled directly by the transpiler and emit
the corresponding C malloc/free/realloc/memset calls.
*/

/**
Allocate a single heap instance of T and call its primary constructor.
Usage: val p = HeapAlloc<MyClass>(args...)
*/
fun <T> HeapAlloc(): @Ptr T = error("Transpiler intrinsic")

/**
Allocate a heap array of n elements of T.
Returns a @Ptr Array<T> (no stack $len companion — the caller owns the length).
Usage: val arr = HeapAlloc<Array<T>>(n)
*/
fun <T> HeapAlloc(inN: Int): @Ptr Array<T> = error("Transpiler intrinsic")

/**
Allocate a raw heap pointer of n elements (no $len companion).
Usage: val raw = HeapAlloc<RawArray<T>>(n)
*/
fun <T> HeapAlloc(inN: Int): @Ptr RawArray<T> = error("Transpiler intrinsic")

/**
Zero-initialise an existing heap array of n elements using memset.
Works for both Array<T> and RawArray<T>.
Usage: HeapArrayZero<T>(ptr, n)
*/
fun <T> HeapArrayZero(inPtr: @Ptr Array<T>, inN: Int) = error("Transpiler intrinsic")

/**
Resize a heap array to n elements using realloc.
Works for both Array<T> and RawArray<T>.
Returns a new @Ptr to the possibly-moved allocation.
Usage: val newPtr = HeapArrayResize<T>(ptr, n)
*/
fun <T> HeapArrayResize(inPtr: @Ptr Array<T>, inN: Int): @Ptr Array<T> = error("Transpiler intrinsic")

/**
Free a heap allocation obtained from HeapAlloc.
Usage: HeapFree(ptr)
*/
fun HeapFree(inPtr: @Ptr Any) = error("Transpiler intrinsic")

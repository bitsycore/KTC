@file:DocumentationOnly
package ktc.std

/**
Built-in type annotations understood by the transpiler.
These annotations are not real Kotlin annotations at runtime — they are
compiler directives that influence C code generation.
*/

/**
Mark an Array<T> parameter, property, or return type as a raw C pointer.
A @Ptr Array<T> is emitted as `T*` with no companion $len field.
A @Ptr T (non-array) is emitted as `T*` (pointer to single value).
Nullable @Ptr T? is emitted as `T*` and compares to NULL instead of wrapping in an Optional.

Usage:
    fun read(inBuf: @Ptr ByteArray, inLen: Int): Int
    val fData: @Ptr Array<Float>
    fun next(): @Ptr Node?
*/
annotation class Ptr

/**
Mark a fixed-size stack array.
@Size(N) Array<T> is emitted as `T[N]` in the struct layout and is passed
as a raw pointer — no $len companion is added.
The N argument must be a compile-time integer literal.

Usage:
    data class Header(val fDigest: @Size(32) ByteArray)
    fun getKey(): @Size(16) ByteArray
*/
annotation class Size(val inN: Int)

/**
Mark a top-level object or property as thread-local storage.
The generated C variable is prefixed with the `_Thread_local` specifier (C11).

Usage:
    @Tls
    object ThreadContext { ... }

    @Tls
    var threadId: Int = 0
*/
annotation class Tls

/**
Opaque fat-pointer built-in type used for type-erased references.
Any can hold a pointer to any value together with a type identifier.
Passing Any by value is only allowed as @Ptr Any.

Usage:
    fun store(inValue: @Ptr Any)
    fun load(): @Ptr Any

Note: Any is a built-in transpiler type and does not need a class declaration.
*/

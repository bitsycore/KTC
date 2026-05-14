@file:DocumentationOnly
package ktc.std

/**
Opaque fat-pointer built-in type used for type-erased references.
Any can hold a pointer to any value together with a type identifier.
Passing Any by value is only allowed as @Ptr Any.

Usage:
fun store(inValue: @Ptr Any)
fun load(): @Ptr Any
*/
class Any
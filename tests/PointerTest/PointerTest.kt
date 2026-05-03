package PointerTest

// =================================
// MARK: Classes
// =================================

data class Vec2(val x: Int, val y: Int)
data class Offset(val x: Int? = null, val y: Int = 0)

// =================================
// MARK: Array Ptr
// =================================

fun passArrayPtr(inarr: @Ptr Array<Int>) {
    for(i in 0..<inarr.size) {
        println("inarr[$i] = ${inarr[i]}")
    }
}

fun passArrayNullablePtr(inarr: @Ptr Array<Int>? = null) {
    if (inarr == null) {
        println("inarr is null")
        return
    }
    for(i in 0..<inarr.size) {
        println("inarr[$i] = ${inarr[i]}")
    }
}

// =================================
// MARK: Array Value
// =================================

fun passArrayValue(inarr: Array<Int?>) {
    for(i in 0..<(inarr.size)) {
        println("inarr[$i] = ${inarr[i]}")
    }
}

fun passArrayValueNullable(inarr: Array<Int?>? = null) {
    if (inarr == null) {
        println("inarr is null")
        return
    }
    for(i in 0..<(inarr.size)) {
        println("inarr[$i] = ${inarr[i]}")
    }
}

// =================================
// MARK: Vec Ptr
// =================================

fun passVecPtr(inVec: @Ptr Vec2) {
    println(inVec)
}

fun passVecNullablePtr(inVec: @Ptr Vec2? = null) {
    if (inVec == null) {
        println("inVec is null")
        return
    }
    println(inVec)
}

// =================================
// MARK: Vec Value
// =================================

fun passVecValue(inVec: Vec2) {
    println(inVec)
}

fun passVecValueNullable(inVec: Vec2? = null) {
    if (inVec == null) {
        println("inVec is null")
        return
    }
    println(inVec)
}

// =================================
// MARK: Main
// =================================

fun main() {

    // ARRAY

    val array = arrayOf(1, 2, 3)
    val arrayNullable: Array<Int>? = arrayOf(1, 2, 3)

    // ARRAY PTR

    passArrayPtr(array.toPtr())

    passArrayNullablePtr(array.toPtr())
    passArrayNullablePtr(arrayNullable?.toPtr())
    passArrayNullablePtr(null)
    passArrayNullablePtr()

    // ARRAY VALUE

    passArrayValue(array)

    passArrayValueNullable(array)
    passArrayValueNullable(null)
    passArrayValueNullable()

    // VEC

    val vec = Vec2(10,20)
    val vecNullable: Vec2? = Vec2(10,20)

    // VEC PTR

    passVecPtr(vec.toPtr())

    passVecNullablePtr(vec.toPtr())
    passVecNullablePtr(vecNullable?.toPtr())
    passVecNullablePtr()
    passVecNullablePtr(null)

    // VEC VALUE

    passVecValue(vec)

    passVecValueNullable(vec)
    passVecValueNullable(null)
    passVecValueNullable()
}
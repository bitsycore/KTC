package PointerTest

data class Vec2(val x: Int, val y: Int)

data class Test(val x: Int? = null, val y: Int? = null)

fun passArray(inarr: Ptr<Array<Int>>? = null) {
    if (inarr == null) {
        println("inarr is null")
        return
    }
    for(i in 0..<inarr.size) {
        println("inarr[$i] = ${inarr[i]}")
    }
}

fun passArrayValue(inarr: Array<Int?>? = null) {
    if (inarr == null) {
        println("inarr is null")
        return
    }
    for(i in 0..<(inarr.size)) {
        println("inarr[$i] = ${inarr[i]}")
    }
}

fun passVec(inVec: Vec2? = null) {
    if (inVec == null) {
        println("inVec is null")
        return
    }
    println(inVec)
}

fun passVecPtr(inVec: Ptr<Vec2>? = null) {
    if (inVec == null) {
        println("inVec is null")
        return
    }
    println(inVec)
}

fun main() {
    val array = arrayOf(1, 2, 3)
    passArray(array.toPtr())
    passArray()
    passArray(null)

    passArrayValue()
    passArrayValue(null)

    val vec = Vec2(10,20)
    passVec(vec)
    passVec(null)
    passVec()

    passVecPtr(vec.toPtr())
    passVecPtr(null)
    passVecPtr()

    val test1 = Test()
    val test2 = Test(5,null)
    val test3 = Test(null,null)
    val test4 = Test(10,20)

    println(test1)
    println(test2)
    println(test3)
    println(test4)

    if (test1 == test2) {
        println("test1 == test2")
    }

    if (test2 == test3) {
        println("test2 == test3")
    }

    if (test3 == test4) {
        println("test3 == test4")
    }

    if (test4 == test1) {
        println("test4 == test1")
    }

    if (test2 == test4) {
        println("test2 == test4")
    }

    if (test1 == test3) {
        println("test1 == test3")
    }
}
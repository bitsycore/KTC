package test.list.updated

// ===================================================
// MARK: Concrete
// ===================================================

fun <T> testPtr(list: @Ptr ArrayList<T>) {
    println("testPtr(ArrayList)")
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> testValue(list: ArrayList<T>) {
    println("testValue(ArrayList)")
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> @Ptr ArrayList<T>.testPtrExt() {
    println("ArrayList.testPtrExt()")
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> ArrayList<T>.testValueExt() {
    println("ArrayList.testValueExt()")
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

// ===================================================
// MARK: Interface
// ===================================================

fun <T> testListPtr(list: @Ptr List<T>) {
    println("testListPtr(List)")
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> testListValue(list: List<T>) {
    println("testListValue(List)")
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> @Ptr List<T>.testListPtrExt() {
    println("List.testListPtrExt()")
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> List<T>.testListValueExt() {
    println("List.testListValueExt()")
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

// ===================================================
// MARK: Concrete Nullable
// ===================================================

fun <T> testPtrNullable(list: @Ptr ArrayList<T>?) {
    println("testPtrNullable(ArrayList)")
    if (list == null) {
        println("ArrayList is null")
        return
    }
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> testValueNullable(list: ArrayList<T>?) {
    println("testValueNullable(ArrayList)")
    if (list == null) {
        println("ArrayList is null")
        return
    }
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> @Ptr ArrayList<T>?.testPtrExtNullable() {
    println("ArrayList.testPtrExtNullable()")
    if (this == null) {
        println("ArrayList is null")
        return
    }
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> ArrayList<T>?.testValueExtNullable() {
    println("ArrayList.testValueExtNullable()")
    if (this == null) {
        println("ArrayList is null")
        return
    }
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

// ===================================================
// MARK: Interface Nullable
// ===================================================

fun <T> testListPtrNullable(list: @Ptr List<T>?) {
    println("testListPtrNullable(list)")
    if (list == null) {
        println("List is null")
        return
    }
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> testListValueNullable(list: List<T>?) {
    println("testListValueNullable(list)")
    if (list == null) {
        println("List is null")
        return
    }
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> @Ptr List<T>?.testListPtrExtNullable() {
    println("list.testListPtrExtNullable()")
    if (this == null) {
        println("List is null")
        return
    }
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> List<T>?.testListValueExtNullable() {
    println("list.testListValueExtNullable()")
    if (this == null) {
        println("List is null")
        return
    }
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun arrayListAllocTest() {
    // alloc ArrayList on stack, right infered
    val stack1 = ArrayList<Int>(allocator = Heap, capacity = 5)
    defer { stack1.dispose() }
    // alloc ArrayList on stack, left infered
    val stack2: ArrayList<Float> = ArrayList(Heap, 5)
    defer { stack2.dispose() }
    // alloc ArrayList on heap, right infered
    val heap1 = ArrayList<Int>.allocWith(Heap, Heap, 5)
    defer {
        heap1.dispose()
        Heap.freeMem(heap1)
    }
    // alloc ArrayList on heap, left infered
    val heap2: @Ptr ArrayList<Float> = ArrayList.allocWith(Heap, Heap, 5)
    defer {
        heap2.dispose()
        Heap.freeMem(heap2)
    }

    // STACK1 TEST

    stack1.add(0)
    stack1.add(8)
    stack1.add(2)

    println("Range")
    for(i in 0..<stack1.size) {
        println("stack1[$i] = ${stack1[i]}")
    }

    println("Iterator")
    var counter = 0
    for(value in stack1) {
        println("stack1[$counter] = ${value}")
        counter++
    }

    // STACK2 TEST

    stack2.add(0.5f)
    stack2.add(8.3f)
    stack2.add(2.0f)

    println("Range")
    for(i in 0..<stack2.size) {
        println("stack2[$i] = ${stack2[i]}")
    }

    println("Iterator")
    counter = 0
    for(value in stack2) {
        println("stack2[$counter] = ${value}")
        counter++
    }

    // HEAP1 TEST

    heap1.add(10)
    heap1.add(20)
    heap1.add(30)

    println("Range")
    for(i in 0..<heap1.size) {
        println("heap1[$i] = ${heap1[i]}")
    }

    println("Iterator")
    counter = 0
    for(value in heap1) {
        println("heap1[$counter] = ${value}")
        counter++
    }

    // HEAP2 TEST

    heap2.add(0.10)
    heap2.add(0.20)
    heap2.add(0.30)

    println("Range")
    for(i in 0..<heap2.size) {
        println("heap2[$i] = ${heap2[i]}")
    }

    println("Iterator")
    counter = 0
    for(value in heap2) {
        println("heap2[$counter] = ${value}")
        counter++
    }

    // ===============================================
    // MARK: NON NULL
    // ===============================================

    // CONCRETE

    testValue(stack1)
    testValue(stack2)
    testValue(heap1.value())
    testValue(heap2.value())

    testPtr(stack1.ptr())
    testPtr(stack2.ptr())
    testPtr(heap1)
    testPtr(heap2)

    // CONCRETE EXTENSION

    stack1.testValueExt()
    stack2.testValueExt()
    heap1.value().testValueExt()
    heap2.value().testValueExt()

    stack1.ptr().testPtrExt()
    stack2.ptr().testPtrExt()
    heap1.testPtrExt()
    heap2.testPtrExt()

    // INTERFACE

    testListValue(stack1)
    testListValue(stack2)
    testListValue(heap1.value())
    testListValue(heap2.value())

    testListPtr(stack1.ptr())
    testListPtr(stack2.ptr())
    testListPtr(heap1)
    testListPtr(heap2)

    // INTERFACE EXTENSION

    stack1.testListValueExt()
    stack2.testListValueExt()
    heap1.value().testListValueExt()
    heap2.value().testListValueExt()

    stack1.ptr().testListPtrExt()
    stack2.ptr().testListPtrExt()
    heap1.testListPtrExt()
    heap2.testListPtrExt()

    // ===============================================
    // MARK: NULLABLE
    // ===============================================

    val heap1Null1: @Ptr ArrayList<Int>? = heap1
    val heap1Null2: @Ptr ArrayList<Int>? = null
    val stack1Null1: ArrayList<Int>? = stack1
    val stack1Null2: ArrayList<Int>? = null

    // CONCRETE NULLABLE

//    testValueNullable(stack1)
//    testValueNullable(stack2)
//    testValueNullable(heap1.value())
//    testValueNullable(heap2.value())
//
//    testValueNullable(null)
//    heap1Null1?.let { testValueNullable(it.value()) }
//    heap1Null2?.let { testValueNullable(it.value()) }
//    testValueNullable(stack1Null1)
//    testValueNullable(stack1Null2)
//
//    testPtrNullable(stack1.ptr())
//    testPtrNullable(stack2.ptr())
//    testPtrNullable(heap1)
//    testPtrNullable(heap2)
//
//    testPtrNullable(null)
//    testPtrNullable(stack1Null1.ptr())
//    testPtrNullable(stack1Null2.ptr())
//    testPtrNullable(heap1Null1)
//    testPtrNullable(heap1Null2)
//
//    // CONCRETE EXTENSION NULLABLE
//
//    stack1.testValueExtNullable()
//    stack2.testValueExtNullable()
//    heap1.value().testValueExtNullable()
//    heap2.value().testValueExtNullable()
//
//    heap1Null1?.let { it.value().testValueExtNullable() }
//    heap1Null2?.let { it.value().testValueExtNullable() }
//    stack1Null1.testValueExtNullable()
//    stack1Null2.testValueExtNullable()
//
//    stack1.ptr().testPtrExtNullable()
//    stack2.ptr().testPtrExtNullable()
//    heap1.testPtrExtNullable()
//    heap2.testPtrExtNullable()
//
//    heap1Null1.testPtrExtNullable()
//    heap1Null2.testPtrExtNullable()
//    stack1Null1.ptr().testPtrExtNullable()
//    stack1Null2.ptr().testPtrExtNullable()
//
//    // INTERFACE NULLABLE
//
//    testListValueNullable(stack1)
//    testListValueNullable(stack2)
//    testListValueNullable(heap1.value())
//    testListValueNullable(heap2.value())
//
//    testListValueNullable(null)
//    heap1Null1?.let { testListValueNullable(it.value()) }
//    heap1Null2?.let { testListValueNullable(it.value()) }
//    testListValueNullable(stack1Null1)
//    testListValueNullable(stack1Null2)
//
//    testListPtrNullable(stack1.ptr())
//    testListPtrNullable(stack2.ptr())
//    testListPtrNullable(heap1)
//    testListPtrNullable(heap2)
//
//    testListPtrNullable(null)
//    testListPtrNullable(stack1Null1.ptr())
//    testListPtrNullable(stack1Null2.ptr())
//    testListPtrNullable(heap1Null1)
//    testListPtrNullable(heap1Null2)
//
//    // INTERFACE EXTENSION NULLABLE
//
//    stack1.testListValueExtNullable()
//    stack2.testListValueExtNullable()
//    heap1.value().testListValueExtNullable()
//    heap2.value().testListValueExtNullable()
//
//    heap1Null1?.let { it.value().testListValueExtNullable() }
//    heap1Null2?.let { it.value().testListValueExtNullable() }
//    stack1Null1.testListValueExtNullable()
//    stack1Null2.testListValueExtNullable()
//
//    stack1.ptr().testListPtrExtNullable()
//    stack2.ptr().testListPtrExtNullable()
//    heap1.testListPtrExtNullable()
//    heap2.testListPtrExtNullable()
//
//    heap1Null1.testListPtrExtNullable()
//    heap1Null2.testListPtrExtNullable()
//    stack1Null1.ptr().testListPtrExtNullable()
//    stack1Null2.ptr().testListPtrExtNullable()
}

fun main() {
    arrayListAllocTest()
    println("all ok")
}
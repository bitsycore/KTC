package test.list.updated

// ===================================================
// MARK: Concrete
// ===================================================

fun <T> testPtr(list: @Ptr ArrayList<T>) {
    println("testPtr(list)")
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> testValue(list: ArrayList<T>) {
    println("testValue(list)")
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> @Ptr ArrayList<T>.testPtrExt() {
    println("list.testPtrExt()")
    var counter = 0
    for (value in this) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> ArrayList<T>.testValueExt() {
    println("testValueExt(list)")
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
    println("testListPtr(list)")
    var counter = 0
    for (value in list) {
        println("list[$counter] = ${value}")
        counter++
    }
}

fun <T> testListValue(list: List<T>) {
    println("testListValue(list)")
    var counter = 0
    for (value in list) {
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

    testValue(stack1)
    testValue(stack2)
    testValue(heap1.value())
    testValue(heap2.value())

    testPtr(stack1.ptr())
    testPtr(stack2.ptr())
    testPtr(heap1)
    testPtr(heap2)

    stack1.testValueExt()
    stack2.testValueExt()
    heap1.value().testValueExt()
    heap2.value().testValueExt()

    stack1.ptr().testPtrExt()
    stack2.ptr().testPtrExt()
    heap1.testPtrExt()
    heap2.testPtrExt()

    testListValue(stack1)
    testListValue(stack2)
    testListValue(heap1.value())
    testListValue(heap2.value())

    testListPtr(stack1.ptr())
    testListPtr(stack2.ptr())
    testListPtr(heap1)
    testListPtr(heap2)
}

fun main() {
    arrayListAllocTest()
    println("all ok")
}
package test.list.updated

fun arrayListAllocTest() {
    // alloc ArrayList on stack, right infered
    val stack1 = ArrayList<Int>(allocator = Heap, capacity = 5)
    defer { stack1.dispose() }
    // alloc ArrayList on stack, left infered
    val stack2: ArrayList<Int> = ArrayList(Heap, 5)
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
}

fun main() {
    arrayListAllocTest()
}
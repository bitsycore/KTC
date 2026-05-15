package ktc.std

interface Allocator {
    fun allocMem(size: Int): AnyPtr
    fun freeMem(ptr: AnyPtr)
    fun reallocMem(ptr: AnyPtr, newSize: Int): AnyPtr
}

object Heap : Allocator {
    override fun allocMem(size: Int): AnyPtr {
        return c.malloc(size)
    }

    override fun freeMem(ptr: AnyPtr) {
        c.free(ptr)
    }

    override fun reallocMem(ptr: AnyPtr, newSize: Int): AnyPtr {
        return c.realloc(ptr, newSize)
    }
}

package ktc.std

interface Allocator {
    fun allocMem(size: Int): @Ptr Byte
    fun freeMem(ptr: @Ptr Byte)
    fun reallocMem(ptr: @Ptr Byte, newSize: Int): @Ptr Byte
}

object Heap : Allocator {
    override fun allocMem(size: Int): @Ptr Byte {
        return c.malloc(size)
    }

    override fun freeMem(ptr: @Ptr Byte) {
        c.free(ptr)
    }

    override fun reallocMem(ptr: @Ptr Byte, newSize: Int): @Ptr Byte {
        return c.realloc(ptr, newSize)
    }
}

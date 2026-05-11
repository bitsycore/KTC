package test

data class Vec2(val x: Float, val y: Float)
data class Point(val x: Int)
data class Person(val name: String, val age: Int)
data class Line(val start: Vec2, val end: Vec2)

class Foo(val x: Int)

fun testToStringViaFunction(sb: StringBuffer, p: Point): String {
    return p.toString(sb)
}

fun main() {
    var failed = false

    // 1. Data class toString with stack buffer
    val v = Vec2(1.5f, 2.5f)
    val buf1 = CharArray(256)
    var sb1 = StringBuffer(buf1.ptr(), 0)
    val s1 = v.toString(sb1)
    if (s1 != "Vec2(x=1.500000, y=2.500000)") {
        c.printf("FAIL: dataClassStackBuffer got [%.*s]\n", s1.len, s1.ptr)
        failed = true
    }

    // 2. Counting mode (null buffer) then write
    val p2 = Point(42)
    var sbNull = StringBuffer(null, 0)
    p2.toString(sbNull)
    val needed = sbNull.len
    if (needed <= 0) {
        c.printf("FAIL: nullBufferCounting needed=%d\n", needed)
        failed = true
    } else {
        val buf2 = CharArray(needed + 1)
        var sb2 = StringBuffer(buf2.ptr(), 0)
        val s2 = p2.toString(sb2)
        if (s2 != "Point(x=42)") {
            c.printf("FAIL: nullBufferCounting got [%.*s]\n", s2.len, s2.ptr)
            failed = true
        }
    }

    // 3. Int toString
    val iv = -12345
    val buf3 = CharArray(32)
    var sb3 = StringBuffer(buf3.ptr(), 0)
    val s3 = iv.toString(sb3)
    if (s3 != "-12345") {
        c.printf("FAIL: intToString got [%.*s]\n", s3.len, s3.ptr)
        failed = true
    }

    // 4. Double toString
    val dv = 3.14159
    val buf4 = CharArray(32)
    var sb4 = StringBuffer(buf4.ptr(), 0)
    val s4 = dv.toString(sb4)
    if (s4 != "3.141590") {
        c.printf("FAIL: doubleToString got [%.*s]\n", s4.len, s4.ptr)
        failed = true
    }

    // 5. Boolean toString
    val buf5 = CharArray(32)
    var sb5 = StringBuffer(buf5.ptr(), 0)
    val s5 = true.toString(sb5)
    if (s5 != "true") {
        c.printf("FAIL: booleanToString got [%.*s]\n", s5.len, s5.ptr)
        failed = true
    }

    // 6. Nested data class toString
    val start = Vec2(0f, 0f)
    val endV = Vec2(10f, 20f)
    val line = Line(start, endV)
    val buf6 = CharArray(512)
    var sb6 = StringBuffer(buf6.ptr(), 0)
    val s6 = line.toString(sb6)
    if (s6 != "Line(start=Vec2(x=0.000000, y=0.000000), end=Vec2(x=10.000000, y=20.000000))") {
        c.printf("FAIL: nestedDataClassToString got [%.*s]\n", s6.len, s6.ptr)
        failed = true
    }

    // 7. StringBuffer passed as function parameter
    val p7 = Point(99)
    val buf7 = CharArray(128)
    var sb7 = StringBuffer(buf7.ptr(), 0)
    val s7 = testToStringViaFunction(sb7, p7)
    if (s7 != "Point(x=99)") {
        c.printf("FAIL: stringBufferAsParameter got [%.*s]\n", s7.len, s7.ptr)
        failed = true
    }

    // 8. Heap-allocated buffer
    val p8 = Point(7)
    val bufHeap = HeapAlloc<Array<Char>>(256)
    var sb8 = StringBuffer(bufHeap, 0)
    val s8 = p8.toString(sb8)
    if (s8 != "Point(x=7)") {
        c.printf("FAIL: heapBufferToString got [%.*s]\n", s8.len, s8.ptr)
        failed = true
    }
    c.free(bufHeap)

    // 9. StringBuffer field access
    val buf9 = CharArray(128)
    var sb9 = StringBuffer(buf9.ptr(), 0)
    val ptr9 = sb9.buffer
    val len9 = sb9.len
    if (ptr9 == null || len9 != 0) {
        c.printf("FAIL: stringBufferFieldAccess ptr=%p len=%d\n", ptr9, len9)
        failed = true
    }

    // 10. Person data class (String field)
    val p10 = Person("Alice", 30)
    val buf10 = CharArray(256)
    var sb10 = StringBuffer(buf10.ptr(), 0)
    val s10 = p10.toString(sb10)
    if (s10 != "Person(name=Alice, age=30)") {
        c.printf("FAIL: personDataClass got [%.*s]\n", s10.len, s10.ptr)
        failed = true
    }

    // 11. Reuse StringBuffer (reset len)
    val v11a = Vec2(1f, 2f)
    val v11b = Vec2(3f, 4f)
    val buf11 = CharArray(512)
    var sb11 = StringBuffer(buf11.ptr(), 0)
    val s11a = v11a.toString(sb11)
    if (s11a != "Vec2(x=1.000000, y=2.000000)") {
        c.printf("FAIL: reuse1 got [%.*s]\n", s11a.len, s11a.ptr)
        failed = true
    }
    sb11.len = 0
    val s11b = v11b.toString(sb11)
    if (s11b != "Vec2(x=3.000000, y=4.000000)") {
        c.printf("FAIL: reuse2 got [%.*s]\n", s11b.len, s11b.ptr)
        failed = true
    }

    // 12. Default class toString with StringBuffer
    val f12 = Foo(1)
    val buf12 = CharArray(128)
    var sb12 = StringBuffer(buf12.ptr(), 0)
    val s12 = f12.toString(sb12)
    if (s12.len == 0) {
        c.printf("FAIL: defaultClassToString empty\n")
        failed = true
    }

    if (failed) {
        c.printf("StringBufferTest: FAILED\n")
        c.exit(1)
    } else {
        c.printf("StringBufferTest: PASSED\n")
    }
}

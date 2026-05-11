package ArrayInitTest

fun main() {
    var ok = true

    // ── Array<Int>(n) { init } ────────────────────────────────────────
    val squares = Array<Int>(5) { it * it }
    if (squares[0] != 0) { println("FAIL: squares[0]"); ok = false }
    else println("OK: squares[0] = ${squares[0]}")
    if (squares[1] != 1) { println("FAIL: squares[1]"); ok = false }
    else println("OK: squares[1] = ${squares[1]}")
    if (squares[2] != 4) { println("FAIL: squares[2]"); ok = false }
    else println("OK: squares[2] = ${squares[2]}")
    if (squares[3] != 9) { println("FAIL: squares[3]"); ok = false }
    else println("OK: squares[3] = ${squares[3]}")
    if (squares[4] != 16) { println("FAIL: squares[4]"); ok = false }
    else println("OK: squares[4] = ${squares[4]}")

    // ── Array<Float>(n) { init } ──────────────────────────────────────
    val floats = Array<Float>(4) { it.toFloat() * 0.5f }
    if (floats[0] != 0.0f) { println("FAIL: floats[0]"); ok = false }
    else println("OK: floats[0] = ${floats[0]}")
    if (floats[2] != 1.0f) { println("FAIL: floats[2]"); ok = false }
    else println("OK: floats[2] = ${floats[2]}")

    // ── Multi-statement lambda body ───────────────────────────────────
    val doubles = Array<Double>(3) {
        val temp = it.toDouble() * 2.5
        temp * temp
    }
    if (doubles[0] != 0.0) { println("FAIL: doubles[0]"); ok = false }
    else println("OK: doubles[0] = ${doubles[0]}")
    if (doubles[1] != 6.25) { println("FAIL: doubles[1]"); ok = false }
    else println("OK: doubles[1] = ${doubles[1]}")
    if (doubles[2] != 25.0) { println("FAIL: doubles[2]"); ok = false }
    else println("OK: doubles[2] = ${doubles[2]}")

    // ── Multi-statement with compound assignment ──────────────────────
    val seq = Array<Int>(3) {
        var v = it * 10
        v += it
        v
    }
    if (seq[0] != 0) { println("FAIL: seq[0]"); ok = false }
    else println("OK: seq[0] = ${seq[0]}")
    if (seq[1] != 11) { println("FAIL: seq[1]"); ok = false }
    else println("OK: seq[1] = ${seq[1]}")
    if (seq[2] != 22) { println("FAIL: seq[2]"); ok = false }
    else println("OK: seq[2] = ${seq[2]}")

    if (ok) {
        println("ALL OK")
    } else {
        println("SOME FAILED")
    }
}

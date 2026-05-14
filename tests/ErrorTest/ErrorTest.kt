package ErrorTest

fun main() {
    // ── check(value: Boolean) ────────────────────────────────────────
    check(true)
    println("check(true): ok")

    // ── check(value, lazyMessage) ────────────────────────────────────
    check(1 + 1 == 2, { "math broke" })
    println("check(1+1==2): ok")

    // ── require(value, lazyMessage) ──────────────────────────────────
    require(10 > 5, { "number compare broke" })
    println("require(10>5): ok")

    // ── require(value, lazyMessage) with string length ───────────────
    require("abc".length == 3, { "string length wrong" })
    println("require(length==3): ok")

    println("ALL OK")
}

package AnyDeepTest

fun deep1(item: Any): String {
    if (item is Int)    { return "i:${item * 2}" }
    if (item is String) { return "s:${item.length}" }
    if (item is Boolean){ return "b:$item" }
    return "?"
}

fun deepAsQuestion(item: Any): String {
    val i: Int?    = (item as? Int)
    val s: String? = (item as? String)
    if (i == null && s == null) return "null"
    if (i != null) return "i:$i"
    return "s:$s"
}

fun checkNullable(item: Any?) {
    if (item == null)  { println("null!") }
    else if (item is Int)    { println("int: $item") }
    else if (item is String) { println("str: $item") }
}

fun main() {
    println(deep1(42))
    println(deep1("hello"))
    println(deep1(true))

    println(deepAsQuestion(99))
    println(deepAsQuestion("test"))
    println(deepAsQuestion(1.0f))

    // ── Any? nullable ──
    checkNullable(42)
    checkNullable(null)

    // inlined nullable check (no function return)
    val a: Any? = 42
    val b: Any? = null
    if (a != null) { println("a: not null") }
    if (b == null) { println("b is null") }

    // ── check with String ──
    val c: Any? = "hello"
    checkNullable(c)

    // ── nested is / !is ──
    val x: Any = "example"
    if (x !is Int)     { println("not int") }
    if (x !is Boolean) { println("not bool") }
    if (x is String)   { println("is string: $x") }

    println("done")
}

package LiteralsTest

fun main2() {

    // =========================================================
    // INTEGER LITERALS (ALL VARIATIONS)
    // =========================================================

    val decimalInt = 42                 // Decimal Int
    val longNumber = 42L                // Long

    // Unsigned Int variations
    val uInt1 = 42U                     // UInt (uppercase U)
    val uInt2 = 42u                     // UInt (lowercase u)

    // Unsigned Long variations
    val uLong1 = 42UL                   // ULong
    val uLong2 = 42uL                   // ULong (lowercase u + L)
    //val uLong3 = 42Lu                   // Invalid

    // Hexadecimal Int
    val hexInt = 0xFF                   // Int
    val hexIntUnderscore = 0xFF_FF_FF   // Int with underscores

    // Hexadecimal Unsigned
    val hexUInt1 = 0xFFu                // UInt
    val hexULong1 = 0xFFuL              // ULong
    val hexULong2 = 0xFFUL              // ULong uppercase suffix

    // Binary Int
    val binaryInt = 0b1010             // Int
    val binaryInt2 = 0b1111_0000       // Int with underscore

    // Binary Unsigned
    val binaryUInt = 0b1010u           // UInt
    val binaryULong = 0b1010uL         // ULong

    // Readable underscores (all types)
    val underscoredInt = 1_000_000
    val underscoredLong = 1_000_000L
    val underscoredUInt = 1_000_000u
    val underscoredUInt2 = 1_000_000U
    val underscoredULong = 1_000_000uL

    val bigHex = 0xDE_AD_BE_EF
    val bigBinary = 0b1101_0010_0110_1111

    // =========================================================
    // FLOATING POINT LITERALS (ALL VARIATIONS)
    // =========================================================

    val double1 = 3.14                  // Double
    val double2 = 3.0                   // Double
    val double3 = 1.2e3                 // scientific notation
    val double4 = 1.2E-3                // scientific notation

    val float1 = 3.14F                  // Float
    val float2 = 3.14f                  // Float lowercase

    val floatScientific = 1.2e3f         // Float scientific
    val doubleScientific = 1.2e3         // Double scientific

    val underscoredDouble = 1_234.567_890
    val underscoredFloat = 1_234.567_890f

    // =========================================================
    // BOOLEAN LITERALS
    // =========================================================

    val booleanTrue = true
    val booleanFalse = false

    // =========================================================
    // CHAR LITERALS (ALL FORMS)
    // =========================================================

    val charA = 'A'
    val charDigit = '7'
    val charNewLine = '\n'
    val charTab = '\t'
    val charBackslash = '\\'
    val charQuote = '\''
    val charUnicode = '\u0041'

    // =========================================================
    // STRING LITERALS (ALL FORMS)
    // =========================================================

    val stringSimple = "Hello"
    val stringEmpty = ""

    val stringEscape = "Line1\nLine2\tTabbed"

    val name = "Kotlin"

    val multiLineRaw1 = """
        Hello
        World
    """.trimIndent()

    val multiLineRaw2 = """
        |Line 1
        |Line 2
    """.trimMargin()

    val rawNoTrim = """
        Raw string without trimming
            keeps indentation
    """

    // =========================================================
    // NULL LITERAL
    // =========================================================

    val nullable1: String? = null
}

fun main() {
    var ok = true

    // ── Decimal Int ───────────────────────────────────────────────────
    val decInt: Int = 42
    if (decInt != 42) { println("FAIL: decInt"); ok = false } else println("OK: decInt")

    // ── Long ──────────────────────────────────────────────────────────
    val longVal: Long = 100L
    if (longVal != 100L) { println("FAIL: longVal"); ok = false } else println("OK: longVal")

    // ── Float ─────────────────────────────────────────────────────────
    val floatVal: Float = 3.14f
    if (floatVal <= 3.13f || floatVal >= 3.15f) { println("FAIL: floatVal"); ok = false } else println("OK: floatVal")

    // ── Double ────────────────────────────────────────────────────────
    val doubleVal: Double = 3.14
    if (doubleVal <= 3.13 || doubleVal >= 3.15) { println("FAIL: doubleVal"); ok = false } else println("OK: doubleVal")

    // ── Boolean ───────────────────────────────────────────────────────
    val t = true; val f = false
    if (t != true || f != false) { println("FAIL: bool"); ok = false } else println("OK: bool")

    // ── Char ──────────────────────────────────────────────────────────
    val ch = 'A'
    if (ch != 'A') { println("FAIL: char"); ok = false } else println("OK: char")

    val chEsc = '\n'
    if (chEsc != '\n') { println("FAIL: charEscape"); ok = false } else println("OK: charEscape")

    // ── String ────────────────────────────────────────────────────────
    val s = "Hello"
    if (s != "Hello") { println("FAIL: string"); ok = false } else println("OK: string")

    // ── Null ──────────────────────────────────────────────────────────
    val n: String? = null
    if (n != null) { println("FAIL: null"); ok = false } else println("OK: null")

    // ── Hex Int ───────────────────────────────────────────────────────
    val hexVal: Int = 0xFF
    if (hexVal != 255) { println("FAIL: hexInt"); ok = false } else println("OK: hexInt")

    // ── Binary Int ────────────────────────────────────────────────────
    val binVal: Int = 0b1010
    if (binVal != 10) { println("FAIL: binInt"); ok = false } else println("OK: binInt")

    if (ok) {
        println("ALL OK")
    } else {
        println("SOME FAILED")
    }
}

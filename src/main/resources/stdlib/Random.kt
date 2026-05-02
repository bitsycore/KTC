package ktc

/**
Kotlin Random singleton implementation.
Delegates to C stdlib rand() / srand(), seeded once from the system clock.
Mirrors the Kotlin standard library Random API.

Method naming note: the transpiler does not support C-level overloading, so
variants with different arities use distinct names:
  nextInt(until)         — Kotlin: Random.nextInt(until)
  nextIntBetween(f, u)   — Kotlin: Random.nextInt(from, until)
  nextLong(until)        — Kotlin: Random.nextLong(until)
  nextLongBetween(f, u)  — Kotlin: Random.nextLong(from, until)
  nextDouble()           — Kotlin: Random.nextDouble()
  nextDoubleBetween(f,u) — Kotlin: Random.nextDouble(from, until)
*/
object Random {

	/***
	Seeded once at program start via srand(time(NULL)).
	All methods share this global PRNG state.
	*/
	init {
		c.ktc_srand(c.time(c.NULL))
	}

	/**
	Returns a non-negative random Int when called with no argument (until <= 0),
	or a random Int in [0, until) when until > 0.
	Matches Kotlin's Random.nextInt() and Random.nextInt(until: Int).
	*/
	fun nextInt(until: Int = 0): Int {
		if (until <= 0) return c.ktc_rand()
		return c.ktc_rand() % until
	}

	/**
	Returns a random Int in [from, until).
	Matches Kotlin's Random.nextInt(from: Int, until: Int).
	*/
	fun nextIntBetween(from: Int, until: Int): Int {
		return from + c.ktc_rand() % (until - from)
	}

	/**
	Returns a non-negative random Long when called with no argument (until <= 0L),
	or a random Long in [0, until) when until > 0L.
	Two rand() calls are combined so the result spans at least 30 bits even
	on platforms where KTC_RAND_MAX is only 32767.
	Matches Kotlin's Random.nextLong() and Random.nextLong(until: Long).
	*/
	fun nextLong(until: Long = 0L): Long {
		val vA: Long = c.ktc_rand().toLong()
		val vB: Long = c.ktc_rand().toLong()
		val vRaw: Long = vA * (c.KTC_RAND_MAX.toLong() + 1L) + vB
		if (until <= 0L) return vRaw
		return vRaw % until
	}

	/**
	Returns a random Long in [from, until).
	Matches Kotlin's Random.nextLong(from: Long, until: Long).
	*/
	fun nextLongBetween(from: Long, until: Long): Long {
		val vA: Long = c.ktc_rand().toLong()
		val vB: Long = c.ktc_rand().toLong()
		val vRaw: Long = vA * (c.KTC_RAND_MAX.toLong() + 1L) + vB
		return from + vRaw % (until - from)
	}

	/**
	Returns a random Float uniformly distributed in [0.0, 1.0).
	Matches Kotlin's Random.nextFloat().
	*/
	fun nextFloat(): Float {
		return c.ktc_rand().toFloat() / (c.KTC_RAND_MAX.toFloat() + 1.0f)
	}

	/**
	Returns a random Double uniformly distributed in [0.0, 1.0).
	Matches Kotlin's Random.nextDouble().
	*/
	fun nextDouble(): Double {
		return c.ktc_rand().toDouble() / (c.KTC_RAND_MAX.toDouble() + 1.0)
	}

	/**
	Returns a random Double in [from, until).
	Matches Kotlin's Random.nextDouble(from: Double, until: Double).
	*/
	fun nextDoubleBetween(from: Double, until: Double): Double {
		val vRaw: Double = c.ktc_rand().toDouble() / (c.KTC_RAND_MAX.toDouble() + 1.0)
		return from + vRaw * (until - from)
	}

	/**
	Returns a random Boolean.
	Matches Kotlin's Random.nextBoolean().
	*/
	fun nextBoolean(): Boolean {
		return c.ktc_rand() % 2 != 0
	}
}

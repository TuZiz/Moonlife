package ym.moonlife.core

import kotlin.random.Random

data class IntRangeRule(val min: Int, val max: Int) {
    init {
        require(min <= max) { "min must be <= max" }
    }

    fun contains(value: Int): Boolean = value in min..max

    fun random(random: Random = Random.Default): Int = if (min == max) min else random.nextInt(min, max + 1)

    companion object {
        val ANY_Y = IntRangeRule(-64, 320)
        val ANY_LIGHT = IntRangeRule(0, 15)
    }
}

data class DoubleRangeRule(val min: Double, val max: Double) {
    init {
        require(min <= max) { "min must be <= max" }
    }

    fun contains(value: Double): Boolean = value in min..max
}

data class AmountRange(val min: Int, val max: Int) {
    init {
        require(min > 0) { "min must be positive" }
        require(max >= min) { "max must be >= min" }
    }

    fun random(random: Random = Random.Default): Int = if (min == max) min else random.nextInt(min, max + 1)
}

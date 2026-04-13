package ym.moonlife.util

import kotlin.random.Random

class WeightedPool<T>(entries: Collection<Pair<T, Int>>) {
    private val cumulative: List<Pair<T, Int>>
    private val totalWeight: Int

    init {
        var total = 0
        cumulative = entries
            .asSequence()
            .filter { it.second > 0 }
            .map {
                total += it.second
                it.first to total
            }
            .toList()
        totalWeight = total
    }

    val isEmpty: Boolean get() = cumulative.isEmpty()

    fun pick(random: Random = Random.Default): T? {
        if (cumulative.isEmpty()) return null
        val value = random.nextInt(totalWeight) + 1
        return cumulative.first { value <= it.second }.first
    }
}

package ym.moonlife.spawn

import ym.moonlife.core.EcologyContext
import ym.moonlife.util.WeightedPool
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class SpawnRuleCounters(
    val nearbyPlayers: Int,
    val chunkCount: Int,
    val worldCount: Int,
    val cooldownReady: Boolean
)

class SpawnRuleEngine(
    rules: List<SpawnRule>,
    private val targetResolver: SpawnTargetResolver
) {
    private val enabledRules = rules.filter { it.enabled && it.weight > 0 }
    private val rulesByWorld = enabledRules
        .flatMap { rule ->
            if (rule.worlds.isEmpty()) listOf("*" to rule) else rule.worlds.map { it to rule }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, value) -> value.sortedWith(compareByDescending<SpawnRule> { it.priority }.thenBy { it.id }) }
    private val staticCandidateCache = ConcurrentHashMap<SpawnEnvironmentKey, List<SpawnRule>>()

    fun choose(
        context: EcologyContext,
        countersProvider: (SpawnRule) -> SpawnRuleCounters,
        random: Random = Random.Default
    ): SpawnRule? {
        val staticCandidates = staticCandidates(context).filter { targetResolver.isAvailable(it.target) }
        if (staticCandidates.isEmpty()) return null

        val finalCandidates = staticCandidates.filter { rule ->
            val counters = countersProvider(rule)
            counters.cooldownReady &&
                (rule.nearbyPlayerLimit <= 0 || counters.nearbyPlayers <= rule.nearbyPlayerLimit) &&
                (rule.chunkLimit <= 0 || counters.chunkCount < rule.chunkLimit) &&
                (rule.worldLimit <= 0 || counters.worldCount < rule.worldLimit)
        }
        if (finalCandidates.isEmpty()) return null
        return WeightedPool(finalCandidates.map { it to it.weight }).pick(random)
    }

    fun preview(context: EcologyContext): List<SpawnRule> =
        staticCandidates(context)
            .filter { targetResolver.isAvailable(it.target) }

    private fun staticCandidates(context: EcologyContext): List<SpawnRule> {
        val key = SpawnEnvironmentKey.from(context)
        return staticCandidateCache.computeIfAbsent(key) {
            if (staticCandidateCache.size > MAX_CACHE_SIZE) staticCandidateCache.clear()
            rulesForWorld(context.snapshot.worldName).filter { matchesEnvironment(it, context) }
        }
    }

    private fun rulesForWorld(worldName: String): List<SpawnRule> {
        val key = worldName.lowercase(Locale.ROOT)
        return (rulesByWorld["*"].orEmpty() + rulesByWorld[key].orEmpty()).distinctBy { it.id }
    }

    private fun matchesEnvironment(rule: SpawnRule, context: EcologyContext): Boolean {
        if (rule.worlds.isNotEmpty() && !rule.worlds.contains(context.snapshot.worldName.lowercase(Locale.ROOT))) return false
        if (rule.biomes.isNotEmpty() && !rule.biomes.contains(context.biome)) return false
        if (rule.moonPhases.isNotEmpty() && !rule.moonPhases.contains(context.snapshot.moonPhase)) return false
        if (rule.solarPhases.isNotEmpty() && !rule.solarPhases.contains(context.snapshot.solarPhase)) return false
        if (rule.weather.isNotEmpty() && !rule.weather.contains(context.snapshot.weather)) return false
        if (!rule.yRange.contains(context.y)) return false
        if (!rule.lightRange.contains(context.blockLight)) return false
        if (rule.wildernessOnly && !context.wilderness) return false
        return when (rule.terrain) {
            TerrainMode.ANY -> true
            TerrainMode.SURFACE -> !context.underground
            TerrainMode.UNDERGROUND -> context.underground
        }
    }

    private data class SpawnEnvironmentKey(
        val world: String,
        val biome: String,
        val moon: String,
        val solar: String,
        val weather: String,
        val y: Int,
        val light: Int,
        val underground: Boolean,
        val wilderness: Boolean
    ) {
        companion object {
            fun from(context: EcologyContext): SpawnEnvironmentKey =
                SpawnEnvironmentKey(
                    world = context.snapshot.worldName.lowercase(Locale.ROOT),
                    biome = context.biome.key.toString(),
                    moon = context.snapshot.moonPhase.name,
                    solar = context.snapshot.solarPhase.name,
                    weather = context.snapshot.weather.name,
                    y = context.y,
                    light = context.blockLight,
                    underground = context.underground,
                    wilderness = context.wilderness
                )
        }
    }

    companion object {
        private const val MAX_CACHE_SIZE = 4096
    }
}

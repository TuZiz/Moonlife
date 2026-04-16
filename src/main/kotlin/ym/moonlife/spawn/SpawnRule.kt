package ym.moonlife.spawn

import org.bukkit.block.Biome
import ym.moonlife.core.AmountRange
import ym.moonlife.core.IntRangeRule
import ym.moonlife.core.WeatherState
import ym.moonlife.moon.MoonPhase
import ym.moonlife.solar.SolarPhase

data class SpawnRule(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val target: SpawnTarget,
    val targetDisplayName: String,
    val worlds: Set<String>,
    val biomes: Set<Biome>,
    val moonPhases: Set<MoonPhase>,
    val solarPhases: Set<SolarPhase>,
    val weather: Set<WeatherState>,
    val yRange: IntRangeRule,
    val lightRange: IntRangeRule,
    val terrain: TerrainMode,
    val wildernessOnly: Boolean,
    val nearbyPlayerLimit: Int,
    val nearbyPlayerRadius: Double,
    val chunkLimit: Int,
    val worldLimit: Int,
    val cooldownTicks: Long,
    val weight: Int,
    val amount: AmountRange,
    val tags: Set<String>,
    val effects: Set<String>,
    val priority: Int
)

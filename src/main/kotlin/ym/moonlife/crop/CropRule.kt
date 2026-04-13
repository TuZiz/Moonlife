package ym.moonlife.crop

import org.bukkit.Material
import org.bukkit.block.Biome
import ym.moonlife.core.IntRangeRule
import ym.moonlife.core.WeatherState
import ym.moonlife.moon.MoonPhase
import ym.moonlife.solar.SolarPhase

data class CropRule(
    val id: String,
    val enabled: Boolean,
    val cropTypes: Set<Material>,
    val worlds: Set<String>,
    val biomes: Set<Biome>,
    val moonPhases: Set<MoonPhase>,
    val solarPhases: Set<SolarPhase>,
    val weather: Set<WeatherState>,
    val lightRange: IntRangeRule,
    val soilTypes: Set<Material>,
    val moistureRange: IntRangeRule,
    val growthMultiplier: Double,
    val bonusGrowthChance: Double,
    val extraHarvestChance: Double,
    val mutationChance: Double,
    val mutationMaterial: Material?,
    val boneMealInteraction: Boolean,
    val automationApply: Boolean,
    val priority: Int
)

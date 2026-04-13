package ym.moonlife.buff

import org.bukkit.block.Biome
import ym.moonlife.core.WeatherState
import ym.moonlife.moon.MoonPhase
import ym.moonlife.solar.SolarPhase

data class BuffRule(
    val id: String,
    val enabled: Boolean,
    val worlds: Set<String>,
    val biomes: Set<Biome>,
    val moonPhases: Set<MoonPhase>,
    val solarPhases: Set<SolarPhase>,
    val weather: Set<WeatherState>,
    val wildernessOnly: Boolean,
    val underground: Boolean?,
    val inWater: Boolean?,
    val sneaking: Boolean?,
    val permission: String?,
    val potions: List<PotionBuff>,
    val attributes: List<AttributeBuff>,
    val damageMultiplier: Double,
    val experienceMultiplier: Double,
    val dropMultiplier: Double,
    val movementSpeedModifier: Double,
    val attackModifier: Double,
    val resistanceModifier: Double,
    val customTags: Set<String>,
    val conflictStrategy: BuffConflictStrategy,
    val priority: Int
)

data class BuffPlan(
    val ruleIds: Set<String>,
    val potions: List<PotionBuff>,
    val attributes: List<AttributeBuff>,
    val outgoingDamageMultiplier: Double,
    val incomingDamageMultiplier: Double,
    val experienceMultiplier: Double,
    val dropMultiplier: Double,
    val customTags: Set<String>
) {
    companion object {
        val EMPTY = BuffPlan(emptySet(), emptyList(), emptyList(), 1.0, 1.0, 1.0, 1.0, emptySet())
    }
}

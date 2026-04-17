package ym.moonlife.feature

import org.bukkit.Material
import org.bukkit.block.Biome
import ym.moonlife.core.WeatherState
import ym.moonlife.item.CustomItemSpec
import ym.moonlife.moon.MoonPhase
import ym.moonlife.solar.SolarPhase

data class FeatureConfig(
    val calendarDays: Int,
    val debugBossBarRefreshTicks: Long,
    val newPlayerProtectionTicks: Int,
    val spawnProtectionRadius: Double,
    val bountyRules: List<BountyRule>,
    val materials: List<EcologyMaterial>,
    val altarRules: List<AltarRule>,
    val hotspotRules: List<HotspotRule>,
    val achievementRules: List<AchievementRule>,
    val eventPresets: List<EventPreset>,
    val worldTemplates: Map<String, List<String>>
)

data class BountyRule(
    val id: String,
    val displayName: String,
    val objective: BountyObjective,
    val targets: Set<String>,
    val moonPhases: Set<MoonPhase>,
    val solarPhases: Set<SolarPhase>,
    val weather: Set<WeatherState>,
    val mythicMobIds: Set<String>,
    val rewardExp: Int,
    val rewardMaterials: List<String>,
    val codexEntry: String
)

enum class BountyObjective {
    KILL_MYTHIC,
    KILL_ENTITY,
    BREAK_BLOCK,
    HARVEST_CROP,
    VISIT_BIOME
}

data class EcologyMaterial(
    val id: String,
    val displayName: String,
    val material: Material,
    val source: String,
    val item: CustomItemSpec
)

data class AltarRule(
    val id: String,
    val displayName: String,
    val block: Material,
    val cost: CustomItemSpec,
    val costAmount: Int,
    val moonPhases: Set<MoonPhase>,
    val solarPhases: Set<SolarPhase>,
    val weather: Set<WeatherState>,
    val eventId: String,
    val eventMinutes: Int,
    val eventMultiplier: Double,
    val rewardExp: Int,
    val rewardMaterials: List<String>,
    val codexEntry: String?
)

data class HotspotRule(
    val id: String,
    val displayName: String,
    val worlds: Set<String>,
    val biomes: Set<Biome>,
    val center: HotspotCenter?,
    val radius: Double,
    val moonPhases: Set<MoonPhase>,
    val solarPhases: Set<SolarPhase>,
    val weather: Set<WeatherState>,
    val multiplier: Double
)

data class HotspotCenter(
    val mode: HotspotCenterMode,
    val x: Double,
    val y: Double,
    val z: Double,
    val useY: Boolean
)

enum class HotspotCenterMode {
    SPAWN,
    COORDINATE
}

data class AchievementRule(
    val id: String,
    val displayName: String,
    val requiredCodex: Set<String>,
    val rewardExp: Int,
    val rewardMaterials: List<String>,
    val title: String
)

data class EventPreset(
    val id: String,
    val displayName: String,
    val multiplier: Double,
    val defaultMinutes: Int
)

data class ActiveEcologyEvent(
    val id: String,
    val displayName: String,
    val multiplier: Double,
    val endsAtMillis: Long
) {
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Long =
        ((endsAtMillis - now) / 1000L).coerceAtLeast(0L)
}

data class RuleStat(
    val ruleId: String,
    val spawns: Long,
    val skips: Long,
    val lastSeenMillis: Long
)

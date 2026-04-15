package ym.moonlife.config

import ym.moonlife.buff.BuffRule
import ym.moonlife.crop.CropRule
import ym.moonlife.moon.MoonPhase
import ym.moonlife.solar.SolarPhase
import ym.moonlife.solar.SolarPhaseWindow
import ym.moonlife.spawn.SpawnRule
import ym.moonlife.util.PerformanceLimits

data class ConfigBundle(
    val main: MainConfig,
    val moon: MoonConfig,
    val solar: SolarConfig,
    val spawnRules: List<SpawnRule>,
    val cropRules: List<CropRule>,
    val buffRules: List<BuffRule>
)

data class MainConfig(
    val debug: Boolean,
    val phaseCheckIntervalTicks: Long,
    val spawn: SpawnRuntimeConfig,
    val crop: CropRuntimeConfig,
    val buff: BuffRuntimeConfig,
    val wilderness: WildernessConfig,
    val performance: PerformanceLimits
)

data class SpawnRuntimeConfig(
    val enabled: Boolean,
    val mythicMobsOnly: Boolean,
    val intervalTicks: Long,
    val maxPlayerSamplesPerCycle: Int,
    val attemptsPerPlayer: Int,
    val spawnRadiusMin: Int,
    val spawnRadiusMax: Int,
    val cleanupIntervalTicks: Long
)

data class CropRuntimeConfig(
    val enabled: Boolean,
    val maxBonusStepsPerEvent: Int
)

data class BuffRuntimeConfig(
    val enabled: Boolean,
    val refreshIntervalTicks: Long,
    val potionRefreshThresholdTicks: Int
)

data class WildernessConfig(
    val enabled: Boolean,
    val respectWorldGuard: Boolean,
    val respectResidencePlugins: Boolean,
    val disabledWorlds: Set<String>
)

data class MoonConfig(
    val enabledWorlds: Set<String>,
    val disabledWorlds: Set<String>,
    val broadcastChanges: Boolean,
    val actionBarChanges: Boolean,
    val bossBarChanges: Boolean,
    val titleChanges: Boolean
)

data class SolarConfig(
    val enabledWorlds: Set<String>,
    val disabledWorlds: Set<String>,
    val windows: List<SolarPhaseWindow>,
    val broadcastChanges: Boolean,
    val actionBarChanges: Boolean,
    val bossBarChanges: Boolean,
    val titleChanges: Boolean
)

data class ReloadResult(
    val success: Boolean,
    val errors: List<String>
)

data class PhaseAssignments(
    val moonMonsters: Map<String, Set<MoonPhase>> = emptyMap(),
    val moonCrops: Map<String, Set<MoonPhase>> = emptyMap(),
    val moonBuffs: Map<String, Set<MoonPhase>> = emptyMap(),
    val moonAltars: Map<String, Set<MoonPhase>> = emptyMap(),
    val moonHotspots: Map<String, Set<MoonPhase>> = emptyMap(),
    val solarMonsters: Map<String, Set<SolarPhase>> = emptyMap(),
    val solarCrops: Map<String, Set<SolarPhase>> = emptyMap(),
    val solarBuffs: Map<String, Set<SolarPhase>> = emptyMap(),
    val solarAltars: Map<String, Set<SolarPhase>> = emptyMap(),
    val solarHotspots: Map<String, Set<SolarPhase>> = emptyMap()
)

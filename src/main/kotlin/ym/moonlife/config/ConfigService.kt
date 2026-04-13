package ym.moonlife.config

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.moonlife.buff.AttributeBuff
import ym.moonlife.buff.BuffConflictStrategy
import ym.moonlife.buff.BuffRule
import ym.moonlife.buff.PotionBuff
import ym.moonlife.core.AmountRange
import ym.moonlife.core.IntRangeRule
import ym.moonlife.core.WeatherState
import ym.moonlife.crop.CropRule
import ym.moonlife.moon.MoonPhase
import ym.moonlife.solar.SolarPhase
import ym.moonlife.solar.SolarPhaseWindow
import ym.moonlife.spawn.MythicSpawnTarget
import ym.moonlife.spawn.SpawnBackend
import ym.moonlife.spawn.SpawnRule
import ym.moonlife.spawn.TerrainMode
import ym.moonlife.spawn.VanillaSpawnTarget
import ym.moonlife.util.ConfigReaders
import ym.moonlife.util.PerformanceLimits
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class ConfigService(private val plugin: JavaPlugin) {
    private val currentRef = AtomicReference<ConfigBundle>()
    val current: ConfigBundle get() = currentRef.get() ?: error("Configuration is not loaded")

    private val defaultFiles = listOf(
        "config.yml",
        "messages.yml",
        "moon-phases.yml",
        "solar-phases.yml",
        "spawn-rules.yml",
        "crop-rules.yml",
        "buff-rules.yml"
    )

    fun loadInitial() {
        val result = reload()
        if (!result.success) {
            result.errors.forEach { plugin.logger.severe(it) }
            error("Moonlife configuration failed to load")
        }
    }

    fun reload(): ReloadResult {
        ensureDefaults()
        val errors = mutableListOf<String>()
        val loaded = runCatching {
            val config = loadYaml("config.yml")
            val moon = loadYaml("moon-phases.yml")
            val solar = loadYaml("solar-phases.yml")
            val spawn = loadYaml("spawn-rules.yml")
            val crop = loadYaml("crop-rules.yml")
            val buff = loadYaml("buff-rules.yml")
            ConfigBundle(
                main = parseMain(config, errors),
                moon = parseMoon(moon, errors),
                solar = parseSolar(solar, errors),
                spawnRules = parseSpawnRules(spawn, errors),
                cropRules = parseCropRules(crop, errors),
                buffRules = parseBuffRules(buff, errors)
            )
        }.getOrElse { throwable ->
            errors += "Configuration parse exception: ${throwable.message}"
            null
        }

        if (errors.isNotEmpty() || loaded == null) {
            return ReloadResult(success = false, errors = errors)
        }
        currentRef.set(loaded)
        return ReloadResult(success = true, errors = emptyList())
    }

    private fun ensureDefaults() {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        defaultFiles.forEach { fileName ->
            val file = File(plugin.dataFolder, fileName)
            if (!file.exists()) {
                plugin.saveResource(fileName, false)
            }
        }
    }

    private fun loadYaml(name: String): YamlConfiguration =
        YamlConfiguration.loadConfiguration(File(plugin.dataFolder, name))

    private fun parseMain(config: YamlConfiguration, errors: MutableList<String>): MainConfig =
        MainConfig(
            debug = config.getBoolean("debug", false),
            phaseCheckIntervalTicks = config.getLong("phase-check-interval-ticks", 1200L).coerceAtLeast(20L),
            spawn = SpawnRuntimeConfig(
                enabled = config.getBoolean("spawn.enabled", true),
                mythicMobsOnly = config.getBoolean("spawn.mythic-mobs-only", true),
                intervalTicks = config.getLong("spawn.interval-ticks", 200L).coerceAtLeast(20L),
                maxPlayerSamplesPerCycle = config.getInt("spawn.max-player-samples-per-cycle", 24).coerceAtLeast(1),
                attemptsPerPlayer = config.getInt("spawn.attempts-per-player", 2).coerceIn(1, 16),
                spawnRadiusMin = config.getInt("spawn.spawn-radius.min", 24).coerceAtLeast(1),
                spawnRadiusMax = config.getInt("spawn.spawn-radius.max", 72).coerceAtLeast(2),
                cleanupIntervalTicks = config.getLong("spawn.cleanup-interval-ticks", 1200L).coerceAtLeast(200L)
            ).also {
                if (it.spawnRadiusMin > it.spawnRadiusMax) {
                    errors += "config.yml: spawn.spawn-radius.min must not be greater than max"
                }
            },
            crop = CropRuntimeConfig(
                enabled = config.getBoolean("crop.enabled", true),
                maxBonusStepsPerEvent = config.getInt("crop.max-bonus-steps-per-event", 2).coerceIn(0, 8)
            ),
            buff = BuffRuntimeConfig(
                enabled = config.getBoolean("buff.enabled", true),
                refreshIntervalTicks = config.getLong("buff.refresh-interval-ticks", 100L).coerceAtLeast(20L),
                potionRefreshThresholdTicks = config.getInt("buff.potion-refresh-threshold-ticks", 80).coerceAtLeast(20)
            ),
            wilderness = WildernessConfig(
                enabled = config.getBoolean("wilderness.enabled", true),
                respectWorldGuard = config.getBoolean("wilderness.respect-worldguard", true),
                respectResidencePlugins = config.getBoolean("wilderness.respect-residence-plugins", true),
                disabledWorlds = ConfigReaders.stringSet(config, "wilderness.disabled-worlds")
            ),
            performance = PerformanceLimits(
                enabled = config.getBoolean("performance.enabled", true),
                minTps = config.getDouble("performance.min-tps", 18.5),
                maxMspt = config.getDouble("performance.max-mspt", 55.0)
            )
        )

    private fun parseMoon(config: YamlConfiguration, errors: MutableList<String>): MoonConfig =
        MoonConfig(
            enabledWorlds = ConfigReaders.stringSet(config, "worlds.enabled"),
            disabledWorlds = ConfigReaders.stringSet(config, "worlds.disabled"),
            broadcastChanges = config.getBoolean("events.broadcast", true),
            actionBarChanges = config.getBoolean("events.actionbar", true),
            bossBarChanges = config.getBoolean("events.bossbar", false),
            titleChanges = config.getBoolean("events.title", false)
        )

    private fun parseSolar(config: YamlConfiguration, errors: MutableList<String>): SolarConfig {
        val windows = SolarPhase.entries.mapNotNull { phase ->
            val key = "phases.${phase.name.lowercase(Locale.ROOT)}"
            val start = config.getLong("$key.start", Long.MIN_VALUE)
            val end = config.getLong("$key.end", Long.MIN_VALUE)
            if (start == Long.MIN_VALUE || end == Long.MIN_VALUE) {
                errors += "solar-phases.yml: missing $key.start/end"
                null
            } else {
                SolarPhaseWindow(phase, Math.floorMod(start, 24000L), Math.floorMod(end, 24000L))
            }
        }
        return SolarConfig(
            enabledWorlds = ConfigReaders.stringSet(config, "worlds.enabled"),
            disabledWorlds = ConfigReaders.stringSet(config, "worlds.disabled"),
            windows = windows,
            broadcastChanges = config.getBoolean("events.broadcast", true),
            actionBarChanges = config.getBoolean("events.actionbar", false),
            bossBarChanges = config.getBoolean("events.bossbar", false),
            titleChanges = config.getBoolean("events.title", false)
        )
    }

    private fun parseSpawnRules(config: YamlConfiguration, errors: MutableList<String>): List<SpawnRule> =
        parseRuleSections(config, "rules").mapNotNull { (id, section) ->
            val backend = ConfigReaders.valueOfEnum<SpawnBackend>(section.getString("spawn-backend"))
            if (backend == null) {
                errors += "spawn-rules.yml: $id has missing or invalid spawn-backend"
                return@mapNotNull null
            }
            val target = when (backend) {
                SpawnBackend.VANILLA -> {
                    val type = ConfigReaders.entityType(section, "vanilla-entity")
                    if (type == null) {
                        errors += "spawn-rules.yml: $id uses VANILLA but vanilla-entity is invalid"
                        return@mapNotNull null
                    }
                    VanillaSpawnTarget(type)
                }
                SpawnBackend.MYTHIC_MOB -> {
                    val mobId = section.getString("mythic-mob-id")?.trim().orEmpty()
                    if (mobId.isEmpty()) {
                        errors += "spawn-rules.yml: $id uses MYTHIC_MOB but mythic-mob-id is empty"
                        return@mapNotNull null
                    }
                    MythicSpawnTarget(mobId)
                }
            }
            SpawnRule(
                id = section.getString("id", id) ?: id,
                enabled = section.getBoolean("enable", true),
                target = target,
                worlds = ConfigReaders.stringSet(section, "worlds"),
                biomes = ConfigReaders.biomeSet(section, "biomes"),
                moonPhases = ConfigReaders.enumSet(section, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(section, "solar-phases"),
                weather = ConfigReaders.enumSet(section, "weather"),
                yRange = ConfigReaders.intRange(section, "y-range", IntRangeRule.ANY_Y),
                lightRange = ConfigReaders.intRange(section, "light-range", IntRangeRule.ANY_LIGHT),
                terrain = ConfigReaders.valueOfEnum<TerrainMode>(section.getString("terrain"))
                    ?: when {
                        section.getBoolean("surface", false) -> TerrainMode.SURFACE
                        section.getBoolean("underground", false) -> TerrainMode.UNDERGROUND
                        else -> TerrainMode.ANY
                    },
                wildernessOnly = section.getBoolean("wilderness-only", true),
                nearbyPlayerLimit = section.getInt("nearby-player-limit", 6).coerceAtLeast(0),
                nearbyPlayerRadius = section.getDouble("nearby-player-radius", 48.0).coerceAtLeast(1.0),
                chunkLimit = section.getInt("chunk-limit", 4).coerceAtLeast(0),
                worldLimit = section.getInt("world-limit", 64).coerceAtLeast(0),
                cooldownTicks = section.getLong("cooldown", 600L).coerceAtLeast(0L),
                weight = section.getInt("weight", 10).coerceAtLeast(0),
                amount = ConfigReaders.amountRange(section, "amount", AmountRange(1, 1)),
                tags = section.getStringList("tags").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                effects = section.getStringList("effects").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                priority = section.getInt("priority", 0)
            )
        }.sortedWith(compareByDescending<SpawnRule> { it.priority }.thenBy { it.id })

    private fun parseCropRules(config: YamlConfiguration, errors: MutableList<String>): List<CropRule> =
        parseRuleSections(config, "rules").map { (id, section) ->
            CropRule(
                id = section.getString("id", id) ?: id,
                enabled = section.getBoolean("enable", true),
                cropTypes = ConfigReaders.materialSet(section, "crop-types"),
                worlds = ConfigReaders.stringSet(section, "worlds"),
                biomes = ConfigReaders.biomeSet(section, "biomes"),
                moonPhases = ConfigReaders.enumSet(section, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(section, "solar-phases"),
                weather = ConfigReaders.enumSet(section, "weather"),
                lightRange = ConfigReaders.intRange(section, "light-range", IntRangeRule.ANY_LIGHT),
                soilTypes = ConfigReaders.materialSet(section, "soil-types"),
                moistureRange = ConfigReaders.intRange(section, "moisture", IntRangeRule(0, 7)),
                growthMultiplier = section.getDouble("growth-multiplier", 1.0).coerceAtLeast(0.0),
                bonusGrowthChance = section.getDouble("bonus-growth-chance", 0.0).coerceIn(0.0, 1.0),
                extraHarvestChance = section.getDouble("extra-harvest-chance", 0.0).coerceIn(0.0, 1.0),
                mutationChance = section.getDouble("mutation-chance", 0.0).coerceIn(0.0, 1.0),
                mutationMaterial = section.getString("mutation-material")?.let { Material.matchMaterial(it) },
                boneMealInteraction = section.getBoolean("bone-meal-interaction", true),
                automationApply = section.getBoolean("automation-apply", true),
                priority = section.getInt("priority", 0)
            )
        }.sortedWith(compareByDescending<CropRule> { it.priority }.thenBy { it.id })

    private fun parseBuffRules(config: YamlConfiguration, errors: MutableList<String>): List<BuffRule> =
        parseRuleSections(config, "rules").map { (id, section) ->
            BuffRule(
                id = section.getString("id", id) ?: id,
                enabled = section.getBoolean("enable", true),
                worlds = ConfigReaders.stringSet(section, "worlds"),
                biomes = ConfigReaders.biomeSet(section, "biomes"),
                moonPhases = ConfigReaders.enumSet(section, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(section, "solar-phases"),
                weather = ConfigReaders.enumSet(section, "weather"),
                wildernessOnly = section.getBoolean("wilderness-only", false),
                underground = section.optionalBoolean("underground"),
                inWater = section.optionalBoolean("in-water"),
                sneaking = section.optionalBoolean("sneaking"),
                permission = section.getString("permission")?.takeIf { it.isNotBlank() },
                potions = parsePotionBuffs(section.getConfigurationSection("potion-effects")),
                attributes = parseAttributeBuffs(section.getConfigurationSection("attribute-modifiers")),
                damageMultiplier = section.getDouble("damage-multiplier", 1.0).coerceAtLeast(0.0),
                experienceMultiplier = section.getDouble("experience-multiplier", 1.0).coerceAtLeast(0.0),
                dropMultiplier = section.getDouble("drop-multiplier", 1.0).coerceAtLeast(0.0),
                movementSpeedModifier = section.getDouble("movement-speed-modifier", 0.0),
                attackModifier = section.getDouble("attack-modifier", 0.0),
                resistanceModifier = section.getDouble("resistance-modifier", 0.0),
                customTags = section.getStringList("custom-tags").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                conflictStrategy = ConfigReaders.valueOfEnum<BuffConflictStrategy>(section.getString("conflict-strategy"))
                    ?: BuffConflictStrategy.PRIORITY,
                priority = section.getInt("priority", 0)
            )
        }.sortedWith(compareByDescending<BuffRule> { it.priority }.thenBy { it.id })

    private fun parsePotionBuffs(section: ConfigurationSection?): List<PotionBuff> {
        section ?: return emptyList()
        return section.getKeys(false).mapNotNull { key ->
            val child = section.getConfigurationSection(key) ?: return@mapNotNull null
            PotionBuff(
                type = child.getString("type", key) ?: key,
                amplifier = child.getInt("amplifier", 0).coerceAtLeast(0),
                durationTicks = child.getInt("duration-ticks", 220).coerceAtLeast(40),
                ambient = child.getBoolean("ambient", true),
                particles = child.getBoolean("particles", false),
                icon = child.getBoolean("icon", true)
            )
        }
    }

    private fun parseAttributeBuffs(section: ConfigurationSection?): List<AttributeBuff> {
        section ?: return emptyList()
        return section.getKeys(false).mapNotNull { key ->
            val child = section.getConfigurationSection(key) ?: return@mapNotNull null
            AttributeBuff(
                attribute = child.getString("attribute", key) ?: key,
                amount = child.getDouble("amount", 0.0),
                operation = child.getString("operation", "ADD_NUMBER") ?: "ADD_NUMBER"
            )
        }
    }

    private fun parseRuleSections(config: YamlConfiguration, path: String): List<Pair<String, ConfigurationSection>> {
        val root = config.getConfigurationSection(path) ?: return emptyList()
        return root.getKeys(false).mapNotNull { key ->
            val section = root.getConfigurationSection(key) ?: return@mapNotNull null
            key to section
        }
    }

    private fun ConfigurationSection.optionalBoolean(path: String): Boolean? =
        if (contains(path)) getBoolean(path) else null
}

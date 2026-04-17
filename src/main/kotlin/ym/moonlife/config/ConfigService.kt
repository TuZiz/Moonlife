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
import ym.moonlife.item.CustomItemDrop
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
    private val phaseAssignmentsRef = AtomicReference(PhaseAssignments())
    val current: ConfigBundle get() = currentRef.get() ?: error("Configuration is not loaded")
    val phaseAssignments: PhaseAssignments get() = phaseAssignmentsRef.get()

    private val defaultFiles = listOf(
        "config.yml",
        "monsters.yml",
        "altars.yml",
        "crops.yml",
        "buffs.yml",
        "moon-phases/新月.yml",
        "moon-phases/峨眉月.yml",
        "moon-phases/上弦月.yml",
        "moon-phases/盈凸月.yml",
        "moon-phases/满月.yml",
        "moon-phases/亏凸月.yml",
        "moon-phases/下弦月.yml",
        "moon-phases/残月.yml",
        "solar-phases/黎明.yml",
        "solar-phases/白昼.yml",
        "solar-phases/黄昏.yml",
        "solar-phases/夜晚.yml",
        "solar-phases/午夜.yml",
        "lang/zh_cn.yml"
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
            val config = loadYamlPreferred("config.yml", "settings/config.yml")
            val moon = config.getConfigurationSection("moon") ?: loadYamlPreferred("moon-phases/settings.yml", "moon-phases.yml")
            val solar = config.getConfigurationSection("solar") ?: loadYamlPreferred("solar-phases/settings.yml", "solar-phases.yml")
            val spawn = loadYamlPreferred("monsters.yml", "spawn-rules.yml")
            val crop = loadYamlPreferred("crops.yml", "crop-rules.yml")
            val buff = loadYamlPreferred("buffs.yml", "buff-rules.yml")
            val assignments = loadPhaseAssignments()
            ConfigBundle(
                main = parseMain(config, errors),
                moon = parseMoon(moon, errors),
                solar = parseSolar(solar, errors),
                spawnRules = applySpawnAssignments(parseSpawnRules(spawn, errors), assignments),
                cropRules = applyCropAssignments(parseCropRules(crop, errors), assignments),
                buffRules = applyBuffAssignments(parseBuffRules(buff, errors), assignments)
            ).also {
                phaseAssignmentsRef.set(assignments)
            }
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

    private fun loadYamlPreferred(primary: String, legacy: String): YamlConfiguration {
        val primaryFile = File(plugin.dataFolder, primary)
        return if (primaryFile.exists()) {
            YamlConfiguration.loadConfiguration(primaryFile)
        } else {
            YamlConfiguration.loadConfiguration(File(plugin.dataFolder, legacy))
        }
    }

    private fun loadPhaseAssignments(): PhaseAssignments {
        val moonMonsters = mutableMapOf<String, MutableSet<MoonPhase>>()
        val moonCrops = mutableMapOf<String, MutableSet<MoonPhase>>()
        val moonBuffs = mutableMapOf<String, MutableSet<MoonPhase>>()
        val moonAltars = mutableMapOf<String, MutableSet<MoonPhase>>()
        val moonHotspots = mutableMapOf<String, MutableSet<MoonPhase>>()
        val moonMaterialDrops = mutableMapOf<String, MutableSet<MoonPhase>>()
        MoonPhase.entries.forEach { phase ->
            val yaml = phaseYaml("moon-phases/${moonFileName(phase)}.yml")
                ?: phaseYaml("moon-pgases/${moonFileName(phase)}.yml")
                ?: return@forEach
            if (!yaml.getBoolean("enable", true)) return@forEach
            addAssignments(moonMonsters, ids(yaml, "functions.monsters", "functions.怪物", "monsters", "怪物"), phase)
            addAssignments(moonCrops, ids(yaml, "functions.crops", "functions.作物", "crops", "作物"), phase)
            addAssignments(moonBuffs, ids(yaml, "functions.buffs", "functions.buff", "functions.增益", "buffs", "buff", "增益"), phase)
            addAssignments(moonAltars, ids(yaml, "functions.altars", "functions.祭坛", "altars", "祭坛"), phase)
            addAssignments(moonHotspots, ids(yaml, "functions.hotspots", "functions.热点", "hotspots", "热点"), phase)
            addAssignments(moonMaterialDrops, ids(yaml, "functions.material-drops", "functions.material_drops", "functions.材料掉落", "material-drops", "材料掉落"), phase)
        }

        val solarMonsters = mutableMapOf<String, MutableSet<SolarPhase>>()
        val solarCrops = mutableMapOf<String, MutableSet<SolarPhase>>()
        val solarBuffs = mutableMapOf<String, MutableSet<SolarPhase>>()
        val solarAltars = mutableMapOf<String, MutableSet<SolarPhase>>()
        val solarHotspots = mutableMapOf<String, MutableSet<SolarPhase>>()
        val solarMaterialDrops = mutableMapOf<String, MutableSet<SolarPhase>>()
        SolarPhase.entries.forEach { phase ->
            val yaml = phaseYaml("solar-phases/${solarFileName(phase)}.yml") ?: return@forEach
            if (!yaml.getBoolean("enable", true)) return@forEach
            addAssignments(solarMonsters, ids(yaml, "functions.monsters", "functions.怪物", "monsters", "怪物"), phase)
            addAssignments(solarCrops, ids(yaml, "functions.crops", "functions.作物", "crops", "作物"), phase)
            addAssignments(solarBuffs, ids(yaml, "functions.buffs", "functions.buff", "functions.增益", "buffs", "buff", "增益"), phase)
            addAssignments(solarAltars, ids(yaml, "functions.altars", "functions.祭坛", "altars", "祭坛"), phase)
            addAssignments(solarHotspots, ids(yaml, "functions.hotspots", "functions.热点", "hotspots", "热点"), phase)
            addAssignments(solarMaterialDrops, ids(yaml, "functions.material-drops", "functions.material_drops", "functions.材料掉落", "material-drops", "材料掉落"), phase)
        }

        return PhaseAssignments(
            moonMonsters = freeze(moonMonsters),
            moonCrops = freeze(moonCrops),
            moonBuffs = freeze(moonBuffs),
            moonAltars = freeze(moonAltars),
            moonHotspots = freeze(moonHotspots),
            moonMaterialDrops = freeze(moonMaterialDrops),
            solarMonsters = freeze(solarMonsters),
            solarCrops = freeze(solarCrops),
            solarBuffs = freeze(solarBuffs),
            solarAltars = freeze(solarAltars),
            solarHotspots = freeze(solarHotspots),
            solarMaterialDrops = freeze(solarMaterialDrops)
        )
    }

    private fun <P> addAssignments(target: MutableMap<String, MutableSet<P>>, ids: List<String>, phase: P) {
        ids.forEach { id ->
            target.computeIfAbsent(id.lowercase(Locale.ROOT)) { linkedSetOf() } += phase
        }
    }

    private fun <P> freeze(source: Map<String, Set<P>>): Map<String, Set<P>> =
        source.mapValues { it.value.toSet() }

    private fun ids(config: YamlConfiguration, vararg paths: String): List<String> =
        paths.flatMap { path ->
            when {
                config.isList(path) -> config.getStringList(path)
                config.isString(path) -> listOfNotNull(config.getString(path))
                else -> emptyList()
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun phaseYaml(relativePath: String): YamlConfiguration? {
        val file = File(plugin.dataFolder, relativePath)
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else null
    }

    private fun applySpawnAssignments(rules: List<SpawnRule>, assignments: PhaseAssignments): List<SpawnRule> =
        rules.map { rule ->
            rule.copy(
                moonPhases = merge(rule.moonPhases, assignments.moonMonsters[rule.id.lowercase(Locale.ROOT)]),
                solarPhases = merge(rule.solarPhases, assignments.solarMonsters[rule.id.lowercase(Locale.ROOT)])
            )
        }

    private fun applyCropAssignments(rules: List<CropRule>, assignments: PhaseAssignments): List<CropRule> =
        rules.map { rule ->
            rule.copy(
                moonPhases = merge(rule.moonPhases, assignments.moonCrops[rule.id.lowercase(Locale.ROOT)]),
                solarPhases = merge(rule.solarPhases, assignments.solarCrops[rule.id.lowercase(Locale.ROOT)])
            )
        }

    private fun applyBuffAssignments(rules: List<BuffRule>, assignments: PhaseAssignments): List<BuffRule> =
        rules.map { rule ->
            rule.copy(
                moonPhases = merge(rule.moonPhases, assignments.moonBuffs[rule.id.lowercase(Locale.ROOT)]),
                solarPhases = merge(rule.solarPhases, assignments.solarBuffs[rule.id.lowercase(Locale.ROOT)])
            )
        }

    private fun <P> merge(existing: Set<P>, assigned: Set<P>?): Set<P> =
        if (assigned.isNullOrEmpty()) existing else existing + assigned

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

    private fun parseMoon(config: ConfigurationSection, errors: MutableList<String>): MoonConfig =
        MoonConfig(
            enabledWorlds = ConfigReaders.stringSet(config, "worlds.enabled"),
            disabledWorlds = ConfigReaders.stringSet(config, "worlds.disabled"),
            broadcastChanges = config.getBoolean("events.broadcast", true),
            actionBarChanges = config.getBoolean("events.actionbar", true),
            bossBarChanges = config.getBoolean("events.bossbar", false),
            titleChanges = config.getBoolean("events.title", false),
            visibleOnlyMessages = config.getBoolean("events.visible-only", true),
            phaseMessages = MoonPhase.entries.associateWith { phase ->
                parsePhaseMessages(
                    phaseYaml("moon-phases/${moonFileName(phase)}.yml")
                        ?: phaseYaml("moon-pgases/${moonFileName(phase)}.yml")
                )
            }
        )

    private fun parseSolar(config: ConfigurationSection, errors: MutableList<String>): SolarConfig {
        val windows = loadSolarPhaseWindows(config, errors)
        return SolarConfig(
            enabledWorlds = ConfigReaders.stringSet(config, "worlds.enabled"),
            disabledWorlds = ConfigReaders.stringSet(config, "worlds.disabled"),
            windows = windows,
            broadcastChanges = config.getBoolean("events.broadcast", true),
            actionBarChanges = config.getBoolean("events.actionbar", false),
            bossBarChanges = config.getBoolean("events.bossbar", false),
            titleChanges = config.getBoolean("events.title", false),
            phaseMessages = SolarPhase.entries.associateWith { phase ->
                parsePhaseMessages(phaseYaml("solar-phases/${solarFileName(phase)}.yml"))
            }
        )
    }

    private fun parsePhaseMessages(config: YamlConfiguration?): PhaseMessageConfig {
        val section = config?.getConfigurationSection("messages")
        val featureIds = if (config == null) {
            emptyList()
        } else {
            ids(
                config,
                "functions.monsters", "functions.怪物", "monsters", "怪物",
                "functions.crops", "functions.作物", "crops", "作物",
                "functions.buffs", "functions.buff", "functions.增益", "buffs", "buff", "增益",
                "functions.altars", "functions.祭坛", "altars", "祭坛",
                "functions.hotspots", "functions.热点", "hotspots", "热点",
                "functions.material-drops", "functions.material_drops", "functions.材料掉落", "material-drops", "材料掉落"
            )
        }
        return PhaseMessageConfig(
            announce = section?.getBoolean("announce", true) ?: true,
            displayName = config?.getString("display-name"),
            broadcast = section?.getString("broadcast"),
            actionBar = section?.getString("actionbar") ?: section?.getString("action-bar"),
            title = section?.getString("title"),
            subtitle = section?.getString("subtitle"),
            bossBar = section?.getString("bossbar") ?: section?.getString("boss-bar"),
            featureLines = when {
                section?.isList("features") == true -> section.getStringList("features")
                section?.isList("feature-lines") == true -> section.getStringList("feature-lines")
                else -> emptyList()
            },
            featureIds = featureIds
        )
    }

    private fun loadSolarPhaseWindows(settings: ConfigurationSection, errors: MutableList<String>): List<SolarPhaseWindow> {
        val fromFiles = SolarPhase.entries.mapNotNull { phase ->
            val file = File(plugin.dataFolder, "solar-phases/${solarFileName(phase)}.yml")
            if (!file.exists()) return@mapNotNull null
            val yaml = YamlConfiguration.loadConfiguration(file)
            val start = yaml.getLong("time.start", Long.MIN_VALUE)
            val end = yaml.getLong("time.end", Long.MIN_VALUE)
            if (start == Long.MIN_VALUE || end == Long.MIN_VALUE) {
                errors += "${file.name}: missing time.start/time.end"
                null
            } else {
                SolarPhaseWindow(phase, Math.floorMod(start, 24000L), Math.floorMod(end, 24000L))
            }
        }
        if (fromFiles.size == SolarPhase.entries.size) return fromFiles

        return SolarPhase.entries.mapNotNull { phase ->
            val key = "phases.${phase.name.lowercase(Locale.ROOT)}"
            val start = settings.getLong("$key.start", Long.MIN_VALUE)
            val end = settings.getLong("$key.end", Long.MIN_VALUE)
            if (start == Long.MIN_VALUE || end == Long.MIN_VALUE) {
                errors += "solar phase config: missing $key.start/end or solar-phases/${solarFileName(phase)}.yml time.start/time.end"
                null
            } else {
                SolarPhaseWindow(phase, Math.floorMod(start, 24000L), Math.floorMod(end, 24000L))
            }
        }
    }

    private fun parseSpawnRules(config: YamlConfiguration, errors: MutableList<String>): List<SpawnRule> =
        parseRuleSections(config, "rules").mapNotNull { (id, section) ->
            val backend = ConfigReaders.valueOfEnum<SpawnBackend>(section.getString("spawn-backend"))
            if (backend == null) {
                errors += "monsters.yml: $id has missing or invalid spawn-backend"
                return@mapNotNull null
            }
            val target = when (backend) {
                SpawnBackend.VANILLA -> {
                    val type = ConfigReaders.entityType(section, "vanilla-entity")
                    if (type == null) {
                        errors += "monsters.yml: $id uses VANILLA but vanilla-entity is invalid"
                        return@mapNotNull null
                    }
                    VanillaSpawnTarget(type)
                }
                SpawnBackend.MYTHIC_MOB -> {
                    val mobId = section.getString("mythic-mob-id")?.trim().orEmpty()
                    if (mobId.isEmpty()) {
                        errors += "monsters.yml: $id uses MYTHIC_MOB but mythic-mob-id is empty"
                        return@mapNotNull null
                    }
                    MythicSpawnTarget(mobId)
                }
            }
            val ruleId = section.getString("id", id) ?: id
            val displayName = section.getString("display-name", ruleId) ?: ruleId
            SpawnRule(
                id = ruleId,
                displayName = displayName,
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
                extraHarvestDrops = CustomItemDrop.parseList(section, "extra-harvest-items", Material.AMETHYST_SHARD),
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

    private fun moonFileName(phase: MoonPhase): String = when (phase) {
        MoonPhase.NEW_MOON -> "新月"
        MoonPhase.WAXING_CRESCENT -> "峨眉月"
        MoonPhase.FIRST_QUARTER -> "上弦月"
        MoonPhase.WAXING_GIBBOUS -> "盈凸月"
        MoonPhase.FULL_MOON -> "满月"
        MoonPhase.WANING_GIBBOUS -> "亏凸月"
        MoonPhase.LAST_QUARTER -> "下弦月"
        MoonPhase.WANING_CRESCENT -> "残月"
    }

    private fun solarFileName(phase: SolarPhase): String = when (phase) {
        SolarPhase.DAWN -> "黎明"
        SolarPhase.DAY -> "白昼"
        SolarPhase.DUSK -> "黄昏"
        SolarPhase.NIGHT -> "夜晚"
        SolarPhase.MIDNIGHT -> "午夜"
    }

    private fun ConfigurationSection.optionalBoolean(path: String): Boolean? =
        if (contains(path)) getBoolean(path) else null
}

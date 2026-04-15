package ym.moonlife.feature

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.World
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.block.data.Ageable
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ym.moonlife.buff.PlayerBuffService
import ym.moonlife.config.ConfigService
import ym.moonlife.core.EnvironmentSnapshotService
import ym.moonlife.core.WeatherState
import ym.moonlife.crop.CropGrowthService
import ym.moonlife.hook.HookManager
import ym.moonlife.locale.MessageService
import ym.moonlife.moon.MoonPhase
import ym.moonlife.moon.MoonPhaseService
import ym.moonlife.scheduler.ScheduledTaskHandle
import ym.moonlife.scheduler.SchedulerFacade
import ym.moonlife.solar.SolarPhase
import ym.moonlife.solar.SolarPhaseService
import ym.moonlife.spawn.MythicSpawnTarget
import ym.moonlife.spawn.SpawnRule
import ym.moonlife.spawn.SpawnService
import ym.moonlife.spawn.VanillaSpawnTarget
import ym.moonlife.util.ConfigReaders
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class EcologyFeatureService(
    private val plugin: JavaPlugin,
    private val configService: ConfigService,
    private val environment: EnvironmentSnapshotService,
    private val moonPhaseService: MoonPhaseService,
    private val solarPhaseService: SolarPhaseService,
    private val hookManager: HookManager,
    private val messages: MessageService,
    private val scheduler: SchedulerFacade,
    private val spawnService: SpawnService,
    private val cropGrowthService: CropGrowthService,
    private val playerBuffService: PlayerBuffService
) : Listener {
    private val configRef = AtomicReference(defaultConfig())
    private val activeEvents = ConcurrentHashMap<String, ActiveEcologyEvent>()
    private val stats = ConcurrentHashMap<String, MutableRuleStat>()
    private val debugBossBarPlayers = ConcurrentHashMap.newKeySet<java.util.UUID>()
    private val debugBossBars = ConcurrentHashMap<java.util.UUID, BossBar>()
    private var bossBarTask: ScheduledTaskHandle = ScheduledTaskHandle.NOOP
    private var registered = false

    fun start() {
        reload()
        if (!registered) {
            plugin.server.pluginManager.registerEvents(this, plugin)
            registered = true
        }
        bossBarTask.cancel()
        bossBarTask = scheduler.global.runTimer(40L, config().debugBossBarRefreshTicks) { tickDebugBossBar() }
    }

    fun stop() {
        bossBarTask.cancel()
        debugBossBars.values.forEach { bar -> bar.removeAll() }
        debugBossBars.clear()
        debugBossBarPlayers.clear()
        activeEvents.clear()
    }

    fun reload() {
        val file = File(plugin.dataFolder, "features.yml")
        if (!file.exists()) plugin.saveResource("features.yml", false)
        val yaml = YamlConfiguration.loadConfiguration(file)
        configRef.set(parseConfig(yaml))
        activeEvents.entries.removeIf { it.value.remainingSeconds() <= 0L }
    }

    fun config(): FeatureConfig = configRef.get()

    fun calendar(world: World, days: Int = config().calendarDays): List<String> {
        val now = world.fullTime
        return (0 until days.coerceIn(1, 32)).map { offset ->
            val phase = MoonPhase.fromFullTime(now + offset * 24000L)
            val feature = phaseFeatureSummary(phase)
            "Day +$offset: ${phase.name} | $feature"
        }
    }

    fun inspect(player: Player): List<String> {
        val context = environment.context(player.location, player)
        val spawnRules = spawnService.preview(player)
        val cropRules = cropGrowthService.preview(player)
        val buffPlan = playerBuffService.preview(player)
        val hotspot = activeHotspot(player)
        val event = activeEvent()
        val danger = dangerScore(player)
        return listOf(
            "World=${context.snapshot.worldName} Moon=${context.snapshot.moonPhase.name} Solar=${context.snapshot.solarPhase.name} Weather=${context.snapshot.weather.name}",
            "Biome=${context.biome.key} Wilderness=${context.wilderness} Underground=${context.underground} Protected=${isPlayerProtected(player)}",
            "Danger=${DangerLevel.fromScore(danger)} Score=$danger Hotspot=${hotspot?.id ?: "-"} Event=${event?.id ?: "-"}",
            "SpawnRules=${spawnRules.joinToString(", ") { it.id }.ifEmpty { "-" }}",
            "CropRules=${cropRules.joinToString(", ") { it.id }.ifEmpty { "-" }}",
            "BuffRules=${buffPlan.ruleIds.joinToString(", ").ifEmpty { "-" }}",
            "Stats=${statsSummary(5).joinToString(" | ").ifEmpty { "-" }}"
        )
    }

    fun validate(): List<String> {
        val lines = mutableListOf<String>()
        val bundle = configService.current
        val knownMythic = hookManager.mythicMobs.knownMobIds()
        lines += "Spawn rules: ${bundle.spawnRules.size}, crop rules: ${bundle.cropRules.size}, buff rules: ${bundle.buffRules.size}"
        if (!hookManager.mythicMobs.available) {
            lines += "WARN MythicMobs is not available. MYTHIC_MOB spawn rules will be skipped."
        }
        bundle.spawnRules.forEach { rule ->
            when (val target = rule.target) {
                is MythicSpawnTarget -> {
                    if (hookManager.mythicMobs.available && knownMythic.isNotEmpty() && target.mobId !in knownMythic) {
                        lines += "WARN ${rule.id}: MythicMob '${target.mobId}' was not found in MythicMobs index."
                    }
                }
                is VanillaSpawnTarget -> {
                    if (bundle.main.spawn.mythicMobsOnly) lines += "WARN ${rule.id}: VANILLA rule is skipped because spawn.mythic-mobs-only=true."
                }
            }
            if (rule.weight <= 0) lines += "WARN ${rule.id}: weight <= 0."
            if (rule.worlds.isNotEmpty() && rule.worlds.none { Bukkit.getWorld(it) != null }) {
                lines += "WARN ${rule.id}: none of configured worlds are currently loaded."
            }
        }
        config().bountyRules.forEach { bounty ->
            if (bounty.mythicMobIds.isEmpty()) lines += "WARN bounty ${bounty.id}: no MythicMobs targets."
        }
        return lines.ifEmpty { listOf("OK No validation warnings.") }
    }

    fun startEvent(id: String, minutes: Int?, multiplier: Double?): ActiveEcologyEvent? {
        val preset = config().eventPresets.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return null
        val durationMinutes = (minutes ?: preset.defaultMinutes).coerceIn(1, 24 * 60)
        val value = (multiplier ?: preset.multiplier).coerceIn(0.1, 10.0)
        val event = ActiveEcologyEvent(
            id = preset.id,
            displayName = preset.displayName,
            multiplier = value,
            endsAtMillis = System.currentTimeMillis() + durationMinutes * 60_000L
        )
        activeEvents[preset.id.lowercase(Locale.ROOT)] = event
        return event
    }

    fun activeEvent(): ActiveEcologyEvent? {
        activeEvents.entries.removeIf { it.value.remainingSeconds() <= 0L }
        return activeEvents.values.maxByOrNull { it.multiplier }
    }

    fun eventMultiplier(): Double = activeEvent()?.multiplier ?: 1.0

    fun spawnAmountMultiplier(rule: SpawnRule): Double {
        val event = eventMultiplier()
        return if (rule.target is MythicSpawnTarget) event else 1.0
    }

    fun isPlayerProtected(player: Player): Boolean {
        val played = runCatching { player.getStatistic(Statistic.PLAY_ONE_MINUTE) }.getOrDefault(Int.MAX_VALUE)
        if (played < config().newPlayerProtectionTicks) return true
        val spawn = player.world.spawnLocation
        return player.location.world == spawn.world && player.location.distanceSquared(spawn) <= config().spawnProtectionRadius * config().spawnProtectionRadius
    }

    fun dangerScore(player: Player): Int {
        val context = environment.context(player.location, player)
        val spawnWeight = spawnService.preview(player).sumOf { it.weight }.coerceAtMost(80)
        val weather = when (context.snapshot.weather) {
            WeatherState.CLEAR -> 0
            WeatherState.RAIN -> 8
            WeatherState.THUNDER -> 20
        }
        val solar = when (context.snapshot.solarPhase) {
            SolarPhase.DAY -> 0
            SolarPhase.DAWN, SolarPhase.DUSK -> 8
            SolarPhase.NIGHT -> 18
            SolarPhase.MIDNIGHT -> 28
        }
        val moon = when (context.snapshot.moonPhase) {
            MoonPhase.NEW_MOON, MoonPhase.FULL_MOON -> 18
            MoonPhase.WAXING_GIBBOUS, MoonPhase.WANING_GIBBOUS -> 10
            else -> 4
        }
        val hotspot = ((activeHotspot(player)?.multiplier ?: 1.0) * 8.0).roundToInt()
        val event = ((eventMultiplier() - 1.0) * 20.0).roundToInt()
        val protection = if (isPlayerProtected(player)) 35 else 0
        return (spawnWeight + weather + solar + moon + hotspot + event - protection).coerceIn(0, 120)
    }

    fun dangerLevel(player: Player): DangerLevel = DangerLevel.fromScore(dangerScore(player))

    fun activeHotspot(player: Player): HotspotRule? {
        val context = environment.context(player.location, player)
        return config().hotspotRules.firstOrNull { rule ->
            (rule.biomes.isEmpty() || context.biome in rule.biomes) &&
                (rule.moonPhases.isEmpty() || context.snapshot.moonPhase in rule.moonPhases) &&
                (rule.solarPhases.isEmpty() || context.snapshot.solarPhase in rule.solarPhases) &&
                (rule.weather.isEmpty() || context.snapshot.weather in rule.weather)
        }
    }

    fun featuresText(player: Player): String =
        listOf(
            "danger=${dangerLevel(player)}",
            "spawn=${spawnService.preview(player).joinToString(",") { it.target.key }.ifEmpty { "-" }}",
            "crop=${cropGrowthService.preview(player).joinToString(",") { it.id }.ifEmpty { "-" }}",
            "buff=${playerBuffService.preview(player).ruleIds.joinToString(",").ifEmpty { "-" }}",
            "hotspot=${activeHotspot(player)?.id ?: "-"}",
            "event=${activeEvent()?.id ?: "-"}"
        ).joinToString(" ")

    fun bountyLines(player: Player): List<String> =
        config().bountyRules.map { bounty ->
            val done = player.scoreboardTags.contains(bountyTag(bounty.id))
            "${bounty.id}: ${bounty.displayName} target=${bounty.mythicMobIds.joinToString(",")} rewardExp=${bounty.rewardExp} done=$done"
        }

    fun codexLines(player: Player): List<String> =
        player.scoreboardTags
            .filter { it.startsWith(CODEX_TAG_PREFIX) }
            .map { it.removePrefix(CODEX_TAG_PREFIX) }
            .sorted()
            .ifEmpty { listOf("No codex entries yet.") }

    fun materialsLines(): List<String> =
        config().materials.map { "${it.id}: ${it.displayName} item=${it.material.name} source=${it.source}" }

    fun templateLines(name: String?): List<String> {
        val templates = config().worldTemplates
        if (name.isNullOrBlank()) return templates.keys.sorted().map { "template: $it" }
        return templates[name.lowercase(Locale.ROOT)].orEmpty().ifEmpty { listOf("Unknown template: $name") }
    }

    fun statsSummary(limit: Int = 10): List<String> =
        stats.values
            .sortedByDescending { it.spawns + it.skips }
            .take(limit)
            .map { "${it.ruleId}: spawns=${it.spawns} skips=${it.skips}" }

    fun recordSpawn(ruleId: String) {
        stats.computeIfAbsent(ruleId) { MutableRuleStat(ruleId) }.spawn()
    }

    fun recordSkip(ruleId: String) {
        stats.computeIfAbsent(ruleId) { MutableRuleStat(ruleId) }.skip()
    }

    fun recordPerformanceSkip() {
        recordSkip("performance_guard")
    }

    fun toggleDebugBossBar(player: Player): Boolean {
        return if (debugBossBarPlayers.remove(player.uniqueId)) {
            debugBossBars.remove(player.uniqueId)?.removeAll()
            false
        } else {
            debugBossBarPlayers += player.uniqueId
            debugBossBars.computeIfAbsent(player.uniqueId) {
                Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SEGMENTED_10)
            }.addPlayer(player)
            true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        val internalName = hookManager.mythicMobs.internalName(event.entity) ?: return
        config().bountyRules
            .filter { bounty -> internalName in bounty.mythicMobIds }
            .forEach { bounty ->
                killer.addScoreboardTag(CODEX_TAG_PREFIX + bounty.codexEntry)
                if (!killer.scoreboardTags.contains(bountyTag(bounty.id))) {
                    killer.addScoreboardTag(bountyTag(bounty.id))
                    if (bounty.rewardExp > 0) killer.giveExp(bounty.rewardExp)
                    bounty.rewardMaterials.forEach { materialId -> giveMaterial(killer, materialId) }
                    messages.send(killer, "feature.bounty.completed", mapOf("bounty" to bounty.displayName))
                }
            }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCropBreak(event: BlockBreakEvent) {
        val data = event.block.blockData as? Ageable ?: return
        if (data.age < data.maximumAge) return
        val player = event.player
        val context = environment.context(event.block.location, player)
        val materialId = when (context.snapshot.moonPhase) {
            MoonPhase.FULL_MOON -> "moonlit_seed"
            MoonPhase.NEW_MOON -> "shadow_dust"
            else -> null
        } ?: return
        if (Math.random() < 0.06) giveMaterial(player, materialId)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onAltarUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val hand = event.hand ?: return
        val block = event.clickedBlock ?: return
        val item = event.item ?: return
        val context = environment.context(block.location, event.player)
        val rule = config().altarRules.firstOrNull { altar ->
            block.type == altar.block &&
                item.type == altar.cost &&
                (altar.moonPhases.isEmpty() || context.snapshot.moonPhase in altar.moonPhases) &&
                (altar.solarPhases.isEmpty() || context.snapshot.solarPhase in altar.solarPhases) &&
                (altar.weather.isEmpty() || context.snapshot.weather in altar.weather)
        } ?: return
        event.isCancelled = true
        if (item.amount <= 1) {
            event.player.inventory.setItem(hand, null)
        } else {
            item.amount -= 1
        }
        val active = startEvent(rule.eventId, rule.eventMinutes, rule.eventMultiplier)
        block.world.strikeLightningEffect(block.location)
        messages.send(event.player, "feature.altar.activated", mapOf("altar" to rule.displayName, "event" to (active?.displayName ?: rule.eventId)))
    }

    private fun tickDebugBossBar() {
        val players = Bukkit.getOnlinePlayers().filter { debugBossBarPlayers.contains(it.uniqueId) }
        debugBossBars.keys.removeIf { uuid ->
            val remove = players.none { it.uniqueId == uuid }
            if (remove) debugBossBars.remove(uuid)?.removeAll()
            remove
        }
        players.forEach { player ->
            scheduler.entity.run(player) {
                val score = dangerScore(player)
                val bar = debugBossBars.computeIfAbsent(player.uniqueId) {
                    Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SEGMENTED_10)
                }
                if (!bar.players.contains(player)) bar.addPlayer(player)
                bar.setTitle(messages.plain(
                    "feature.debug-bossbar.line",
                    mapOf(
                        "danger" to dangerLevel(player).name,
                        "score" to score.toString(),
                        "event" to (activeEvent()?.id ?: "-"),
                        "hotspot" to (activeHotspot(player)?.id ?: "-")
                    )
                ))
                bar.progress = (score / 120.0).coerceIn(0.0, 1.0)
                bar.color = when (dangerLevel(player)) {
                    DangerLevel.SAFE -> BarColor.GREEN
                    DangerLevel.WATCH -> BarColor.YELLOW
                    DangerLevel.DANGER -> BarColor.RED
                    DangerLevel.NIGHTMARE -> BarColor.PURPLE
                }
            }
        }
    }

    private fun giveMaterial(player: Player, materialId: String) {
        val material = config().materials.firstOrNull { it.id.equals(materialId, ignoreCase = true) } ?: return
        val stack = ItemStack(material.material)
        val meta = stack.itemMeta
        if (meta != null) {
            meta.setDisplayName(material.displayName)
            meta.lore = listOf("Moonlife material", "Source: ${material.source}")
            stack.itemMeta = meta
        }
        player.inventory.addItem(stack).values.forEach { leftover ->
            player.world.dropItemNaturally(player.location, leftover)
        }
    }

    private fun phaseFeatureSummary(phase: MoonPhase): String {
        val bundle = configService.current
        val spawn = bundle.spawnRules.filter { it.moonPhases.isEmpty() || phase in it.moonPhases }.take(3).joinToString(",") { it.id }
        val crop = bundle.cropRules.filter { it.moonPhases.isEmpty() || phase in it.moonPhases }.take(3).joinToString(",") { it.id }
        val buff = bundle.buffRules.filter { it.moonPhases.isEmpty() || phase in it.moonPhases }.take(3).joinToString(",") { it.id }
        return "spawn=${spawn.ifEmpty { "-" }} crop=${crop.ifEmpty { "-" }} buff=${buff.ifEmpty { "-" }}"
    }

    private fun parseConfig(yaml: YamlConfiguration): FeatureConfig =
        FeatureConfig(
            calendarDays = yaml.getInt("calendar.days", 8).coerceIn(1, 32),
            debugBossBarRefreshTicks = yaml.getLong("debug-bossbar.refresh-ticks", 40L).coerceAtLeast(20L),
            newPlayerProtectionTicks = yaml.getInt("player-protection.new-player-ticks", 72000).coerceAtLeast(0),
            spawnProtectionRadius = yaml.getDouble("player-protection.spawn-radius", 96.0).coerceAtLeast(0.0),
            bountyRules = parseBounties(yaml.getConfigurationSection("bounties")),
            materials = parseMaterials(yaml.getConfigurationSection("materials")),
            altarRules = parseAltars(yaml.getConfigurationSection("altars")),
            hotspotRules = parseHotspots(yaml.getConfigurationSection("hotspots")),
            eventPresets = parseEvents(yaml.getConfigurationSection("events")),
            worldTemplates = parseTemplates(yaml.getConfigurationSection("world-templates"))
        )

    private fun parseBounties(section: ConfigurationSection?): List<BountyRule> {
        section ?: return defaultConfig().bountyRules
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            BountyRule(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                mythicMobIds = child.getStringList("mythic-mob-ids").toSet(),
                rewardExp = child.getInt("reward-exp", 0).coerceAtLeast(0),
                rewardMaterials = child.getStringList("reward-materials"),
                codexEntry = child.getString("codex-entry", id) ?: id
            )
        }
    }

    private fun parseMaterials(section: ConfigurationSection?): List<EcologyMaterial> {
        section ?: return defaultConfig().materials
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            EcologyMaterial(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                material = Material.matchMaterial(child.getString("material", "AMETHYST_SHARD") ?: "AMETHYST_SHARD") ?: Material.AMETHYST_SHARD,
                source = child.getString("source", "unknown") ?: "unknown"
            )
        }
    }

    private fun parseAltars(section: ConfigurationSection?): List<AltarRule> {
        section ?: return defaultConfig().altarRules
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            AltarRule(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                block = Material.matchMaterial(child.getString("block", "CRYING_OBSIDIAN") ?: "CRYING_OBSIDIAN") ?: Material.CRYING_OBSIDIAN,
                cost = Material.matchMaterial(child.getString("cost", "AMETHYST_SHARD") ?: "AMETHYST_SHARD") ?: Material.AMETHYST_SHARD,
                moonPhases = ConfigReaders.enumSet(child, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(child, "solar-phases"),
                weather = ConfigReaders.enumSet(child, "weather"),
                eventId = child.getString("event-id", "fullmoon_frenzy") ?: "fullmoon_frenzy",
                eventMinutes = child.getInt("event-minutes", 10).coerceIn(1, 240),
                eventMultiplier = child.getDouble("event-multiplier", 1.25).coerceIn(0.1, 10.0)
            )
        }
    }

    private fun parseHotspots(section: ConfigurationSection?): List<HotspotRule> {
        section ?: return defaultConfig().hotspotRules
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            HotspotRule(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                biomes = ConfigReaders.biomeSet(child, "biomes"),
                moonPhases = ConfigReaders.enumSet(child, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(child, "solar-phases"),
                weather = ConfigReaders.enumSet(child, "weather"),
                multiplier = child.getDouble("multiplier", 1.0).coerceIn(0.1, 10.0)
            )
        }
    }

    private fun parseEvents(section: ConfigurationSection?): List<EventPreset> {
        section ?: return defaultConfig().eventPresets
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            EventPreset(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                multiplier = child.getDouble("multiplier", 1.25).coerceIn(0.1, 10.0),
                defaultMinutes = child.getInt("default-minutes", 30).coerceIn(1, 24 * 60)
            )
        }
    }

    private fun parseTemplates(section: ConfigurationSection?): Map<String, List<String>> {
        section ?: return defaultConfig().worldTemplates
        return section.getKeys(false).associate { key ->
            key.lowercase(Locale.ROOT) to section.getStringList(key)
        }
    }

    private fun bountyTag(id: String): String = BOUNTY_TAG_PREFIX + id.lowercase(Locale.ROOT)

    private data class MutableRuleStat(
        val ruleId: String,
        var spawns: Long = 0,
        var skips: Long = 0,
        var lastSeenMillis: Long = 0
    ) {
        fun spawn() {
            spawns += 1
            lastSeenMillis = System.currentTimeMillis()
        }

        fun skip() {
            skips += 1
            lastSeenMillis = System.currentTimeMillis()
        }
    }

    companion object {
        private const val CODEX_TAG_PREFIX = "moonlife_codex:"
        private const val BOUNTY_TAG_PREFIX = "moonlife_bounty:"

        private fun defaultConfig(): FeatureConfig =
            FeatureConfig(
                calendarDays = 8,
                debugBossBarRefreshTicks = 40L,
                newPlayerProtectionTicks = 72000,
                spawnProtectionRadius = 96.0,
                bountyRules = emptyList(),
                materials = emptyList(),
                altarRules = emptyList(),
                hotspotRules = emptyList(),
                eventPresets = emptyList(),
                worldTemplates = emptyMap()
            )
    }
}

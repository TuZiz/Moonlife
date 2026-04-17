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
import org.bukkit.plugin.java.JavaPlugin
import ym.moonlife.buff.PlayerBuffService
import ym.moonlife.config.ConfigService
import ym.moonlife.core.EnvironmentSnapshotService
import ym.moonlife.core.WeatherState
import ym.moonlife.crop.CropGrowthService
import ym.moonlife.hook.HookManager
import ym.moonlife.item.CustomItemSpec
import ym.moonlife.item.PersistentTagSpec
import ym.moonlife.item.PersistentTagType
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
        val file = listOf(File(plugin.dataFolder, "altars.yml"), File(plugin.dataFolder, "features.yml"))
            .firstOrNull { it.exists() }
            ?: File(plugin.dataFolder, "altars.yml")
        val yaml = YamlConfiguration.loadConfiguration(file)
        configRef.set(applyPhaseAssignments(parseConfig(yaml)))
        activeEvents.entries.removeIf { it.value.remainingSeconds() <= 0L }
    }

    fun config(): FeatureConfig = configRef.get()

    fun calendar(world: World, days: Int = config().calendarDays): List<String> {
        val now = world.fullTime
        return (0 until days.coerceIn(1, 32)).map { offset ->
            val phase = MoonPhase.fromFullTime(now + offset * 24000L)
            val feature = phaseFeatureSummary(phase)
            "第 $offset 天：${moonDisplay(phase)}｜$feature"
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
            "世界=${worldDisplay(context.snapshot.worldName)} 月相=${moonDisplay(context.snapshot.moonPhase)} 日相=${solarDisplay(context.snapshot.solarPhase)} 天气=${weatherDisplay(context.snapshot.weather)}",
            "群系=${biomeDisplay(context.biome.key.key)} 荒野=${yesNo(context.wilderness)} 地下=${yesNo(context.underground)} 保护=${yesNo(isPlayerProtected(player))}",
            "危险=${dangerDisplay(DangerLevel.fromScore(danger))} 分数=$danger 热点=${hotspot?.displayName ?: "无"} 活动=${event?.displayName ?: "无"}",
            "刷怪规则=${spawnRules.joinToString("、") { it.displayName }.ifEmpty { "无" }}",
            "作物规则=${cropRules.joinToString("、") { ruleDisplay(it.id) }.ifEmpty { "无" }}",
            "状态规则=${buffPlan.ruleIds.joinToString("、") { ruleDisplay(it) }.ifEmpty { "无" }}",
            "统计=${statsSummary(5).joinToString("｜").ifEmpty { "暂无" }}"
        )
    }

    fun validate(): List<String> {
        val lines = mutableListOf<String>()
        val bundle = configService.current
        val knownMythic = hookManager.mythicMobs.knownMobIds()
        val knownMythicLower = knownMythic.map { it.lowercase(Locale.ROOT) }.toSet()
        lines += "刷怪规则：${bundle.spawnRules.size} 条，作物规则：${bundle.cropRules.size} 条，状态规则：${bundle.buffRules.size} 条。"
        if (!hookManager.mythicMobs.available) {
            lines += "警告：未检测到 MythicMobs，自定义怪物刷新规则会被跳过。"
        } else if (knownMythic.isEmpty()) {
            lines += "提示：MythicMobs 怪物索引为空，请执行 MythicMobs 重载后再执行 /ecology reload。"
        }
        bundle.spawnRules.forEach { rule ->
            when (val target = rule.target) {
                is MythicSpawnTarget -> {
                    if (hookManager.mythicMobs.available && knownMythic.isNotEmpty() && target.mobId.lowercase(Locale.ROOT) !in knownMythicLower) {
                        lines += "警告：${rule.displayName} 的 MythicMobs 目标「${target.mobId}」未出现在索引中。"
                    }
                }
                is VanillaSpawnTarget -> {
                    if (bundle.main.spawn.mythicMobsOnly) lines += "警告：${rule.displayName} 是原版怪规则，当前已开启只使用 MythicMobs，因此会跳过。"
                }
            }
            if (rule.weight <= 0) lines += "警告：${rule.displayName} 的权重小于等于 0。"
            if (rule.worlds.isNotEmpty() && rule.worlds.none { Bukkit.getWorld(it) != null }) {
                lines += "警告：${rule.displayName} 配置的世界当前没有加载。"
            }
        }
        config().bountyRules.forEach { bounty ->
            if (bounty.mythicMobIds.isEmpty()) lines += "警告：悬赏「${bounty.displayName}」没有配置 MythicMobs 目标。"
        }
        return lines.ifEmpty { listOf("校验通过：没有发现配置警告。") }
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
            "危险=${dangerDisplay(dangerLevel(player))}",
            "刷怪=${spawnService.preview(player).joinToString("、") { it.displayName }.ifEmpty { "无" }}",
            "作物=${cropGrowthService.preview(player).joinToString("、") { ruleDisplay(it.id) }.ifEmpty { "无" }}",
            "状态=${playerBuffService.preview(player).ruleIds.joinToString("、") { ruleDisplay(it) }.ifEmpty { "无" }}",
            "热点=${activeHotspot(player)?.displayName ?: "无"}",
            "活动=${activeEvent()?.displayName ?: "无"}"
        ).joinToString(" ")

    fun bountyLines(player: Player): List<String> =
        config().bountyRules.map { bounty ->
            val done = player.scoreboardTags.contains(bountyTag(bounty.id))
            "${bounty.displayName}：目标=${bounty.mythicMobIds.joinToString("、") { targetDisplay(it) }} 奖励经验=${bounty.rewardExp} 已完成=${yesNo(done)}"
        }

    fun codexLines(player: Player): List<String> =
        player.scoreboardTags
            .filter { it.startsWith(CODEX_TAG_PREFIX) }
            .map { it.removePrefix(CODEX_TAG_PREFIX) }
            .map { codexDisplay(it) }
            .sorted()
            .ifEmpty { listOf("暂未解锁生态图鉴。") }

    fun materialsLines(): List<String> =
        config().materials.map { "${it.displayName}：物品=${materialDisplay(it.material)} 来源=${sourceDisplay(it.source)}" }

    fun templateLines(name: String?): List<String> {
        val templates = config().worldTemplates
        if (name.isNullOrBlank()) return templates.keys.sorted().map { "可用模板：${templateDisplay(it)}" }
        return templates[name.lowercase(Locale.ROOT)].orEmpty().ifEmpty { listOf("未知模板：$name") }
    }

    fun statsSummary(limit: Int = 10): List<String> =
        stats.values
            .sortedByDescending { it.spawns + it.skips }
            .take(limit)
            .map { "${ruleDisplay(it.ruleId)}：刷新=${it.spawns} 跳过=${it.skips}" }

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
                altar.cost.matches(plugin, item) &&
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
                        "danger" to dangerDisplay(dangerLevel(player)),
                        "score" to score.toString(),
                        "event" to (activeEvent()?.id ?: "-"),
                        "hotspot" to (activeHotspot(player)?.displayName ?: "无")
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
        val stack = material.item.create(plugin)
        player.inventory.addItem(stack).values.forEach { leftover ->
            player.world.dropItemNaturally(player.location, leftover)
        }
    }

    private fun phaseFeatureSummary(phase: MoonPhase): String {
        val bundle = configService.current
        val spawn = bundle.spawnRules.filter { it.moonPhases.isEmpty() || phase in it.moonPhases }.take(3).joinToString("、") { it.displayName }
        val crop = bundle.cropRules.filter { it.moonPhases.isEmpty() || phase in it.moonPhases }.take(3).joinToString("、") { ruleDisplay(it.id) }
        val buff = bundle.buffRules.filter { it.moonPhases.isEmpty() || phase in it.moonPhases }.take(3).joinToString("、") { ruleDisplay(it.id) }
        return "刷怪=${spawn.ifEmpty { "无" }} 作物=${crop.ifEmpty { "无" }} 状态=${buff.ifEmpty { "无" }}"
    }

    private fun moonDisplay(phase: MoonPhase): String = messages.phaseName(phase.displayKey)

    private fun solarDisplay(phase: SolarPhase): String = messages.phaseName(phase.displayKey)

    private fun dangerDisplay(level: DangerLevel): String = when (level) {
        DangerLevel.SAFE -> "安全"
        DangerLevel.WATCH -> "警戒"
        DangerLevel.DANGER -> "危险"
        DangerLevel.NIGHTMARE -> "噩梦"
    }

    private fun weatherDisplay(weather: WeatherState): String = when (weather) {
        WeatherState.CLEAR -> "晴朗"
        WeatherState.RAIN -> "降雨"
        WeatherState.THUNDER -> "雷暴"
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private fun worldDisplay(world: String): String = when (world.lowercase(Locale.ROOT)) {
        "world" -> "主世界"
        "world_nether" -> "下界"
        "world_the_end" -> "末地"
        else -> world
    }

    private fun biomeDisplay(key: String): String = when (key.lowercase(Locale.ROOT)) {
        "plains" -> "平原"
        "forest" -> "森林"
        "dark_forest" -> "黑森林"
        "swamp" -> "沼泽"
        "mangrove_swamp" -> "红树林沼泽"
        "desert" -> "沙漠"
        "taiga" -> "针叶林"
        "snowy_plains" -> "雪原"
        "river" -> "河流"
        "beach" -> "海滩"
        "cherry_grove" -> "樱花林"
        "old_growth_pine_taiga" -> "原始松木针叶林"
        "meadow" -> "草甸"
        "jungle" -> "丛林"
        "savanna" -> "热带草原"
        else -> "其他群系"
    }

    private fun ruleDisplay(id: String): String = when (id.lowercase(Locale.ROOT)) {
        in configService.current.spawnRules.map { it.id.lowercase(Locale.ROOT) } ->
            configService.current.spawnRules.first { it.id.equals(id, ignoreCase = true) }.displayName
        "fullmoon_zombie_knight" -> "满月僵尸骑士"
        "newmoon_shadow_beast" -> "新月影兽"
        "thunder_night_raider" -> "雷雨夜袭击者"
        "sunny_day_growth" -> "晴天白昼成长"
        "fullmoon_nether_wart" -> "满月地狱疣"
        "dusk_forager" -> "黄昏采集者"
        "thunder_night_danger" -> "雷雨夜危机"
        "performance_guard" -> "性能保护"
        else -> id.replace('_', ' ')
    }

    private fun targetDisplay(key: String): String = when (key.lowercase(Locale.ROOT)) {
        "fullmoonzombieknight" -> "满月僵尸骑士"
        "shadowbeast" -> "新月影兽"
        "stormboneraider" -> "雷骨袭击者"
        else -> key
    }

    private fun codexDisplay(id: String): String = when (id.lowercase(Locale.ROOT)) {
        "shadow_beast" -> "新月影兽"
        "stormbone_raider" -> "雷骨袭击者"
        "fullmoon_zombie_knight" -> "满月僵尸骑士"
        else -> id.replace('_', ' ')
    }

    private fun templateDisplay(id: String): String = when (id.lowercase(Locale.ROOT)) {
        "survival" -> "生存主世界"
        "resource" -> "资源世界"
        "nether" -> "下界"
        else -> id
    }

    private fun materialDisplay(material: Material): String = when (material) {
        Material.AMETHYST_SHARD -> "紫水晶碎片"
        Material.GUNPOWDER -> "火药"
        Material.BONE -> "骨头"
        else -> material.name
    }

    private fun sourceDisplay(source: String): String = source
        .replace("FullMoonZombieKnight", "满月僵尸骑士")
        .replace("ShadowBeast", "新月影兽")
        .replace("StormboneRaider", "雷骨袭击者")

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

    private fun applyPhaseAssignments(config: FeatureConfig): FeatureConfig {
        val assignments = configService.phaseAssignments
        return config.copy(
            altarRules = config.altarRules.map { rule ->
                rule.copy(
                    moonPhases = merge(rule.moonPhases, assignments.moonAltars[rule.id.lowercase(Locale.ROOT)]),
                    solarPhases = merge(rule.solarPhases, assignments.solarAltars[rule.id.lowercase(Locale.ROOT)])
                )
            },
            hotspotRules = config.hotspotRules.map { rule ->
                rule.copy(
                    moonPhases = merge(rule.moonPhases, assignments.moonHotspots[rule.id.lowercase(Locale.ROOT)]),
                    solarPhases = merge(rule.solarPhases, assignments.solarHotspots[rule.id.lowercase(Locale.ROOT)])
                )
            }
        )
    }

    private fun <P> merge(existing: Set<P>, assigned: Set<P>?): Set<P> =
        if (assigned.isNullOrEmpty()) existing else existing + assigned

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
            val source = child.getString("source", "unknown") ?: "unknown"
            val displayName = child.getString("display-name", id) ?: id
            val material = Material.matchMaterial(child.getString("material", "AMETHYST_SHARD") ?: "AMETHYST_SHARD") ?: Material.AMETHYST_SHARD
            val itemSection = child.getConfigurationSection("item") ?: child
            EcologyMaterial(
                id = id,
                displayName = displayName,
                material = material,
                source = source,
                item = CustomItemSpec.parse(
                    itemSection,
                    defaultMaterial = material,
                    defaultDisplayName = displayName,
                    defaultLore = listOf("月息生态材料", "来源：${sourceDisplay(source)}"),
                    defaultTags = listOf(PersistentTagSpec("moonlife:item_id", PersistentTagType.STRING, id.lowercase(Locale.ROOT)))
                )
            )
        }
    }

    private fun parseAltars(section: ConfigurationSection?): List<AltarRule> {
        section ?: return defaultConfig().altarRules
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            val legacyCost = Material.matchMaterial(child.getString("cost", "AMETHYST_SHARD") ?: "AMETHYST_SHARD") ?: Material.AMETHYST_SHARD
            val cost = child.getConfigurationSection("cost-item")
                ?.let { CustomItemSpec.parse(it, legacyCost) }
                ?: CustomItemSpec.legacyMaterial(legacyCost)
            AltarRule(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                block = Material.matchMaterial(child.getString("block", "CRYING_OBSIDIAN") ?: "CRYING_OBSIDIAN") ?: Material.CRYING_OBSIDIAN,
                cost = cost,
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

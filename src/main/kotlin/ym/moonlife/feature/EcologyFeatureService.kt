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
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import ym.moonlife.buff.PotionBuff
import ym.moonlife.buff.PlayerBuffService
import ym.moonlife.config.ConfigService
import ym.moonlife.core.AmountRange
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
    private val materialDropCooldowns = ConcurrentHashMap<String, Long>()
    private val biomeCheckCooldowns = ConcurrentHashMap<java.util.UUID, Long>()
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
        materialDropCooldowns.clear()
        biomeCheckCooldowns.clear()
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
            "材料规则=${activeMaterialDrops(player).joinToString("、") { it.displayName }.ifEmpty { "无" }}",
            "统计=${statsSummary(5).joinToString("｜").ifEmpty { "暂无" }}"
        )
    }

    fun validate(): List<String> {
        val lines = mutableListOf<String>()
        val bundle = configService.current
        val knownMythic = hookManager.mythicMobs.knownMobIds()
        val knownMythicLower = knownMythic.map { it.lowercase(Locale.ROOT) }.toSet()
        val mythicRules = bundle.spawnRules.filter { it.target is MythicSpawnTarget }
        lines += "刷怪规则：${bundle.spawnRules.size} 条，作物规则：${bundle.cropRules.size} 条，状态规则：${bundle.buffRules.size} 条，材料掉落：${config().materialDropRules.size} 条。"
        if (!hookManager.mythicMobs.available && mythicRules.isNotEmpty()) {
            lines += "警告：未检测到 MythicMobs，自定义怪物刷新规则会被跳过。"
        } else if (hookManager.mythicMobs.available && mythicRules.isNotEmpty() && knownMythic.isEmpty()) {
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
            if (bounty.objective == BountyObjective.KILL_MYTHIC && bounty.targets.isEmpty() && bounty.mythicMobIds.isEmpty()) {
                lines += "警告：悬赏「${bounty.displayName}」没有配置 MythicMobs 目标。"
            }
        }
        config().materialDropRules.forEach { rule ->
            if (rule.rewardMaterials.isEmpty()) lines += "警告：材料掉落「${rule.displayName}」没有配置 reward-materials。"
            rule.rewardMaterials.forEach { materialId ->
                if (config().materials.none { it.id.equals(materialId, ignoreCase = true) }) {
                    lines += "警告：材料掉落「${rule.displayName}」引用了不存在的生态材料：$materialId。"
                }
            }
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
            (rule.worlds.isEmpty() || context.snapshot.worldName.lowercase(Locale.ROOT) in rule.worlds) &&
                matchesHotspotCenter(rule, context.location) &&
                (rule.biomes.isEmpty() || context.biome in rule.biomes) &&
                (rule.moonPhases.isEmpty() || context.snapshot.moonPhase in rule.moonPhases) &&
                (rule.solarPhases.isEmpty() || context.snapshot.solarPhase in rule.solarPhases) &&
                (rule.weather.isEmpty() || context.snapshot.weather in rule.weather)
        }
    }

    fun activeMaterialDrops(player: Player): List<MaterialDropRule> {
        val context = environment.context(player.location, player)
        return config().materialDropRules
            .filter { it.matchesContext(context) }
            .sortedByDescending { it.priority }
    }

    fun featuresText(player: Player): String =
        listOf(
            "危险=${dangerDisplay(dangerLevel(player))}",
            "刷怪=${spawnService.preview(player).joinToString("、") { it.displayName }.ifEmpty { "无" }}",
            "作物=${cropGrowthService.preview(player).joinToString("、") { ruleDisplay(it.id) }.ifEmpty { "无" }}",
            "状态=${playerBuffService.preview(player).ruleIds.joinToString("、") { ruleDisplay(it) }.ifEmpty { "无" }}",
            "材料=${activeMaterialDrops(player).joinToString("、") { it.displayName }.ifEmpty { "无" }}",
            "热点=${activeHotspot(player)?.displayName ?: "无"}",
            "活动=${activeEvent()?.displayName ?: "无"}"
        ).joinToString(" ")

    fun bountyLines(player: Player): List<String> =
        config().bountyRules.map { bounty ->
            val done = player.scoreboardTags.contains(bountyTag(bounty.id))
            "${bounty.displayName}：类型=${objectiveDisplay(bounty.objective)} 目标=${bounty.targets.joinToString("、") { targetDisplay(it) }.ifEmpty { "任意" }} 奖励经验=${bounty.rewardExp} 已完成=${yesNo(done)}"
        }

    fun codexLines(player: Player): List<String> =
        (player.scoreboardTags
            .filter { it.startsWith(CODEX_TAG_PREFIX) }
            .map { it.removePrefix(CODEX_TAG_PREFIX) }
            .map { codexDisplay(it) }
            .sorted() +
            config().achievementRules.map { achievement ->
                val done = player.scoreboardTags.contains(achievementTag(achievement.id))
                "成就 ${achievement.displayName}：${if (done) "已完成" else "未完成"}"
            })
            .ifEmpty { listOf("暂未解锁生态图鉴。") }

    fun materialsLines(): List<String> =
        config().materials.map { "${it.displayName}：物品=${materialDisplay(it.material)} 来源=${sourceDisplay(it.source)} 用途=${it.usage.ifBlank { "轻量祭坛与自定义拓展" }}" }

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
        val context = environment.context(killer.location, killer)
        val internalName = hookManager.mythicMobs.internalName(event.entity)
        val entityType = event.entity.type.name
        config().bountyRules
            .filter { bounty -> bounty.matchesContext(context) && bounty.matchesKill(internalName, entityType) }
            .forEach { bounty ->
                completeBounty(killer, bounty)
            }
        triggerMaterialDrops(killer, context, MaterialDropObjective.KILL_ENTITY, entityType)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCropBreak(event: BlockBreakEvent) {
        val player = event.player
        val data = event.block.blockData as? Ageable
        val matureCrop = data != null && data.age >= data.maximumAge
        val context = environment.context(event.block.location, player)
        config().bountyRules
            .filter { bounty -> bounty.matchesContext(context) && bounty.matchesBlock(event.block.type.name, matureCrop) }
            .forEach { bounty -> completeBounty(player, bounty) }
        triggerMaterialDrops(player, context, MaterialDropObjective.BREAK_BLOCK, event.block.type.name)
        if (matureCrop) {
            triggerMaterialDrops(player, context, MaterialDropObjective.HARVEST_CROP, event.block.type.name)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = event.player
        val context = environment.context(player.location, player)
        val caughtType = ((event.caught as? Item)?.itemStack?.type?.name ?: "FISH")
        triggerMaterialDrops(player, context, MaterialDropObjective.FISH, caughtType)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val to = event.to
        val from = event.from
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        val now = System.currentTimeMillis()
        val last = biomeCheckCooldowns[player.uniqueId] ?: 0L
        if (now - last < 3000L) return
        biomeCheckCooldowns[player.uniqueId] = now
        val context = environment.context(to, player)
        val biomeKey = context.biome.key.key.uppercase(Locale.ROOT)
        config().bountyRules
            .filter { bounty -> bounty.matchesContext(context) && bounty.objective == BountyObjective.VISIT_BIOME && bounty.matchesTarget(biomeKey) }
            .forEach { bounty -> completeBounty(player, bounty) }
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
        if (item.amount < rule.costAmount) {
            messages.send(event.player, "feature.altar.insufficient", mapOf("altar" to rule.displayName, "amount" to rule.costAmount.toString()))
            return
        }
        if (item.amount <= rule.costAmount) {
            event.player.inventory.setItem(hand, null)
        } else {
            item.amount -= rule.costAmount
        }
        val active = rule.eventId.takeIf { it.isNotBlank() }?.let { startEvent(it, rule.eventMinutes, rule.eventMultiplier) }
        rule.codexEntry?.let { unlockCodex(event.player, it) }
        if (rule.rewardExp > 0) event.player.giveExp(rule.rewardExp)
        rule.rewardMaterials.forEach { materialId -> giveMaterial(event.player, materialId) }
        rule.potionEffects.forEach { effect ->
            val type = ConfigReaders.potionType(effect.type) ?: return@forEach
            event.player.addPotionEffect(PotionEffect(type, effect.durationTicks, effect.amplifier, effect.ambient, effect.particles, effect.icon))
        }
        when (rule.visualEffect) {
            AltarVisualEffect.NONE -> Unit
            AltarVisualEffect.LIGHTNING -> block.world.strikeLightningEffect(block.location)
            AltarVisualEffect.SMOKE -> Unit
        }
        messages.send(event.player, "feature.altar.activated", mapOf("altar" to rule.displayName, "event" to (active?.displayName ?: "轻量效果")))
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

    private fun giveMaterial(player: Player, materialId: String, amount: Int = 1) {
        val material = config().materials.firstOrNull { it.id.equals(materialId, ignoreCase = true) } ?: return
        val stack = material.item.create(plugin, amount)
        player.inventory.addItem(stack).values.forEach { leftover ->
            player.world.dropItemNaturally(player.location, leftover)
        }
    }

    private fun triggerMaterialDrops(
        player: Player,
        context: ym.moonlife.core.EcologyContext,
        objective: MaterialDropObjective,
        target: String
    ) {
        config().materialDropRules
            .asSequence()
            .filter { rule -> rule.objective == objective && rule.matchesContext(context) && rule.matchesTarget(target) }
            .sortedByDescending { it.priority }
            .forEach { rule ->
                if (rule.onCooldown(player)) return@forEach
                if (Math.random() > rule.chance) return@forEach
                rule.rewardMaterials.forEach { materialId -> giveMaterial(player, materialId, rule.amount.random()) }
                rule.markCooldown(player)
            }
    }

    private fun completeBounty(player: Player, bounty: BountyRule) {
        if (player.scoreboardTags.contains(bountyTag(bounty.id))) return
        player.addScoreboardTag(bountyTag(bounty.id))
        unlockCodex(player, bounty.codexEntry)
        if (bounty.rewardExp > 0) player.giveExp(bounty.rewardExp)
        bounty.rewardMaterials.forEach { materialId -> giveMaterial(player, materialId) }
        messages.send(player, "feature.bounty.completed", mapOf("bounty" to bounty.displayName))
    }

    private fun unlockCodex(player: Player, entry: String) {
        if (entry.isBlank()) return
        player.addScoreboardTag(CODEX_TAG_PREFIX + entry.lowercase(Locale.ROOT))
        evaluateAchievements(player)
    }

    private fun evaluateAchievements(player: Player) {
        config().achievementRules.forEach { achievement ->
            if (player.scoreboardTags.contains(achievementTag(achievement.id))) return@forEach
            val completed = achievement.requiredCodex.all { required ->
                player.scoreboardTags.contains(CODEX_TAG_PREFIX + required.lowercase(Locale.ROOT))
            }
            if (!completed) return@forEach
            player.addScoreboardTag(achievementTag(achievement.id))
            if (achievement.rewardExp > 0) player.giveExp(achievement.rewardExp)
            achievement.rewardMaterials.forEach { materialId -> giveMaterial(player, materialId) }
            messages.send(player, "feature.achievement.completed", mapOf("achievement" to achievement.displayName, "title" to achievement.title))
        }
    }

    private fun matchesHotspotCenter(rule: HotspotRule, location: org.bukkit.Location): Boolean {
        val center = rule.center ?: return true
        val world = location.world ?: return false
        val base = when (center.mode) {
            HotspotCenterMode.SPAWN -> world.spawnLocation
            HotspotCenterMode.COORDINATE -> org.bukkit.Location(world, center.x, center.y, center.z)
        }
        val dx = location.x - base.x
        val dy = if (center.useY) location.y - base.y else 0.0
        val dz = location.z - base.z
        return dx * dx + dy * dy + dz * dz <= rule.radius * rule.radius
    }

    private fun BountyRule.matchesKill(internalName: String?, entityType: String): Boolean = when (objective) {
        BountyObjective.KILL_MYTHIC -> internalName != null && matchesTarget(internalName)
        BountyObjective.KILL_ENTITY -> matchesTarget(entityType)
        else -> false
    }

    private fun BountyRule.matchesBlock(blockType: String, matureCrop: Boolean): Boolean = when (objective) {
        BountyObjective.BREAK_BLOCK -> matchesTarget(blockType)
        BountyObjective.HARVEST_CROP -> matureCrop && (targets.isEmpty() || matchesTarget(blockType))
        else -> false
    }

    private fun BountyRule.matchesTarget(value: String): Boolean {
        val normalized = value.uppercase(Locale.ROOT)
        return targets.isEmpty() || normalized in targets
    }

    private fun BountyRule.matchesContext(context: ym.moonlife.core.EcologyContext): Boolean =
        (moonPhases.isEmpty() || context.snapshot.moonPhase in moonPhases) &&
            (solarPhases.isEmpty() || context.snapshot.solarPhase in solarPhases) &&
            (weather.isEmpty() || context.snapshot.weather in weather)

    private fun MaterialDropRule.matchesTarget(value: String): Boolean {
        val normalized = value.uppercase(Locale.ROOT)
        return targets.isEmpty() || normalized in targets
    }

    private fun MaterialDropRule.matchesContext(context: ym.moonlife.core.EcologyContext): Boolean =
        (worlds.isEmpty() || context.snapshot.worldName.lowercase(Locale.ROOT) in worlds) &&
            (biomes.isEmpty() || context.biome in biomes) &&
            (moonPhases.isEmpty() || context.snapshot.moonPhase in moonPhases) &&
            (solarPhases.isEmpty() || context.snapshot.solarPhase in solarPhases) &&
            (weather.isEmpty() || context.snapshot.weather in weather) &&
            (!wildernessOnly || context.wilderness) &&
            (underground == null || underground == context.underground) &&
            (inWater == null || inWater == context.inWater) &&
            (sneaking == null || sneaking == context.sneaking)

    private fun MaterialDropRule.onCooldown(player: Player): Boolean {
        if (cooldownTicks <= 0L) return false
        val key = "${player.uniqueId}:${id.lowercase(Locale.ROOT)}"
        val last = materialDropCooldowns[key] ?: return false
        return System.currentTimeMillis() - last < cooldownTicks * 50L
    }

    private fun MaterialDropRule.markCooldown(player: Player) {
        if (cooldownTicks <= 0L) return
        val key = "${player.uniqueId}:${id.lowercase(Locale.ROOT)}"
        materialDropCooldowns[key] = System.currentTimeMillis()
    }

    private fun phaseFeatureSummary(phase: MoonPhase): String {
        val bundle = configService.current
        val spawn = bundle.spawnRules.filter { phase in it.moonPhases }.take(3).joinToString("、") { it.displayName }
        val crop = bundle.cropRules.filter { phase in it.moonPhases }.take(3).joinToString("、") { ruleDisplay(it.id) }
        val buff = bundle.buffRules.filter { phase in it.moonPhases }.take(3).joinToString("、") { ruleDisplay(it.id) }
        val drops = config().materialDropRules.filter { phase in it.moonPhases }.take(3).joinToString("、") { it.displayName }
        return "刷怪=${spawn.ifEmpty { "无" }} 作物=${crop.ifEmpty { "无" }} 状态=${buff.ifEmpty { "无" }} 材料=${drops.ifEmpty { "无" }}"
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

    private fun objectiveDisplay(objective: BountyObjective): String = when (objective) {
        BountyObjective.KILL_MYTHIC -> "自定义怪击杀"
        BountyObjective.KILL_ENTITY -> "原版怪击杀"
        BountyObjective.BREAK_BLOCK -> "采集方块"
        BountyObjective.HARVEST_CROP -> "收获作物"
        BountyObjective.VISIT_BIOME -> "探索群系"
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
        in config().materialDropRules.map { it.id.lowercase(Locale.ROOT) } ->
            config().materialDropRules.first { it.id.equals(id, ignoreCase = true) }.displayName
        "fullmoon_zombie_pack" -> "满月僵尸群"
        "newmoon_night_spider" -> "新月夜蛛"
        "thunder_skeleton_patrol" -> "雷雨骷髅巡游"
        "clear_day_field_growth" -> "晴天田垄"
        "rain_root_crop_growth" -> "雨润根茎"
        "waxing_gibbous_field_care" -> "盈凸月农田"
        "fullmoon_nether_wart" -> "满月地狱疣"
        "rain_angling_luck" -> "雨钓耐心"
        "dusk_forager" -> "黄昏采集者"
        "thunder_night_risk" -> "雷雨夜风险"
        "waxing_crescent_pathfinder" -> "峨眉月旅人"
        "first_quarter_miner" -> "上弦月矿工"
        "waxing_gibbous_field_hand" -> "盈凸月田间手"
        "waning_gibbous_forager" -> "亏凸月采集者"
        "last_quarter_resilience" -> "下弦月韧性"
        "waning_crescent_sneak" -> "残月潜行者"
        "shadow_lantern" -> "影尘灯盏"
        "moonlit_composter" -> "月露堆肥台"
        "stormbone_rod" -> "雷骨避雷针"
        "forest_trail" -> "林地小径"
        "river_mouth" -> "河口湿地"
        "dark_swamp_edge" -> "暗沼边缘"
        "cave_echo" -> "洞穴回声"
        "storm_ridge" -> "风暴山脊"
        "performance_guard" -> "性能保护"
        else -> id.replace('_', ' ')
    }

    private fun targetDisplay(key: String): String = when (key.lowercase(Locale.ROOT)) {
        "zombie" -> "原版僵尸"
        "spider" -> "原版蜘蛛"
        "skeleton" -> "原版骷髅"
        "stray" -> "流浪者"
        "wheat" -> "小麦"
        "carrots" -> "胡萝卜"
        "potatoes" -> "马铃薯"
        "beetroots" -> "甜菜"
        "brown_mushroom" -> "棕色蘑菇"
        "red_mushroom" -> "红色蘑菇"
        "sugar_cane" -> "甘蔗"
        "kelp" -> "海带"
        "seagrass" -> "海草"
        "copper_ore" -> "铜矿石"
        "deepslate_copper_ore" -> "深层铜矿石"
        "forest" -> "森林"
        "plains" -> "平原"
        "cherry_grove" -> "樱花林"
        "river" -> "河流"
        "swamp" -> "沼泽"
        "mangrove_swamp" -> "红树林沼泽"
        "beach" -> "海滩"
        "ocean" -> "海洋"
        "deep_ocean" -> "深海"
        "dripstone_caves" -> "溶洞"
        "lush_caves" -> "繁茂洞穴"
        else -> key
    }

    private fun codexDisplay(id: String): String = when (id.lowercase(Locale.ROOT)) {
        "shadow_dust" -> "影尘"
        "moonlit_seed" -> "月露种子"
        "stormbone_fragment" -> "雷骨碎片"
        "cave_echo" -> "洞穴回声"
        "fullmoon_zombie_pack" -> "满月僵尸群"
        "newmoon_night_spider" -> "新月夜蛛"
        "thunder_skeleton_patrol" -> "雷雨骷髅巡游"
        else -> id.replace('_', ' ')
    }

    private fun templateDisplay(id: String): String = when (id.lowercase(Locale.ROOT)) {
        "survival" -> "生存主世界"
        "resource" -> "资源世界"
        "nether" -> "下界"
        "farming" -> "农耕世界"
        "exploration" -> "探索世界"
        else -> id
    }

    private fun materialDisplay(material: Material): String = when (material) {
        Material.AMETHYST_SHARD -> "紫水晶碎片"
        Material.GUNPOWDER -> "火药"
        Material.BONE -> "骨头"
        Material.WHEAT_SEEDS -> "小麦种子"
        Material.GLOWSTONE_DUST -> "萤石粉"
        Material.PRISMARINE_CRYSTALS -> "海晶砂粒"
        Material.PAPER -> "纸"
        Material.STRING -> "线"
        else -> material.name
    }

    private fun sourceDisplay(source: String): String = source
        .replace("shadow_dust", "影尘")
        .replace("moonlit_seed", "月露种子")
        .replace("stormbone_fragment", "雷骨碎片")

    private fun achievementTag(id: String): String = ACHIEVEMENT_TAG_PREFIX + id.lowercase(Locale.ROOT)

    private fun parseConfig(yaml: YamlConfiguration): FeatureConfig =
        FeatureConfig(
            calendarDays = yaml.getInt("calendar.days", 8).coerceIn(1, 32),
            debugBossBarRefreshTicks = yaml.getLong("debug-bossbar.refresh-ticks", 40L).coerceAtLeast(20L),
            newPlayerProtectionTicks = yaml.getInt("player-protection.new-player-ticks", 72000).coerceAtLeast(0),
            spawnProtectionRadius = yaml.getDouble("player-protection.spawn-radius", 96.0).coerceAtLeast(0.0),
            bountyRules = parseBounties(yaml.getConfigurationSection("bounties")),
            materials = parseMaterials(yaml.getConfigurationSection("materials")),
            materialDropRules = parseMaterialDrops(yaml.getConfigurationSection("material-drops")),
            altarRules = parseAltars(yaml.getConfigurationSection("altars")),
            hotspotRules = parseHotspots(yaml.getConfigurationSection("hotspots")),
            achievementRules = parseAchievements(yaml.getConfigurationSection("achievements")),
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
            },
            materialDropRules = config.materialDropRules.map { rule ->
                rule.copy(
                    moonPhases = merge(rule.moonPhases, assignments.moonMaterialDrops[rule.id.lowercase(Locale.ROOT)]),
                    solarPhases = merge(rule.solarPhases, assignments.solarMaterialDrops[rule.id.lowercase(Locale.ROOT)])
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
            val legacyMythic = child.getStringList("mythic-mob-ids").toSet()
            val objective = ConfigReaders.valueOfEnum<BountyObjective>(child.getString("objective"))
                ?: if (legacyMythic.isNotEmpty()) BountyObjective.KILL_MYTHIC else BountyObjective.KILL_ENTITY
            val targets = (child.getStringList("targets") + child.getStringList("target") + legacyMythic)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.uppercase(Locale.ROOT) }
                .toSet()
            BountyRule(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                objective = objective,
                targets = targets,
                moonPhases = ConfigReaders.enumSet(child, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(child, "solar-phases"),
                weather = ConfigReaders.enumSet(child, "weather"),
                mythicMobIds = legacyMythic,
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
            val usage = child.getString("usage", "轻量祭坛与自定义拓展") ?: "轻量祭坛与自定义拓展"
            val displayName = child.getString("display-name", id) ?: id
            val material = Material.matchMaterial(child.getString("material", "AMETHYST_SHARD") ?: "AMETHYST_SHARD") ?: Material.AMETHYST_SHARD
            val itemSection = child.getConfigurationSection("item") ?: child
            EcologyMaterial(
                id = id,
                displayName = displayName,
                material = material,
                source = source,
                usage = usage,
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

    private fun parseMaterialDrops(section: ConfigurationSection?): List<MaterialDropRule> {
        section ?: return defaultConfig().materialDropRules
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            val objective = ConfigReaders.valueOfEnum<MaterialDropObjective>(child.getString("objective"))
                ?: return@mapNotNull null
            val targets = (child.getStringList("targets") + child.getStringList("target"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.uppercase(Locale.ROOT) }
                .toSet()
            MaterialDropRule(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                objective = objective,
                targets = targets,
                worlds = ConfigReaders.stringSet(child, "worlds"),
                biomes = ConfigReaders.biomeSet(child, "biomes"),
                moonPhases = ConfigReaders.enumSet(child, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(child, "solar-phases"),
                weather = ConfigReaders.enumSet(child, "weather"),
                wildernessOnly = child.getBoolean("wilderness-only", false),
                underground = child.optionalBoolean("underground"),
                inWater = child.optionalBoolean("in-water"),
                sneaking = child.optionalBoolean("sneaking"),
                chance = child.getDouble("chance", 0.0).coerceIn(0.0, 1.0),
                amount = ConfigReaders.amountRange(child, "amount", AmountRange(1, 1)),
                rewardMaterials = child.getStringList("reward-materials"),
                cooldownTicks = child.getLong("cooldown", 0L).coerceAtLeast(0L),
                priority = child.getInt("priority", 0)
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
                costAmount = child.getInt("cost-amount", 1).coerceIn(1, 64),
                moonPhases = ConfigReaders.enumSet(child, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(child, "solar-phases"),
                weather = ConfigReaders.enumSet(child, "weather"),
                eventId = child.getString("event-id", "") ?: "",
                eventMinutes = child.getInt("event-minutes", 10).coerceIn(1, 240),
                eventMultiplier = child.getDouble("event-multiplier", 1.25).coerceIn(0.1, 10.0),
                rewardExp = child.getInt("reward-exp", 0).coerceAtLeast(0),
                rewardMaterials = child.getStringList("reward-materials"),
                codexEntry = child.getString("codex-entry")?.takeIf { it.isNotBlank() },
                potionEffects = parsePotionEffects(child.getConfigurationSection("potion-effects")),
                visualEffect = ConfigReaders.valueOfEnum<AltarVisualEffect>(child.getString("visual-effect")) ?: AltarVisualEffect.NONE
            )
        }
    }

    private fun parsePotionEffects(section: ConfigurationSection?): List<PotionBuff> {
        section ?: return emptyList()
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            val type = child.getString("type")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PotionBuff(
                type = type,
                amplifier = child.getInt("amplifier", 0).coerceAtLeast(0),
                durationTicks = child.getInt("duration-ticks", 200).coerceAtLeast(1),
                ambient = child.getBoolean("ambient", true),
                particles = child.getBoolean("particles", false),
                icon = child.getBoolean("icon", true)
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
                worlds = ConfigReaders.stringSet(child, "worlds"),
                biomes = ConfigReaders.biomeSet(child, "biomes"),
                center = parseHotspotCenter(child.getConfigurationSection("center")),
                radius = child.getDouble("radius", 0.0).coerceAtLeast(0.0),
                moonPhases = ConfigReaders.enumSet(child, "moon-phases"),
                solarPhases = ConfigReaders.enumSet(child, "solar-phases"),
                weather = ConfigReaders.enumSet(child, "weather"),
                multiplier = child.getDouble("multiplier", 1.0).coerceIn(0.1, 10.0)
            )
        }
    }

    private fun parseAchievements(section: ConfigurationSection?): List<AchievementRule> {
        section ?: return defaultConfig().achievementRules
        return section.getKeys(false).mapNotNull { id ->
            val child = section.getConfigurationSection(id) ?: return@mapNotNull null
            AchievementRule(
                id = id,
                displayName = child.getString("display-name", id) ?: id,
                requiredCodex = child.getStringList("required-codex")
                    .map { it.lowercase(Locale.ROOT) }
                    .toSet(),
                rewardExp = child.getInt("reward-exp", 0).coerceAtLeast(0),
                rewardMaterials = child.getStringList("reward-materials"),
                title = child.getString("title", child.getString("display-name", id) ?: id) ?: id
            )
        }
    }

    private fun parseHotspotCenter(section: ConfigurationSection?): HotspotCenter? {
        section ?: return null
        if (section.getKeys(false).isEmpty()) return null
        val mode = ConfigReaders.valueOfEnum<HotspotCenterMode>(section.getString("mode")) ?: HotspotCenterMode.COORDINATE
        return HotspotCenter(
            mode = mode,
            x = section.getDouble("x", 0.0),
            y = section.getDouble("y", 64.0),
            z = section.getDouble("z", 0.0),
            useY = section.getBoolean("use-y", false)
        )
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

    private fun ConfigurationSection.optionalBoolean(path: String): Boolean? =
        if (contains(path)) getBoolean(path) else null

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
        private const val ACHIEVEMENT_TAG_PREFIX = "moonlife_achievement:"

        private fun defaultConfig(): FeatureConfig =
            FeatureConfig(
                calendarDays = 8,
                debugBossBarRefreshTicks = 40L,
                newPlayerProtectionTicks = 72000,
                spawnProtectionRadius = 96.0,
                bountyRules = emptyList(),
                materials = emptyList(),
                materialDropRules = emptyList(),
                altarRules = emptyList(),
                hotspotRules = emptyList(),
                achievementRules = emptyList(),
                eventPresets = emptyList(),
                worldTemplates = emptyMap()
            )
    }
}

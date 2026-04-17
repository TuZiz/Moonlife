package ym.moonlife.moon

import org.bukkit.Bukkit
import org.bukkit.World
import ym.moonlife.config.ConfigService
import ym.moonlife.config.PhaseMessageConfig
import ym.moonlife.locale.MessageService
import ym.moonlife.scheduler.ScheduledTaskHandle
import ym.moonlife.scheduler.SchedulerFacade
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MoonPhaseService(
    private val configService: ConfigService,
    private val scheduler: SchedulerFacade,
    private val messages: MessageService
) {
    private val cache = ConcurrentHashMap<String, MoonPhase>()
    private val announced = ConcurrentHashMap<String, MoonPhase>()
    private val overrides = ConcurrentHashMap<String, MoonPhase>()
    private var task: ScheduledTaskHandle = ScheduledTaskHandle.NOOP

    fun start() {
        task.cancel()
        val interval = configService.current.main.phaseCheckIntervalTicks
        task = scheduler.global.runTimer(20L, interval) { checkAllWorlds() }
        checkAllWorlds()
    }

    fun stop() {
        task.cancel()
        cache.clear()
        announced.clear()
        overrides.clear()
    }

    fun phase(world: World): MoonPhase =
        overrides[world.configKey()] ?: cache[world.configKey()] ?: MoonPhase.fromFullTime(world.fullTime)

    fun setOverride(world: World, phase: MoonPhase) {
        val key = world.configKey()
        val old = phase(world)
        overrides[key] = phase
        cache[key] = phase
        fireChange(world, old, phase, manual = true)
    }

    fun clearOverride(world: World) {
        overrides.remove(world.configKey())
        checkWorld(world)
    }

    fun checkAllWorlds() {
        Bukkit.getWorlds().forEach { world ->
            if (isWorldEnabled(world)) checkWorld(world)
        }
    }

    private fun checkWorld(world: World) {
        val key = world.configKey()
        val newPhase = overrides[key] ?: MoonPhase.fromFullTime(world.fullTime)
        val old = cache.put(key, newPhase)
        if (old != null && old != newPhase) {
            fireChange(world, old, newPhase, manual = false)
        } else if (old != null) {
            announceIfReady(world, old, newPhase, manual = false)
        }
    }

    private fun fireChange(world: World, old: MoonPhase?, newPhase: MoonPhase, manual: Boolean) {
        Bukkit.getPluginManager().callEvent(MoonPhaseChangeEvent(world, old, newPhase, manual))
        announceIfReady(world, old, newPhase, manual)
    }

    private fun announceIfReady(world: World, old: MoonPhase?, newPhase: MoonPhase, manual: Boolean) {
        val key = world.configKey()
        val config = configService.current.moon
        if (!manual && config.visibleOnlyMessages && !isMoonVisible(world)) return
        if (!manual && announced[key] == newPhase) return
        announced[key] = newPhase
        val phaseMessage = config.phaseMessages[newPhase] ?: emptyPhaseMessage()
        val placeholders = mapOf(
            "world" to world.name,
            "phase" to phaseDisplay(newPhase, config.phaseMessages[newPhase]),
            "old_phase" to (old?.let { phaseDisplay(it, config.phaseMessages[it]) } ?: ""),
            "phase_id" to newPhase.name,
            "features" to featureDisplay(phaseMessage.featureIds),
            "feature_count" to phaseMessage.featureIds.size.toString()
        )
        if (!phaseMessage.announce) return
        if (config.broadcastChanges) {
            phaseMessage.broadcast?.let { messages.broadcastRaw(it, placeholders) }
                ?: messages.broadcast("moon.changed.broadcast", placeholders)
            if (phaseMessage.featureIds.isNotEmpty()) {
                phaseMessage.featureLines.forEach { line -> messages.broadcastRaw(line, placeholders) }
            }
        }
        Bukkit.getOnlinePlayers().forEach { player ->
            scheduler.entity.run(player) {
                if (player.world != world) return@run
                if (config.actionBarChanges) {
                    phaseMessage.actionBar?.let { messages.actionBarRaw(player, it, placeholders) }
                        ?: messages.actionBar(player, "moon.changed.actionbar", placeholders)
                }
                if (config.titleChanges) {
                    val title = phaseMessage.title
                    val subtitle = phaseMessage.subtitle
                    if (title != null || subtitle != null) {
                        messages.titleRaw(
                            player,
                            title ?: messages.raw("moon.changed.title"),
                            subtitle ?: messages.raw("moon.changed.subtitle"),
                            placeholders
                        )
                    } else {
                        messages.title(player, "moon.changed.title", "moon.changed.subtitle", placeholders)
                    }
                }
            }
        }
        if (config.bossBarChanges) {
            phaseMessage.bossBar?.let { messages.bossBarWorldRaw(world, it, placeholders) }
                ?: messages.bossBarWorld(world, "moon.changed.bossbar", placeholders)
        }
    }

    private fun phaseDisplay(phase: MoonPhase, message: PhaseMessageConfig?): String =
        message?.displayName?.takeIf { it.isNotBlank() } ?: messages.raw(phase.displayKey)

    private fun featureDisplay(ids: List<String>): String =
        ids.joinToString("、") { id ->
            configService.current.spawnRules.firstOrNull { it.id.equals(id, ignoreCase = true) }?.displayName
                ?: configService.current.cropRules.firstOrNull { it.id.equals(id, ignoreCase = true) }?.id?.let { ruleDisplay(it) }
                ?: configService.current.buffRules.firstOrNull { it.id.equals(id, ignoreCase = true) }?.id?.let { ruleDisplay(it) }
                ?: ruleDisplay(id)
        }.ifEmpty { "无" }

    private fun ruleDisplay(id: String): String = when (id.lowercase(Locale.ROOT)) {
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
        "newmoon_spider_shadow_dust" -> "新月夜蛛影尘"
        "dark_forest_shadow_gather" -> "暗林影尘采集"
        "moonlit_harvest_seed" -> "月露成熟收获"
        "rain_riverside_moonlit_seed" -> "雨天河岸月露"
        "rain_fishing_moonlit_seed" -> "雨钓月露"
        "fullmoon_fishing_moonlit_seed" -> "满月夜钓月露"
        "thunder_skeleton_stormbone" -> "雷雨骷髅雷骨"
        "thunder_copper_stormbone" -> "雷雨铜脉雷骨"
        else -> id.replace('_', ' ')
    }

    private fun emptyPhaseMessage(): PhaseMessageConfig =
        PhaseMessageConfig(true, null, null, null, null, null, null, emptyList(), emptyList())

    private fun isMoonVisible(world: World): Boolean = world.time >= MOON_VISIBLE_START

    private fun isWorldEnabled(world: World): Boolean {
        val key = world.configKey()
        val config = configService.current.moon
        if (config.disabledWorlds.contains(key)) return false
        return config.enabledWorlds.isEmpty() || config.enabledWorlds.contains(key)
    }

    private fun World.configKey(): String = name.lowercase(Locale.ROOT)

    private companion object {
        private const val MOON_VISIBLE_START = 12000L
    }
}

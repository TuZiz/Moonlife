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
        "sunny_day_growth" -> "晴天白昼成长"
        "fullmoon_nether_wart" -> "满月地狱疣"
        "dusk_forager" -> "黄昏采集者"
        "thunder_night_danger" -> "雷雨夜危机"
        "fullmoon_altar" -> "满月祭坛"
        "newmoon_shadow_hotspot" -> "新月暗影热点"
        "thunder_night_hotspot" -> "雷雨夜热点"
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

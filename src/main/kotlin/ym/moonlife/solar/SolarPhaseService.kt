package ym.moonlife.solar

import org.bukkit.Bukkit
import org.bukkit.World
import ym.moonlife.config.ConfigService
import ym.moonlife.config.PhaseMessageConfig
import ym.moonlife.locale.MessageService
import ym.moonlife.scheduler.ScheduledTaskHandle
import ym.moonlife.scheduler.SchedulerFacade
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SolarPhaseService(
    private val configService: ConfigService,
    private val scheduler: SchedulerFacade,
    private val messages: MessageService
) {
    private val cache = ConcurrentHashMap<String, SolarPhase>()
    private val overrides = ConcurrentHashMap<String, SolarPhase>()
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
        overrides.clear()
    }

    fun phase(world: World): SolarPhase =
        overrides[world.configKey()] ?: cache[world.configKey()] ?: compute(world)

    fun setOverride(world: World, phase: SolarPhase) {
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
        val newPhase = overrides[key] ?: compute(world)
        val old = cache.put(key, newPhase)
        if (old != null && old != newPhase) {
            fireChange(world, old, newPhase, manual = false)
        }
    }

    private fun compute(world: World): SolarPhase {
        val time = world.time
        return configService.current.solar.windows.firstOrNull { it.contains(time) }?.phase ?: SolarPhase.DAY
    }

    private fun fireChange(world: World, old: SolarPhase?, newPhase: SolarPhase, manual: Boolean) {
        Bukkit.getPluginManager().callEvent(SolarPhaseChangeEvent(world, old, newPhase, manual))
        val config = configService.current.solar
        val phaseMessage = config.phaseMessages[newPhase] ?: emptyPhaseMessage()
        val placeholders = mapOf(
            "world" to world.name,
            "phase" to phaseDisplay(newPhase, config.phaseMessages[newPhase]),
            "old_phase" to (old?.let { phaseDisplay(it, config.phaseMessages[it]) } ?: ""),
            "phase_id" to newPhase.name,
            "features" to phaseMessage.featureIds.joinToString("、").ifEmpty { "无" },
            "feature_count" to phaseMessage.featureIds.size.toString()
        )
        if (config.broadcastChanges) {
            phaseMessage.broadcast?.let { messages.broadcastRaw(it, placeholders) }
                ?: messages.broadcast("solar.changed.broadcast", placeholders)
            if (phaseMessage.featureIds.isNotEmpty()) {
                phaseMessage.featureLines.forEach { line -> messages.broadcastRaw(line, placeholders) }
            }
        }
        Bukkit.getOnlinePlayers().forEach { player ->
            scheduler.entity.run(player) {
                if (player.world != world) return@run
                if (config.actionBarChanges) {
                    phaseMessage.actionBar?.let { messages.actionBarRaw(player, it, placeholders) }
                        ?: messages.actionBar(player, "solar.changed.actionbar", placeholders)
                }
                if (config.titleChanges) {
                    val title = phaseMessage.title
                    val subtitle = phaseMessage.subtitle
                    if (title != null || subtitle != null) {
                        messages.titleRaw(
                            player,
                            title ?: messages.raw("solar.changed.title"),
                            subtitle ?: messages.raw("solar.changed.subtitle"),
                            placeholders
                        )
                    } else {
                        messages.title(player, "solar.changed.title", "solar.changed.subtitle", placeholders)
                    }
                }
            }
        }
        if (config.bossBarChanges) {
            phaseMessage.bossBar?.let { messages.bossBarWorldRaw(world, it, placeholders) }
                ?: messages.bossBarWorld(world, "solar.changed.bossbar", placeholders)
        }
    }

    private fun phaseDisplay(phase: SolarPhase, message: PhaseMessageConfig?): String =
        message?.displayName?.takeIf { it.isNotBlank() } ?: messages.raw(phase.displayKey)

    private fun emptyPhaseMessage(): PhaseMessageConfig =
        PhaseMessageConfig(null, null, null, null, null, null, emptyList(), emptyList())

    private fun isWorldEnabled(world: World): Boolean {
        val key = world.configKey()
        val config = configService.current.solar
        if (config.disabledWorlds.contains(key)) return false
        return config.enabledWorlds.isEmpty() || config.enabledWorlds.contains(key)
    }

    private fun World.configKey(): String = name.lowercase(Locale.ROOT)
}

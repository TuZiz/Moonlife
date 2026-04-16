package ym.moonlife.moon

import org.bukkit.Bukkit
import org.bukkit.World
import ym.moonlife.config.ConfigService
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
        val placeholders = mapOf(
            "world" to world.name,
            "phase" to messages.phaseName(newPhase.displayKey),
            "old_phase" to (old?.let { messages.phaseName(it.displayKey) } ?: "")
        )
        if (config.broadcastChanges) messages.broadcast("moon.changed.broadcast", placeholders)
        Bukkit.getOnlinePlayers().forEach { player ->
            scheduler.entity.run(player) {
                if (player.world != world) return@run
                if (config.actionBarChanges) messages.actionBar(player, "moon.changed.actionbar", placeholders)
                if (config.titleChanges) messages.title(player, "moon.changed.title", "moon.changed.subtitle", placeholders)
            }
        }
        if (config.bossBarChanges) messages.bossBarWorld(world, "moon.changed.bossbar", placeholders)
    }

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

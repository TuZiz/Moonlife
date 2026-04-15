package ym.moonlife.hook

import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.java.JavaPlugin
import ym.moonlife.config.ConfigService
import ym.moonlife.buff.PlayerBuffService
import ym.moonlife.locale.MessageService
import ym.moonlife.moon.MoonPhaseService
import ym.moonlife.solar.SolarPhaseService
import ym.moonlife.crop.CropGrowthService
import ym.moonlife.feature.EcologyFeatureService
import ym.moonlife.spawn.SpawnService
import ym.moonlife.util.ReflectionUtil
import java.util.concurrent.ConcurrentHashMap

class HookManager(
    private val plugin: JavaPlugin,
    private val configService: ConfigService
) : Listener {
    var mythicMobs: MythicMobsHook = DisabledMythicMobsHook
        private set

    val placeholderApiAvailable: Boolean get() = plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")

    private val protectionHooks = mutableListOf<ProtectionHook>()
    private val wildernessCache = ConcurrentHashMap<String, WildernessCacheEntry>()
    private var placeholderExpansion: Any? = null
    private var registered = false

    fun load() {
        refreshMythicMobs()
        protectionHooks.clear()
        protectionHooks += WorldGuardProtectionHook(plugin)
        protectionHooks += ReservedProtectionHook("Residence", "Residence", plugin)
        protectionHooks += ReservedProtectionHook("GriefPrevention", "GriefPrevention", plugin)
        protectionHooks += ReservedProtectionHook("Lands", "Lands", plugin)
        if (!registered) {
            plugin.server.pluginManager.registerEvents(this, plugin)
            registered = true
        }
    }

    fun reload() {
        refreshMythicMobs()
        wildernessCache.clear()
    }

    fun registerPlaceholderExpansion(
        moonPhaseService: MoonPhaseService,
        solarPhaseService: SolarPhaseService,
        messages: MessageService,
        spawnService: SpawnService,
        cropGrowthService: CropGrowthService,
        playerBuffService: PlayerBuffService,
        featureService: EcologyFeatureService
    ) {
        if (!placeholderApiAvailable || !ReflectionUtil.classExists("me.clip.placeholderapi.expansion.PlaceholderExpansion")) {
            plugin.logger.info("PlaceholderAPI not found. Placeholder expansion is skipped.")
            return
        }
        runCatching {
            unregisterPlaceholderExpansion()
            val expansion = MoonlifePlaceholderExpansion(
                plugin,
                moonPhaseService,
                solarPhaseService,
                messages,
                spawnService,
                cropGrowthService,
                playerBuffService,
                featureService
            )
            val registered = expansion.register()
            placeholderExpansion = expansion
            plugin.logger.info("PlaceholderAPI expansion registered: $registered")
        }.onFailure {
            plugin.logger.warning("Failed to register PlaceholderAPI expansion: ${it.message}")
        }
    }

    fun unregisterPlaceholderExpansion() {
        val expansion = placeholderExpansion ?: return
        runCatching {
            expansion.javaClass.methods.firstOrNull { it.name == "unregister" && it.parameterCount == 0 }?.invoke(expansion)
        }
        placeholderExpansion = null
    }

    private fun refreshMythicMobs() {
        mythicMobs = if (plugin.server.pluginManager.isPluginEnabled("MythicMobs")) {
            ReflectiveMythicMobsHook(plugin).also {
                it.reloadIndex()
                plugin.logger.info("MythicMobs hook enabled. Known mob ids: ${it.knownMobIds().size}")
            }
        } else {
            plugin.logger.warning("MythicMobs not found. MYTHIC_MOB spawn rules will be skipped.")
            DisabledMythicMobsHook
        }
    }

    fun isWilderness(location: Location): Boolean {
        val config = configService.current.main.wilderness
        if (!config.enabled) return true
        val world = location.world ?: return true
        if (config.disabledWorlds.contains(world.name.lowercase())) return true
        val key = "${world.name}:${location.blockX shr 4}:${location.blockZ shr 4}"
        val now = System.currentTimeMillis()
        wildernessCache[key]?.takeIf { now - it.createdAt <= WILDERNESS_CACHE_TTL_MILLIS }?.let { return it.value }
        val value = protectionHooks.filter { it.available }.all { it.isWilderness(location) }
        if (wildernessCache.size > MAX_WILDERNESS_CACHE_SIZE) wildernessCache.clear()
        wildernessCache[key] = WildernessCacheEntry(value, now)
        return value
    }

    @EventHandler
    fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name.equals("MythicMobs", ignoreCase = true)) {
            mythicMobs = ReflectiveMythicMobsHook(plugin).also { it.reloadIndex() }
            plugin.logger.info("MythicMobs hook refreshed after plugin enable.")
        }
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin.name.equals("MythicMobs", ignoreCase = true)) {
            mythicMobs = DisabledMythicMobsHook
            plugin.logger.warning("MythicMobs disabled. MYTHIC_MOB spawn rules are suspended.")
        }
    }

    private data class WildernessCacheEntry(val value: Boolean, val createdAt: Long)

    companion object {
        private const val WILDERNESS_CACHE_TTL_MILLIS = 5000L
        private const val MAX_WILDERNESS_CACHE_SIZE = 8192
    }
}

package ym.moonlife.hook

import org.bukkit.Location
import org.bukkit.plugin.Plugin

class WorldGuardProtectionHook(private val plugin: Plugin) : ProtectionHook {
    override val name: String = "WorldGuard"
    override val available: Boolean get() = plugin.server.pluginManager.isPluginEnabled("WorldGuard")

    override fun isWilderness(location: Location): Boolean {
        if (!available) return true
        return runCatching {
            val adapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
            val worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard")
            val worldGuard = worldGuardClass.getMethod("getInstance").invoke(null)
            val platform = worldGuard.javaClass.getMethod("getPlatform").invoke(worldGuard)
            val regionContainer = platform.javaClass.getMethod("getRegionContainer").invoke(platform)
            val query = regionContainer.javaClass.getMethod("createQuery").invoke(regionContainer)
            val adaptedLocation = adapterClass.methods
                .firstOrNull { it.name == "adapt" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(Location::class.java) }
                ?.invoke(null, location)
                ?: return@runCatching true
            val applicable = query.javaClass.methods
                .firstOrNull { it.name == "getApplicableRegions" && it.parameterCount == 1 }
                ?.invoke(query, adaptedLocation)
                ?: return@runCatching true
            val size = applicable.javaClass.methods
                .firstOrNull { it.name == "size" && it.parameterCount == 0 }
                ?.invoke(applicable) as? Int
            if (size != null) return@runCatching size == 0
            val regions = applicable.javaClass.methods
                .firstOrNull { it.name == "getRegions" && it.parameterCount == 0 }
                ?.invoke(applicable) as? Collection<*>
            regions == null || regions.isEmpty()
        }.getOrElse { true }
    }
}

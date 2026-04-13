package ym.moonlife.hook

import org.bukkit.Location
import org.bukkit.plugin.Plugin

class ReservedProtectionHook(
    override val name: String,
    private val pluginName: String,
    private val plugin: Plugin
) : ProtectionHook {
    override val available: Boolean get() = plugin.server.pluginManager.isPluginEnabled(pluginName)
    override fun isWilderness(location: Location): Boolean = true
}

package ym.moonlife.scheduler

import org.bukkit.plugin.Plugin
import ym.moonlife.platform.PlatformCapabilities

object SchedulerFactory {
    fun create(plugin: Plugin, capabilities: PlatformCapabilities): SchedulerFacade {
        val fallback = BukkitSchedulerFacade(plugin)
        return if (capabilities.folia) FoliaSchedulerFacade(plugin, fallback) else fallback
    }
}

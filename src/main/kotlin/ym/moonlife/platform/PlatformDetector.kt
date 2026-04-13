package ym.moonlife.platform

import org.bukkit.Bukkit
import ym.moonlife.util.ReflectionUtil

object PlatformDetector {
    fun detect(): PlatformCapabilities {
        val server = Bukkit.getServer()
        val folia = ReflectionUtil.classExists("io.papermc.paper.threadedregions.RegionizedServer")
            || server.javaClass.methods.any { it.name == "getGlobalRegionScheduler" }
        val paper = server.javaClass.name.contains("paper", ignoreCase = true)
            || ReflectionUtil.classExists("com.destroystokyo.paper.PaperConfig")
        return PlatformCapabilities(
            folia = folia,
            paper = paper,
            serverName = server.name,
            serverVersion = server.version
        )
    }
}

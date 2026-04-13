package ym.moonlife.hook

import org.bukkit.Location

interface ProtectionHook {
    val name: String
    val available: Boolean
    fun isWilderness(location: Location): Boolean
}

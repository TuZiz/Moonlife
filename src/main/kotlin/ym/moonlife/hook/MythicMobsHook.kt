package ym.moonlife.hook

import org.bukkit.Location
import org.bukkit.entity.Entity

interface MythicMobsHook {
    val available: Boolean
    fun reloadIndex()
    fun knownMobIds(): Set<String>
    fun spawn(mobId: String, location: Location, level: Double = 1.0): Entity?
    fun isMythicMob(entity: Entity): Boolean
    fun internalName(entity: Entity): String?
}

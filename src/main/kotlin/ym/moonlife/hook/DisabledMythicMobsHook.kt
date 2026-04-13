package ym.moonlife.hook

import org.bukkit.Location
import org.bukkit.entity.Entity

object DisabledMythicMobsHook : MythicMobsHook {
    override val available: Boolean = false
    override fun reloadIndex() = Unit
    override fun knownMobIds(): Set<String> = emptySet()
    override fun spawn(mobId: String, location: Location, level: Double): Entity? = null
    override fun isMythicMob(entity: Entity): Boolean = false
    override fun internalName(entity: Entity): String? = null
}

package ym.moonlife.spawn

import org.bukkit.Location
import org.bukkit.entity.Entity
import ym.moonlife.hook.MythicMobsHook

class MythicSpawnAdapter(private val mythicMobsHook: () -> MythicMobsHook) {
    fun spawn(target: MythicSpawnTarget, location: Location, amount: Int): List<Entity> {
        val hook = mythicMobsHook()
        if (!hook.available) return emptyList()
        return (1..amount).mapNotNull {
            hook.spawn(target.mobId, location)
        }
    }
}

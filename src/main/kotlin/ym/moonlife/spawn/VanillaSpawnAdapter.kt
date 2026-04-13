package ym.moonlife.spawn

import org.bukkit.Location
import org.bukkit.entity.Entity

class VanillaSpawnAdapter {
    fun spawn(target: VanillaSpawnTarget, location: Location, amount: Int): List<Entity> {
        val world = location.world ?: return emptyList()
        return (1..amount).mapNotNull {
            runCatching { world.spawnEntity(location, target.entityType) }.getOrNull()
        }
    }
}

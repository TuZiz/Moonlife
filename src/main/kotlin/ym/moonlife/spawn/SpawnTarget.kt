package ym.moonlife.spawn

import org.bukkit.entity.EntityType

sealed interface SpawnTarget {
    val backend: SpawnBackend
    val key: String
}

data class VanillaSpawnTarget(val entityType: EntityType) : SpawnTarget {
    override val backend: SpawnBackend = SpawnBackend.VANILLA
    override val key: String = entityType.name
}

data class MythicSpawnTarget(val mobId: String) : SpawnTarget {
    override val backend: SpawnBackend = SpawnBackend.MYTHIC_MOB
    override val key: String = mobId
}

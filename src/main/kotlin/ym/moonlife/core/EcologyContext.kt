package ym.moonlife.core

import org.bukkit.Location
import org.bukkit.block.Biome
import org.bukkit.entity.Player

data class EcologyContext(
    val snapshot: WorldEnvironmentSnapshot,
    val location: Location,
    val player: Player?,
    val biome: Biome,
    val y: Int,
    val blockLight: Int,
    val skyLight: Int,
    val underground: Boolean,
    val inWater: Boolean,
    val sneaking: Boolean,
    val wilderness: Boolean
)

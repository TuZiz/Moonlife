package ym.moonlife.core

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ym.moonlife.hook.HookManager
import ym.moonlife.moon.MoonPhaseService
import ym.moonlife.solar.SolarPhaseService

class EnvironmentSnapshotService(
    private val moonPhaseService: MoonPhaseService,
    private val solarPhaseService: SolarPhaseService,
    private val hookManager: HookManager
) {
    fun snapshot(world: World): WorldEnvironmentSnapshot =
        WorldEnvironmentSnapshot(
            world = world,
            worldName = world.name,
            fullTime = world.fullTime,
            time = world.time,
            moonPhase = moonPhaseService.phase(world),
            solarPhase = solarPhaseService.phase(world),
            weather = WeatherState.from(world)
        )

    fun context(location: Location, player: Player? = null): EcologyContext {
        val world = location.world ?: error("Location has no world")
        val block = location.block
        val biome = block.biome
        val highest = runCatching { world.getHighestBlockYAt(location.blockX, location.blockZ) }.getOrDefault(location.blockY)
        return EcologyContext(
            snapshot = snapshot(world),
            location = location,
            player = player,
            biome = biome,
            y = location.blockY,
            blockLight = block.lightFromBlocks.toInt(),
            skyLight = block.lightFromSky.toInt(),
            underground = location.blockY < highest - 8,
            inWater = block.isLiquid,
            sneaking = player?.isSneaking ?: false,
            wilderness = hookManager.isWilderness(location)
        )
    }
}

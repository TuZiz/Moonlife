package ym.moonlife.api

import org.bukkit.World
import org.bukkit.entity.Player
import ym.moonlife.feature.DangerLevel
import ym.moonlife.moon.MoonPhase
import ym.moonlife.solar.SolarPhase

interface MoonlifeApi {
    fun moonPhase(world: World): MoonPhase
    fun solarPhase(world: World): SolarPhase
    fun dangerLevel(player: Player): DangerLevel
    fun activeFeatures(player: Player): String
    fun activeEventId(): String?
}

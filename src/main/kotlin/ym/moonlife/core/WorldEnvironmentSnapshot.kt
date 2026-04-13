package ym.moonlife.core

import org.bukkit.World
import ym.moonlife.moon.MoonPhase
import ym.moonlife.solar.SolarPhase

data class WorldEnvironmentSnapshot(
    val world: World,
    val worldName: String,
    val fullTime: Long,
    val time: Long,
    val moonPhase: MoonPhase,
    val solarPhase: SolarPhase,
    val weather: WeatherState
)

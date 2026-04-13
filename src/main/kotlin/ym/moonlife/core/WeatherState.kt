package ym.moonlife.core

import org.bukkit.World

enum class WeatherState {
    CLEAR,
    RAIN,
    THUNDER;

    companion object {
        fun from(world: World): WeatherState = when {
            world.isThundering -> THUNDER
            world.hasStorm() -> RAIN
            else -> CLEAR
        }
    }
}

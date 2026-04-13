package ym.moonlife.solar

import org.bukkit.World
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class SolarPhaseChangeEvent(
    val world: World,
    val oldPhase: SolarPhase?,
    val newPhase: SolarPhase,
    val manual: Boolean
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

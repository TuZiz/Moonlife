package ym.moonlife.moon

import org.bukkit.World
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class MoonPhaseChangeEvent(
    val world: World,
    val oldPhase: MoonPhase?,
    val newPhase: MoonPhase,
    val manual: Boolean
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

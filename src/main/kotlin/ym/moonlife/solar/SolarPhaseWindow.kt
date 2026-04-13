package ym.moonlife.solar

data class SolarPhaseWindow(
    val phase: SolarPhase,
    val startTick: Long,
    val endTick: Long
) {
    fun contains(time: Long): Boolean {
        val normalized = Math.floorMod(time, 24000L)
        return if (startTick <= endTick) {
            normalized in startTick..endTick
        } else {
            normalized >= startTick || normalized <= endTick
        }
    }
}

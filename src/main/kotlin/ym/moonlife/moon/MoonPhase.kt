package ym.moonlife.moon

enum class MoonPhase(val displayKey: String) {
    NEW_MOON("moon-phase.new_moon"),
    WAXING_CRESCENT("moon-phase.waxing_crescent"),
    FIRST_QUARTER("moon-phase.first_quarter"),
    WAXING_GIBBOUS("moon-phase.waxing_gibbous"),
    FULL_MOON("moon-phase.full_moon"),
    WANING_GIBBOUS("moon-phase.waning_gibbous"),
    LAST_QUARTER("moon-phase.last_quarter"),
    WANING_CRESCENT("moon-phase.waning_crescent");

    companion object {
        fun fromFullTime(fullTime: Long): MoonPhase {
            val day = Math.floorDiv(fullTime, 24000L)
            return entries[Math.floorMod(day, entries.size.toLong()).toInt()]
        }
    }
}

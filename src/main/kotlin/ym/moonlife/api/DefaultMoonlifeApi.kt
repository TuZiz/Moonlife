package ym.moonlife.api

import org.bukkit.World
import org.bukkit.entity.Player
import ym.moonlife.feature.DangerLevel
import ym.moonlife.feature.EcologyFeatureService
import ym.moonlife.moon.MoonPhase
import ym.moonlife.moon.MoonPhaseService
import ym.moonlife.solar.SolarPhase
import ym.moonlife.solar.SolarPhaseService

class DefaultMoonlifeApi(
    private val moonPhaseService: MoonPhaseService,
    private val solarPhaseService: SolarPhaseService,
    private val featureService: EcologyFeatureService
) : MoonlifeApi {
    override fun moonPhase(world: World): MoonPhase = moonPhaseService.phase(world)
    override fun solarPhase(world: World): SolarPhase = solarPhaseService.phase(world)
    override fun dangerLevel(player: Player): DangerLevel = featureService.dangerLevel(player)
    override fun activeFeatures(player: Player): String = featureService.featuresText(player)
    override fun activeEventId(): String? = featureService.activeEvent()?.id
}

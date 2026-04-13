package ym.moonlife.crop

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.type.Farmland
import ym.moonlife.core.EcologyContext
import java.util.Locale

class CropRuleEngine(private val rules: List<CropRule>) {
    private val enabledRules = rules.filter { it.enabled }

    fun firstMatch(context: EcologyContext, cropBlock: Block): CropRule? =
        enabledRules.firstOrNull { matches(it, context, cropBlock) }

    fun matching(context: EcologyContext, cropBlock: Block): List<CropRule> =
        enabledRules.filter { matches(it, context, cropBlock) }

    fun preview(context: EcologyContext): List<CropRule> =
        enabledRules.filter { matchesEnvironmentOnly(it, context) }

    private fun matches(rule: CropRule, context: EcologyContext, cropBlock: Block): Boolean {
        val data = cropBlock.blockData
        if (data !is Ageable) return false
        if (rule.cropTypes.isNotEmpty() && !rule.cropTypes.contains(cropBlock.type)) return false
        if (!matchesEnvironmentOnly(rule, context)) return false
        if (rule.soilTypes.isNotEmpty() && !rule.soilTypes.contains(cropBlock.getRelative(0, -1, 0).type)) return false
        val soil = cropBlock.getRelative(0, -1, 0)
        val moisture = (soil.blockData as? Farmland)?.moisture ?: if (soil.type == Material.FARMLAND) 0 else 7
        if (!rule.moistureRange.contains(moisture)) return false
        return true
    }

    private fun matchesEnvironmentOnly(rule: CropRule, context: EcologyContext): Boolean {
        if (rule.worlds.isNotEmpty() && !rule.worlds.contains(context.snapshot.worldName.lowercase(Locale.ROOT))) return false
        if (rule.biomes.isNotEmpty() && !rule.biomes.contains(context.biome)) return false
        if (rule.moonPhases.isNotEmpty() && !rule.moonPhases.contains(context.snapshot.moonPhase)) return false
        if (rule.solarPhases.isNotEmpty() && !rule.solarPhases.contains(context.snapshot.solarPhase)) return false
        if (rule.weather.isNotEmpty() && !rule.weather.contains(context.snapshot.weather)) return false
        if (!rule.lightRange.contains(context.blockLight)) return false
        return true
    }
}

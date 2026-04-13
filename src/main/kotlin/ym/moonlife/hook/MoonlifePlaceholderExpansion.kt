package ym.moonlife.hook

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.moonlife.buff.BuffPlan
import ym.moonlife.buff.PlayerBuffService
import ym.moonlife.core.WeatherState
import ym.moonlife.crop.CropGrowthService
import ym.moonlife.locale.MessageService
import ym.moonlife.moon.MoonPhaseService
import ym.moonlife.solar.SolarPhaseService
import ym.moonlife.spawn.SpawnService
import java.util.Locale

class MoonlifePlaceholderExpansion(
    private val plugin: JavaPlugin,
    private val moonPhaseService: MoonPhaseService,
    private val solarPhaseService: SolarPhaseService,
    private val messages: MessageService,
    private val spawnService: SpawnService,
    private val cropGrowthService: CropGrowthService,
    private val playerBuffService: PlayerBuffService
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "moonlife"
    override fun getAuthor(): String = "ymxc"
    override fun getVersion(): String = plugin.javaClass.`package`?.implementationVersion ?: "1.0-SNAPSHOT"
    override fun persist(): Boolean = true
    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String {
        val onlinePlayer = player?.player
        val world = onlinePlayer?.world ?: Bukkit.getWorlds().firstOrNull() ?: return ""
        val moon = moonPhaseService.phase(world)
        val solar = solarPhaseService.phase(world)
        return when (params.lowercase(Locale.ROOT)) {
            "moon", "moon_phase" -> moonPhaseService.phase(world).name
            "moon_localized", "moon_display" -> messages.phaseName(moonPhaseService.phase(world).displayKey)
            "moon_index" -> (moon.ordinal + 1).toString()
            "solar", "solar_phase" -> solarPhaseService.phase(world).name
            "solar_localized", "solar_display" -> messages.phaseName(solarPhaseService.phase(world).displayKey)
            "solar_index" -> (solar.ordinal + 1).toString()
            "weather" -> WeatherState.from(world).name
            "world" -> world.name
            "summary" -> "${moon.name}:${solar.name}:${WeatherState.from(world).name}"
            "phase_summary" -> "${messages.phaseName(moon.displayKey)} / ${messages.phaseName(solar.displayKey)} / ${WeatherState.from(world).name}"
            "spawn_rules", "active_spawn_rules" -> onlinePlayer?.spawnRulesText().orEmpty()
            "spawn_count", "active_spawn_count" -> onlinePlayer?.let { spawnService.preview(it).size.toString() }.orEmpty()
            "spawn_targets" -> onlinePlayer?.spawnTargetsText().orEmpty()
            "spawn_feature" -> onlinePlayer?.spawnFeatureText().orEmpty()
            "crop_rules", "active_crop_rules" -> onlinePlayer?.cropRulesText().orEmpty()
            "crop_count", "active_crop_count" -> onlinePlayer?.let { cropGrowthService.preview(it).size.toString() }.orEmpty()
            "crop_feature" -> onlinePlayer?.cropFeatureText().orEmpty()
            "buff_rules", "active_buff_rules" -> onlinePlayer?.buffPlan()?.ruleIds?.joinToString(", ").orEmpty()
            "buff_count", "active_buff_count" -> onlinePlayer?.buffPlan()?.ruleIds?.size?.toString().orEmpty()
            "buff_feature" -> onlinePlayer?.buffFeatureText().orEmpty()
            "features", "phase_features" -> onlinePlayer?.allFeaturesText().orEmpty()
            else -> ""
        }
    }

    private fun Player.spawnRulesText(): String =
        spawnService.preview(this).joinToString(", ") { it.id }

    private fun Player.spawnTargetsText(): String =
        spawnService.preview(this).joinToString(", ") { it.target.key }

    private fun Player.spawnFeatureText(): String {
        val rules = spawnService.preview(this)
        if (rules.isEmpty()) return "无活跃野外刷新"
        return rules.joinToString("; ") { rule ->
            "刷怪:${rule.target.key} x${rule.amount.min}-${rule.amount.max} 权重${rule.weight}"
        }
    }

    private fun Player.cropRulesText(): String =
        cropGrowthService.preview(this).joinToString(", ") { it.id }

    private fun Player.cropFeatureText(): String {
        val rules = cropGrowthService.preview(this)
        if (rules.isEmpty()) return "无活跃作物规则"
        return rules.joinToString("; ") { rule ->
            "作物:${rule.id} 成长x${format(rule.growthMultiplier)} 收获+${percent(rule.extraHarvestChance)} 变异+${percent(rule.mutationChance)}"
        }
    }

    private fun Player.buffPlan(): BuffPlan = playerBuffService.preview(this)

    private fun Player.buffFeatureText(): String {
        val plan = buffPlan()
        if (plan.ruleIds.isEmpty()) return "无活跃玩家状态"
        return "状态:${plan.ruleIds.joinToString(", ")} 经验x${format(plan.experienceMultiplier)} 掉落x${format(plan.dropMultiplier)} 伤害x${format(plan.outgoingDamageMultiplier)} 承伤x${format(plan.incomingDamageMultiplier)}"
    }

    private fun Player.allFeaturesText(): String =
        listOf(spawnFeatureText(), cropFeatureText(), buffFeatureText()).joinToString(" | ")

    private fun format(value: Double): String =
        "%.2f".format(Locale.US, value)

    private fun percent(value: Double): String =
        "${format(value * 100.0)}%"
}

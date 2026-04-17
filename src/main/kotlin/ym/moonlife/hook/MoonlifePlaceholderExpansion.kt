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
import ym.moonlife.feature.EcologyFeatureService
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
    private val playerBuffService: PlayerBuffService,
    private val featureService: EcologyFeatureService
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
            "moon", "moon_phase" -> messages.phaseName(moonPhaseService.phase(world).displayKey)
            "moon_localized", "moon_display" -> messages.phaseName(moonPhaseService.phase(world).displayKey)
            "moon_index" -> (moon.ordinal + 1).toString()
            "solar", "solar_phase" -> messages.phaseName(solarPhaseService.phase(world).displayKey)
            "solar_localized", "solar_display" -> messages.phaseName(solarPhaseService.phase(world).displayKey)
            "solar_index" -> (solar.ordinal + 1).toString()
            "weather" -> weatherDisplay(WeatherState.from(world))
            "world" -> world.name
            "summary" -> "${messages.phaseName(moon.displayKey)}：${messages.phaseName(solar.displayKey)}：${weatherDisplay(WeatherState.from(world))}"
            "phase_summary" -> "${messages.phaseName(moon.displayKey)} / ${messages.phaseName(solar.displayKey)} / ${weatherDisplay(WeatherState.from(world))}"
            "spawn_rules", "active_spawn_rules" -> onlinePlayer?.spawnRulesText().orEmpty()
            "spawn_count", "active_spawn_count" -> onlinePlayer?.let { spawnService.preview(it).size.toString() }.orEmpty()
            "spawn_targets" -> onlinePlayer?.spawnTargetsText().orEmpty()
            "spawn_feature" -> onlinePlayer?.spawnFeatureText().orEmpty()
            "crop_rules", "active_crop_rules" -> onlinePlayer?.cropRulesText().orEmpty()
            "crop_count", "active_crop_count" -> onlinePlayer?.let { cropGrowthService.preview(it).size.toString() }.orEmpty()
            "crop_feature" -> onlinePlayer?.cropFeatureText().orEmpty()
            "buff_rules", "active_buff_rules" -> onlinePlayer?.buffPlan()?.ruleIds?.joinToString("、") { ruleDisplay(it) }.orEmpty()
            "buff_count", "active_buff_count" -> onlinePlayer?.buffPlan()?.ruleIds?.size?.toString().orEmpty()
            "buff_feature" -> onlinePlayer?.buffFeatureText().orEmpty()
            "features", "phase_features" -> onlinePlayer?.allFeaturesText().orEmpty()
            "danger", "danger_level" -> onlinePlayer?.let { dangerDisplay(featureService.dangerLevel(it).name) }.orEmpty()
            "danger_score" -> onlinePlayer?.let { featureService.dangerScore(it).toString() }.orEmpty()
            "hotspot" -> onlinePlayer?.let { featureService.activeHotspot(it)?.displayName ?: "无" }.orEmpty()
            "hotspot_multiplier" -> onlinePlayer?.let { featureService.activeHotspot(it)?.multiplier?.let(::format) ?: "1.00" }.orEmpty()
            "active_event" -> featureService.activeEvent()?.displayName ?: "无"
            "event_multiplier" -> format(featureService.eventMultiplier())
            "event_seconds" -> featureService.activeEvent()?.remainingSeconds()?.toString() ?: "0"
            "protected" -> onlinePlayer?.let { yesNo(featureService.isPlayerProtected(it)) }.orEmpty()
            "bounty_count" -> onlinePlayer?.let { featureService.bountyLines(it).size.toString() }.orEmpty()
            "bounties", "bounty_lines" -> onlinePlayer?.let { featureService.bountyLines(it).joinToString("; ") }.orEmpty()
            "codex_count" -> onlinePlayer?.let { featureService.codexLines(it).count { line -> line != "暂未解锁生态图鉴。" }.toString() }.orEmpty()
            "codex", "codex_lines", "achievements" -> onlinePlayer?.let { featureService.codexLines(it).joinToString("; ") }.orEmpty()
            "materials" -> featureService.materialsLines().joinToString("; ")
            else -> ""
        }
    }

    private fun Player.spawnRulesText(): String =
        spawnService.preview(this).joinToString("、") { it.displayName }

    private fun Player.spawnTargetsText(): String =
        spawnService.preview(this).joinToString("、") { targetDisplay(it.target.key) }

    private fun Player.spawnFeatureText(): String {
        val rules = spawnService.preview(this)
        if (rules.isEmpty()) return "无活跃野外刷新"
        return rules.joinToString("; ") { rule ->
            "刷怪:${rule.displayName} x${rule.amount.min}-${rule.amount.max} 权重${rule.weight}"
        }
    }

    private fun Player.cropRulesText(): String =
        cropGrowthService.preview(this).joinToString("、") { ruleDisplay(it.id) }

    private fun Player.cropFeatureText(): String {
        val rules = cropGrowthService.preview(this)
        if (rules.isEmpty()) return "无活跃作物规则"
        return rules.joinToString("; ") { rule ->
            "作物:${ruleDisplay(rule.id)} 成长x${format(rule.growthMultiplier)} 收获+${percent(rule.extraHarvestChance)} 变异+${percent(rule.mutationChance)}"
        }
    }

    private fun Player.buffPlan(): BuffPlan = playerBuffService.preview(this)

    private fun Player.buffFeatureText(): String {
        val plan = buffPlan()
        if (plan.ruleIds.isEmpty()) return "无活跃玩家状态"
        return "状态:${plan.ruleIds.joinToString("、") { ruleDisplay(it) }} 经验x${format(plan.experienceMultiplier)} 掉落x${format(plan.dropMultiplier)} 伤害x${format(plan.outgoingDamageMultiplier)} 承伤x${format(plan.incomingDamageMultiplier)}"
    }

    private fun Player.allFeaturesText(): String =
        listOf(spawnFeatureText(), cropFeatureText(), buffFeatureText(), featureService.featuresText(this)).joinToString(" | ")

    private fun format(value: Double): String =
        "%.2f".format(Locale.US, value)

    private fun percent(value: Double): String =
        "${format(value * 100.0)}%"

    private fun weatherDisplay(weather: WeatherState): String = when (weather) {
        WeatherState.CLEAR -> "晴朗"
        WeatherState.RAIN -> "降雨"
        WeatherState.THUNDER -> "雷暴"
    }

    private fun dangerDisplay(name: String): String = when (name.uppercase(Locale.ROOT)) {
        "SAFE" -> "安全"
        "WATCH" -> "警戒"
        "DANGER" -> "危险"
        "NIGHTMARE" -> "噩梦"
        else -> name
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private fun ruleDisplay(id: String): String = when (id.lowercase(Locale.ROOT)) {
        "fullmoon_zombie_pack" -> "满月僵尸群"
        "newmoon_night_spider" -> "新月夜蛛"
        "thunder_skeleton_patrol" -> "雷雨骷髅巡游"
        "fullmoon_zombie_knight" -> "满月僵尸骑士"
        "newmoon_shadow_beast" -> "新月影兽"
        "thunder_night_raider" -> "雷雨夜袭击者"
        "sunny_day_growth" -> "晴天白昼成长"
        "fullmoon_nether_wart" -> "满月地狱疣"
        "waxing_gibbous_growth" -> "盈凸月丰壤"
        "dusk_forager" -> "黄昏采集者"
        "thunder_night_danger" -> "雷雨夜危机"
        "waxing_crescent_pathfinder" -> "峨眉月旅人"
        "first_quarter_miner" -> "上弦月矿工"
        "waning_gibbous_forager" -> "亏凸月采集者"
        "last_quarter_resilience" -> "下弦月韧性"
        "waning_crescent_sneak" -> "残月潜行者"
        "cycle_collection_basin" -> "月相收集盆"
        "spawn_grove" -> "出生林地"
        "river_mist" -> "河雾带"
        "old_mine_echo" -> "旧矿回声"
        else -> id.replace('_', ' ')
    }

    private fun targetDisplay(key: String): String = when (key.uppercase(Locale.ROOT)) {
        "ZOMBIE" -> "原版僵尸"
        "SPIDER" -> "原版蜘蛛"
        "SKELETON" -> "原版骷髅"
        else -> key
    }
}

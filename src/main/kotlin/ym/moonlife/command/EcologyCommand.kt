package ym.moonlife.command

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ym.moonlife.config.ConfigService
import ym.moonlife.locale.MessageService
import ym.moonlife.moon.MoonPhase
import ym.moonlife.moon.MoonPhaseService
import ym.moonlife.solar.SolarPhase
import ym.moonlife.solar.SolarPhaseService
import ym.moonlife.spawn.SpawnService
import ym.moonlife.buff.PlayerBuffService
import ym.moonlife.core.WeatherState
import ym.moonlife.feature.EcologyFeatureService
import java.util.Locale

class EcologyCommand(
    private val configService: ConfigService,
    private val messages: MessageService,
    private val moonPhaseService: MoonPhaseService,
    private val solarPhaseService: SolarPhaseService,
    private val spawnService: SpawnService,
    private val buffService: PlayerBuffService,
    private val featureService: EcologyFeatureService,
    private val onReload: () -> Boolean
) : CommandExecutor, TabCompleter {
    private val subcommands = listOf(
        "reload", "debug", "setmoon", "setsolar", "info", "preview", "testspawn", "testbuff",
        "calendar", "inspect", "validate", "bossbar", "event", "bounty", "codex", "materials", "template", "stats", "help"
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        return when (label.lowercase(Locale.ROOT)) {
            "lunarphase" -> showLunar(sender)
            "solarphase" -> showSolar(sender)
            else -> handleEcology(sender, args)
        }
    }

    private fun handleEcology(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) return help(sender)
        return when (args[0].lowercase(Locale.ROOT)) {
            "reload" -> reload(sender)
            "debug" -> debug(sender)
            "setmoon" -> setMoon(sender, args)
            "setsolar" -> setSolar(sender, args)
            "info" -> info(sender)
            "preview" -> preview(sender)
            "testspawn" -> testSpawn(sender)
            "testbuff" -> testBuff(sender)
            "calendar" -> calendar(sender, args)
            "inspect" -> inspect(sender)
            "validate" -> validate(sender)
            "bossbar" -> bossbar(sender)
            "event" -> event(sender, args)
            "bounty" -> bounty(sender)
            "codex" -> codex(sender)
            "materials" -> materials(sender)
            "template" -> template(sender, args)
            "stats" -> stats(sender)
            "help" -> help(sender)
            else -> {
                messages.send(sender, "command.unknown")
                help(sender)
            }
        }
    }

    private fun help(sender: CommandSender): Boolean {
        messages.sendList(sender, "command.help")
        return true
    }

    private fun reload(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.reload")) return true
        if (onReload()) {
            messages.send(sender, "command.reload.success")
        } else {
            messages.send(sender, "command.reload.failed")
        }
        return true
    }

    private fun debug(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.debug")) return true
        val bundle = configService.current
        messages.send(
            sender,
            "command.debug.summary",
            mapOf(
                "spawn_rules" to bundle.spawnRules.size.toString(),
                "crop_rules" to bundle.cropRules.size.toString(),
                "buff_rules" to bundle.buffRules.size.toString()
            )
        )
        return true
    }

    private fun setMoon(sender: CommandSender, args: Array<out String>): Boolean {
        if (!require(sender, "ecology.setphase")) return true
        val phase = args.getOrNull(1)?.let { parseEnum<MoonPhase>(it) }
        if (phase == null) {
            messages.send(sender, "command.setmoon.invalid")
            return true
        }
        val world = resolveWorld(sender, args.getOrNull(2)) ?: return true
        moonPhaseService.setOverride(world, phase)
        messages.send(sender, "command.setmoon.success", mapOf("world" to world.name, "phase" to messages.phaseName(phase.displayKey)))
        return true
    }

    private fun setSolar(sender: CommandSender, args: Array<out String>): Boolean {
        if (!require(sender, "ecology.setphase")) return true
        val phase = args.getOrNull(1)?.let { parseEnum<SolarPhase>(it) }
        if (phase == null) {
            messages.send(sender, "command.setsolar.invalid")
            return true
        }
        val world = resolveWorld(sender, args.getOrNull(2)) ?: return true
        solarPhaseService.setOverride(world, phase)
        messages.send(sender, "command.setsolar.success", mapOf("world" to world.name, "phase" to messages.phaseName(phase.displayKey)))
        return true
    }

    private fun showLunar(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.info")) return true
        val world = resolveWorld(sender, null) ?: return true
        val phase = moonPhaseService.phase(world)
        messages.send(sender, "command.lunarphase.info", mapOf("world" to world.name, "phase" to messages.phaseName(phase.displayKey)))
        return true
    }

    private fun showSolar(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.info")) return true
        val world = resolveWorld(sender, null) ?: return true
        val phase = solarPhaseService.phase(world)
        messages.send(sender, "command.solarphase.info", mapOf("world" to world.name, "phase" to messages.phaseName(phase.displayKey)))
        return true
    }

    private fun info(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.info")) return true
        val world = resolveWorld(sender, null) ?: return true
        messages.send(
            sender,
            "command.info.summary",
            mapOf(
                "world" to world.name,
                "moon" to messages.phaseName(moonPhaseService.phase(world).displayKey),
                "solar" to messages.phaseName(solarPhaseService.phase(world).displayKey),
                "weather" to WeatherState.from(world).name
            )
        )
        return true
    }

    private fun preview(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.preview")) return true
        val player = sender as? Player ?: return playerOnly(sender)
        val rules = spawnService.preview(player)
        messages.send(sender, "command.preview.header", mapOf("count" to rules.size.toString()))
        rules.take(12).forEach { rule ->
            messages.send(sender, "command.preview.entry", mapOf("id" to rule.id, "target" to rule.target.key, "weight" to rule.weight.toString()))
        }
        return true
    }

    private fun testSpawn(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.debug")) return true
        val player = sender as? Player ?: return playerOnly(sender)
        spawnService.testSpawn(player)
        messages.send(sender, "command.testspawn.queued")
        return true
    }

    private fun testBuff(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.debug")) return true
        val player = sender as? Player ?: return playerOnly(sender)
        val plan = buffService.testBuff(player)
        messages.send(sender, "command.testbuff.summary", mapOf("rules" to plan.ruleIds.joinToString(", ").ifEmpty { "-" }))
        return true
    }

    private fun calendar(sender: CommandSender, args: Array<out String>): Boolean {
        if (!require(sender, "ecology.info")) return true
        val world = resolveWorld(sender, args.getOrNull(1)) ?: return true
        featureService.calendar(world).forEach { line -> featureLine(sender, line) }
        return true
    }

    private fun inspect(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.debug")) return true
        val player = sender as? Player ?: return playerOnly(sender)
        featureService.inspect(player).forEach { line -> featureLine(sender, line) }
        return true
    }

    private fun validate(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.debug")) return true
        featureService.validate().forEach { line -> featureLine(sender, line) }
        return true
    }

    private fun bossbar(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.debug")) return true
        val player = sender as? Player ?: return playerOnly(sender)
        val enabled = featureService.toggleDebugBossBar(player)
        messages.send(sender, if (enabled) "feature.debug-bossbar.enabled" else "feature.debug-bossbar.disabled")
        return true
    }

    private fun event(sender: CommandSender, args: Array<out String>): Boolean {
        if (!require(sender, "ecology.debug")) return true
        if (args.size < 3 || !args[1].equals("start", ignoreCase = true)) {
            featureService.config().eventPresets.forEach {
                featureLine(sender, "event ${it.id}: multiplier=${it.multiplier} minutes=${it.defaultMinutes}")
            }
            return true
        }
        val id = args[2]
        val minutes = args.getOrNull(3)?.toIntOrNull()
        val multiplier = args.getOrNull(4)?.toDoubleOrNull()
        val active = featureService.startEvent(id, minutes, multiplier)
        if (active == null) {
            messages.send(sender, "feature.event.unknown", mapOf("event" to id))
        } else {
            messages.send(sender, "feature.event.started", mapOf("event" to active.displayName, "seconds" to active.remainingSeconds().toString()))
        }
        return true
    }

    private fun bounty(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.info")) return true
        val player = sender as? Player ?: return playerOnly(sender)
        featureService.bountyLines(player).forEach { line -> featureLine(sender, line) }
        return true
    }

    private fun codex(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.info")) return true
        val player = sender as? Player ?: return playerOnly(sender)
        featureService.codexLines(player).forEach { line -> featureLine(sender, line) }
        return true
    }

    private fun materials(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.info")) return true
        featureService.materialsLines().forEach { line -> featureLine(sender, line) }
        return true
    }

    private fun template(sender: CommandSender, args: Array<out String>): Boolean {
        if (!require(sender, "ecology.preview")) return true
        featureService.templateLines(args.getOrNull(1)).forEach { line -> featureLine(sender, line) }
        return true
    }

    private fun stats(sender: CommandSender): Boolean {
        if (!require(sender, "ecology.debug")) return true
        featureService.statsSummary().forEach { line -> featureLine(sender, line) }
        return true
    }

    private fun resolveWorld(sender: CommandSender, name: String?): World? {
        if (!name.isNullOrBlank()) {
            return Bukkit.getWorld(name).also {
                if (it == null) messages.send(sender, "command.world-not-found", mapOf("world" to name))
            }
        }
        return (sender as? Player)?.world ?: Bukkit.getWorlds().firstOrNull().also {
            if (it == null) messages.send(sender, "command.no-world")
        }
    }

    private fun require(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.hasPermission("ecology.admin")) return true
        messages.send(sender, "command.no-permission")
        return false
    }

    private fun playerOnly(sender: CommandSender): Boolean {
        messages.send(sender, "command.player-only")
        return true
    }

    private fun featureLine(sender: CommandSender, line: String) {
        messages.send(sender, "feature.line", mapOf("line" to line))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (alias.equals("lunarphase", ignoreCase = true) || alias.equals("solarphase", ignoreCase = true)) return emptyList()
        if (args.size == 1) return subcommands.filter { it.startsWith(args[0], ignoreCase = true) }
        if (args.size == 2 && args[0].equals("setmoon", ignoreCase = true)) {
            return MoonPhase.entries.map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("setsolar", ignoreCase = true)) {
            return SolarPhase.entries.map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 3 && (args[0].equals("setmoon", true) || args[0].equals("setsolar", true))) {
            return Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("event", true)) return listOf("start").filter { it.startsWith(args[1], true) }
        if (args.size == 3 && args[0].equals("event", true) && args[1].equals("start", true)) {
            return featureService.config().eventPresets.map { it.id }.filter { it.startsWith(args[2], true) }
        }
        if (args.size == 2 && args[0].equals("calendar", true)) {
            return Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("template", true)) {
            return featureService.config().worldTemplates.keys.filter { it.startsWith(args[1], true) }
        }
        return emptyList()
    }

    private inline fun <reified E : Enum<E>> parseEnum(value: String): E? =
        runCatching { enumValueOf<E>(value.uppercase(Locale.ROOT).replace('-', '_')) }.getOrNull()
}

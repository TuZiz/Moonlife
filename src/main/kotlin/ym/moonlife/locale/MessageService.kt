package ym.moonlife.locale

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ym.moonlife.scheduler.SchedulerFacade

class MessageService(
    private val locale: LocaleService,
    private val scheduler: SchedulerFacade
) {
    fun send(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sender.sendMessage(locale.plain(key, placeholders))
    }

    fun sendList(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        locale.list(key, placeholders).forEach { sender.sendMessage(it) }
    }

    fun broadcast(key: String, placeholders: Map<String, String> = emptyMap()) {
        val text = locale.plain(key, placeholders)
        Bukkit.getConsoleSender().sendMessage(text)
        Bukkit.getOnlinePlayers().forEach { player ->
            scheduler.entity.run(player) { player.sendMessage(text) }
        }
    }

    fun actionBar(player: Player, key: String, placeholders: Map<String, String> = emptyMap()) {
        val text = locale.plain(key, placeholders)
        runCatching {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(text))
        }.onFailure {
            player.sendMessage(text)
        }
    }

    fun title(player: Player, titleKey: String, subtitleKey: String, placeholders: Map<String, String> = emptyMap()) {
        player.sendTitle(locale.plain(titleKey, placeholders), locale.plain(subtitleKey, placeholders), 10, 60, 10)
    }

    fun bossBar(players: Collection<Player>, key: String, placeholders: Map<String, String> = emptyMap()) {
        if (players.isEmpty()) return
        val bar = Bukkit.createBossBar(locale.plain(key, placeholders), BarColor.PURPLE, BarStyle.SOLID)
        players.forEach { player ->
            scheduler.entity.run(player) { bar.addPlayer(player) }
        }
        scheduler.global.runDelayed(100L) {
            players.forEach { player ->
                scheduler.entity.run(player) { bar.removePlayer(player) }
            }
        }
    }

    fun bossBarWorld(world: World, key: String, placeholders: Map<String, String> = emptyMap()) {
        val bar = Bukkit.createBossBar(locale.plain(key, placeholders), BarColor.PURPLE, BarStyle.SOLID)
        val players = Bukkit.getOnlinePlayers().toList()
        players.forEach { player ->
            scheduler.entity.run(player) {
                if (player.world == world) bar.addPlayer(player)
            }
        }
        scheduler.global.runDelayed(100L) {
            players.forEach { player ->
                scheduler.entity.run(player) { bar.removePlayer(player) }
            }
        }
    }

    fun phaseName(key: String): String = locale.plain(key)
}

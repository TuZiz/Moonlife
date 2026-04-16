package ym.moonlife.locale

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class LocaleService(private val plugin: JavaPlugin) {
    private val miniMessage = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.builder()
        .character('§')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    private val messages = AtomicReference<YamlConfiguration>()

    fun reload() {
        val zhCn = File(plugin.dataFolder, "lang/zh_cn.yml")
        val file = if (zhCn.exists()) zhCn else File(plugin.dataFolder, "messages.yml")
        messages.set(YamlConfiguration.loadConfiguration(file))
    }

    fun plain(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val raw = raw(key, placeholders)
        return render(raw)
    }

    fun render(template: String, placeholders: Map<String, String> = emptyMap()): String =
        render(applyPlaceholders(template, placeholders))

    fun raw(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val config = messages.get() ?: return key
        return applyPlaceholders(config.getString(key, key) ?: key, placeholders)
    }

    fun list(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val config = messages.get() ?: return listOf(key)
        return config.getStringList(key).map { line ->
            render(line, placeholders)
        }
    }

    private fun applyPlaceholders(template: String, placeholders: Map<String, String>): String {
        val config = messages.get()
        val prefix = normalizePlaceholder(config?.getString("prefix", "") ?: "")
        var value = template.replace("<prefix>", prefix)
        placeholders.forEach { (placeholder, replacement) ->
            value = value.replace("<$placeholder>", normalizePlaceholder(replacement))
        }
        return value
    }

    private fun render(raw: String): String =
        runCatching { legacy.serialize(miniMessage.deserialize(raw)) }
            .getOrElse { ChatColor.translateAlternateColorCodes('&', raw) }

    private fun normalizePlaceholder(value: String): String {
        if ('§' !in value) return value
        return miniMessage.serialize(legacy.deserialize(value))
    }
}

package ym.moonlife.locale

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class LocaleService(private val plugin: JavaPlugin) {
    private val miniMessage = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()
    private val messages = AtomicReference<YamlConfiguration>()

    fun reload() {
        val file = File(plugin.dataFolder, "messages.yml")
        messages.set(YamlConfiguration.loadConfiguration(file))
    }

    fun plain(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val raw = raw(key, placeholders)
        return legacy.serialize(miniMessage.deserialize(raw))
    }

    fun raw(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val config = messages.get() ?: return key
        val prefix = normalizePlaceholder(config.getString("prefix", "") ?: "")
        var value = config.getString(key, key) ?: key
        value = value.replace("<prefix>", prefix)
        placeholders.forEach { (placeholder, replacement) ->
            value = value.replace("<$placeholder>", normalizePlaceholder(replacement))
        }
        return value
    }

    fun list(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        val config = messages.get() ?: return listOf(key)
        val prefix = normalizePlaceholder(config.getString("prefix", "") ?: "")
        return config.getStringList(key).map { line ->
            var value = line.replace("<prefix>", prefix)
            placeholders.forEach { (placeholder, replacement) ->
                value = value.replace("<$placeholder>", normalizePlaceholder(replacement))
            }
            legacy.serialize(miniMessage.deserialize(value))
        }
    }

    private fun normalizePlaceholder(value: String): String {
        if ('§' !in value) return value
        return miniMessage.serialize(legacy.deserialize(value))
    }
}

package ym.moonlife.item

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ym.moonlife.core.AmountRange
import java.util.Locale

data class CustomItemSpec(
    val material: Material,
    val displayName: String?,
    val lore: List<String>,
    val customModelData: Int?,
    val tags: List<PersistentTagSpec>,
    val matchDisplayName: Boolean,
    val matchLore: Boolean,
    val matchCustomModelData: Boolean,
    val matchTags: Boolean,
    val nameMatchMode: TextMatchMode
) {
    fun create(plugin: JavaPlugin, amount: Int = 1): ItemStack {
        val stack = ItemStack(material, amount.coerceAtLeast(1))
        val meta = stack.itemMeta ?: return stack
        displayName?.takeIf { it.isNotBlank() }?.let { meta.setDisplayName(render(it)) }
        if (lore.isNotEmpty()) meta.lore = lore.map { render(it) }
        customModelData?.let { meta.setCustomModelData(it) }
        tags.forEach { it.write(plugin, meta.persistentDataContainer) }
        stack.itemMeta = meta
        return stack
    }

    fun matches(plugin: JavaPlugin, stack: ItemStack): Boolean {
        if (stack.type != material) return false
        val meta = stack.itemMeta
        if (matchDisplayName) {
            val expected = displayName?.let { render(it) } ?: return false
            val actual = meta?.displayName ?: return false
            if (!matchesText(expected, actual, nameMatchMode)) return false
        }
        if (matchLore) {
            val expected = lore.map { render(it) }
            val actual = meta?.lore ?: return false
            if (actual != expected) return false
        }
        if (matchCustomModelData) {
            if (customModelData == null || meta == null || !meta.hasCustomModelData() || meta.customModelData != customModelData) return false
        }
        if (matchTags) {
            val container = meta?.persistentDataContainer ?: return false
            if (tags.any { !it.matches(plugin, container) }) return false
        }
        return true
    }

    companion object {
        private val miniMessage = MiniMessage.miniMessage()
        private val legacy = LegacyComponentSerializer.legacySection()

        fun legacyMaterial(material: Material): CustomItemSpec =
            CustomItemSpec(
                material = material,
                displayName = null,
                lore = emptyList(),
                customModelData = null,
                tags = emptyList(),
                matchDisplayName = false,
                matchLore = false,
                matchCustomModelData = false,
                matchTags = false,
                nameMatchMode = TextMatchMode.EXACT
            )

        fun parse(
            section: ConfigurationSection,
            defaultMaterial: Material,
            defaultDisplayName: String? = null,
            defaultLore: List<String> = emptyList(),
            defaultTags: List<PersistentTagSpec> = emptyList()
        ): CustomItemSpec {
            val material = Material.matchMaterial(section.getString("material", defaultMaterial.name) ?: defaultMaterial.name)
                ?: defaultMaterial
            val displayName = section.getString("display-name")
                ?: section.getString("name")
                ?: defaultDisplayName
            val lore = if (section.isList("lore")) section.getStringList("lore") else defaultLore
            val customModelData = if (section.contains("custom-model-data")) section.getInt("custom-model-data") else null
            val tags = mergeTags(defaultTags, parseTags(section))
            val matchSection = section.getConfigurationSection("match")
            val matchDisplayName = matchSection?.getBoolean("display-name", displayName != null)
                ?: section.getBoolean("match-display-name", displayName != null)
            val matchLore = matchSection?.getBoolean("lore", false)
                ?: section.getBoolean("match-lore", false)
            val matchCustomModelData = matchSection?.getBoolean("custom-model-data", customModelData != null)
                ?: section.getBoolean("match-custom-model-data", customModelData != null)
            val matchTags = matchSection?.getBoolean("nbt", tags.isNotEmpty())
                ?: section.getBoolean("match-nbt", tags.isNotEmpty())
            val nameMatchMode = TextMatchMode.from(
                matchSection?.getString("name-mode") ?: section.getString("name-match-mode")
            )
            return CustomItemSpec(
                material = material,
                displayName = displayName,
                lore = lore,
                customModelData = customModelData,
                tags = tags,
                matchDisplayName = matchDisplayName,
                matchLore = matchLore,
                matchCustomModelData = matchCustomModelData,
                matchTags = matchTags,
                nameMatchMode = nameMatchMode
            )
        }

        private fun parseTags(section: ConfigurationSection): List<PersistentTagSpec> {
            val root = section.getConfigurationSection("nbt") ?: section.getConfigurationSection("pdc") ?: return emptyList()
            return root.getKeys(false).mapNotNull { key ->
                val child = root.getConfigurationSection(key)
                if (child != null) {
                    val value = child.get("value")?.toString() ?: return@mapNotNull null
                    PersistentTagSpec(
                        key = key,
                        type = PersistentTagType.from(child.getString("type")),
                        value = value
                    )
                } else {
                    val value = root.get(key)?.toString() ?: return@mapNotNull null
                    PersistentTagSpec(key = key, type = PersistentTagType.STRING, value = value)
                }
            }
        }

        private fun mergeTags(defaultTags: List<PersistentTagSpec>, configuredTags: List<PersistentTagSpec>): List<PersistentTagSpec> {
            val merged = linkedMapOf<String, PersistentTagSpec>()
            (defaultTags + configuredTags).forEach { tag ->
                merged[tag.key.lowercase(Locale.ROOT)] = tag
            }
            return merged.values.toList()
        }

        private fun render(text: String): String {
            if ('§' in text) return text
            if ('<' in text && '>' in text) {
                return runCatching { legacy.serialize(miniMessage.deserialize(text)) }
                    .getOrElse { ChatColor.translateAlternateColorCodes('&', text) }
            }
            return ChatColor.translateAlternateColorCodes('&', text)
        }

        private fun matchesText(expected: String, actual: String, mode: TextMatchMode): Boolean =
            when (mode) {
                TextMatchMode.EXACT -> actual == expected
                TextMatchMode.PLAIN_EQUALS -> strip(actual).equals(strip(expected), ignoreCase = true)
                TextMatchMode.PLAIN_CONTAINS -> strip(actual).contains(strip(expected), ignoreCase = true)
                TextMatchMode.DISABLED -> true
            }

        private fun strip(value: String): String = ChatColor.stripColor(value) ?: value
    }
}

data class CustomItemDrop(
    val id: String,
    val item: CustomItemSpec,
    val amount: AmountRange,
    val chance: Double
) {
    fun create(plugin: JavaPlugin): ItemStack = item.create(plugin, amount.random())

    companion object {
        fun parseList(section: ConfigurationSection, path: String, defaultMaterial: Material): List<CustomItemDrop> {
            val root = section.getConfigurationSection(path) ?: return emptyList()
            return root.getKeys(false).mapNotNull { id ->
                val child = root.getConfigurationSection(id) ?: return@mapNotNull null
                val amount = parseAmount(child)
                CustomItemDrop(
                    id = id,
                    item = CustomItemSpec.parse(child, defaultMaterial),
                    amount = amount,
                    chance = child.getDouble("chance", 1.0).coerceIn(0.0, 1.0)
                )
            }
        }

        private fun parseAmount(section: ConfigurationSection): AmountRange {
            val values = section.getIntegerList("amount")
            return when {
                values.size >= 2 -> AmountRange(values[0].coerceAtLeast(1), values[1].coerceAtLeast(values[0].coerceAtLeast(1)))
                values.size == 1 -> AmountRange(values[0].coerceAtLeast(1), values[0].coerceAtLeast(1))
                else -> AmountRange(1, 1)
            }
        }
    }
}

data class PersistentTagSpec(
    val key: String,
    val type: PersistentTagType,
    val value: String
) {
    fun write(plugin: JavaPlugin, container: PersistentDataContainer) {
        val namespacedKey = namespacedKey(plugin) ?: return
        when (type) {
            PersistentTagType.STRING -> container.set(namespacedKey, PersistentDataType.STRING, value)
            PersistentTagType.INTEGER -> value.toIntOrNull()?.let { container.set(namespacedKey, PersistentDataType.INTEGER, it) }
            PersistentTagType.LONG -> value.toLongOrNull()?.let { container.set(namespacedKey, PersistentDataType.LONG, it) }
            PersistentTagType.DOUBLE -> value.toDoubleOrNull()?.let { container.set(namespacedKey, PersistentDataType.DOUBLE, it) }
            PersistentTagType.BOOLEAN -> container.set(namespacedKey, PersistentDataType.BYTE, if (value.toBooleanStrictOrNull() == true) 1.toByte() else 0.toByte())
        }
    }

    fun matches(plugin: JavaPlugin, container: PersistentDataContainer): Boolean {
        val namespacedKey = namespacedKey(plugin) ?: return false
        return when (type) {
            PersistentTagType.STRING -> container.get(namespacedKey, PersistentDataType.STRING) == value
            PersistentTagType.INTEGER -> container.get(namespacedKey, PersistentDataType.INTEGER) == value.toIntOrNull()
            PersistentTagType.LONG -> container.get(namespacedKey, PersistentDataType.LONG) == value.toLongOrNull()
            PersistentTagType.DOUBLE -> container.get(namespacedKey, PersistentDataType.DOUBLE) == value.toDoubleOrNull()
            PersistentTagType.BOOLEAN -> container.get(namespacedKey, PersistentDataType.BYTE) == if (value.toBooleanStrictOrNull() == true) 1.toByte() else 0.toByte()
        }
    }

    private fun namespacedKey(plugin: JavaPlugin): NamespacedKey? {
        val normalized = key.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null
        val parts = normalized.split(':', limit = 2)
        return runCatching {
            if (parts.size == 2) {
                NamespacedKey(sanitizeNamespace(parts[0], plugin.name), sanitizeKey(parts[1]))
            } else {
                NamespacedKey(plugin, sanitizeKey(parts[0]))
            }
        }.getOrNull()
    }

    private fun sanitizeNamespace(value: String, fallback: String): String =
        value.replace(Regex("[^a-z0-9._-]"), "").ifBlank { fallback.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "") }

    private fun sanitizeKey(value: String): String =
        value.replace(Regex("[^a-z0-9/._-]"), "_").ifBlank { "item" }
}

enum class PersistentTagType {
    STRING,
    INTEGER,
    LONG,
    DOUBLE,
    BOOLEAN;

    companion object {
        fun from(value: String?): PersistentTagType =
            entries.firstOrNull { it.name.equals(value?.trim()?.replace('-', '_'), ignoreCase = true) } ?: STRING
    }
}

enum class TextMatchMode {
    EXACT,
    PLAIN_EQUALS,
    PLAIN_CONTAINS,
    DISABLED;

    companion object {
        fun from(value: String?): TextMatchMode =
            entries.firstOrNull { it.name.equals(value?.trim()?.replace('-', '_'), ignoreCase = true) } ?: EXACT
    }
}

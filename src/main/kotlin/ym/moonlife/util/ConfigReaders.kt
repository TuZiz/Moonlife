package ym.moonlife.util

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Biome
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.EntityType
import org.bukkit.potion.PotionEffectType
import ym.moonlife.core.AmountRange
import ym.moonlife.core.IntRangeRule
import java.util.Locale

object ConfigReaders {
    fun stringSet(section: ConfigurationSection, path: String): Set<String> =
        section.getStringList(path)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "*" }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

    inline fun <reified E : Enum<E>> enumSet(section: ConfigurationSection, path: String): Set<E> =
        section.getStringList(path)
            .asSequence()
            .mapNotNull { valueOfEnum<E>(it) }
            .toSet()

    fun materialSet(section: ConfigurationSection, path: String): Set<Material> =
        section.getStringList(path).mapNotNull { Material.matchMaterial(it) }.toSet()

    fun biomeSet(section: ConfigurationSection, path: String): Set<Biome> =
        section.getStringList(path).mapNotNull { biome(it) }.toSet()

    fun entityType(section: ConfigurationSection, path: String): EntityType? =
        valueOfEnum(section.getString(path))

    fun potionType(name: String): PotionEffectType? =
        runCatching { PotionEffectType.getByName(name.uppercase(Locale.ROOT)) }.getOrNull()

    fun biome(name: String): Biome? {
        val normalized = name.trim().lowercase(Locale.ROOT).replace('-', '_')
        if (normalized.isEmpty()) return null
        val key = normalized.removePrefix("minecraft:")
        return runCatching {
            val registryClass = Class.forName("org.bukkit.Registry")
            val registry = registryClass.fields.firstOrNull { it.name == "BIOME" }?.get(null) ?: return@runCatching null
            val method = registry.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 1 } ?: return@runCatching null
            method.invoke(registry, NamespacedKey.minecraft(key)) as? Biome
        }.getOrNull() ?: runCatching {
            Biome::class.java.fields.firstOrNull { it.name.equals(normalized.uppercase(Locale.ROOT), ignoreCase = true) }?.get(null) as? Biome
        }.getOrNull()
    }

    fun intRange(section: ConfigurationSection, path: String, default: IntRangeRule): IntRangeRule {
        val values = section.getIntegerList(path)
        return when {
            values.size >= 2 -> IntRangeRule(values[0], values[1])
            values.size == 1 -> IntRangeRule(values[0], values[0])
            else -> default
        }
    }

    fun amountRange(section: ConfigurationSection, path: String, default: AmountRange = AmountRange(1, 1)): AmountRange {
        val values = section.getIntegerList(path)
        return when {
            values.size >= 2 -> AmountRange(values[0], values[1])
            values.size == 1 -> AmountRange(values[0], values[0])
            else -> default
        }
    }

    inline fun <reified E : Enum<E>> valueOfEnum(value: String?): E? {
        if (value == null) return null
        return runCatching { enumValueOf<E>(value.trim().uppercase(Locale.ROOT).replace('-', '_')) }.getOrNull()
    }
}

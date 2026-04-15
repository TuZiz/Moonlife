package ym.moonlife.buff

import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Locale
import java.util.UUID

class AttributeModifierService(private val plugin: Plugin) {
    private val knownAttributes: List<Attribute> by lazy { discoverAttributes() }

    fun apply(player: Player, plan: BuffPlan) {
        clear(player)
        plan.attributes.forEachIndexed { index, buff ->
            val attribute = resolveAttribute(buff.attribute) ?: return@forEachIndexed
            val instance = player.getAttribute(attribute) ?: return@forEachIndexed
            val operation = resolveOperation(buff.operation) ?: return@forEachIndexed
            val modifier = createModifier("moonlife_${index}_${buff.attribute.lowercase(Locale.ROOT)}", buff.amount, operation)
                ?: return@forEachIndexed
            runCatching { instance.addModifier(modifier) }
        }
    }

    fun clear(player: Player) {
        knownAttributes.forEach { attribute ->
            val instance = player.getAttribute(attribute) ?: return@forEach
            instance.modifiers
                .filter { modifier -> isMoonlifeModifier(modifier) }
                .forEach { modifier -> runCatching { instance.removeModifier(modifier) } }
        }
    }

    private fun resolveAttribute(raw: String): Attribute? {
        val normalized = raw.trim().uppercase(Locale.ROOT).replace('-', '_')
        val candidates = listOf(
            normalized,
            "GENERIC_$normalized",
            normalized.removePrefix("GENERIC_"),
            normalized.removePrefix("GENERIC_").lowercase(Locale.ROOT)
        ).distinct()
        return knownAttributes.firstOrNull { attribute ->
            val names = attributeNames(attribute)
            candidates.any { candidate -> candidate in names }
        }
    }

    private fun discoverAttributes(): List<Attribute> {
        Attribute::class.java.enumConstants?.let { return it.toList() }
        val values = runCatching {
            Attribute::class.java.getMethod("values").invoke(null) as? Array<*>
        }.getOrNull()
        if (values != null) return values.filterIsInstance<Attribute>()
        return runCatching {
            val registry = Class.forName("org.bukkit.Registry").getField("ATTRIBUTE").get(null)
            val iterator = registry.javaClass.methods
                .firstOrNull { method -> method.name == "iterator" && method.parameterCount == 0 }
                ?.invoke(registry) as? Iterator<*>
            iterator?.asSequence()?.filterIsInstance<Attribute>()?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun attributeNames(attribute: Attribute): Set<String> {
        val names = mutableSetOf<String>()
        runCatching { names += attribute.name().uppercase(Locale.ROOT) }
        runCatching {
            val key = attribute.key
            names += key.key.uppercase(Locale.ROOT)
            names += key.key.lowercase(Locale.ROOT)
            names += key.toString().uppercase(Locale.ROOT)
        }
        return names
    }

    private fun resolveOperation(raw: String): AttributeModifier.Operation? {
        val normalized = raw.trim().uppercase(Locale.ROOT).replace('-', '_')
        val candidates = listOf(normalized, "ADD_NUMBER", "ADD_SCALAR", "MULTIPLY_SCALAR_1").distinct()
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { AttributeModifier.Operation.valueOf(candidate) }.getOrNull()
        }
    }

    private fun createModifier(name: String, amount: Double, operation: AttributeModifier.Operation): AttributeModifier? = runCatching {
        val namespacedKey = NamespacedKey(plugin, name.take(50))
        val constructors = AttributeModifier::class.java.constructors
        constructors.firstOrNull { it.parameterCount == 4 && it.parameterTypes[0] == UUID::class.java }?.let { constructor ->
            return@runCatching constructor.newInstance(UUID.nameUUIDFromBytes(name.toByteArray()), name, amount, operation) as AttributeModifier
        }
        constructors.firstOrNull { it.parameterCount == 3 && it.parameterTypes[0] == NamespacedKey::class.java }?.let { constructor ->
            return@runCatching constructor.newInstance(namespacedKey, amount, operation) as AttributeModifier
        }
        constructors.firstOrNull { it.parameterCount == 4 && it.parameterTypes[0] == NamespacedKey::class.java }?.let { constructor ->
            val slotGroup = runCatching {
                val clazz = Class.forName("org.bukkit.inventory.EquipmentSlotGroup")
                clazz.fields.firstOrNull { it.name == "ANY" }?.get(null)
            }.getOrNull()
            return@runCatching constructor.newInstance(namespacedKey, amount, operation, slotGroup) as AttributeModifier
        }
        null
    }.getOrNull()

    private fun isMoonlifeModifier(modifier: AttributeModifier): Boolean =
        runCatching {
            val key = modifier.javaClass.methods.firstOrNull { it.name == "getKey" && it.parameterCount == 0 }?.invoke(modifier)
            key?.toString()?.startsWith("${plugin.name.lowercase(Locale.ROOT)}:moonlife_") == true
        }.getOrDefault(false) || modifier.name.startsWith("moonlife_")
}

package ym.moonlife.buff

import ym.moonlife.core.EcologyContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class BuffEngine(private val rules: List<BuffRule>) {
    private val enabledRules = rules.filter { it.enabled }

    fun plan(context: EcologyContext): BuffPlan {
        val matched = enabledRules.filter { matches(it, context) }
        if (matched.isEmpty()) return BuffPlan.EMPTY
        val selected = matched.filter { it.conflictStrategy == BuffConflictStrategy.EXCLUSIVE }
            .maxWithOrNull(compareBy<BuffRule> { it.priority }.thenBy { it.id })
            ?.let { listOf(it) }
            ?: matched

        val potions = linkedMapOf<String, PotionBuff>()
        val attributes = mutableListOf<AttributeBuff>()
        var outgoing = 1.0
        var incoming = 1.0
        var exp = 1.0
        var drops = 1.0
        val tags = mutableSetOf<String>()

        selected.sortedBy { it.priority }.forEach { rule ->
            rule.potions.forEach { potion ->
                val existing = potions[potion.type.uppercase(Locale.ROOT)]
                potions[potion.type.uppercase(Locale.ROOT)] = resolvePotion(rule, existing, potion)
            }
            attributes += rule.attributes
            if (rule.movementSpeedModifier != 0.0) attributes += AttributeBuff("MOVEMENT_SPEED", rule.movementSpeedModifier, "ADD_SCALAR")
            if (rule.attackModifier != 0.0) attributes += AttributeBuff("ATTACK_DAMAGE", rule.attackModifier, "ADD_NUMBER")
            outgoing = resolveNumber(rule, outgoing, rule.damageMultiplier)
            incoming = resolveNumber(rule, incoming, (1.0 - rule.resistanceModifier).coerceAtLeast(0.0))
            exp = resolveNumber(rule, exp, rule.experienceMultiplier)
            drops = resolveNumber(rule, drops, rule.dropMultiplier)
            tags += rule.customTags
        }

        return BuffPlan(
            ruleIds = selected.map { it.id }.toSet(),
            potions = potions.values.toList(),
            attributes = attributes,
            outgoingDamageMultiplier = outgoing,
            incomingDamageMultiplier = incoming,
            experienceMultiplier = exp,
            dropMultiplier = drops,
            customTags = tags
        )
    }

    private fun matches(rule: BuffRule, context: EcologyContext): Boolean {
        if (rule.worlds.isNotEmpty() && !rule.worlds.contains(context.snapshot.worldName.lowercase(Locale.ROOT))) return false
        if (rule.biomes.isNotEmpty() && !rule.biomes.contains(context.biome)) return false
        if (rule.moonPhases.isNotEmpty() && !rule.moonPhases.contains(context.snapshot.moonPhase)) return false
        if (rule.solarPhases.isNotEmpty() && !rule.solarPhases.contains(context.snapshot.solarPhase)) return false
        if (rule.weather.isNotEmpty() && !rule.weather.contains(context.snapshot.weather)) return false
        if (rule.wildernessOnly && !context.wilderness) return false
        if (rule.underground != null && rule.underground != context.underground) return false
        if (rule.inWater != null && rule.inWater != context.inWater) return false
        if (rule.sneaking != null && rule.sneaking != context.sneaking) return false
        if (rule.permission != null && context.player?.hasPermission(rule.permission) != true) return false
        return true
    }

    private fun resolvePotion(rule: BuffRule, existing: PotionBuff?, next: PotionBuff): PotionBuff {
        existing ?: return next
        return when (rule.conflictStrategy) {
            BuffConflictStrategy.MAX -> if (next.amplifier >= existing.amplifier) next else existing
            BuffConflictStrategy.MIN -> if (next.amplifier <= existing.amplifier) next else existing
            BuffConflictStrategy.STACK -> next.copy(amplifier = existing.amplifier + next.amplifier + 1)
            BuffConflictStrategy.OVERRIDE, BuffConflictStrategy.PRIORITY, BuffConflictStrategy.EXCLUSIVE -> next
        }
    }

    private fun resolveNumber(rule: BuffRule, current: Double, next: Double): Double =
        when (rule.conflictStrategy) {
            BuffConflictStrategy.STACK -> current * next
            BuffConflictStrategy.MAX -> max(current, next)
            BuffConflictStrategy.MIN -> min(current, next)
            BuffConflictStrategy.OVERRIDE, BuffConflictStrategy.PRIORITY, BuffConflictStrategy.EXCLUSIVE -> next
        }
}

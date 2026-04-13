package ym.moonlife.spawn

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Entity
import ym.moonlife.hook.MythicMobsHook
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TrackedSpawnRepository(private val mythicMobsHook: () -> MythicMobsHook) {
    private val byRuleWorld = ConcurrentHashMap<String, MutableSet<UUID>>()

    fun track(rule: SpawnRule, entity: Entity) {
        entity.addScoreboardTag(TAG_PLUGIN)
        entity.addScoreboardTag("$TAG_RULE_PREFIX${rule.id}")
        rule.tags.forEach { entity.addScoreboardTag(it) }
        byRuleWorld.computeIfAbsent(key(rule.id, entity.world.name)) { ConcurrentHashMap.newKeySet() }.add(entity.uniqueId)
    }

    fun untrack(entity: Entity) {
        val ruleTag = entity.scoreboardTags.firstOrNull { it.startsWith(TAG_RULE_PREFIX) } ?: return
        val ruleId = ruleTag.removePrefix(TAG_RULE_PREFIX)
        byRuleWorld[key(ruleId, entity.world.name)]?.remove(entity.uniqueId)
    }

    fun worldCount(rule: SpawnRule, worldName: String): Int {
        val set = byRuleWorld[key(rule.id, worldName)] ?: return 0
        return set.size
    }

    fun chunkCount(rule: SpawnRule, chunk: Chunk): Int =
        chunk.entities.count { entity ->
            when (val target = rule.target) {
                is VanillaSpawnTarget -> entity.type == target.entityType
                is MythicSpawnTarget -> mythicMobsHook().internalName(entity)?.equals(target.mobId, ignoreCase = true) == true
            }
        }

    fun cleanup() {
        byRuleWorld.values.forEach { set ->
            set.removeIf { uuid -> Bukkit.getEntity(uuid) == null }
        }
    }

    private fun key(ruleId: String, worldName: String): String = "$ruleId:${worldName.lowercase()}"

    companion object {
        const val TAG_PLUGIN = "moonlife_spawned"
        const val TAG_RULE_PREFIX = "moonlife_rule:"
    }
}

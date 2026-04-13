package ym.moonlife.spawn

import ym.moonlife.hook.MythicMobsHook

class SpawnTargetResolver(
    private val mythicMobsHook: () -> MythicMobsHook,
    private val mythicMobsOnly: () -> Boolean
) {
    fun isAvailable(target: SpawnTarget): Boolean = when (target) {
        is VanillaSpawnTarget -> !mythicMobsOnly()
        is MythicSpawnTarget -> mythicMobsHook().available
    }
}

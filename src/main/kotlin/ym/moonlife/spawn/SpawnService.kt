package ym.moonlife.spawn

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import ym.moonlife.config.ConfigService
import ym.moonlife.core.EnvironmentSnapshotService
import ym.moonlife.locale.MessageService
import ym.moonlife.scheduler.ScheduledTaskHandle
import ym.moonlife.scheduler.SchedulerFacade
import ym.moonlife.util.ConfigReaders
import ym.moonlife.util.PerformanceGuard
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SpawnService(
    private val plugin: JavaPlugin,
    private val configService: ConfigService,
    private val environment: EnvironmentSnapshotService,
    private val scheduler: SchedulerFacade,
    private val messages: MessageService,
    private val mythicSpawnAdapter: MythicSpawnAdapter,
    private val vanillaSpawnAdapter: VanillaSpawnAdapter,
    private val targetResolver: SpawnTargetResolver,
    private val tracker: TrackedSpawnRepository,
    private val performanceGuard: PerformanceGuard
) : Listener {
    private var tickTask: ScheduledTaskHandle = ScheduledTaskHandle.NOOP
    private var cleanupTask: ScheduledTaskHandle = ScheduledTaskHandle.NOOP
    private var engine = SpawnRuleEngine(emptyList(), targetResolver)
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val playerPositions = ConcurrentHashMap<java.util.UUID, CachedPlayerPosition>()

    fun start() {
        reload()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun stop() {
        tickTask.cancel()
        cleanupTask.cancel()
    }

    fun reload() {
        tickTask.cancel()
        cleanupTask.cancel()
        engine = SpawnRuleEngine(configService.current.spawnRules, targetResolver)
        val config = configService.current.main.spawn
        if (!config.enabled) return
        tickTask = scheduler.global.runTimer(config.intervalTicks, config.intervalTicks) { tick() }
        cleanupTask = scheduler.global.runTimer(config.cleanupIntervalTicks, config.cleanupIntervalTicks) { tracker.cleanup() }
    }

    fun preview(player: Player): List<SpawnRule> = engine.preview(environment.context(player.location, player))

    fun testSpawn(player: Player): Boolean {
        val location = player.location
        scheduler.region.run(location) {
            val context = environment.context(location, player)
            val rule = engine.choose(context, { countersFor(it, context.location) }) ?: return@run
            spawnRule(rule, context.location)
        }
        return true
    }

    private fun tick() {
        if (!performanceGuard.allowHeavyWork()) return
        val config = configService.current.main.spawn
        val players = Bukkit.getOnlinePlayers()
            .filter { it.isOnline && it.isValid }
            .shuffled()
            .take(config.maxPlayerSamplesPerCycle)
            .toList()
        players.forEach { player ->
            scheduler.entity.run(player) { attemptAround(player) }
        }
    }

    private fun attemptAround(player: Player) {
        cachePlayerPosition(player)
        val config = configService.current.main.spawn
        repeat(config.attemptsPerPlayer) {
            val center = player.location
            val candidate = sampleCandidate(center) ?: return@repeat
            scheduler.region.run(candidate) {
                for (spawnLocation in resolveSpawnLocations(candidate)) {
                    val context = environment.context(spawnLocation, player)
                    val rule = engine.choose(context, { countersFor(it, spawnLocation) }) ?: continue
                    spawnRule(rule, spawnLocation)
                    return@run
                }
            }
        }
    }

    private fun sampleCandidate(center: Location): Location? {
        val world = center.world ?: return null
        val config = configService.current.main.spawn
        val radius = Random.nextInt(config.spawnRadiusMin, config.spawnRadiusMax + 1)
        val angle = Random.nextDouble(0.0, Math.PI * 2.0)
        val x = center.blockX + (cos(angle) * radius).toInt()
        val z = center.blockZ + (sin(angle) * radius).toInt()
        return Location(world, x + 0.5, center.y, z + 0.5)
    }

    private fun resolveSpawnLocations(candidate: Location): List<Location> {
        val world = candidate.world ?: return emptyList()
        val highest = runCatching { world.getHighestBlockYAt(candidate.blockX, candidate.blockZ) }.getOrNull() ?: return emptyList()
        val surface = Location(world, candidate.x, (highest + 1).toDouble(), candidate.z)
        val locations = mutableListOf<Location>()
        if (isSpawnable(surface)) locations += surface
        val minHeight = runCatching { world.minHeight }.getOrDefault(-64)
        val maxUnderground = highest - 10
        if (maxUnderground > minHeight + 8) {
            repeat(4) {
                val y = Random.nextInt(minHeight + 4, maxUnderground)
                val underground = Location(world, candidate.x, y.toDouble(), candidate.z)
                if (isSpawnable(underground)) locations += underground
            }
        }
        return locations.shuffled()
    }

    private fun isSpawnable(location: Location): Boolean {
        val block = location.block
        val above = block.getRelative(0, 1, 0)
        val below = block.getRelative(0, -1, 0)
        if (!block.type.isAir || !above.type.isAir) return false
        if (below.type == Material.AIR || below.isLiquid) return false
        return true
    }

    private fun countersFor(rule: SpawnRule, location: Location): SpawnRuleCounters {
        val now = System.currentTimeMillis()
        val nextAllowed = cooldowns["${rule.id}:${location.world?.name?.lowercase(Locale.ROOT)}"] ?: 0L
        val worldName = location.world?.name ?: ""
        val nearby = playerPositions.values.count { position ->
            position.worldName == worldName &&
                position.distanceSquared(location.x, location.z) <= rule.nearbyPlayerRadius * rule.nearbyPlayerRadius
        }
        return SpawnRuleCounters(
            nearbyPlayers = nearby,
            chunkCount = tracker.chunkCount(rule, location.chunk),
            worldCount = tracker.worldCount(rule, worldName),
            cooldownReady = now >= nextAllowed
        )
    }

    private fun spawnRule(rule: SpawnRule, location: Location) {
        val amount = rule.amount.random()
        val entities = when (val target = rule.target) {
            is VanillaSpawnTarget -> vanillaSpawnAdapter.spawn(target, location, amount)
            is MythicSpawnTarget -> mythicSpawnAdapter.spawn(target, location, amount)
        }
        entities.forEach { entity -> afterSpawn(rule, entity) }
        if (entities.isNotEmpty()) {
            val key = "${rule.id}:${location.world?.name?.lowercase(Locale.ROOT)}"
            cooldowns[key] = System.currentTimeMillis() + rule.cooldownTicks * 50L
        }
    }

    private fun afterSpawn(rule: SpawnRule, entity: Entity) {
        tracker.track(rule, entity)
        applyEffects(rule, entity)
        if (configService.current.main.debug) {
            plugin.logger.info("Spawned ${rule.target.key} by rule ${rule.id} at ${entity.location.blockX},${entity.location.blockY},${entity.location.blockZ}")
        }
    }

    private fun applyEffects(rule: SpawnRule, entity: Entity) {
        val living = entity as? LivingEntity ?: return
        rule.effects.forEach { raw ->
            val parts = raw.split(":")
            val type = ConfigReaders.potionType(parts.getOrNull(0).orEmpty()) ?: return@forEach
            val amplifier = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val duration = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(20) ?: 200
            living.addPotionEffect(PotionEffect(type, duration, amplifier, true, false, true))
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        tracker.untrack(event.entity)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        cachePlayerPosition(event.player)
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to
        val from = event.from
        if (from.blockX == to.blockX && from.blockZ == to.blockZ && from.world == to.world) return
        val world = to.world
        playerPositions[event.player.uniqueId] = CachedPlayerPosition(world.name, to.x, to.z)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        playerPositions.remove(event.player.uniqueId)
    }

    private fun cachePlayerPosition(player: Player) {
        val location = player.location
        val world = location.world ?: return
        playerPositions[player.uniqueId] = CachedPlayerPosition(world.name, location.x, location.z)
    }

    private data class CachedPlayerPosition(val worldName: String, val x: Double, val z: Double) {
        fun distanceSquared(otherX: Double, otherZ: Double): Double {
            val dx = x - otherX
            val dz = z - otherZ
            return dx * dx + dz * dz
        }
    }
}

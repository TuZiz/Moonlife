package ym.moonlife.buff

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import ym.moonlife.config.ConfigService
import ym.moonlife.core.EnvironmentSnapshotService
import ym.moonlife.locale.MessageService
import ym.moonlife.scheduler.ScheduledTaskHandle
import ym.moonlife.scheduler.SchedulerFacade
import ym.moonlife.util.ConfigReaders
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.random.Random

class PlayerBuffService(
    private val plugin: JavaPlugin,
    private val configService: ConfigService,
    private val environment: EnvironmentSnapshotService,
    private val scheduler: SchedulerFacade,
    private val messages: MessageService,
    private val attributeService: AttributeModifierService
) : Listener {
    private var engine = BuffEngine(emptyList())
    private var task: ScheduledTaskHandle = ScheduledTaskHandle.NOOP
    private val activePlans = ConcurrentHashMap<UUID, BuffPlan>()

    fun start() {
        reload()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun stop() {
        task.cancel()
        Bukkit.getOnlinePlayers().forEach { player ->
            scheduler.entity.run(player) { clearPlayer(player) }
        }
        activePlans.clear()
    }

    fun reload() {
        task.cancel()
        engine = BuffEngine(configService.current.buffRules)
        if (!configService.current.main.buff.enabled) return
        val interval = configService.current.main.buff.refreshIntervalTicks
        task = scheduler.global.runTimer(40L, interval) { tick() }
    }

    fun testBuff(player: Player): BuffPlan {
        val plan = engine.plan(environment.context(player.location, player))
        scheduler.entity.run(player) { applyPlan(player, plan, notify = true) }
        return plan
    }

    fun preview(player: Player): BuffPlan =
        activePlans[player.uniqueId] ?: engine.plan(environment.context(player.location, player))

    private fun tick() {
        Bukkit.getOnlinePlayers().forEach { player ->
            scheduler.entity.run(player) {
                val plan = engine.plan(environment.context(player.location, player))
                applyPlan(player, plan, notify = false)
            }
        }
    }

    private fun applyPlan(player: Player, plan: BuffPlan, notify: Boolean) {
        val previous = activePlans.put(player.uniqueId, plan)
        applyPotions(player, plan)
        attributeService.apply(player, plan)
        applyTags(player, plan)
        if (notify && plan.ruleIds.isNotEmpty()) {
            messages.send(player, "buff.test.applied", mapOf("rules" to plan.ruleIds.joinToString(", ")))
        } else if (previous?.ruleIds != plan.ruleIds && plan.ruleIds.isNotEmpty() && configService.current.main.debug) {
            messages.actionBar(player, "buff.changed.actionbar", mapOf("rules" to plan.ruleIds.joinToString(", ")))
        }
    }

    private fun applyPotions(player: Player, plan: BuffPlan) {
        val threshold = configService.current.main.buff.potionRefreshThresholdTicks
        plan.potions.forEach { buff ->
            val type = ConfigReaders.potionType(buff.type) ?: return@forEach
            val current = player.getPotionEffect(type)
            if (current != null && current.amplifier >= buff.amplifier && current.duration > threshold) return@forEach
            player.addPotionEffect(PotionEffect(type, buff.durationTicks, buff.amplifier, buff.ambient, buff.particles, buff.icon))
        }
    }

    private fun applyTags(player: Player, plan: BuffPlan) {
        player.scoreboardTags.filter { it.startsWith(TAG_PREFIX) }.forEach { player.removeScoreboardTag(it) }
        plan.customTags.forEach { player.addScoreboardTag("$TAG_PREFIX$it") }
    }

    private fun clearPlayer(player: Player) {
        attributeService.clear(player)
        player.scoreboardTags.filter { it.startsWith(TAG_PREFIX) }.forEach { player.removeScoreboardTag(it) }
    }

    private fun plan(player: Player): BuffPlan = activePlans[player.uniqueId] ?: BuffPlan.EMPTY

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? Player
        if (damager != null) {
            event.damage *= plan(damager).outgoingDamageMultiplier
        }
        val victim = event.entity as? Player
        if (victim != null) {
            event.damage *= plan(victim).incomingDamageMultiplier
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val victim = event.entity as? Player ?: return
        event.damage *= plan(victim).incomingDamageMultiplier
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExperience(event: PlayerExpChangeEvent) {
        val multiplier = plan(event.player).experienceMultiplier
        event.amount = (event.amount * multiplier).toInt().coerceAtLeast(0)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        val multiplier = plan(killer).dropMultiplier
        if (multiplier <= 1.0) return
        val extra = multiplier - 1.0
        event.drops.toList().forEach { drop ->
            val guaranteed = floor(extra).toInt()
            val fractional = extra - guaranteed
            val amount = drop.amount * guaranteed + if (Random.nextDouble() < fractional) drop.amount else 0
            if (amount > 0) event.drops.add(drop.clone().also { it.amount = amount.coerceAtMost(drop.maxStackSize) })
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player)
        activePlans.remove(event.player.uniqueId)
    }

    companion object {
        private const val TAG_PREFIX = "moonlife_buff:"
    }
}

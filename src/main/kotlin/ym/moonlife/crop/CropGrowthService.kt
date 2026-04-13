package ym.moonlife.crop

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ym.moonlife.config.ConfigService
import ym.moonlife.core.EnvironmentSnapshotService
import ym.moonlife.scheduler.SchedulerFacade
import kotlin.math.floor
import kotlin.random.Random

class CropGrowthService(
    private val plugin: JavaPlugin,
    private val configService: ConfigService,
    private val environment: EnvironmentSnapshotService,
    private val scheduler: SchedulerFacade
) : Listener {
    private var engine = CropRuleEngine(emptyList())

    fun start() {
        reload()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun reload() {
        engine = CropRuleEngine(configService.current.cropRules)
    }

    fun preview(player: Player): List<CropRule> =
        engine.preview(environment.context(player.location, player))

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) {
        if (!configService.current.main.crop.enabled) return
        val block = event.block
        if (block.blockData !is Ageable) return
        val context = environment.context(block.location)
        val rule = engine.firstMatch(context, block) ?: return
        val multiplier = rule.growthMultiplier
        if (multiplier <= 0.0) {
            event.isCancelled = true
            return
        }
        if (multiplier < 1.0 && Random.nextDouble() > multiplier) {
            event.isCancelled = true
            return
        }
        val guaranteedExtra = floor(multiplier - 1.0).toInt().coerceAtLeast(0)
        val fractionalExtra = (multiplier - 1.0 - guaranteedExtra).coerceAtLeast(0.0)
        val extraSteps = (guaranteedExtra + if (Random.nextDouble() < fractionalExtra) 1 else 0)
            .coerceAtMost(configService.current.main.crop.maxBonusStepsPerEvent)
        if (extraSteps > 0 || Random.nextDouble() < rule.bonusGrowthChance) {
            val totalSteps = (extraSteps + 1).coerceAtMost(configService.current.main.crop.maxBonusStepsPerEvent)
            scheduler.region.runDelayed(block.location, 1L) {
                repeat(totalSteps) { advanceAge(block) }
            }
        }
        if (rule.mutationMaterial != null && Random.nextDouble() < rule.mutationChance) {
            scheduler.region.runDelayed(block.location, 2L) {
                mutate(block, rule.mutationMaterial)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        if (!configService.current.main.crop.enabled) return
        val context = environment.context(event.block.location, event.player)
        val rule = engine.firstMatch(context, event.block) ?: return
        if (!rule.boneMealInteraction || (event.player == null && !rule.automationApply)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!configService.current.main.crop.enabled) return
        val block = event.block
        val ageable = block.blockData as? Ageable ?: return
        if (ageable.age < ageable.maximumAge) return
        val context = environment.context(block.location, event.player)
        val rule = engine.firstMatch(context, block) ?: return
        if (Random.nextDouble() < rule.extraHarvestChance) {
            val drops = block.getDrops(event.player.inventory.itemInMainHand, event.player).map { it.clone() }
            scheduler.region.run(block.location) {
                drops.forEach { drop ->
                    if (drop.type != Material.AIR && drop.amount > 0) {
                        block.world.dropItemNaturally(block.location, ItemStack(drop.type, drop.amount))
                    }
                }
            }
        }
    }

    private fun advanceAge(block: Block) {
        val data = block.blockData as? Ageable ?: return
        if (data.age >= data.maximumAge) return
        data.age += 1
        block.blockData = data
    }

    private fun mutate(block: Block, material: Material) {
        if (!material.isBlock) return
        block.type = material
    }
}

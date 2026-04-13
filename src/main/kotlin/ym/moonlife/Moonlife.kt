package ym.moonlife

import org.bukkit.plugin.java.JavaPlugin
import ym.moonlife.buff.AttributeModifierService
import ym.moonlife.buff.PlayerBuffService
import ym.moonlife.command.EcologyCommand
import ym.moonlife.config.ConfigService
import ym.moonlife.core.EnvironmentSnapshotService
import ym.moonlife.crop.CropGrowthService
import ym.moonlife.hook.HookManager
import ym.moonlife.locale.LocaleService
import ym.moonlife.locale.MessageService
import ym.moonlife.moon.MoonPhaseService
import ym.moonlife.platform.PlatformDetector
import ym.moonlife.scheduler.SchedulerFacade
import ym.moonlife.scheduler.SchedulerFactory
import ym.moonlife.solar.SolarPhaseService
import ym.moonlife.spawn.MythicSpawnAdapter
import ym.moonlife.spawn.SpawnService
import ym.moonlife.spawn.SpawnTargetResolver
import ym.moonlife.spawn.TrackedSpawnRepository
import ym.moonlife.spawn.VanillaSpawnAdapter
import ym.moonlife.util.PerformanceGuard

class Moonlife : JavaPlugin() {
    private lateinit var scheduler: SchedulerFacade
    private lateinit var configService: ConfigService
    private lateinit var localeService: LocaleService
    private lateinit var messageService: MessageService
    private lateinit var hookManager: HookManager
    private lateinit var moonPhaseService: MoonPhaseService
    private lateinit var solarPhaseService: SolarPhaseService
    private lateinit var environmentSnapshotService: EnvironmentSnapshotService
    private lateinit var spawnService: SpawnService
    private lateinit var cropGrowthService: CropGrowthService
    private lateinit var playerBuffService: PlayerBuffService

    override fun onEnable() {
        val capabilities = PlatformDetector.detect()
        logger.info("Starting Moonlife on ${capabilities.serverName} ${capabilities.serverVersion}; folia=${capabilities.folia}; paper=${capabilities.paper}")

        scheduler = SchedulerFactory.create(this, capabilities)
        configService = ConfigService(this)
        configService.loadInitial()
        localeService = LocaleService(this)
        localeService.reload()
        messageService = MessageService(localeService, scheduler)
        hookManager = HookManager(this, configService)
        hookManager.load()

        moonPhaseService = MoonPhaseService(configService, scheduler, messageService)
        solarPhaseService = SolarPhaseService(configService, scheduler, messageService)
        environmentSnapshotService = EnvironmentSnapshotService(moonPhaseService, solarPhaseService, hookManager)
        val targetResolver = SpawnTargetResolver({ hookManager.mythicMobs }, { configService.current.main.spawn.mythicMobsOnly })
        val tracker = TrackedSpawnRepository { hookManager.mythicMobs }
        val performanceGuard = PerformanceGuard { configService.current.main.performance }
        spawnService = SpawnService(
            plugin = this,
            configService = configService,
            environment = environmentSnapshotService,
            scheduler = scheduler,
            messages = messageService,
            mythicSpawnAdapter = MythicSpawnAdapter { hookManager.mythicMobs },
            vanillaSpawnAdapter = VanillaSpawnAdapter(),
            targetResolver = targetResolver,
            tracker = tracker,
            performanceGuard = performanceGuard
        )
        cropGrowthService = CropGrowthService(this, configService, environmentSnapshotService, scheduler)
        playerBuffService = PlayerBuffService(
            plugin = this,
            configService = configService,
            environment = environmentSnapshotService,
            scheduler = scheduler,
            messages = messageService,
            attributeService = AttributeModifierService(this)
        )
        hookManager.registerPlaceholderExpansion(
            moonPhaseService,
            solarPhaseService,
            messageService,
            spawnService,
            cropGrowthService,
            playerBuffService
        )

        moonPhaseService.start()
        solarPhaseService.start()
        spawnService.start()
        cropGrowthService.start()
        playerBuffService.start()
        registerCommands()
    }

    override fun onDisable() {
        if (::playerBuffService.isInitialized) playerBuffService.stop()
        if (::spawnService.isInitialized) spawnService.stop()
        if (::moonPhaseService.isInitialized) moonPhaseService.stop()
        if (::solarPhaseService.isInitialized) solarPhaseService.stop()
        if (::hookManager.isInitialized) hookManager.unregisterPlaceholderExpansion()
        if (::scheduler.isInitialized) scheduler.cancelAll()
    }

    private fun registerCommands() {
        val command = EcologyCommand(
            configService = configService,
            messages = messageService,
            moonPhaseService = moonPhaseService,
            solarPhaseService = solarPhaseService,
            spawnService = spawnService,
            buffService = playerBuffService,
            onReload = ::reloadAll
        )
        listOf("ecology", "lunarphase", "solarphase").forEach { name ->
            getCommand(name)?.setExecutor(command)
            getCommand(name)?.tabCompleter = command
        }
    }

    private fun reloadAll(): Boolean {
        val result = configService.reload()
        if (!result.success) {
            result.errors.forEach { logger.severe(it) }
            return false
        }
        localeService.reload()
        hookManager.reload()
        moonPhaseService.start()
        solarPhaseService.start()
        spawnService.reload()
        cropGrowthService.reload()
        playerBuffService.reload()
        hookManager.registerPlaceholderExpansion(
            moonPhaseService,
            solarPhaseService,
            messageService,
            spawnService,
            cropGrowthService,
            playerBuffService
        )
        return true
    }
}

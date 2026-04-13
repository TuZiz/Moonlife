package ym.moonlife.scheduler

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class FoliaSchedulerFacade(private val plugin: Plugin, private val fallback: BukkitSchedulerFacade) : SchedulerFacade {
    private val tasks = mutableSetOf<ScheduledTaskHandle>()

    override val async: AsyncExecutor = object : AsyncExecutor {
        override fun run(task: () -> Unit): ScheduledTaskHandle =
            invokeAsync("runNow", listOf(plugin, consumer(task))) ?: fallback.async.run(task)

        override fun runDelayed(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            invokeAsync("runDelayed", listOf(plugin, consumer(task), ticksToMillis(delayTicks), TimeUnit.MILLISECONDS))
                ?: fallback.async.runDelayed(delayTicks, task)

        override fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            invokeAsync("runAtFixedRate", listOf(plugin, consumer(task), ticksToMillis(delayTicks), ticksToMillis(periodTicks), TimeUnit.MILLISECONDS))
                ?: fallback.async.runTimer(delayTicks, periodTicks, task)
    }

    override val global: GlobalExecutor = object : GlobalExecutor {
        override fun run(task: () -> Unit): ScheduledTaskHandle =
            invokeGlobal("run", listOf(plugin, consumer(task))) ?: fallback.global.run(task)

        override fun runDelayed(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            invokeGlobal("runDelayed", listOf(plugin, consumer(task), delayTicks)) ?: fallback.global.runDelayed(delayTicks, task)

        override fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            invokeGlobal("runAtFixedRate", listOf(plugin, consumer(task), delayTicks, periodTicks))
                ?: fallback.global.runTimer(delayTicks, periodTicks, task)
    }

    override val region: RegionExecutor = object : RegionExecutor {
        override fun run(location: Location, task: () -> Unit): ScheduledTaskHandle =
            invokeRegion("run", listOf(plugin, location, consumer(task))) ?: fallback.region.run(location, task)

        override fun runDelayed(location: Location, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            invokeRegion("runDelayed", listOf(plugin, location, consumer(task), delayTicks)) ?: fallback.region.runDelayed(location, delayTicks, task)
    }

    override val entity: EntityExecutor = object : EntityExecutor {
        override fun run(entity: Entity, task: () -> Unit): ScheduledTaskHandle =
            invokeEntity(entity, "run", listOf(plugin, consumer(task), Runnable {})) ?: fallback.entity.run(entity, task)

        override fun runDelayed(entity: Entity, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            invokeEntity(entity, "runDelayed", listOf(plugin, consumer(task), Runnable {}, delayTicks)) ?: fallback.entity.runDelayed(entity, delayTicks, task)
    }

    override fun cancelAll() {
        tasks.toList().forEach { it.cancel() }
        tasks.clear()
        fallback.cancelAll()
    }

    private fun invokeAsync(methodName: String, args: List<Any>): ScheduledTaskHandle? {
        val scheduler = runCatching { Bukkit.getServer().javaClass.getMethod("getAsyncScheduler").invoke(Bukkit.getServer()) }.getOrNull()
            ?: return null
        return invokeBest(scheduler, methodName, args)
    }

    private fun invokeGlobal(methodName: String, args: List<Any>): ScheduledTaskHandle? {
        val scheduler = runCatching { Bukkit.getServer().javaClass.getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer()) }.getOrNull()
            ?: return null
        return invokeBest(scheduler, methodName, args)
    }

    private fun invokeRegion(methodName: String, args: List<Any>): ScheduledTaskHandle? {
        val scheduler = runCatching { Bukkit.getServer().javaClass.getMethod("getRegionScheduler").invoke(Bukkit.getServer()) }.getOrNull()
            ?: return null
        return invokeBest(scheduler, methodName, args)
    }

    private fun invokeEntity(entity: Entity, methodName: String, args: List<Any>): ScheduledTaskHandle? {
        val scheduler = runCatching { entity.javaClass.getMethod("getScheduler").invoke(entity) }.getOrNull()
            ?: return null
        return invokeBest(scheduler, methodName, args)
    }

    private fun invokeBest(target: Any, methodName: String, args: List<Any>): ScheduledTaskHandle? = runCatching {
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterCount == args.size &&
                method.parameterTypes.zip(args).all { (type, value) -> type.isAssignableFrom(value.javaClass) || type.isPrimitive }
        } ?: return null
        val result = method.invoke(target, *args.toTypedArray())
        track(ReflectiveScheduledTaskHandle(result))
    }.getOrNull()

    private fun consumer(task: () -> Unit): Consumer<Any> = Consumer { task() }

    private fun ticksToMillis(ticks: Long): Long = ticks.coerceAtLeast(1) * 50L

    private fun track(handle: ScheduledTaskHandle): ScheduledTaskHandle {
        tasks += handle
        return handle
    }
}

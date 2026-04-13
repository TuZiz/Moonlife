package ym.moonlife.scheduler

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

class BukkitSchedulerFacade(private val plugin: Plugin) : SchedulerFacade {
    private val tasks = mutableSetOf<ScheduledTaskHandle>()

    override val async: AsyncExecutor = object : AsyncExecutor {
        override fun run(task: () -> Unit): ScheduledTaskHandle =
            track(BukkitScheduledTaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable(task))))

        override fun runDelayed(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            track(BukkitScheduledTaskHandle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable(task), delayTicks)))

        override fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            track(BukkitScheduledTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable(task), delayTicks, periodTicks)))
    }

    override val global: GlobalExecutor = object : GlobalExecutor {
        override fun run(task: () -> Unit): ScheduledTaskHandle =
            track(BukkitScheduledTaskHandle(Bukkit.getScheduler().runTask(plugin, Runnable(task))))

        override fun runDelayed(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            track(BukkitScheduledTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, Runnable(task), delayTicks)))

        override fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle =
            track(BukkitScheduledTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, Runnable(task), delayTicks, periodTicks)))
    }

    override val region: RegionExecutor = object : RegionExecutor {
        override fun run(location: Location, task: () -> Unit): ScheduledTaskHandle = global.run(task)
        override fun runDelayed(location: Location, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle = global.runDelayed(delayTicks, task)
    }

    override val entity: EntityExecutor = object : EntityExecutor {
        override fun run(entity: Entity, task: () -> Unit): ScheduledTaskHandle {
            if (!entity.isValid) return ScheduledTaskHandle.NOOP
            return global.run(task)
        }

        override fun runDelayed(entity: Entity, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle {
            if (!entity.isValid) return ScheduledTaskHandle.NOOP
            return global.runDelayed(delayTicks, task)
        }
    }

    override fun cancelAll() {
        tasks.toList().forEach { it.cancel() }
        tasks.clear()
    }

    private fun track(handle: ScheduledTaskHandle): ScheduledTaskHandle {
        tasks += handle
        return handle
    }
}

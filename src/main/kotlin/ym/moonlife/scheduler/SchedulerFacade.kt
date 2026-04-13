package ym.moonlife.scheduler

import org.bukkit.Location
import org.bukkit.entity.Entity

interface AsyncExecutor {
    fun run(task: () -> Unit): ScheduledTaskHandle
    fun runDelayed(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle
    fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle
}

interface GlobalExecutor {
    fun run(task: () -> Unit): ScheduledTaskHandle
    fun runDelayed(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle
    fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle
}

interface RegionExecutor {
    fun run(location: Location, task: () -> Unit): ScheduledTaskHandle
    fun runDelayed(location: Location, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle
}

interface EntityExecutor {
    fun run(entity: Entity, task: () -> Unit): ScheduledTaskHandle
    fun runDelayed(entity: Entity, delayTicks: Long, task: () -> Unit): ScheduledTaskHandle
}

interface SchedulerFacade {
    val async: AsyncExecutor
    val global: GlobalExecutor
    val region: RegionExecutor
    val entity: EntityExecutor
    fun cancelAll()
}

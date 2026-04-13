package ym.moonlife.scheduler

import org.bukkit.scheduler.BukkitTask

class BukkitScheduledTaskHandle(private val task: BukkitTask) : ScheduledTaskHandle {
    override fun cancel() {
        task.cancel()
    }
}

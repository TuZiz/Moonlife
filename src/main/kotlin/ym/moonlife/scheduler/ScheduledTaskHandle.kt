package ym.moonlife.scheduler

fun interface ScheduledTaskHandle {
    fun cancel()

    companion object {
        val NOOP = ScheduledTaskHandle {}
    }
}

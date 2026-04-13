package ym.moonlife.scheduler

class ReflectiveScheduledTaskHandle(private val delegate: Any?) : ScheduledTaskHandle {
    override fun cancel() {
        val task = delegate ?: return
        runCatching {
            task.javaClass.methods.firstOrNull { it.name == "cancel" && it.parameterCount == 0 }?.invoke(task)
        }
    }
}

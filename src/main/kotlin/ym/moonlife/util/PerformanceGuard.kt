package ym.moonlife.util

import org.bukkit.Bukkit

data class PerformanceLimits(
    val enabled: Boolean,
    val minTps: Double,
    val maxMspt: Double
)

class PerformanceGuard(private val limitsProvider: () -> PerformanceLimits) {
    fun allowHeavyWork(): Boolean {
        val limits = limitsProvider()
        if (!limits.enabled) return true
        val tps = currentTps()
        val mspt = currentMspt()
        if (tps != null && tps < limits.minTps) return false
        if (mspt != null && mspt > limits.maxMspt) return false
        return true
    }

    private fun currentTps(): Double? = runCatching {
        val method = Bukkit.getServer().javaClass.methods.firstOrNull { it.name == "getTPS" && it.parameterCount == 0 }
            ?: return null
        val values = method.invoke(Bukkit.getServer()) as? DoubleArray ?: return null
        values.firstOrNull()
    }.getOrNull()

    private fun currentMspt(): Double? = runCatching {
        val method = Bukkit.getServer().javaClass.methods.firstOrNull { it.name == "getAverageTickTime" && it.parameterCount == 0 }
            ?: return null
        method.invoke(Bukkit.getServer()) as? Double
    }.getOrNull()
}

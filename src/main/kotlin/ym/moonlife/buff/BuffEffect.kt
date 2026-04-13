package ym.moonlife.buff

data class PotionBuff(
    val type: String,
    val amplifier: Int,
    val durationTicks: Int,
    val ambient: Boolean,
    val particles: Boolean,
    val icon: Boolean
)

data class AttributeBuff(
    val attribute: String,
    val amount: Double,
    val operation: String
)

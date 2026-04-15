package ym.moonlife.feature

enum class DangerLevel {
    SAFE,
    WATCH,
    DANGER,
    NIGHTMARE;

    companion object {
        fun fromScore(score: Int): DangerLevel = when {
            score >= 90 -> NIGHTMARE
            score >= 55 -> DANGER
            score >= 25 -> WATCH
            else -> SAFE
        }
    }
}

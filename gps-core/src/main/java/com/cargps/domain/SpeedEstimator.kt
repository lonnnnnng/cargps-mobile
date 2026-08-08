package com.cargps.domain

enum class SpeedState {
    MOVING,
    STOPPED,
    UNKNOWN,
}

data class SpeedReading(
    val smoothedMps: Double,
    val state: SpeedState,
)

class SpeedEstimator(
    private val alpha: Double = 0.35,
    private val stopThresholdMps: Double = 2.0 / 3.6,
    private val stopSnapAfterMillis: Long = 2_000L,
) {
    private var previousSmoothedMps: Double? = null
    private var belowThresholdSinceMillis: Long? = null

    /**
     * 作者：long｜定位存储故障、暂停恢复或进程重建后，下一段速度不能沿用故障窗口前的平滑值。
     * 清理平滑器可以避免恢复首点被旧样本“补算”为跨故障速度。
     */
    fun reset() {
        previousSmoothedMps = null
        belowThresholdSinceMillis = null
    }

    fun update(speedMps: Double?, timestampMillis: Long): SpeedReading {
        val safeSpeed = speedMps?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return SpeedReading(previousSmoothedMps ?: 0.0, SpeedState.UNKNOWN)

        val smoothed = previousSmoothedMps?.let { previous ->
            previous + alpha * (safeSpeed - previous)
        } ?: safeSpeed
        previousSmoothedMps = smoothed

        if (safeSpeed <= stopThresholdMps) {
            if (belowThresholdSinceMillis == null) {
                belowThresholdSinceMillis = timestampMillis
            }
        } else {
            belowThresholdSinceMillis = null
        }

        val stoppedLongEnough = belowThresholdSinceMillis?.let {
            timestampMillis - it >= stopSnapAfterMillis
        } == true
        val displaySpeed = if (stoppedLongEnough) 0.0 else smoothed
        if (stoppedLongEnough) {
            previousSmoothedMps = 0.0
        }

        return SpeedReading(
            smoothedMps = displaySpeed,
            state = when {
                stoppedLongEnough -> SpeedState.STOPPED
                displaySpeed > stopThresholdMps -> SpeedState.MOVING
                else -> SpeedState.UNKNOWN
            },
        )
    }
}

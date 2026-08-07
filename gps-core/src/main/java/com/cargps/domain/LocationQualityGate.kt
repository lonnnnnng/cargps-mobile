package com.cargps.domain

data class SampleDecision(
    val displayable: Boolean,
    val connectsToPrevious: Boolean,
    val reason: String,
)

class LocationQualityGate(
    private val maxDistanceAccuracyMeters: Float = 30f,
    private val maxConnectGapMillis: Long = 10_000L,
) {
    fun evaluate(previous: LocationSample?, current: LocationSample): SampleDecision {
        if (!current.hasValidCoordinates()) {
            return SampleDecision(false, false, "坐标无效")
        }

        if (previous != null && current.timestampMillis <= previous.timestampMillis) {
            return SampleDecision(false, false, "时间顺序无效")
        }

        if (previous == null) {
            return SampleDecision(true, false, "首个有效样本")
        }

        val gapMillis = current.timestampMillis - previous.timestampMillis
        val enoughAccuracy = current.accuracyMeters != null &&
            previous.accuracyMeters != null &&
            current.accuracyMeters <= maxDistanceAccuracyMeters &&
            previous.accuracyMeters <= maxDistanceAccuracyMeters

        if (gapMillis > maxConnectGapMillis) {
            return SampleDecision(true, false, "样本间隔过长")
        }

        if (!enoughAccuracy) {
            return SampleDecision(true, false, "定位精度不足")
        }

        return SampleDecision(true, true, "样本可连接")
    }

    private fun LocationSample.hasValidCoordinates(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

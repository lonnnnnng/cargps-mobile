package com.cargps.domain

data class TripPoint(
    val timestampMillis: Long,
    val speedMps: Double,
    val distanceFromPreviousMeters: Double,
    val moving: Boolean,
)

data class TripStats(
    val distanceMeters: Double,
    val elapsedMillis: Long,
    val movingMillis: Long,
    val stoppedMillis: Long,
    val tripAverageMps: Double,
    val movingAverageMps: Double,
    val maxSpeedMps: Double,
)

object TripStatsCalculator {
    fun calculate(
        startAtMillis: Long,
        endAtMillis: Long,
        points: List<TripPoint>,
        pausedMillis: Long,
    ): TripStats {
        val elapsedMillis = (endAtMillis - startAtMillis - pausedMillis).coerceAtLeast(0L)
        val distanceMeters = points.sumOf { it.distanceFromPreviousMeters.coerceAtLeast(0.0) }
        val movingMillis = points.zipWithNext().sumOf { (previous, current) ->
            // 作者：long｜距离与移动标记描述的是“到达当前点”的路段，时间必须取上一点到当前点。
            if (current.moving) {
                (current.timestampMillis - previous.timestampMillis).coerceAtLeast(0L)
            } else {
                0L
            }
        }.let { movingBeforeLast ->
            val last = points.lastOrNull()
            if (last != null && last.moving && points.isNotEmpty()) {
                (endAtMillis - last.timestampMillis).coerceAtLeast(0L) + movingBeforeLast
            } else {
                movingBeforeLast
            }
        }.coerceAtMost(elapsedMillis)
        val stoppedMillis = (elapsedMillis - movingMillis).coerceAtLeast(0L)
        val maxSpeedMps = points.maxOfOrNull { it.speedMps.coerceAtLeast(0.0) } ?: 0.0

        return TripStats(
            distanceMeters = distanceMeters,
            elapsedMillis = elapsedMillis,
            movingMillis = movingMillis,
            stoppedMillis = stoppedMillis,
            tripAverageMps = distanceMeters / elapsedMillis.secondsOrOne(),
            movingAverageMps = distanceMeters / movingMillis.secondsOrOne(),
            maxSpeedMps = maxSpeedMps,
        )
    }

    private fun Long.secondsOrOne(): Double =
        if (this == 0L) 1.0 else this / 1_000.0
}

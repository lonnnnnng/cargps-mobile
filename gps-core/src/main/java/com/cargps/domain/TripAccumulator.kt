package com.cargps.domain

/**
 * 作者：long
 *
 * 行程期间只保留统计累计值和最后一个点，使每次定位更新保持 O(1)。完整轨迹仍由存储层持久化，
 * 仪表盘不再因为长行程把所有点常驻内存或每秒重复扫描。
 */
class TripAccumulator {
    private var distanceMeters = 0.0
    private var committedMovingMillis = 0L
    private var maxSpeedMps = 0.0
    private var previousPoint: TripPoint? = null

    var recordedPointCount: Long = 0L
        private set

    fun reset() {
        distanceMeters = 0.0
        committedMovingMillis = 0L
        maxSpeedMps = 0.0
        previousPoint = null
        recordedPointCount = 0L
    }

    fun restore(points: Iterable<TripPoint>) {
        reset()
        points.forEach(::append)
    }

    fun append(point: TripPoint) {
        distanceMeters += point.distanceFromPreviousMeters.coerceAtLeast(0.0)
        maxSpeedMps = maxOf(maxSpeedMps, point.speedMps.coerceAtLeast(0.0))
        val previous = previousPoint
        if (previous != null && point.moving) {
            committedMovingMillis += (point.timestampMillis - previous.timestampMillis).coerceAtLeast(0L)
        }
        previousPoint = point
        recordedPointCount += 1L
    }

    fun breakSegment() {
        // 作者：long｜暂停、恢复进程或定位断点后首个点不能与旧点形成时间段，否则会补算离线期间移动时长。
        previousPoint = null
    }

    fun snapshot(startAtMillis: Long, endAtMillis: Long, pausedMillis: Long): TripStats {
        val elapsedMillis = (endAtMillis - startAtMillis - pausedMillis).coerceAtLeast(0L)
        val liveMovingMillis = previousPoint?.takeIf(TripPoint::moving)?.let { last ->
            (endAtMillis - last.timestampMillis).coerceAtLeast(0L)
        } ?: 0L
        val movingMillis = (committedMovingMillis + liveMovingMillis).coerceAtMost(elapsedMillis)
        val stoppedMillis = (elapsedMillis - movingMillis).coerceAtLeast(0L)

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

    private fun Long.secondsOrOne(): Double = if (this == 0L) 1.0 else this / 1_000.0
}

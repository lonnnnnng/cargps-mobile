package com.cargps.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TripAccumulatorTest {
    @Test
    fun `增量统计与原有全量统计结果一致`() {
        val points = listOf(
            TripPoint(0L, 0.0, 0.0, false),
            TripPoint(10_000L, 10.0, 100.0, true),
            TripPoint(20_000L, 10.0, 100.0, true),
            TripPoint(30_000L, 0.0, 100.0, false),
        )
        val accumulator = TripAccumulator().apply { restore(points) }

        val actual = accumulator.snapshot(0L, 30_000L, pausedMillis = 5_000L)
        val expected = TripStatsCalculator.calculate(0L, 30_000L, points, pausedMillis = 5_000L)

        assertEquals(expected, actual)
    }

    @Test
    fun `暂停恢复后的首点不会连接暂停前时间段`() {
        val accumulator = TripAccumulator()
        accumulator.append(TripPoint(1_000L, 5.0, 0.0, true))
        accumulator.breakSegment()
        accumulator.append(TripPoint(11_000L, 5.0, 0.0, true))

        val stats = accumulator.snapshot(0L, 12_000L, pausedMillis = 10_000L)

        assertEquals(1_000L, stats.movingMillis)
        assertEquals(1_000L, stats.stoppedMillis)
    }

    @Test
    fun `十万点只保留累计状态且统计不溢出`() {
        val accumulator = TripAccumulator()
        repeat(100_000) { index ->
            accumulator.append(
                TripPoint(
                    timestampMillis = index * 500L,
                    speedMps = 10.0,
                    distanceFromPreviousMeters = 5.0,
                    moving = true,
                ),
            )
        }

        val stats = accumulator.snapshot(0L, 50_000_000L, pausedMillis = 0L)

        assertEquals(100_000L, accumulator.recordedPointCount)
        assertEquals(500_000.0, stats.distanceMeters, 0.001)
        assertEquals(10.0, stats.maxSpeedMps, 0.001)
        assertEquals(50_000_000L, stats.movingMillis)
    }
}

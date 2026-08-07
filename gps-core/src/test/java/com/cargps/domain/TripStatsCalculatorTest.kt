package com.cargps.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TripStatsCalculatorTest {
    @Test
    fun `trip and moving averages use their named time bases`() {
        val points = listOf(
            TripPoint(timestampMillis = 0L, speedMps = 0.0, distanceFromPreviousMeters = 0.0, moving = false),
            TripPoint(timestampMillis = 10_000L, speedMps = 10.0, distanceFromPreviousMeters = 100.0, moving = true),
            TripPoint(timestampMillis = 20_000L, speedMps = 10.0, distanceFromPreviousMeters = 100.0, moving = true),
            TripPoint(timestampMillis = 30_000L, speedMps = 0.0, distanceFromPreviousMeters = 100.0, moving = false),
        )

        val stats = TripStatsCalculator.calculate(
            startAtMillis = 0L,
            endAtMillis = 30_000L,
            points = points,
            pausedMillis = 5_000L,
        )

        assertEquals(300.0, stats.distanceMeters, 0.001)
        assertEquals(25_000L, stats.elapsedMillis)
        assertEquals(20_000L, stats.movingMillis)
        assertEquals(5_000L, stats.stoppedMillis)
        assertEquals(12.0, stats.tripAverageMps, 0.001)
        assertEquals(15.0, stats.movingAverageMps, 0.001)
        assertEquals(10.0, stats.maxSpeedMps, 0.001)
    }

    @Test
    fun `empty trip has zero averages instead of dividing by zero`() {
        val stats = TripStatsCalculator.calculate(100L, 100L, emptyList(), pausedMillis = 0L)

        assertEquals(0.0, stats.tripAverageMps, 0.001)
        assertEquals(0.0, stats.movingAverageMps, 0.001)
        assertEquals(0.0, stats.maxSpeedMps, 0.001)
    }

    @Test
    fun `movement flag belongs to the segment ending at the current point`() {
        val points = listOf(
            TripPoint(0L, speedMps = 0.0, distanceFromPreviousMeters = 0.0, moving = false),
            TripPoint(2_000L, speedMps = 25.0, distanceFromPreviousMeters = 50.0, moving = true),
            TripPoint(4_000L, speedMps = 25.0, distanceFromPreviousMeters = 50.0, moving = true),
        )

        val stats = TripStatsCalculator.calculate(0L, 4_000L, points, pausedMillis = 0L)

        assertEquals(4_000L, stats.movingMillis)
        assertEquals(25.0, stats.movingAverageMps, 0.001)
        assertEquals(25.0, stats.maxSpeedMps, 0.001)
    }
}

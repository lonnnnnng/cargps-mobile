package com.cargps.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedEstimatorTest {
    @Test
    fun `first valid speed is shown immediately`() {
        val estimator = SpeedEstimator()

        val reading = estimator.update(speedMps = 10.0, timestampMillis = 1_000L)

        assertEquals(10.0, reading.smoothedMps, 0.001)
    }

    @Test
    fun `speed uses a bounded smoothing step`() {
        val estimator = SpeedEstimator(alpha = 0.35)
        estimator.update(speedMps = 10.0, timestampMillis = 1_000L)

        val reading = estimator.update(speedMps = 0.0, timestampMillis = 2_000L)

        assertEquals(6.5, reading.smoothedMps, 0.001)
    }

    @Test
    fun `sustained stop snaps the display to zero`() {
        val estimator = SpeedEstimator()
        estimator.update(speedMps = 12.0, timestampMillis = 1_000L)
        estimator.update(speedMps = 0.0, timestampMillis = 2_000L)

        val reading = estimator.update(speedMps = 0.0, timestampMillis = 4_100L)

        assertEquals(0.0, reading.smoothedMps, 0.001)
        assertEquals(SpeedState.STOPPED, reading.state)
    }
}

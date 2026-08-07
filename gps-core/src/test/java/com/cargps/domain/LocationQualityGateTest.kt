package com.cargps.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityGateTest {
    private val gate = LocationQualityGate()

    @Test
    fun `valid fresh sample can connect to previous sample`() {
        val previous = sample(timestampMillis = 1_000L)
        val current = sample(timestampMillis = 2_000L, latitude = 23.001)

        val decision = gate.evaluate(previous, current)

        assertTrue(decision.displayable)
        assertTrue(decision.connectsToPrevious)
    }

    @Test
    fun `poor accuracy remains visible but does not add distance`() {
        val decision = gate.evaluate(null, sample(accuracyMeters = 80f))

        assertTrue(decision.displayable)
        assertFalse(decision.connectsToPrevious)
    }

    @Test
    fun `long gap creates a discontinuity instead of a fake straight line`() {
        val previous = sample(timestampMillis = 1_000L)
        val current = sample(timestampMillis = 12_000L, latitude = 23.001)

        val decision = gate.evaluate(previous, current)

        assertTrue(decision.displayable)
        assertFalse(decision.connectsToPrevious)
    }

    @Test
    fun `out of order timestamp is not displayable`() {
        val previous = sample(timestampMillis = 2_000L)
        val current = sample(timestampMillis = 1_000L)

        val decision = gate.evaluate(previous, current)

        assertFalse(decision.displayable)
        assertFalse(decision.connectsToPrevious)
    }

    private fun sample(
        timestampMillis: Long = 1_000L,
        latitude: Double = 23.0,
        longitude: Double = 113.0,
        accuracyMeters: Float = 5f,
    ) = LocationSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
    )
}

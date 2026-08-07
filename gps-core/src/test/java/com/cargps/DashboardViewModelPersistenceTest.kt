package com.cargps

import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import com.cargps.storage.ActiveTripRecord
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.TripStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardViewModelPersistenceTest {
    @Test
    fun `进程重建后恢复活动行程和已有统计`() {
        val storage = FakeTripStorage(
            activeTrip = ActiveTripRecord(
                mode = TripMode.RECORDING,
                startedAtMillis = 1_000L,
                pausedAtMillis = null,
                totalPausedMillis = 0L,
                points = listOf(
                    TripPoint(
                        timestampMillis = 2_000L,
                        speedMps = 10.0,
                        distanceFromPreviousMeters = 20.0,
                        moving = true,
                    ),
                ),
            ),
        )

        val viewModel = DashboardViewModel(storage = storage, nowProvider = { 3_000L })

        assertEquals(TripMode.RECORDING, viewModel.state.value.tripMode)
        assertTrue(viewModel.state.value.restoredTrip)
        assertEquals(20.0, viewModel.state.value.tripStats.distanceMeters, 0.001)
        assertEquals(2_000L, viewModel.state.value.tripStats.elapsedMillis)
    }

    @Test
    fun `暂停状态结束行程时暂停时长只累计一次`() {
        val storage = FakeTripStorage(
            activeTrip = ActiveTripRecord(
                mode = TripMode.PAUSED,
                startedAtMillis = 1_000L,
                pausedAtMillis = 8_000L,
                totalPausedMillis = 1_000L,
                points = emptyList(),
            ),
        )
        val viewModel = DashboardViewModel(storage = storage, nowProvider = { 10_000L })

        viewModel.endTrip(10_000L)

        assertEquals(TripMode.IDLE, viewModel.state.value.tripMode)
        assertFalse(viewModel.state.value.restoredTrip)
        assertEquals(6_000L, storage.completed.single().stats.elapsedMillis)
    }

    private class FakeTripStorage(
        private var activeTrip: ActiveTripRecord? = null,
    ) : TripStorage {
        val completed = mutableListOf<CompletedTripRecord>()

        override fun loadActiveTrip(): ActiveTripRecord? = activeTrip

        override fun startTrip(startedAtMillis: Long) {
            activeTrip = ActiveTripRecord(
                mode = TripMode.RECORDING,
                startedAtMillis = startedAtMillis,
                pausedAtMillis = null,
                totalPausedMillis = 0L,
                points = emptyList(),
            )
        }

        override fun appendPoint(point: TripPoint) {
            activeTrip = activeTrip?.copy(points = activeTrip.orEmptyPoints() + point)
        }

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) {
            activeTrip = activeTrip?.copy(
                mode = mode,
                pausedAtMillis = pausedAtMillis,
                totalPausedMillis = totalPausedMillis,
            )
        }

        override fun completeTrip(record: CompletedTripRecord) {
            completed += record
            activeTrip = null
        }

        override fun recentTrips(limit: Int): List<CompletedTripRecord> = completed.takeLast(limit).reversed()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        private fun ActiveTripRecord?.orEmptyPoints(): List<TripPoint> = this?.points.orEmpty()
    }
}

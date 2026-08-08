package com.cargps.session

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.ActiveTripLoadResult
import com.cargps.storage.ActiveTripRecord
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.QueuedTripStorage
import com.cargps.storage.TripStorage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStorageFailureIntegrationTest {
    @Test
    fun `永久写失败时保留最后确认点并在恢复后确认尾批`() = runBlocking {
        val delegate = FailingBatchStorage()
        val storage = QueuedTripStorage(delegate)
        val coordinator = TripSessionCoordinator(
            storage = storage,
            scope = this,
            nowProvider = { 3_000L },
            storageDispatcher = Dispatchers.IO,
        )

        try {
            coordinator.dispatch(TripSessionCommand.Restore)
            val started = coordinator.dispatch(TripSessionCommand.Start(1_000L))
            assertTrue(started is TripSessionResult.Confirmed)
            assertEquals(0L, started.state.confirmedCheckpoint?.confirmedPointCount)

            delegate.failBatches = true
            val accepted = coordinator.dispatch(
                TripSessionCommand.AppendPoint(TripPoint(2_000L, 4.0, 12.0, true)),
            )
            assertTrue(accepted is TripSessionResult.Accepted)

            val failed = coordinator.dispatch(TripSessionCommand.Checkpoint)
            assertTrue(failed is TripSessionResult.Failed)
            assertEquals(TripMode.RECORDING, failed.state.mode)
            assertEquals(TripPersistenceState.FAILED, failed.state.persistence)
            assertEquals(0L, failed.state.confirmedCheckpoint?.confirmedPointCount)
            assertEquals(12, failed.state.stats.distanceMeters.toInt())

            delegate.failBatches = false
            val recovered = coordinator.dispatch(TripSessionCommand.Checkpoint)

            assertTrue(recovered is TripSessionResult.Confirmed)
            assertEquals(TripPersistenceState.CONFIRMED, recovered.state.persistence)
            assertNull(recovered.state.storageError)
            assertEquals(1L, recovered.state.confirmedCheckpoint?.confirmedPointCount)
            assertEquals(1, delegate.points.size)
        } finally {
            coordinator.close()
        }
    }

    private class FailingBatchStorage : TripStorage {
        private var startedAtMillis: Long? = null
        private val persistedPoints = CopyOnWriteArrayList<TripPoint>()
        var failBatches: Boolean = false
        val points: List<TripPoint> get() = persistedPoints.toList()

        override fun loadActiveTrip(): ActiveTripLoadResult = startedAtMillis?.let { startedAt ->
            ActiveTripLoadResult.Loaded(
                ActiveTripRecord(
                    mode = TripMode.RECORDING,
                    startedAtMillis = startedAt,
                    pausedAtMillis = null,
                    totalPausedMillis = 0L,
                    points = persistedPoints.toList(),
                ),
            )
        } ?: ActiveTripLoadResult.Empty

        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? {
            val startedAt = startedAtMillis ?: return null
            return ActiveTripCheckpoint(
                startedAtMillis = startedAt,
                confirmedPointCount = persistedPoints.size.toLong(),
                lastConfirmedPointSequence = persistedPoints.size.toLong().takeIf { it > 0L },
                lastConfirmedPointTimestampMillis = persistedPoints.lastOrNull()?.timestampMillis,
            )
        }

        override fun startTrip(startedAtMillis: Long) {
            this.startedAtMillis = startedAtMillis
            persistedPoints.clear()
        }

        override fun appendPoint(point: TripPoint) = persistedPoints.add(point).let { Unit }

        override fun appendPoints(points: List<TripPoint>) {
            if (failBatches) throw IllegalStateException("disk full")
            persistedPoints.addAll(points)
        }

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit

        override fun completeTrip(record: CompletedTripRecord) {
            startedAtMillis = null
            persistedPoints.clear()
        }

        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        override fun close() = Unit
    }
}

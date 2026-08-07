package com.cargps

import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import com.cargps.storage.ActiveTripRecord
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.ActiveTripLoadResult
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.TripStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardRuntimePersistenceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `进程重建后恢复活动行程和已有统计`() = runTest(mainDispatcherRule.dispatcher) {
        val checkpoint = ActiveTripCheckpoint(
            startedAtMillis = 1_000L,
            confirmedPointCount = 1L,
            lastConfirmedPointSequence = 7L,
            lastConfirmedPointTimestampMillis = 2_000L,
        )
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
            checkpoint = checkpoint,
        )

        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            nowProvider = { 3_000L },
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        advanceUntilIdle()

        assertEquals(TripMode.RECORDING, runtime.state.value.tripMode)
        assertTrue(runtime.state.value.restoredTrip)
        assertEquals(20.0, runtime.state.value.tripStats.distanceMeters, 0.001)
        assertEquals(2_000L, runtime.state.value.tripStats.elapsedMillis)
        assertEquals(checkpoint, runtime.state.value.confirmedTripCheckpoint)
        runtime.close()
    }

    @Test
    fun `前台服务恢复门禁等待初始存储结果`() = runTest(mainDispatcherRule.dispatcher) {
        val runtime = DashboardRuntime(
            scope = this,
            storage = FakeTripStorage(),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        val restoredState = async { runtime.awaitInitialRestore() }
        assertFalse(restoredState.isCompleted)
        advanceUntilIdle()

        assertTrue(restoredState.await().storageReady)
        runtime.close()
    }

    @Test
    fun `暂停状态结束行程时暂停时长只累计一次`() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakeTripStorage(
            activeTrip = ActiveTripRecord(
                mode = TripMode.PAUSED,
                startedAtMillis = 1_000L,
                pausedAtMillis = 8_000L,
                totalPausedMillis = 1_000L,
                points = emptyList(),
            ),
        )
        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            nowProvider = { 10_000L },
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        advanceUntilIdle()

        runtime.endTrip(10_000L)
        advanceUntilIdle()

        assertEquals(TripMode.IDLE, runtime.state.value.tripMode)
        assertFalse(runtime.state.value.restoredTrip)
        assertEquals(6_000L, storage.completed.single().stats.elapsedMillis)
        runtime.close()
    }

    @Test
    fun `恢复读取失败时保持存储门禁并显示异常`() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakeTripStorage(loadFailure = IllegalStateException("database unavailable"))

        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        advanceUntilIdle()

        assertFalse(runtime.state.value.storageReady)
        assertEquals("database unavailable", runtime.state.value.storageError)
        runtime.close()
    }

    private class FakeTripStorage(
        private var activeTrip: ActiveTripRecord? = null,
        private val loadFailure: Throwable? = null,
        private var checkpoint: ActiveTripCheckpoint? = null,
    ) : TripStorage {
        val completed = mutableListOf<CompletedTripRecord>()

        override fun loadActiveTrip(): ActiveTripLoadResult {
            loadFailure?.let { throw it }
            return activeTrip?.let(ActiveTripLoadResult::Loaded) ?: ActiveTripLoadResult.Empty
        }

        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = checkpoint

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

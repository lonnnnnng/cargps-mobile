package com.cargps

import com.cargps.domain.LocationSample
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import com.cargps.storage.ActiveTripRecord
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.ActiveTripLoadResult
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.TripStorage
import com.cargps.storage.TripStorageBackpressureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun `等待型开始返回前运行时已经发布记录状态`() = runTest(mainDispatcherRule.dispatcher) {
        val runtime = DashboardRuntime(
            scope = this,
            storage = FakeTripStorage(),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        advanceUntilIdle()

        val startedState = runtime.startTripAndAwait(1_000L)

        assertEquals(TripMode.RECORDING, startedState.tripMode)
        assertEquals(TripMode.RECORDING, runtime.state.value.tripMode)
        assertFalse(startedState.tripCommandInProgress)
        runtime.close()
    }

    @Test
    fun `检查点等待存储确认后才返回`() = runTest(mainDispatcherRule.dispatcher) {
        val expectedCheckpoint = ActiveTripCheckpoint(1_000L, 0L, null, null)
        val storage = FakeTripStorage(
            activeTrip = ActiveTripRecord(
                mode = TripMode.RECORDING,
                startedAtMillis = 1_000L,
                pausedAtMillis = null,
                totalPausedMillis = 0L,
                points = emptyList(),
            ),
            checkpoint = expectedCheckpoint,
        )
        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            ioDispatcher = Dispatchers.Default,
        )
        advanceUntilIdle()

        val gate = CountDownLatch(1)
        storage.awaitGate = gate
        val checkpoint = async { runtime.checkpointTripWritesAndAwait() }
        runCurrent()
        assertFalse(checkpoint.isCompleted)

        gate.countDown()
        val result = checkpoint.await()
        assertEquals(TripMode.RECORDING, result.tripMode)
        assertEquals(expectedCheckpoint, result.confirmedTripCheckpoint)
        runtime.close()
    }

    @Test
    fun `存储失败后恢复首点不跨故障窗口补算距离速度和确认序列`() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakeTripStorage()
        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        advanceUntilIdle()
        runtime.startTripAndAwait(1_000L)

        runtime.onLocationSample(
            sample = LocationSample(2_000L, 0.0, 0.0, accuracyMeters = 5f),
            distanceToPreviousMeters = 0.0,
        )
        advanceUntilIdle()

        storage.appendFailure = TripStorageBackpressureException(16, 16)
        runtime.onLocationSample(
            sample = LocationSample(3_000L, 0.001, 0.0, accuracyMeters = 5f),
            distanceToPreviousMeters = 111.0,
        )
        advanceUntilIdle()

        assertTrue(runtime.state.value.storageBackpressure)
        assertEquals(0.0, runtime.state.value.tripStats.distanceMeters, 0.001)
        assertEquals(1, storage.persistedPoints.size)
        assertEquals(2, storage.appendCalls)

        runtime.onLocationSample(
            sample = LocationSample(3_500L, 0.0015, 0.0, accuracyMeters = 5f),
            distanceToPreviousMeters = 55.5,
        )
        advanceUntilIdle()

        assertEquals(2, storage.appendCalls)
        assertEquals(1, storage.persistedPoints.size)

        storage.appendFailure = null
        runtime.checkpointTripWritesAndAwait()
        advanceUntilIdle()

        runtime.onLocationSample(
            sample = LocationSample(4_000L, 0.002, 0.0, accuracyMeters = 5f),
            distanceToPreviousMeters = 111.0,
        )
        advanceUntilIdle()
        val checkpoint = runtime.checkpointTripWritesAndAwait().confirmedTripCheckpoint

        assertEquals(0.0, runtime.state.value.tripStats.distanceMeters, 0.001)
        assertEquals(2, storage.persistedPoints.size)
        assertEquals(0.0, storage.persistedPoints.last().distanceFromPreviousMeters, 0.001)
        assertEquals(0.0, storage.persistedPoints.last().speedMps, 0.001)
        assertEquals(2L, checkpoint?.confirmedPointCount)
        runtime.close()
    }

    @Test
    fun `一般存储失败期间不再接收定位点且恢复首点重新断开`() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakeTripStorage()
        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            ioDispatcher = mainDispatcherRule.dispatcher,
        )
        advanceUntilIdle()
        runtime.startTripAndAwait(1_000L)

        runtime.onLocationSample(
            sample = LocationSample(2_000L, 0.0, 0.0, accuracyMeters = 5f, speedMps = 10f),
            distanceToPreviousMeters = 0.0,
        )
        advanceUntilIdle()

        storage.appendFailure = IllegalStateException("database unavailable")
        runtime.onLocationSample(
            sample = LocationSample(3_000L, 0.001, 0.0, accuracyMeters = 5f, speedMps = 10f),
            distanceToPreviousMeters = 111.0,
        )
        advanceUntilIdle()

        assertEquals("database unavailable", runtime.state.value.storageError)
        assertEquals(2, storage.appendCalls)
        assertEquals(1, storage.persistedPoints.size)

        runtime.onLocationSample(
            sample = LocationSample(3_500L, 0.0015, 0.0, accuracyMeters = 5f, speedMps = 10f),
            distanceToPreviousMeters = 55.5,
        )
        advanceUntilIdle()

        // 作者：long｜错误仍未被确认前，新的定位回调只能更新仪表，不能继续向存储队列投递失败点。
        assertEquals(2, storage.appendCalls)
        assertEquals(1, storage.persistedPoints.size)

        storage.appendFailure = null
        runtime.checkpointTripWritesAndAwait()
        advanceUntilIdle()

        runtime.onLocationSample(
            sample = LocationSample(4_000L, 0.002, 0.0, accuracyMeters = 5f, speedMps = 10f),
            distanceToPreviousMeters = 111.0,
        )
        advanceUntilIdle()

        assertEquals(3, storage.appendCalls)
        assertEquals(2, storage.persistedPoints.size)
        assertEquals(0.0, storage.persistedPoints.last().distanceFromPreviousMeters, 0.001)
        assertEquals(10.0, storage.persistedPoints.last().speedMps, 0.001)
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
        var appendFailure: Throwable? = null
        var appendCalls: Int = 0
        var awaitGate: CountDownLatch? = null

        val persistedPoints: List<TripPoint>
            get() = activeTrip?.points.orEmpty()

        override fun loadActiveTrip(): ActiveTripLoadResult {
            loadFailure?.let { throw it }
            return activeTrip?.let(ActiveTripLoadResult::Loaded) ?: ActiveTripLoadResult.Empty
        }

        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = checkpoint ?: activeTrip?.let { trip ->
            ActiveTripCheckpoint(
                startedAtMillis = trip.startedAtMillis,
                confirmedPointCount = trip.points.size.toLong(),
                lastConfirmedPointSequence = trip.points.size.toLong().takeIf { it > 0L },
                lastConfirmedPointTimestampMillis = trip.points.lastOrNull()?.timestampMillis,
            )
        }

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
            appendCalls += 1
            appendFailure?.let { throw it }
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

        override fun awaitPendingWrites() {
            val gate = awaitGate ?: return
            check(gate.await(5, TimeUnit.SECONDS)) { "测试存储确认等待超时" }
        }

        private fun ActiveTripRecord?.orEmptyPoints(): List<TripPoint> = this?.points.orEmpty()
    }

}

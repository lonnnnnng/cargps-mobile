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
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
        // 作者：long｜真实 IO 调度器不受测试调度器的 advance 控制，必须等待 Restore 明确发布可用状态后再验证检查点。
        assertTrue(runtime.awaitInitialRestore().storageReady)

        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        storage.awaitEntered = entered
        storage.awaitGate = gate
        val checkpoint = async { runtime.checkpointTripWritesAndAwait() }
        runCurrent()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
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
    fun `事件 actor 异常后从确认边界自动恢复并断开故障窗口`() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakeTripStorage()
        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            ioDispatcher = Dispatchers.Default,
        )
        assertTrue(runtime.awaitInitialRestore().storageReady)
        runtime.startTripAndAwait(1_000L)
        runtime.onLocationSample(
            sample = LocationSample(2_000L, 0.0, 0.0, accuracyMeters = 5f, speedMps = 8f),
            distanceToPreviousMeters = 0.0,
        )
        advanceUntilIdle()

        val recoveryRestoreEntered = CountDownLatch(1)
        val releaseRecoveryRestore = CountDownLatch(1)
        storage.loadEntered = recoveryRestoreEntered
        storage.loadGate = releaseRecoveryRestore
        storage.appendCancellation = CancellationException("injected actor failure")

        runtime.onLocationSample(
            sample = LocationSample(3_000L, 0.001, 0.0, accuracyMeters = 5f, speedMps = 8f),
            distanceToPreviousMeters = 111.0,
        )
        runCurrent()
        assertTrue(recoveryRestoreEntered.await(5, TimeUnit.SECONDS))
        assertEquals(TripMode.RECORDING, runtime.state.value.tripMode)
        assertFalse(runtime.state.value.storageReady)
        assertTrue(runtime.state.value.tripRuntimeRecovering)
        assertTrue(runtime.state.value.tripRuntimeError?.contains("injected actor failure") == true)

        runtime.onLocationSample(
            sample = LocationSample(3_500L, 0.0015, 0.0, accuracyMeters = 5f, speedMps = 8f),
            distanceToPreviousMeters = 55.5,
        )
        assertEquals(2, storage.appendCalls)

        releaseRecoveryRestore.countDown()
        val restored = withTimeout(5_000L) {
            runtime.state.first { state -> state.storageReady && state.tripRuntimeError == null }
        }
        assertEquals(TripMode.RECORDING, restored.tripMode)
        assertFalse(restored.tripRuntimeRecovering)
        assertEquals(1, storage.persistedPoints.size)

        runtime.onLocationSample(
            sample = LocationSample(4_000L, 0.002, 0.0, accuracyMeters = 5f, speedMps = 8f),
            distanceToPreviousMeters = 111.0,
        )
        advanceUntilIdle()

        // 作者：long｜actor 恢复后首点必须从确认边界重新分段，不能把失败点或恢复等待期间的点补算进里程。
        assertEquals(3, storage.appendCalls)
        assertEquals(2, storage.persistedPoints.size)
        assertEquals(0.0, storage.persistedPoints.last().distanceFromPreviousMeters, 0.001)
        runtime.close()
    }

    @Test
    fun `初始恢复 actor 异常时等待自动重建结果`() = runTest(mainDispatcherRule.dispatcher) {
        val recoveryRestoreEntered = CountDownLatch(1)
        val releaseRecoveryRestore = CountDownLatch(1)
        val storage = FakeTripStorage().apply {
            loadCancellationsRemaining = 1
            loadEntered = recoveryRestoreEntered
            loadGate = releaseRecoveryRestore
        }
        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            ioDispatcher = Dispatchers.Default,
        )
        val initialRestore = async { runtime.awaitInitialRestore() }
        runCurrent()

        assertTrue(recoveryRestoreEntered.await(5, TimeUnit.SECONDS))
        assertTrue(runtime.state.value.tripRuntimeRecovering)
        assertFalse(initialRestore.isCompleted)

        releaseRecoveryRestore.countDown()
        val restored = withTimeout(5_000L) { initialRestore.await() }

        // 作者：long｜START_STICKY 必须等自动重建的 Restore 真正结束，不能把中间错误误判为最终恢复失败并提前停服。
        assertTrue(restored.storageReady)
        assertFalse(restored.tripRuntimeRecovering)
        assertEquals(null, restored.tripRuntimeError)
        assertEquals(2, storage.loadCalls)
        runtime.close()
    }

    @Test
    fun `事件 actor 每个 Runtime 最多自动重建一次`() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakeTripStorage().apply {
            loadCancellationsRemaining = 2
        }
        val runtime = DashboardRuntime(
            scope = this,
            storage = storage,
            ioDispatcher = Dispatchers.Default,
        )

        val terminalState = runtime.awaitInitialRestore()

        assertFalse(terminalState.storageReady)
        assertFalse(terminalState.tripRuntimeRecovering)
        assertTrue(terminalState.tripRuntimeError?.contains("injected restore actor failure") == true)
        assertEquals(2, storage.loadCalls)
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
        var appendCancellation: CancellationException? = null
        var appendCalls: Int = 0
        var awaitEntered: CountDownLatch? = null
        var awaitGate: CountDownLatch? = null
        var loadEntered: CountDownLatch? = null
        var loadGate: CountDownLatch? = null
        var loadCancellationsRemaining: Int = 0
        var loadCalls: Int = 0

        val persistedPoints: List<TripPoint>
            get() = activeTrip?.points.orEmpty()

        override fun loadActiveTrip(): ActiveTripLoadResult {
            loadCalls += 1
            loadFailure?.let { throw it }
            if (loadCancellationsRemaining > 0) {
                loadCancellationsRemaining -= 1
                throw CancellationException("injected restore actor failure")
            }
            loadEntered?.countDown()
            loadGate?.let { gate ->
                check(gate.await(5, TimeUnit.SECONDS)) { "测试未释放 actor 恢复读取" }
                loadEntered = null
                loadGate = null
            }
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
            appendCancellation?.let { error ->
                appendCancellation = null
                throw error
            }
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
            // 作者：long｜先向测试线程确认已经进入持久化尾批等待，避免把线程调度延迟误判成检查点提前返回。
            awaitEntered?.countDown()
            val gate = awaitGate ?: return
            check(gate.await(5, TimeUnit.SECONDS)) { "测试存储确认等待超时" }
        }

        private fun ActiveTripRecord?.orEmptyPoints(): List<TripPoint> = this?.points.orEmpty()
    }

}

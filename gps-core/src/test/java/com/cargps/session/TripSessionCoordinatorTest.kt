package com.cargps.session

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.ActiveTripLoadResult
import com.cargps.storage.ActiveTripRecord
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.TripStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripSessionCoordinatorTest {
    @Test
    fun `恢复活动行程并重建累计统计`() = runTest {
        val checkpoint = ActiveTripCheckpoint(
            startedAtMillis = 1_000L,
            confirmedPointCount = 1L,
            lastConfirmedPointSequence = 42L,
            lastConfirmedPointTimestampMillis = 2_000L,
        )
        val storage = FakeTripStorage(
            activeTrip = ActiveTripRecord(
                mode = TripMode.RECORDING,
                startedAtMillis = 1_000L,
                pausedAtMillis = null,
                totalPausedMillis = 0L,
                points = listOf(TripPoint(2_000L, 5.0, 12.0, true)),
            ),
            recent = mutableListOf(completedTrip(100L, 900L)),
            checkpoint = checkpoint,
        )
        val coordinator = coordinator(storage, nowMillis = 3_000L)

        val result = coordinator.dispatch(TripSessionCommand.Restore)

        assertTrue(result is TripSessionResult.Confirmed)
        assertTrue((result as TripSessionResult.Confirmed).breakLocationSegment)
        assertEquals(TripMode.RECORDING, result.state.mode)
        assertTrue(result.state.restoredTrip)
        assertEquals(12.0, result.state.stats.distanceMeters, 0.001)
        assertEquals(2_000L, result.state.stats.elapsedMillis)
        assertEquals(1, result.state.recentTrips.size)
        assertEquals(checkpoint, result.state.confirmedCheckpoint)
        assertEquals(1, storage.awaitCalls)
        coordinator.close()
    }

    @Test
    fun `开始写入失败时保持空闲并发布失败状态`() = runTest {
        val expected = IllegalStateException("start failed")
        val storage = FakeTripStorage(failingOperation = "start", failure = expected)
        val coordinator = coordinator(storage)
        coordinator.dispatch(TripSessionCommand.Restore)

        val result = coordinator.dispatch(TripSessionCommand.Start(1_000L))

        assertTrue(result is TripSessionResult.Failed)
        assertTrue((result as TripSessionResult.Failed).error is IllegalStateException)
        assertEquals(expected.message, result.error.message)
        assertEquals(TripMode.IDLE, result.state.mode)
        assertEquals(TripPersistenceState.FAILED, result.state.persistence)
        assertTrue(result.state.storageReady)
        assertEquals("start failed", result.state.storageError)
        coordinator.close()
    }

    @Test
    fun `活动行程损坏时关闭存储门禁并拒绝开始新行程`() = runTest {
        val storage = FakeTripStorage(
            loadResult = ActiveTripLoadResult.Corrupt(
                reason = "活动行程模式无法识别：UNKNOWN_MODE",
                rawMode = "UNKNOWN_MODE",
            ),
        )
        val coordinator = coordinator(storage)

        val restored = coordinator.dispatch(TripSessionCommand.Restore)
        val start = coordinator.dispatch(TripSessionCommand.Start(2_000L))

        assertTrue(restored is TripSessionResult.Failed)
        assertFalse(restored.state.storageReady)
        assertEquals("活动行程模式无法识别：UNKNOWN_MODE", restored.state.storageError)
        assertTrue(start is TripSessionResult.Rejected)
        assertEquals(null, storage.operationCounts["start"])
        coordinator.close()
    }

    @Test
    fun `暂停恢复结束失败时都保留上一个确认状态`() = runTest {
        val expected = IllegalStateException("write failed")
        val storage = FakeTripStorage(failure = expected)
        val coordinator = coordinator(storage)
        coordinator.dispatch(TripSessionCommand.Restore)
        coordinator.dispatch(TripSessionCommand.Start(1_000L))

        storage.failingOperation = "pause"
        val pauseFailure = coordinator.dispatch(TripSessionCommand.Pause(2_000L))
        assertTrue(pauseFailure is TripSessionResult.Failed)
        assertEquals(TripMode.RECORDING, pauseFailure.state.mode)

        storage.failingOperation = null
        coordinator.dispatch(TripSessionCommand.Pause(2_000L))
        storage.failingOperation = "resume"
        val resumeFailure = coordinator.dispatch(TripSessionCommand.Resume(3_000L))
        assertTrue(resumeFailure is TripSessionResult.Failed)
        assertEquals(TripMode.PAUSED, resumeFailure.state.mode)

        storage.failingOperation = null
        coordinator.dispatch(TripSessionCommand.Resume(3_000L))
        storage.failingOperation = "complete"
        val endFailure = coordinator.dispatch(TripSessionCommand.End(4_000L))
        assertTrue(endFailure is TripSessionResult.Failed)
        assertEquals(TripMode.RECORDING, endFailure.state.mode)
        assertTrue(storage.activeTrip != null)
        assertTrue(storage.recent.isEmpty())
        coordinator.close()
    }

    @Test
    fun `重复会话命令不会产生重复存储写入`() = runTest {
        val storage = FakeTripStorage()
        val coordinator = coordinator(storage)
        coordinator.dispatch(TripSessionCommand.Restore)

        coordinator.dispatch(TripSessionCommand.Start(1_000L))
        assertTrue(coordinator.dispatch(TripSessionCommand.Start(1_000L)) is TripSessionResult.AlreadyApplied)
        coordinator.dispatch(TripSessionCommand.Pause(2_000L))
        assertTrue(coordinator.dispatch(TripSessionCommand.Pause(2_000L)) is TripSessionResult.AlreadyApplied)
        coordinator.dispatch(TripSessionCommand.Resume(3_000L))
        assertTrue(coordinator.dispatch(TripSessionCommand.Resume(3_000L)) is TripSessionResult.AlreadyApplied)
        coordinator.dispatch(TripSessionCommand.End(4_000L))
        assertTrue(coordinator.dispatch(TripSessionCommand.End(4_000L)) is TripSessionResult.AlreadyApplied)

        assertEquals(1, storage.operationCounts["start"])
        assertEquals(1, storage.operationCounts["pause"])
        assertEquals(1, storage.operationCounts["resume"])
        assertEquals(1, storage.operationCounts["complete"])
        coordinator.close()
    }

    @Test
    fun `多次暂停恢复只扣除真实暂停时长`() = runTest {
        val storage = FakeTripStorage()
        val coordinator = coordinator(storage)
        coordinator.dispatch(TripSessionCommand.Restore)
        coordinator.dispatch(TripSessionCommand.Start(1_000L))
        coordinator.dispatch(TripSessionCommand.AppendPoint(TripPoint(2_000L, 4.0, 10.0, true)))
        coordinator.dispatch(TripSessionCommand.AppendPoint(TripPoint(3_000L, 4.0, 10.0, true)))
        coordinator.dispatch(TripSessionCommand.Pause(4_000L))
        coordinator.dispatch(TripSessionCommand.Resume(6_000L))
        coordinator.dispatch(TripSessionCommand.AppendPoint(TripPoint(7_000L, 4.0, 5.0, true)))
        coordinator.dispatch(TripSessionCommand.Pause(8_000L))

        val paused = coordinator.dispatch(TripSessionCommand.Tick(9_000L)).state
        assertEquals(5_000L, paused.stats.elapsedMillis)

        coordinator.dispatch(TripSessionCommand.Resume(10_000L))
        coordinator.dispatch(TripSessionCommand.End(12_000L))
        assertEquals(7_000L, storage.recent.single().stats.elapsedMillis)
        assertEquals(25.0, storage.recent.single().stats.distanceMeters, 0.001)
        coordinator.close()
    }

    @Test
    fun `异步存储错误映射为失败状态但不关闭存储门禁`() = runTest {
        val storage = FakeTripStorage()
        val coordinator = coordinator(storage)
        coordinator.dispatch(TripSessionCommand.Restore)
        runCurrent()

        storage.emitError(IllegalStateException("async failure"))
        runCurrent()

        assertEquals(TripPersistenceState.FAILED, coordinator.state.value.persistence)
        assertEquals("async failure", coordinator.state.value.storageError)
        assertTrue(coordinator.state.value.storageReady)
        coordinator.close()
    }

    @Test
    fun `生命周期检查点冲刷最后批次并发布确认边界`() = runTest {
        val storage = FakeTripStorage()
        val coordinator = coordinator(storage)
        coordinator.dispatch(TripSessionCommand.Restore)
        coordinator.dispatch(TripSessionCommand.Start(1_000L))
        coordinator.dispatch(TripSessionCommand.AppendPoint(TripPoint(2_000L, 4.0, 10.0, true)))
        val checkpoint = ActiveTripCheckpoint(1_000L, 1L, 9L, 2_000L)
        storage.checkpoint = checkpoint

        val result = coordinator.dispatch(TripSessionCommand.Checkpoint)

        assertTrue(result is TripSessionResult.Confirmed)
        assertEquals(checkpoint, result.state.confirmedCheckpoint)
        assertEquals(3, storage.awaitCalls)
        coordinator.close()
    }

    @Test
    fun `后台批次确认后自动推进活动行程持久化边界`() = runTest {
        val storage = FakeTripStorage()
        val coordinator = coordinator(storage)
        coordinator.dispatch(TripSessionCommand.Restore)
        coordinator.dispatch(TripSessionCommand.Start(1_000L))
        runCurrent()
        val checkpoint = ActiveTripCheckpoint(1_000L, 2L, 11L, 3_000L)

        storage.emitCheckpoint(checkpoint)
        runCurrent()

        assertEquals(TripMode.RECORDING, coordinator.state.value.mode)
        assertEquals(checkpoint, coordinator.state.value.confirmedCheckpoint)
        coordinator.close()
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        storage: FakeTripStorage,
        nowMillis: Long = 1_000L,
    ): TripSessionCoordinator = TripSessionCoordinator(
        storage = storage,
        scope = backgroundScope,
        nowProvider = { nowMillis },
        storageDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class FakeTripStorage(
        var activeTrip: ActiveTripRecord? = null,
        private val loadResult: ActiveTripLoadResult? = null,
        val recent: MutableList<CompletedTripRecord> = mutableListOf(),
        var failingOperation: String? = null,
        private val failure: Throwable = IllegalStateException("write failed"),
        var checkpoint: ActiveTripCheckpoint? = null,
    ) : TripStorage {
        private val mutableErrors = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
        private val mutableCheckpoints = MutableSharedFlow<ActiveTripCheckpoint>(extraBufferCapacity = 1)
        override val errors: Flow<Throwable> = mutableErrors
        override val confirmedCheckpoints: Flow<ActiveTripCheckpoint> = mutableCheckpoints
        val operationCounts = mutableMapOf<String, Int>()
        var awaitCalls = 0

        override fun loadActiveTrip(): ActiveTripLoadResult = loadResult ?: activeTrip
            ?.let(ActiveTripLoadResult::Loaded)
            ?: ActiveTripLoadResult.Empty

        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = checkpoint

        override fun startTrip(startedAtMillis: Long) {
            record("start")
            activeTrip = ActiveTripRecord(TripMode.RECORDING, startedAtMillis, null, 0L, emptyList())
        }

        override fun appendPoint(point: TripPoint) {
            record("append")
            activeTrip = activeTrip?.copy(points = activeTrip?.points.orEmpty() + point)
        }

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) {
            record(if (mode == TripMode.PAUSED) "pause" else "resume")
            activeTrip = activeTrip?.copy(
                mode = mode,
                pausedAtMillis = pausedAtMillis,
                totalPausedMillis = totalPausedMillis,
            )
        }

        override fun completeTrip(record: CompletedTripRecord) {
            record("complete")
            recent += record.copy(id = recent.size + 1L)
            activeTrip = null
        }

        override fun recentTrips(limit: Int): List<CompletedTripRecord> = recent.takeLast(limit).reversed()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        override fun awaitPendingWrites() {
            awaitCalls += 1
        }

        suspend fun emitError(error: Throwable) {
            mutableErrors.emit(error)
        }

        suspend fun emitCheckpoint(checkpoint: ActiveTripCheckpoint) {
            mutableCheckpoints.emit(checkpoint)
        }

        private fun record(operation: String) {
            operationCounts[operation] = operationCounts.getOrDefault(operation, 0) + 1
            if (failingOperation == operation) throw failure
        }
    }

    companion object {
        private fun completedTrip(startedAtMillis: Long, endedAtMillis: Long) = CompletedTripRecord(
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            stats = TripStats(0.0, endedAtMillis - startedAtMillis, 0L, 0L, 0.0, 0.0, 0.0),
        )
    }
}

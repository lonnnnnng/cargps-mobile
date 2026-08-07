package com.cargps.storage

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class QueuedTripStorageTest {
    @Test
    fun `写操作在后台线程按提交顺序执行`() {
        val delegate = RecordingTripStorage()
        val callerThread = Thread.currentThread()

        QueuedTripStorage(delegate).use { storage ->
            storage.startTrip(1_000L)
            storage.appendPoint(TripPoint(2_000L, 3.0, 4.0, true))
            storage.updateActiveTrip(TripMode.PAUSED, 3_000L, 200L)

            // 作者：long｜读取是队列屏障，返回时可以确定前三次异步写入已经全部完成。
            storage.recentTrips()
        }

        assertEquals(listOf("start", "append", "pause", "close"), delegate.operations)
        assertFalse(delegate.workerThreads.any { it === callerThread })
        assertEquals(1, delegate.workerThreads.map(Thread::getName).distinct().size)
    }

    @Test
    fun `单次批量写入失败重试成功后不报告终态错误`() {
        val expected = IllegalStateException("disk full")
        val failureReported = CountDownLatch(1)
        var actualFailure: Throwable? = null
        val delegate = RecordingTripStorage(appendFailuresRemaining = 1, appendFailure = expected)

        QueuedTripStorage(
            delegate = delegate,
            onWriteFailure = { error ->
                actualFailure = error
                failureReported.countDown()
            },
        ).use { storage ->
            storage.startTrip(1_000L)
            storage.appendPoint(TripPoint(2_000L, 3.0, 4.0, true))
            storage.updateActiveTrip(TripMode.PAUSED, 3_000L, 200L)
            storage.recentTrips()
        }

        assertFalse(failureReported.await(150, TimeUnit.MILLISECONDS))
        assertEquals(null, actualFailure)
        assertEquals(listOf("start", "append", "append", "pause", "close"), delegate.operations)
    }

    @Test
    fun `批量写入连续失败时屏障抛出并报告终态错误`() {
        val expected = IllegalStateException("disk full")
        val failureReported = CountDownLatch(1)
        var actualFailure: Throwable? = null
        val delegate = RecordingTripStorage(appendFailuresRemaining = 2, appendFailure = expected)

        QueuedTripStorage(
            delegate = delegate,
            onWriteFailure = { error ->
                actualFailure = error
                failureReported.countDown()
            },
        ).use { storage ->
            storage.appendPoint(TripPoint(2_000L, 3.0, 4.0, true))

            assertSame(expected, assertThrows(IllegalStateException::class.java) { storage.awaitPendingWrites() })
            assertEquals(true, failureReported.await(1, TimeUnit.SECONDS))
            storage.awaitPendingWrites()
        }

        assertSame(expected, actualFailure)
        assertEquals(listOf("append", "append", "append", "close"), delegate.operations)
    }

    @Test
    fun `元数据写入失败可被确认屏障捕获`() {
        val expected = IllegalStateException("metadata failure")
        val delegate = RecordingTripStorage(startFailure = expected)

        QueuedTripStorage(delegate).use { storage ->
            storage.startTrip(1_000L)

            assertSame(expected, assertThrows(IllegalStateException::class.java) { storage.awaitPendingWrites() })
        }

        assertEquals(listOf("start", "close"), delegate.operations)
    }

    @Test
    fun `轨迹点按固定上限批量写入且查询会冲刷尾批次`() {
        val delegate = BatchRecordingTripStorage()

        QueuedTripStorage(delegate).use { storage ->
            storage.startTrip(1_000L)
            repeat(17) { index ->
                storage.appendPoint(TripPoint(index.toLong(), 3.0, 4.0, true))
            }
            storage.loadActiveTrip()
        }

        assertEquals(listOf(16, 1), delegate.batchSizes)
    }

    @Test
    fun `批量事务失败后保留轨迹点并在下一次查询重试`() {
        val expected = IllegalStateException("temporary write failure")
        val delegate = BatchRecordingTripStorage(failuresRemaining = 2, failure = expected)

        QueuedTripStorage(delegate).use { storage ->
            storage.appendPoint(TripPoint(1_000L, 3.0, 4.0, true))

            assertThrows(IllegalStateException::class.java) { storage.loadActiveTrip() }
            storage.loadActiveTrip()
        }

        assertEquals(listOf(1, 1, 1), delegate.batchSizes)
    }

    @Test
    fun `尾批次落库后发布最后确认轨迹边界`() = runBlocking {
        val delegate = CheckpointRecordingTripStorage()

        QueuedTripStorage(delegate).use { storage ->
            storage.startTrip(1_000L)
            storage.awaitPendingWrites()
            val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
                storage.confirmedCheckpoints.first()
            }

            storage.appendPoint(TripPoint(2_000L, 3.0, 4.0, true))
            storage.awaitPendingWrites()

            assertEquals(ActiveTripCheckpoint(1_000L, 1L, 1L, 2_000L), confirmation.await())
        }
    }

    private class RecordingTripStorage(
        private var appendFailuresRemaining: Int = 0,
        private val appendFailure: Throwable? = null,
        private val startFailure: Throwable? = null,
    ) : TripStorage {
        val operations = mutableListOf<String>()
        val workerThreads = mutableListOf<Thread>()

        override fun loadActiveTrip(): ActiveTripLoadResult = ActiveTripLoadResult.Empty

        override fun startTrip(startedAtMillis: Long) {
            record("start")
            startFailure?.let { throw it }
        }

        override fun appendPoint(point: TripPoint) {
            record("append")
            if (appendFailuresRemaining > 0) {
                appendFailuresRemaining -= 1
                throw checkNotNull(appendFailure)
            }
        }

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) =
            record("pause")

        override fun completeTrip(record: CompletedTripRecord) = record("complete")

        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        override fun close() = record("close")

        private fun record(operation: String) {
            operations += operation
            workerThreads += Thread.currentThread()
        }
    }

    private class BatchRecordingTripStorage(
        private var failuresRemaining: Int = 0,
        private val failure: Throwable = IllegalStateException("write failure"),
    ) : TripStorage {
        val batchSizes = mutableListOf<Int>()

        override fun loadActiveTrip(): ActiveTripLoadResult = ActiveTripLoadResult.Empty
        override fun startTrip(startedAtMillis: Long) = Unit
        override fun appendPoint(point: TripPoint) = error("批量存储不应退化为单点写入")
        override fun appendPoints(points: List<TripPoint>) {
            batchSizes += points.size
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw failure
            }
        }
        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit
        override fun completeTrip(record: CompletedTripRecord) = Unit
        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()
        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()
    }

    private class CheckpointRecordingTripStorage : TripStorage {
        private var startedAtMillis: Long? = null
        private val points = mutableListOf<TripPoint>()

        override fun loadActiveTrip(): ActiveTripLoadResult = ActiveTripLoadResult.Empty
        override fun startTrip(startedAtMillis: Long) {
            this.startedAtMillis = startedAtMillis
            points.clear()
        }
        override fun appendPoint(point: TripPoint) = error("确认测试必须使用批量写入")
        override fun appendPoints(points: List<TripPoint>) {
            this.points += points
        }
        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? {
            val startedAtMillis = startedAtMillis ?: return null
            return ActiveTripCheckpoint(
                startedAtMillis = startedAtMillis,
                confirmedPointCount = points.size.toLong(),
                lastConfirmedPointSequence = points.size.toLong().takeIf { points.isNotEmpty() },
                lastConfirmedPointTimestampMillis = points.lastOrNull()?.timestampMillis,
            )
        }
        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit
        override fun completeTrip(record: CompletedTripRecord) = Unit
        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()
        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()
    }
}

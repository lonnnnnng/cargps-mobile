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
    fun `单次写入失败不会从定位调用线程冒泡且队列继续工作`() {
        val expected = IllegalStateException("disk full")
        val failureReported = CountDownLatch(1)
        var actualFailure: Throwable? = null
        val delegate = RecordingTripStorage(appendFailure = expected)

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

        assertEquals(true, failureReported.await(1, TimeUnit.SECONDS))
        assertSame(expected, actualFailure)
        assertEquals(listOf("start", "append", "append", "pause", "close"), delegate.operations)
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

    private class RecordingTripStorage(
        private val appendFailure: Throwable? = null,
    ) : TripStorage {
        val operations = mutableListOf<String>()
        val workerThreads = mutableListOf<Thread>()
        private var appendFailuresRemaining = if (appendFailure == null) 0 else 1

        override fun loadActiveTrip(): ActiveTripRecord? = null

        override fun startTrip(startedAtMillis: Long) = record("start")

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

        override fun loadActiveTrip(): ActiveTripRecord? = null
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
}

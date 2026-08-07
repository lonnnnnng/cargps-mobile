package com.cargps.storage

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        assertEquals(listOf("start", "append", "pause", "close"), delegate.operations)
    }

    private class RecordingTripStorage(
        private val appendFailure: Throwable? = null,
    ) : TripStorage {
        val operations = mutableListOf<String>()
        val workerThreads = mutableListOf<Thread>()

        override fun loadActiveTrip(): ActiveTripRecord? = null

        override fun startTrip(startedAtMillis: Long) = record("start")

        override fun appendPoint(point: TripPoint) {
            record("append")
            appendFailure?.let { throw it }
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
}

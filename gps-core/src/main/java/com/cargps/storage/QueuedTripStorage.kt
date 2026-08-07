package com.cargps.storage

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 作者：long
 *
 * 将行程写入排进同一后台队列，保证开始、轨迹点、暂停和结束事务严格按业务发生顺序落库。
 * 读取会作为队列屏障等待此前写入完成，因此恢复和历史查询不会观察到半完成状态。
 */
class QueuedTripStorage(
    private val delegate: TripStorage,
    private val onWriteFailure: (Throwable) -> Unit = {},
) : TripStorage {
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
    }

    override fun loadActiveTrip(): ActiveTripRecord? = query(delegate::loadActiveTrip)

    override fun startTrip(startedAtMillis: Long) = enqueue {
        delegate.startTrip(startedAtMillis)
    }

    override fun appendPoint(point: TripPoint) = enqueue {
        delegate.appendPoint(point)
    }

    override fun updateActiveTrip(
        mode: TripMode,
        pausedAtMillis: Long?,
        totalPausedMillis: Long,
    ) = enqueue {
        delegate.updateActiveTrip(mode, pausedAtMillis, totalPausedMillis)
    }

    override fun completeTrip(record: CompletedTripRecord) = enqueue {
        delegate.completeTrip(record)
    }

    override fun recentTrips(limit: Int): List<CompletedTripRecord> = query {
        delegate.recentTrips(limit)
    }

    override fun completedTripPoints(tripId: Long): List<TripPoint> = query {
        delegate.completedTripPoints(tripId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        // 作者：long｜关闭任务排在队尾并等待完成，避免 ViewModel 销毁时丢掉尚未写入的最后几个轨迹点。
        val closeFuture = executor.submit {
            runCatching(delegate::close).onFailure(::reportWriteFailure)
        }
        executor.shutdown()
        runCatching { closeFuture.get() }.onFailure(::reportWriteFailure)
    }

    private fun enqueue(block: () -> Unit) {
        if (closed.get()) return
        runCatching {
            executor.execute {
                runCatching(block).onFailure(::reportWriteFailure)
            }
        }.onFailure(::reportWriteFailure)
    }

    private fun <T> query(block: () -> T): T {
        check(!closed.get()) { "行程存储已关闭" }
        return try {
            executor.submit<T> { block() }.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun reportWriteFailure(error: Throwable) {
        runCatching { onWriteFailure(error) }
    }

    companion object {
        private const val WORKER_THREAD_NAME = "cargps-trip-storage"
    }
}

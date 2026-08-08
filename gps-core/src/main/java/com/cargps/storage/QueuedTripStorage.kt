package com.cargps.storage

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
    }
    private val pendingPoints = mutableListOf<TripPoint>()
    private val pendingPointCount = AtomicInteger(0)
    private var pointFlushScheduled = false
    private var lastWriteFailure: Throwable? = null
    private val mutableErrors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
    // 作者：long｜确认边界是可恢复状态而非一次性事件，Service/Activity 重绑后必须能拿到最近一次成功落盘的位置。
    private val mutableConfirmedCheckpoints = MutableSharedFlow<ActiveTripCheckpoint>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    override val errors = mutableErrors.asSharedFlow()
    override val confirmedCheckpoints = mutableConfirmedCheckpoints.asSharedFlow()

    override fun loadActiveTrip(): ActiveTripLoadResult = query(delegate::loadActiveTrip)

    override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = query(delegate::loadActiveTripCheckpoint)

    override fun startTrip(startedAtMillis: Long) = enqueue(flushPointsFirst = true, confirmsWrite = true) {
        delegate.startTrip(startedAtMillis)
    }

    override fun appendPoint(point: TripPoint) {
        reservePendingPoint()
        enqueue(
            onRejected = { pendingPointCount.decrementAndGet() },
        ) {
            pendingPoints += point
            if (pendingPoints.size >= POINT_BATCH_SIZE) {
                flushPendingPoints()
            } else if (!pointFlushScheduled) {
                schedulePointFlush()
            }
        }
    }

    override fun updateActiveTrip(
        mode: TripMode,
        pausedAtMillis: Long?,
        totalPausedMillis: Long,
    ) = enqueue(flushPointsFirst = true, confirmsWrite = true) {
        delegate.updateActiveTrip(mode, pausedAtMillis, totalPausedMillis)
    }

    override fun completeTrip(record: CompletedTripRecord) = enqueue(flushPointsFirst = true, confirmsWrite = true) {
        delegate.completeTrip(record)
    }

    override fun recentTrips(limit: Int): List<CompletedTripRecord> = query {
        delegate.recentTrips(limit)
    }

    override fun completedTripPoints(tripId: Long): List<TripPoint> = query {
        delegate.completedTripPoints(tripId)
    }

    override fun awaitPendingWrites() {
        val failure = query { lastWriteFailure }
        if (failure != null) throw failure
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        // 作者：long｜关闭任务排在队尾并等待完成，避免 ViewModel 销毁时丢掉尚未写入的最后几个轨迹点。
        val closeFuture = executor.submit {
            runCatching(::flushPendingPointsWithRetry).onFailure(::reportWriteFailure)
            runCatching(delegate::close).onFailure(::reportWriteFailure)
        }
        executor.shutdown()
        runCatching { closeFuture.get() }.onFailure(::reportWriteFailure)
    }

    private fun enqueue(
        flushPointsFirst: Boolean = false,
        confirmsWrite: Boolean = false,
        onRejected: () -> Unit = {},
        block: () -> Unit,
    ) {
        if (closed.get()) {
            onRejected()
            return
        }
        runCatching {
            executor.execute {
                runCatching {
                    if (flushPointsFirst) flushPendingPointsWithRetry()
                    block()
                    if (confirmsWrite) lastWriteFailure = null
                }.onFailure { error ->
                    lastWriteFailure = error
                    reportWriteFailure(error)
                    if (pendingPoints.isNotEmpty()) schedulePointFlush()
                }
            }
        }.onFailure {
            onRejected()
            reportWriteFailure(it)
        }
    }

    private fun <T> query(block: () -> T): T {
        check(!closed.get()) { "行程存储已关闭" }
        return try {
            executor.submit<T> {
                runCatching(::flushPendingPointsWithRetry).onFailure { error ->
                    // 作者：long｜查询屏障代表调用方正在等待持久化确认，最终重试失败必须进入统一错误通道，不能只向同步调用方抛出。
                    lastWriteFailure = error
                    reportWriteFailure(error)
                }.getOrThrow()
                block()
            }.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun reportWriteFailure(error: Throwable) {
        mutableErrors.tryEmit(error)
        runCatching { onWriteFailure(error) }
    }

    private fun flushPendingPoints() {
        if (pendingPoints.isEmpty()) return
        val batch = pendingPoints.toList()
        delegate.appendPoints(batch)
        // 作者：long｜SQLite 批量事务成功后才移除内存点；磁盘异常时保留原批次，后续定时冲刷或查询屏障可以重试。
        pendingPoints.subList(0, batch.size).clear()
        pendingPointCount.addAndGet(-batch.size)
        lastWriteFailure = null
        publishConfirmedCheckpoint()
    }

    private fun reservePendingPoint() {
        check(!closed.get()) { "行程存储已关闭" }
        while (true) {
            val current = pendingPointCount.get()
            if (current >= MAX_PENDING_POINT_COUNT) {
                throw TripStorageBackpressureException(
                    pendingPointCount = current,
                    maxPendingPointCount = MAX_PENDING_POINT_COUNT,
                )
            }
            if (pendingPointCount.compareAndSet(current, current + 1)) return
        }
    }

    private fun publishConfirmedCheckpoint() {
        runCatching(delegate::loadActiveTripCheckpoint)
            .onSuccess { checkpoint -> checkpoint?.let(mutableConfirmedCheckpoints::tryEmit) }
            .onFailure(::reportWriteFailure)
    }

    private fun flushPendingPointsWithRetry() {
        try {
            flushPendingPoints()
        } catch (firstFailure: Throwable) {
            // 作者：long｜关键状态切换必须先保证此前轨迹落库；短暂 I/O 抖动时在后台线程重试一次并保持调用顺序。
            TimeUnit.MILLISECONDS.sleep(POINT_FLUSH_RETRY_DELAY_MILLIS)
            runCatching(::flushPendingPoints).getOrElse { finalFailure ->
                finalFailure.addSuppressed(firstFailure)
                throw finalFailure
            }
        }
    }

    private fun schedulePointFlush() {
        if (pointFlushScheduled || closed.get()) return
        pointFlushScheduled = true
        runCatching {
            executor.schedule(
                {
                    pointFlushScheduled = false
                    runCatching(::flushPendingPointsWithRetry).onFailure { error ->
                        lastWriteFailure = error
                        reportWriteFailure(error)
                        if (pendingPoints.isNotEmpty()) schedulePointFlush()
                    }
                },
                POINT_BATCH_DELAY_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }.onFailure { error ->
            pointFlushScheduled = false
            reportWriteFailure(error)
        }
    }

    companion object {
        private const val WORKER_THREAD_NAME = "cargps-trip-storage"
        private const val POINT_BATCH_SIZE = 16
        private const val MAX_PENDING_POINT_COUNT = POINT_BATCH_SIZE
        private const val POINT_BATCH_DELAY_MILLIS = 1_000L
        private const val POINT_FLUSH_RETRY_DELAY_MILLIS = 50L
    }
}

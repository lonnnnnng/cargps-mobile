package com.cargps.storage

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class ActiveTripRecord(
    val mode: TripMode,
    val startedAtMillis: Long,
    val pausedAtMillis: Long?,
    val totalPausedMillis: Long,
    val points: List<TripPoint>,
)

sealed interface ActiveTripLoadResult {
    data object Empty : ActiveTripLoadResult

    data class Loaded(val record: ActiveTripRecord) : ActiveTripLoadResult

    data class Corrupt(
        val reason: String,
        val rawMode: String? = null,
    ) : ActiveTripLoadResult
}

class TripStorageCorruptionException(
    val corruption: ActiveTripLoadResult.Corrupt,
) : IllegalStateException(corruption.reason)

/**
 * 作者：long
 *
 * 当磁盘持续不可写且内存尾批达到上限时拒绝新点，避免低存储场景把定位回调无限堆进内存。
 */
class TripStorageBackpressureException(
    val pendingPointCount: Int,
    val maxPendingPointCount: Int,
) : IllegalStateException(
    "行程存储暂不可用，未确认定位点已达到上限：$pendingPointCount/$maxPendingPointCount",
)

fun ActiveTripLoadResult.activeTripOrNull(): ActiveTripRecord? = when (this) {
    ActiveTripLoadResult.Empty -> null
    is ActiveTripLoadResult.Loaded -> record
    is ActiveTripLoadResult.Corrupt -> throw TripStorageCorruptionException(this)
}

data class ActiveTripCheckpoint(
    val startedAtMillis: Long,
    val confirmedPointCount: Long,
    val lastConfirmedPointSequence: Long?,
    val lastConfirmedPointTimestampMillis: Long?,
)

data class CompletedTripRecord(
    val id: Long = 0L,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val stats: TripStats,
)

interface TripStorage : Closeable {
    val errors: Flow<Throwable>
        get() = emptyFlow()

    /**
     * 作者：long
     *
     * 发出最近一次成功落盘的活动行程边界；实现应允许稍晚连接的 Service/Activity 读取最新值。
     */
    val confirmedCheckpoints: Flow<ActiveTripCheckpoint>
        get() = emptyFlow()

    fun loadActiveTrip(): ActiveTripLoadResult

    /**
     * 作者：long
     *
     * 返回数据库已经确认的活动行程边界。内存队列中尚未冲刷的点不得计入该结果，
     * 这样进程重建后可以明确知道统计恢复到了哪个持久化位置。
     */
    fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = loadActiveTrip().activeTripOrNull()?.let { activeTrip ->
        ActiveTripCheckpoint(
            startedAtMillis = activeTrip.startedAtMillis,
            confirmedPointCount = activeTrip.points.size.toLong(),
            lastConfirmedPointSequence = null,
            lastConfirmedPointTimestampMillis = activeTrip.points.lastOrNull()?.timestampMillis,
        )
    }

    fun startTrip(startedAtMillis: Long)

    /**
     * 作者：long
     *
     * 接受一个实时定位点；实现可以在磁盘暂不可写且未确认内存达到上限时抛出
     * [TripStorageBackpressureException]，调用方必须把该点视为未接受并显示存储异常。
     */
    fun appendPoint(point: TripPoint)

    fun appendPoints(points: List<TripPoint>) {
        points.forEach(::appendPoint)
    }

    fun updateActiveTrip(
        mode: TripMode,
        pausedAtMillis: Long?,
        totalPausedMillis: Long,
    )

    fun completeTrip(record: CompletedTripRecord)

    fun recentTrips(limit: Int = 10): List<CompletedTripRecord>

    fun completedTripPoints(tripId: Long): List<TripPoint>

    /**
     * 作者：long
     *
     * 等待此前写命令和尾批次完成；返回即代表调用方可以安全发布对应的已确认行程状态。
     * 同步 adapter 默认已在方法返回前完成写入，因此无需额外操作。
     */
    fun awaitPendingWrites() = Unit

    override fun close() = Unit
}

object NoOpTripStorage : TripStorage {
    override fun loadActiveTrip(): ActiveTripLoadResult = ActiveTripLoadResult.Empty
    override fun startTrip(startedAtMillis: Long) = Unit
    override fun appendPoint(point: TripPoint) = Unit
    override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit
    override fun completeTrip(record: CompletedTripRecord) = Unit
    override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()
    override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()
}

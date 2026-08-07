package com.cargps.session

import com.cargps.TripMode
import com.cargps.domain.TripAccumulator
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import com.cargps.storage.ActiveTripRecord
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.TripStorage
import com.cargps.storage.activeTripOrNull
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class TripPersistenceState {
    LOADING,
    PROCESSING,
    CONFIRMED,
    FAILED,
}

data class TripSessionState(
    val mode: TripMode = TripMode.IDLE,
    val stats: TripStats = EMPTY_TRIP_SESSION_STATS,
    val recentTrips: List<CompletedTripRecord> = emptyList(),
    val restoredTrip: Boolean = false,
    val storageReady: Boolean = false,
    val persistence: TripPersistenceState = TripPersistenceState.LOADING,
    val storageError: String? = null,
    val confirmedCheckpoint: ActiveTripCheckpoint? = null,
)

sealed interface TripSessionCommand {
    data object Restore : TripSessionCommand
    data class Start(val atMillis: Long) : TripSessionCommand
    data class Pause(val atMillis: Long) : TripSessionCommand
    data class Resume(val atMillis: Long) : TripSessionCommand
    data class End(val atMillis: Long) : TripSessionCommand
    data class AppendPoint(val point: TripPoint) : TripSessionCommand
    data class Tick(val atMillis: Long) : TripSessionCommand
    data object Checkpoint : TripSessionCommand
}

sealed interface TripSessionResult {
    val state: TripSessionState

    data class Confirmed(
        override val state: TripSessionState,
        val breakLocationSegment: Boolean = false,
    ) : TripSessionResult

    data class Accepted(override val state: TripSessionState) : TripSessionResult
    data class AlreadyApplied(override val state: TripSessionState) : TripSessionResult
    data class Rejected(val reason: String, override val state: TripSessionState) : TripSessionResult
    data class Failed(val error: Throwable, override val state: TripSessionState) : TripSessionResult
}

/**
 * 作者：long
 *
 * 行程会话的唯一所有者。调用方只投递领域命令并观察已确认状态，不能直接修改累计器或数据库。
 * SQLite 队列、批量冲刷、暂停扣减和恢复断点都隐藏在该 module 的 interface 后面。
 */
class TripSessionCoordinator(
    private val storage: TripStorage,
    scope: CoroutineScope,
    private val nowProvider: () -> Long,
    private val storageDispatcher: CoroutineDispatcher,
) : Closeable {
    private val commandMutex = Mutex()
    private val accumulator = TripAccumulator()
    private var tripStartedAtMillis: Long? = null
    private var pausedAtMillis: Long? = null
    private var totalPausedMillis: Long = 0L

    private val mutableState = MutableStateFlow(TripSessionState())
    val state: StateFlow<TripSessionState> = mutableState.asStateFlow()

    private val storageErrorJob: Job = scope.launch {
        storage.errors.collect { error ->
            commandMutex.withLock {
                publishFailure(error, keepReady = mutableState.value.storageReady)
            }
        }
    }
    private val storageCheckpointJob: Job = scope.launch {
        storage.confirmedCheckpoints.collect { checkpoint ->
            commandMutex.withLock {
                if (checkpoint.startedAtMillis == tripStartedAtMillis && mutableState.value.mode != TripMode.IDLE) {
                    mutableState.value = mutableState.value.copy(confirmedCheckpoint = checkpoint)
                }
            }
        }
    }

    suspend fun dispatch(command: TripSessionCommand): TripSessionResult = commandMutex.withLock {
        when (command) {
            TripSessionCommand.Restore -> restore()
            is TripSessionCommand.Start -> start(command.atMillis)
            is TripSessionCommand.Pause -> pause(command.atMillis)
            is TripSessionCommand.Resume -> resume(command.atMillis)
            is TripSessionCommand.End -> end(command.atMillis)
            is TripSessionCommand.AppendPoint -> appendPoint(command.point)
            is TripSessionCommand.Tick -> tick(command.atMillis)
            TripSessionCommand.Checkpoint -> checkpoint()
        }
    }

    override fun close() {
        storageErrorJob.cancel()
        storageCheckpointJob.cancel()
        storage.close()
    }

    private suspend fun restore(): TripSessionResult {
        mutableState.value = mutableState.value.copy(
            storageReady = false,
            persistence = TripPersistenceState.LOADING,
            storageError = null,
        )
        return try {
            val (recentTrips, activeTrip, checkpoint) = withContext(storageDispatcher) {
                storage.awaitPendingWrites()
                val activeTrip = storage.loadActiveTrip().activeTripOrNull()
                Triple(
                    storage.recentTrips(),
                    activeTrip,
                    activeTrip?.let { storage.loadActiveTripCheckpoint() },
                )
            }
            applyRestoredState(recentTrips, activeTrip, checkpoint)
            TripSessionResult.Confirmed(
                state = mutableState.value,
                breakLocationSegment = activeTrip != null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            TripSessionResult.Failed(error, publishFailure(error, keepReady = false))
        }
    }

    private suspend fun start(atMillis: Long): TripSessionResult {
        val current = mutableState.value
        if (!current.storageReady) return TripSessionResult.Rejected("行程存储尚未就绪", current)
        if (current.mode != TripMode.IDLE) return TripSessionResult.AlreadyApplied(current)

        publishProcessing()
        return confirmWrite(
            write = { storage.startTrip(atMillis) },
            onConfirmed = {
                accumulator.reset()
                tripStartedAtMillis = atMillis
                pausedAtMillis = null
                totalPausedMillis = 0L
                mutableState.value.copy(
                    mode = TripMode.RECORDING,
                    stats = EMPTY_TRIP_SESSION_STATS,
                    restoredTrip = false,
                    persistence = TripPersistenceState.CONFIRMED,
                    storageError = null,
                )
            },
            breakLocationSegment = true,
        )
    }

    private suspend fun pause(atMillis: Long): TripSessionResult {
        val current = mutableState.value
        if (!current.storageReady) return TripSessionResult.Rejected("行程存储尚未就绪", current)
        if (current.mode == TripMode.PAUSED) return TripSessionResult.AlreadyApplied(current)
        if (current.mode != TripMode.RECORDING) return TripSessionResult.Rejected("当前没有可暂停的行程", current)

        publishProcessing()
        return confirmWrite(
            write = { storage.updateActiveTrip(TripMode.PAUSED, atMillis, totalPausedMillis) },
            onConfirmed = {
                pausedAtMillis = atMillis
                mutableState.value.copy(
                    mode = TripMode.PAUSED,
                    stats = snapshot(atMillis),
                    persistence = TripPersistenceState.CONFIRMED,
                    storageError = null,
                )
            },
        )
    }

    private suspend fun resume(atMillis: Long): TripSessionResult {
        val current = mutableState.value
        if (!current.storageReady) return TripSessionResult.Rejected("行程存储尚未就绪", current)
        if (current.mode == TripMode.RECORDING) return TripSessionResult.AlreadyApplied(current)
        if (current.mode != TripMode.PAUSED) return TripSessionResult.Rejected("当前没有可继续的行程", current)

        val confirmedPausedMillis = totalPausedMillis + (atMillis - (pausedAtMillis ?: atMillis)).coerceAtLeast(0L)
        publishProcessing()
        return confirmWrite(
            write = { storage.updateActiveTrip(TripMode.RECORDING, null, confirmedPausedMillis) },
            onConfirmed = {
                totalPausedMillis = confirmedPausedMillis
                pausedAtMillis = null
                accumulator.breakSegment()
                mutableState.value.copy(
                    mode = TripMode.RECORDING,
                    stats = snapshot(atMillis),
                    persistence = TripPersistenceState.CONFIRMED,
                    storageError = null,
                )
            },
            breakLocationSegment = true,
        )
    }

    private suspend fun end(atMillis: Long): TripSessionResult {
        val current = mutableState.value
        if (!current.storageReady) return TripSessionResult.Rejected("行程存储尚未就绪", current)
        if (current.mode == TripMode.IDLE) return TripSessionResult.AlreadyApplied(current)
        val startedAtMillis = tripStartedAtMillis
            ?: return TripSessionResult.Rejected("活动行程缺少开始时间", current)
        val confirmedPausedMillis = totalPausedMillis + if (current.mode == TripMode.PAUSED) {
            (atMillis - (pausedAtMillis ?: atMillis)).coerceAtLeast(0L)
        } else {
            0L
        }
        val finalStats = accumulator.snapshot(startedAtMillis, atMillis, confirmedPausedMillis)
        val completed = CompletedTripRecord(
            startedAtMillis = startedAtMillis,
            endedAtMillis = atMillis,
            stats = finalStats,
        )

        publishProcessing()
        val confirmation = confirmWrite(
            write = { storage.completeTrip(completed) },
            onConfirmed = {
                tripStartedAtMillis = null
                pausedAtMillis = null
                totalPausedMillis = 0L
                mutableState.value.copy(
                    mode = TripMode.IDLE,
                    stats = finalStats,
                    restoredTrip = false,
                    persistence = TripPersistenceState.CONFIRMED,
                    storageError = null,
                )
            },
            breakLocationSegment = true,
        )
        if (confirmation !is TripSessionResult.Confirmed) return confirmation

        val recentTrips = runCatching {
            withContext(storageDispatcher) { storage.recentTrips() }
        }.getOrElse { error ->
            mutableState.value = mutableState.value.copy(storageError = error.storageMessage())
            return TripSessionResult.Confirmed(mutableState.value, breakLocationSegment = true)
        }
        mutableState.value = mutableState.value.copy(recentTrips = recentTrips)
        return TripSessionResult.Confirmed(mutableState.value, breakLocationSegment = true)
    }

    private fun appendPoint(point: TripPoint): TripSessionResult {
        val current = mutableState.value
        if (!current.storageReady || current.mode != TripMode.RECORDING) {
            return TripSessionResult.Rejected("当前行程不接收定位点", current)
        }

        storage.appendPoint(point)
        accumulator.append(point)
        mutableState.value = current.copy(stats = snapshot(point.timestampMillis))
        return TripSessionResult.Accepted(mutableState.value)
    }

    private fun tick(atMillis: Long): TripSessionResult {
        val current = mutableState.value
        if (!current.storageReady || current.mode == TripMode.IDLE) return TripSessionResult.Accepted(current)
        mutableState.value = current.copy(stats = snapshot(atMillis))
        return TripSessionResult.Accepted(mutableState.value)
    }

    private suspend fun checkpoint(): TripSessionResult {
        val current = mutableState.value
        if (!current.storageReady) return TripSessionResult.Rejected("行程存储尚未就绪", current)
        if (current.mode == TripMode.IDLE) return TripSessionResult.Accepted(current)

        return try {
            val checkpoint = withContext(storageDispatcher) {
                storage.awaitPendingWrites()
                checkNotNull(storage.loadActiveTripCheckpoint()) { "活动行程缺少持久化检查点" }
            }
            mutableState.value = current.copy(
                confirmedCheckpoint = checkpoint,
                persistence = TripPersistenceState.CONFIRMED,
                storageError = null,
            )
            TripSessionResult.Confirmed(mutableState.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            TripSessionResult.Failed(error, publishFailure(error, keepReady = true))
        }
    }

    private suspend fun confirmWrite(
        write: () -> Unit,
        onConfirmed: () -> TripSessionState,
        breakLocationSegment: Boolean = false,
    ): TripSessionResult = try {
        val checkpoint = withContext(storageDispatcher) {
            write()
            storage.awaitPendingWrites()
            storage.loadActiveTripCheckpoint()
        }
        mutableState.value = onConfirmed().copy(confirmedCheckpoint = checkpoint)
        TripSessionResult.Confirmed(mutableState.value, breakLocationSegment)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        TripSessionResult.Failed(error, publishFailure(error, keepReady = true))
    }

    private fun applyRestoredState(
        recentTrips: List<CompletedTripRecord>,
        activeTrip: ActiveTripRecord?,
        checkpoint: ActiveTripCheckpoint?,
    ) {
        if (activeTrip == null) {
            accumulator.reset()
            tripStartedAtMillis = null
            pausedAtMillis = null
            totalPausedMillis = 0L
            mutableState.value = TripSessionState(
                recentTrips = recentTrips,
                storageReady = true,
                persistence = TripPersistenceState.CONFIRMED,
            )
            return
        }

        accumulator.restore(activeTrip.points)
        accumulator.breakSegment()
        tripStartedAtMillis = activeTrip.startedAtMillis
        pausedAtMillis = activeTrip.pausedAtMillis
        totalPausedMillis = activeTrip.totalPausedMillis
        val nowMillis = nowProvider()
        val livePausedMillis = activeTrip.totalPausedMillis + if (activeTrip.mode == TripMode.PAUSED) {
            (nowMillis - (activeTrip.pausedAtMillis ?: nowMillis)).coerceAtLeast(0L)
        } else {
            0L
        }
        mutableState.value = TripSessionState(
            mode = activeTrip.mode,
            stats = accumulator.snapshot(activeTrip.startedAtMillis, nowMillis, livePausedMillis),
            recentTrips = recentTrips,
            restoredTrip = true,
            storageReady = true,
            persistence = TripPersistenceState.CONFIRMED,
            confirmedCheckpoint = checkpoint,
        )
    }

    private fun publishProcessing() {
        mutableState.value = mutableState.value.copy(
            persistence = TripPersistenceState.PROCESSING,
            storageError = null,
        )
    }

    private fun publishFailure(error: Throwable, keepReady: Boolean): TripSessionState {
        mutableState.value = mutableState.value.copy(
            storageReady = keepReady,
            persistence = TripPersistenceState.FAILED,
            storageError = error.storageMessage(),
        )
        return mutableState.value
    }

    private fun snapshot(atMillis: Long): TripStats {
        val startedAtMillis = tripStartedAtMillis ?: return mutableState.value.stats
        val pausedMillis = totalPausedMillis + if (mutableState.value.mode == TripMode.PAUSED) {
            (atMillis - (pausedAtMillis ?: atMillis)).coerceAtLeast(0L)
        } else {
            0L
        }
        return accumulator.snapshot(startedAtMillis, atMillis, pausedMillis)
    }

    private fun Throwable.storageMessage(): String = message ?: javaClass.simpleName
}

private val EMPTY_TRIP_SESSION_STATS = TripStats(
    distanceMeters = 0.0,
    elapsedMillis = 0L,
    movingMillis = 0L,
    stoppedMillis = 0L,
    tripAverageMps = 0.0,
    movingAverageMps = 0.0,
    maxSpeedMps = 0.0,
)

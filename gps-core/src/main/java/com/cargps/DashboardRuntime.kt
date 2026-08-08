package com.cargps

import android.location.Location
import com.cargps.domain.LocationQualityGate
import com.cargps.domain.LocationSample
import com.cargps.domain.NmeaFrame
import com.cargps.domain.NmeaSentenceType
import com.cargps.domain.SpeedEstimator
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.NoOpTripStorage
import com.cargps.storage.TripStorage
import com.cargps.session.TripPersistenceState
import com.cargps.session.TripSessionCommand
import com.cargps.session.TripSessionCoordinator
import com.cargps.session.TripSessionEventQueue
import com.cargps.session.TripSessionResult
import com.cargps.session.TripSessionState
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class FixStatus {
    PERMISSION_REQUIRED,
    LOCATION_DISABLED,
    SEARCHING,
    FIXED,
    POOR_ACCURACY,
    STALE,
    LOST,
}

enum class TripMode {
    IDLE,
    RECORDING,
    PAUSED,
}

data class DashboardState(
    val nowMillis: Long = System.currentTimeMillis(),
    val fixStatus: FixStatus = FixStatus.SEARCHING,
    val speedKmh: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Float? = null,
    val accuracyMeters: Float? = null,
    val lastFixAtMillis: Long? = null,
    val satellitesInView: Int? = null,
    val satellitesUsed: Int? = null,
    val hdop: Double? = null,
    val pdop: Double? = null,
    val vdop: Double? = null,
    val lastNmeaType: NmeaSentenceType? = null,
    val tripMode: TripMode = TripMode.IDLE,
    val tripStats: TripStats = EMPTY_TRIP_STATS,
    val recentTrips: List<CompletedTripRecord> = emptyList(),
    val restoredTrip: Boolean = false,
    val storageReady: Boolean = false,
    val tripCommandInProgress: Boolean = false,
    val storageError: String? = null,
    val storageBackpressure: Boolean = false,
    val foregroundServiceError: String? = null,
    val confirmedTripCheckpoint: ActiveTripCheckpoint? = null,
    val darkTheme: Boolean = true,
)

/**
 * 作者：long
 *
 * 手机版进程内唯一仪表与行程运行时。Activity 只观察状态，定位前台服务负责投递定位和行程命令。
 */
class DashboardRuntime(
    scope: CoroutineScope,
    private val storage: TripStorage = NoOpTripStorage,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val runtimeJob = SupervisorJob(scope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(scope.coroutineContext + runtimeJob)
    private val qualityGate = LocationQualityGate()
    private val speedEstimator = SpeedEstimator()
    private var previousSample: LocationSample? = null
    private var previousAndroidLocation: Location? = null

    private enum class LocationSampleResult {
        IGNORED,
        DISPLAYED,
        SEGMENT_BROKEN,
    }

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()
    private val sessionCoordinator = TripSessionCoordinator(
        storage = storage,
        scope = runtimeScope,
        nowProvider = nowProvider,
        storageDispatcher = ioDispatcher,
    )
    private val tripEvents = TripSessionEventQueue(
        scope = runtimeScope,
        currentMode = { sessionCoordinator.state.value.mode },
        dispatch = sessionCoordinator::dispatch,
        onResult = ::handleSessionResult,
    )

    init {
        runtimeScope.launch {
            sessionCoordinator.state.collect { sessionState ->
                publishSessionState(sessionState)
            }
        }
    }

    suspend fun awaitInitialRestore(): DashboardState = state.first { dashboardState ->
        dashboardState.storageReady || dashboardState.storageError != null
    }

    fun onPermissionRequired() = update { copy(fixStatus = FixStatus.PERMISSION_REQUIRED, speedKmh = null) }

    fun onProviderDisabled() = update { copy(fixStatus = FixStatus.LOCATION_DISABLED, speedKmh = null) }

    fun onSearching() = update {
        if (fixStatus == FixStatus.FIXED || fixStatus == FixStatus.POOR_ACCURACY) this
        else copy(fixStatus = FixStatus.SEARCHING)
    }

    fun onLocation(location: Location) {
        val sample = LocationSample(
            timestampMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
            speedMps = location.speed.takeIf { location.hasSpeed() },
        )
        val distanceToPreviousMeters = if (previousAndroidLocation != null) {
            previousAndroidLocation?.distanceTo(location)?.toDouble() ?: 0.0
        } else {
            0.0
        }
        when (processLocationSample(sample, distanceToPreviousMeters)) {
            LocationSampleResult.IGNORED -> Unit
            LocationSampleResult.DISPLAYED -> previousAndroidLocation = Location(location)
            LocationSampleResult.SEGMENT_BROKEN -> previousAndroidLocation = null
        }
    }

    /**
     * 作者：long｜把平台 Location 转换后的纯样本处理单独留出 seam，便于验证存储故障后的首点分段，
     * 同时保证真正的 Android 回调仍由 [onLocation] 负责计算地理距离和复制平台对象。
     */
    internal fun onLocationSample(
        sample: LocationSample,
        distanceToPreviousMeters: Double,
    ): Boolean {
        return processLocationSample(sample, distanceToPreviousMeters) != LocationSampleResult.IGNORED
    }

    private fun processLocationSample(
        sample: LocationSample,
        distanceToPreviousMeters: Double,
    ): LocationSampleResult {
        // 作者：long｜状态流可能晚于存储线程回调，先直接检查协调器状态，避免背压窗口继续接收新点。
        val sessionState = sessionCoordinator.state.value
        val storageInputBlocked = sessionState.persistence == TripPersistenceState.FAILED ||
            sessionState.storageBackpressure
        if (storageInputBlocked) {
            breakLocationSegment()
        }

        val decision = qualityGate.evaluate(previousSample, sample)
        if (!decision.displayable) return LocationSampleResult.IGNORED

        val distanceMeters = if (decision.connectsToPrevious) {
            distanceToPreviousMeters.coerceAtLeast(0.0)
        } else {
            0.0
        }
        val timeDeltaSeconds = previousSample?.let {
            (sample.timestampMillis - it.timestampMillis) / 1_000.0
        } ?: 0.0
        val derivedSpeedMps = if (timeDeltaSeconds > 0.0 && decision.connectsToPrevious) {
            distanceMeters / timeDeltaSeconds
        } else {
            null
        }
        val reading = speedEstimator.update(
            speedMps = sample.speedMps?.toDouble() ?: derivedSpeedMps,
            timestampMillis = sample.timestampMillis,
        )
        val statsSpeedMps = maxOf(reading.smoothedMps, derivedSpeedMps ?: 0.0)

        val isRecording = _state.value.tripMode == TripMode.RECORDING
        if (isRecording && storageInputBlocked) {
            // 作者：long｜存储失败或未确认点达到上限时，回调只更新仪表，不再把新点送入行程累计或存储队列。
            breakLocationSegment()
        } else if (isRecording) {
            val point = TripPoint(
                timestampMillis = sample.timestampMillis,
                speedMps = statsSpeedMps,
                distanceFromPreviousMeters = distanceMeters,
                moving = statsSpeedMps >= MOVING_THRESHOLD_MPS,
            )
            if (!tripEvents.tryDispatch(TripSessionCommand.AppendPoint(point))) {
                // 作者：long｜队列关闭或 actor 异常时，当前点没有进入会话，不能把它留下作为下一点的连接基准。
                breakLocationSegment()
            } else {
                previousSample = sample
            }
        } else {
            previousSample = sample
        }

        update {
            copy(
                fixStatus = if ((sample.accuracyMeters ?: Float.MAX_VALUE) <= 30f) {
                    FixStatus.FIXED
                } else {
                    FixStatus.POOR_ACCURACY
                },
                speedKmh = reading.smoothedMps * 3.6,
                latitude = sample.latitude,
                longitude = sample.longitude,
                altitudeMeters = sample.altitudeMeters,
                bearingDegrees = sample.bearingDegrees,
                accuracyMeters = sample.accuracyMeters,
                lastFixAtMillis = sample.timestampMillis,
            )
        }
        return if (isRecording && storageInputBlocked) {
            LocationSampleResult.SEGMENT_BROKEN
        } else if (isRecording && previousSample == null) {
            LocationSampleResult.SEGMENT_BROKEN
        } else {
            LocationSampleResult.DISPLAYED
        }
    }

    fun onSatellitesChanged(inView: Int, usedInFix: Int) = update {
        copy(satellitesInView = inView, satellitesUsed = usedInFix)
    }

    fun onNmea(frame: NmeaFrame) = update {
        copy(
            lastNmeaType = frame.type,
            hdop = frame.hdop ?: hdop,
            pdop = frame.pdop ?: pdop,
            vdop = frame.vdop ?: vdop,
            satellitesInView = frame.satellitesInView ?: satellitesInView,
            satellitesUsed = frame.satellitesUsed ?: satellitesUsed,
        )
    }

    fun onTick(nowMillis: Long) {
        val current = _state.value
        val ageMillis = current.lastFixAtMillis?.let(nowMillis::minus)
        val currentStatus = when {
            current.fixStatus == FixStatus.PERMISSION_REQUIRED || current.fixStatus == FixStatus.LOCATION_DISABLED ->
                current.fixStatus
            ageMillis == null -> FixStatus.SEARCHING
            ageMillis > 10_000L -> FixStatus.LOST
            ageMillis > 3_000L -> FixStatus.STALE
            else -> current.fixStatus
        }
        val tripStatsAtMillis = if (currentStatus == FixStatus.STALE || currentStatus == FixStatus.LOST) {
            current.lastFixAtMillis ?: nowMillis
        } else {
            nowMillis
        }
        update {
            copy(
                nowMillis = nowMillis,
                fixStatus = currentStatus,
                speedKmh = when (currentStatus) {
                    FixStatus.STALE -> 0.0
                    FixStatus.LOST -> null
                    else -> speedKmh
                },
            )
        }
        tripEvents.tryDispatch(TripSessionCommand.Tick(tripStatsAtMillis))
    }

    fun toggleTrip(nowMillis: Long) {
        if (!_state.value.storageReady || _state.value.tripCommandInProgress) return
        tripEvents.tryToggle(nowMillis)
    }

    suspend fun startTripAndAwait(nowMillis: Long): DashboardState {
        if (!_state.value.storageReady || _state.value.tripCommandInProgress ||
            sessionCoordinator.state.value.mode != TripMode.IDLE
        ) {
            publishSessionState(sessionCoordinator.state.value)
            return _state.value
        }
        tripEvents.dispatchAndAwait(TripSessionCommand.Start(nowMillis))
        return _state.value
    }

    fun endTrip(nowMillis: Long) {
        if (!_state.value.storageReady || _state.value.tripCommandInProgress) return
        tripEvents.tryDispatch(TripSessionCommand.End(nowMillis))
    }

    /**
     * 作者：long
     *
     * 等待活动行程尾批完成后返回最终仪表状态，供 Service 任务移除和正常销毁路径使用。
     */
    suspend fun checkpointTripWritesAndAwait(): DashboardState {
        if (!_state.value.storageReady || sessionCoordinator.state.value.mode == TripMode.IDLE) {
            publishSessionState(sessionCoordinator.state.value)
            return _state.value
        }
        // 作者：long｜任务被移除后由 Service 在协程中等待尾批确认，成功或失败都要让调用方看到最终存储状态。
        tripEvents.dispatchAndAwait(TripSessionCommand.Checkpoint)
        return _state.value
    }

    fun toggleTheme() = update { copy(darkTheme = !darkTheme) }

    fun onForegroundServiceError(message: String?) = update { copy(foregroundServiceError = message) }

    override fun close() {
        tripEvents.close()
        runtimeJob.cancel()
        sessionCoordinator.close()
    }

    private fun handleSessionResult(result: TripSessionResult) {
        // 作者：long｜等待型命令返回前同步发布确认状态，避免 Service 开启定位后首个回调仍读取旧行程模式。
        publishSessionState(result.state)
        when (result) {
            is TripSessionResult.Confirmed -> if (result.breakLocationSegment) {
                // 作者：long｜开始、恢复、结束等已确认边界必须断开上一段，避免跨会话补算位移和移动时长。
                breakLocationSegment()
            }
            is TripSessionResult.Failed,
            is TripSessionResult.Rejected,
            -> {
                // 作者：long｜失败点未被会话接受，恢复后的首个有效点只能从新分段起算。
                breakLocationSegment()
            }
            is TripSessionResult.Accepted,
            is TripSessionResult.AlreadyApplied,
            -> Unit
        }
    }

    private fun publishSessionState(sessionState: TripSessionState) {
        if (sessionState.persistence == TripPersistenceState.FAILED || sessionState.storageBackpressure) {
            // 作者：long｜异步存储错误可能不经过当前定位事件的结果回调，状态流进入失败时也要切断定位段。
            breakLocationSegment()
        }
        update {
            copy(
                tripMode = sessionState.mode,
                tripStats = sessionState.stats,
                recentTrips = sessionState.recentTrips,
                restoredTrip = sessionState.restoredTrip,
                storageReady = sessionState.storageReady,
                tripCommandInProgress = sessionState.persistence == TripPersistenceState.PROCESSING,
                storageError = sessionState.storageError,
                storageBackpressure = sessionState.storageBackpressure,
                confirmedTripCheckpoint = sessionState.confirmedCheckpoint,
            )
        }
    }

    private fun breakLocationSegment() {
        previousSample = null
        previousAndroidLocation = null
        speedEstimator.reset()
    }

    private inline fun update(block: DashboardState.() -> DashboardState) {
        _state.value = _state.value.block()
    }

    companion object {
        private const val MOVING_THRESHOLD_MPS = 2.0 / 3.6

        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())

        fun formatTime(millis: Long): String = TIME_FORMATTER.format(Instant.ofEpochMilli(millis))
        fun formatDate(millis: Long): String = DATE_FORMATTER.format(Instant.ofEpochMilli(millis))
        fun formatDuration(millis: Long): String {
            val seconds = (millis / 1_000L).coerceAtLeast(0L)
            return "%02d:%02d:%02d".format(seconds / 3_600, (seconds % 3_600) / 60, seconds % 60)
        }

        fun formatSpeed(speedMps: Double): String = (speedMps * 3.6).roundToInt().toString()
    }
}

private val EMPTY_TRIP_STATS = TripStats(
    distanceMeters = 0.0,
    elapsedMillis = 0L,
    movingMillis = 0L,
    stoppedMillis = 0L,
    tripAverageMps = 0.0,
    movingAverageMps = 0.0,
    maxSpeedMps = 0.0,
)

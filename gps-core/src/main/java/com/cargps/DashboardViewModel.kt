package com.cargps

import android.location.Location
import androidx.lifecycle.ViewModel
import com.cargps.domain.LocationQualityGate
import com.cargps.domain.LocationSample
import com.cargps.domain.NmeaFrame
import com.cargps.domain.NmeaSentenceType
import com.cargps.domain.SpeedEstimator
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import com.cargps.domain.TripStatsCalculator
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.NoOpTripStorage
import com.cargps.storage.TripStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val darkTheme: Boolean = true,
)

class DashboardViewModel(
    private val storage: TripStorage = NoOpTripStorage,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val qualityGate = LocationQualityGate()
    private val speedEstimator = SpeedEstimator()
    private val points = mutableListOf<TripPoint>()
    private var previousSample: LocationSample? = null
    private var previousAndroidLocation: Location? = null
    private var tripStartedAtMillis: Long? = null
    private var pausedAtMillis: Long? = null
    private var totalPausedMillis: Long = 0L

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        restorePersistedState()
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
        val decision = qualityGate.evaluate(previousSample, sample)
        if (!decision.displayable) return

        val distanceMeters = if (decision.connectsToPrevious && previousAndroidLocation != null) {
            previousAndroidLocation?.distanceTo(location)?.toDouble() ?: 0.0
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

        if (_state.value.tripMode == TripMode.RECORDING) {
            val point = TripPoint(
                timestampMillis = sample.timestampMillis,
                speedMps = statsSpeedMps,
                distanceFromPreviousMeters = distanceMeters,
                moving = statsSpeedMps >= MOVING_THRESHOLD_MPS,
            )
            points += point
            storage.appendPoint(point)
        }

        previousSample = sample
        previousAndroidLocation = Location(location)
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
                tripStats = calculateTripStats(sample.timestampMillis),
            )
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
        update {
            val ageMillis = lastFixAtMillis?.let(nowMillis::minus)
            val currentStatus = when {
                fixStatus == FixStatus.PERMISSION_REQUIRED || fixStatus == FixStatus.LOCATION_DISABLED -> fixStatus
                ageMillis == null -> FixStatus.SEARCHING
                ageMillis > 10_000L -> FixStatus.LOST
                ageMillis > 3_000L -> FixStatus.STALE
                else -> fixStatus
            }
            copy(
                nowMillis = nowMillis,
                fixStatus = currentStatus,
                speedKmh = when (currentStatus) {
                    FixStatus.STALE -> 0.0
                    FixStatus.LOST -> null
                    else -> speedKmh
                },
                tripStats = calculateTripStats(
                    if (currentStatus == FixStatus.STALE || currentStatus == FixStatus.LOST) {
                        lastFixAtMillis ?: nowMillis
                    } else {
                        nowMillis
                    },
                ),
            )
        }
    }

    fun toggleTrip(nowMillis: Long) {
        when (_state.value.tripMode) {
            TripMode.IDLE -> {
                points.clear()
                tripStartedAtMillis = nowMillis
                pausedAtMillis = null
                totalPausedMillis = 0L
                previousSample = null
                previousAndroidLocation = null
                storage.startTrip(nowMillis)
                update {
                    copy(
                        tripMode = TripMode.RECORDING,
                        tripStats = EMPTY_TRIP_STATS,
                        restoredTrip = false,
                    )
                }
            }

            TripMode.RECORDING -> {
                pausedAtMillis = nowMillis
                storage.updateActiveTrip(TripMode.PAUSED, pausedAtMillis, totalPausedMillis)
                update { copy(tripMode = TripMode.PAUSED) }
            }

            TripMode.PAUSED -> {
                totalPausedMillis += nowMillis - (pausedAtMillis ?: nowMillis)
                pausedAtMillis = null
                // 作者：long｜暂停恢复后断开两个定位点，避免把暂停期间位移补算成直线里程。
                previousSample = null
                previousAndroidLocation = null
                storage.updateActiveTrip(TripMode.RECORDING, null, totalPausedMillis)
                update { copy(tripMode = TripMode.RECORDING) }
            }
        }
    }

    fun endTrip(nowMillis: Long) {
        if (_state.value.tripMode == TripMode.IDLE) return
        val startedAtMillis = tripStartedAtMillis ?: return
        if (_state.value.tripMode == TripMode.PAUSED) {
            totalPausedMillis += nowMillis - (pausedAtMillis ?: nowMillis)
            // 作者：long｜结束前清除实时暂停基准，防止统计函数再次把同一段暂停时间计入扣减。
            pausedAtMillis = null
        }
        val finalStats = TripStatsCalculator.calculate(
            startAtMillis = startedAtMillis,
            endAtMillis = nowMillis,
            points = points,
            pausedMillis = totalPausedMillis,
        )
        storage.completeTrip(
            CompletedTripRecord(
                startedAtMillis = startedAtMillis,
                endedAtMillis = nowMillis,
                stats = finalStats,
            ),
        )
        tripStartedAtMillis = null
        previousSample = null
        previousAndroidLocation = null
        update {
            copy(
                tripMode = TripMode.IDLE,
                tripStats = finalStats,
                recentTrips = storage.recentTrips(),
                restoredTrip = false,
            )
        }
    }

    fun toggleTheme() = update { copy(darkTheme = !darkTheme) }

    override fun onCleared() {
        storage.close()
        super.onCleared()
    }

    private fun calculateTripStats(nowMillis: Long): TripStats {
        val startedAt = tripStartedAtMillis ?: return _state.value.tripStats
        val livePausedMillis = totalPausedMillis + if (_state.value.tripMode == TripMode.PAUSED) {
            nowMillis - (pausedAtMillis ?: nowMillis)
        } else {
            0L
        }
        return TripStatsCalculator.calculate(startedAt, nowMillis, points, livePausedMillis)
    }

    private fun restorePersistedState() {
        val recentTrips = storage.recentTrips()
        val activeTrip = storage.loadActiveTrip()
        if (activeTrip == null) {
            update { copy(recentTrips = recentTrips) }
            return
        }

        points += activeTrip.points
        tripStartedAtMillis = activeTrip.startedAtMillis
        pausedAtMillis = activeTrip.pausedAtMillis
        totalPausedMillis = activeTrip.totalPausedMillis
        previousSample = null
        previousAndroidLocation = null

        val nowMillis = nowProvider()
        val livePausedMillis = activeTrip.totalPausedMillis + if (activeTrip.mode == TripMode.PAUSED) {
            nowMillis - (activeTrip.pausedAtMillis ?: nowMillis)
        } else {
            0L
        }
        update {
            copy(
                nowMillis = nowMillis,
                tripMode = activeTrip.mode,
                tripStats = TripStatsCalculator.calculate(
                    startAtMillis = activeTrip.startedAtMillis,
                    endAtMillis = nowMillis,
                    points = activeTrip.points,
                    pausedMillis = livePausedMillis,
                ),
                recentTrips = recentTrips,
                restoredTrip = true,
            )
        }
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

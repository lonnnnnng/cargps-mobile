package com.cargps.storage

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import java.io.Closeable

data class ActiveTripRecord(
    val mode: TripMode,
    val startedAtMillis: Long,
    val pausedAtMillis: Long?,
    val totalPausedMillis: Long,
    val points: List<TripPoint>,
)

data class CompletedTripRecord(
    val id: Long = 0L,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val stats: TripStats,
)

interface TripStorage : Closeable {
    fun loadActiveTrip(): ActiveTripRecord?

    fun startTrip(startedAtMillis: Long)

    fun appendPoint(point: TripPoint)

    fun updateActiveTrip(
        mode: TripMode,
        pausedAtMillis: Long?,
        totalPausedMillis: Long,
    )

    fun completeTrip(record: CompletedTripRecord)

    fun recentTrips(limit: Int = 10): List<CompletedTripRecord>

    fun completedTripPoints(tripId: Long): List<TripPoint>

    override fun close() = Unit
}

object NoOpTripStorage : TripStorage {
    override fun loadActiveTrip(): ActiveTripRecord? = null
    override fun startTrip(startedAtMillis: Long) = Unit
    override fun appendPoint(point: TripPoint) = Unit
    override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit
    override fun completeTrip(record: CompletedTripRecord) = Unit
    override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()
    override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()
}

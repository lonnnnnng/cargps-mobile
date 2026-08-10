package com.cargps.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats

class RoomTripStorage(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val onDatabaseOpen: ((SupportSQLiteDatabase) -> Unit)? = null,
    journalMode: RoomDatabase.JournalMode? = null,
) : TripStorage {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        RoomTripDatabase::class.java,
        databaseName,
    ).addMigrations(
        RoomTripDatabase.MIGRATION_1_2,
        RoomTripDatabase.MIGRATION_2_3,
        RoomTripDatabase.MIGRATION_3_4,
    ).apply {
        journalMode?.let { mode ->
            // 作者：long｜生产装配沿用 Room 默认 journal mode；设备测试用 TRUNCATE 让 max_page_count 真正触发 SQLITE_FULL。
            setJournalMode(mode)
        }
        onDatabaseOpen?.let { callback ->
            // 作者：long｜生产装配不传入回调；测试只借此在 Room 实际打开的连接上注入永久 I/O 故障。
            addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        callback(db)
                    }
                },
            )
        }
    }.build()
    private val dao = database.tripDao()

    override fun loadActiveTrip(): ActiveTripLoadResult {
        var result: ActiveTripLoadResult = ActiveTripLoadResult.Empty
        database.runInTransaction {
            val activeTrip = dao.activeTrip() ?: return@runInTransaction
            val mode = runCatching { TripMode.valueOf(activeTrip.mode) }.getOrNull()
            if (mode == null) {
                result = ActiveTripLoadResult.Corrupt(
                    reason = "活动行程模式无法识别：${activeTrip.mode}",
                    rawMode = activeTrip.mode,
                )
                return@runInTransaction
            }
            result = ActiveTripLoadResult.Loaded(
                ActiveTripRecord(
                    mode = mode,
                    startedAtMillis = activeTrip.startedAtMillis,
                    pausedAtMillis = activeTrip.pausedAtMillis,
                    totalPausedMillis = activeTrip.totalPausedMillis,
                    points = dao.activePoints().map { point -> point.toTripPoint() },
                ),
            )
        }
        return result
    }

    override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = dao.activeTripCheckpoint()?.let { row ->
        ActiveTripCheckpoint(
            startedAtMillis = row.startedAtMillis,
            confirmedPointCount = row.confirmedPointCount,
            lastConfirmedPointSequence = row.lastSequence,
            lastConfirmedPointTimestampMillis = row.lastTimestampMillis,
        )
    }

    override fun startTrip(startedAtMillis: Long) {
        database.runInTransaction {
            dao.deleteActivePoints()
            dao.deleteActiveTrip()
            dao.insertActiveTrip(
                ActiveTripEntity(
                    id = 1L,
                    mode = TripMode.RECORDING.name,
                    startedAtMillis = startedAtMillis,
                    pausedAtMillis = null,
                    totalPausedMillis = 0L,
                ),
            )
        }
    }

    override fun appendPoint(point: TripPoint) {
        dao.insertActivePoint(point.toActivePointEntity())
    }

    override fun appendPoints(points: List<TripPoint>) {
        if (points.isNotEmpty()) dao.insertActivePoints(points.map { point -> point.toActivePointEntity() })
    }

    override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) {
        check(dao.updateActiveTrip(mode.name, pausedAtMillis, totalPausedMillis) == 1) {
            "活动行程不存在，无法更新状态"
        }
    }

    override fun completeTrip(record: CompletedTripRecord) {
        database.runInTransaction {
            val completedTripId = dao.insertCompletedTrip(record.toEntity())
            dao.archiveActivePoints(completedTripId)
            // 作者：long｜历史统计和轨迹在同一事务写完后才清除活动行程，失败时保留完整恢复来源。
            dao.deleteActivePoints()
            dao.deleteActiveTrip()
        }
    }

    override fun recentTrips(limit: Int): List<CompletedTripRecord> =
        dao.recentTrips(limit.coerceIn(1, 100)).map { trip -> trip.toRecord() }

    override fun completedTripPoints(tripId: Long): List<TripPoint> =
        dao.completedTripPoints(tripId).map { point -> point.toTripPoint() }

    override fun close() = database.close()

    private fun TripPoint.toActivePointEntity() = ActivePointEntity(
        timestampMillis = timestampMillis,
        speedMps = speedMps,
        distanceMeters = distanceFromPreviousMeters,
        moving = moving,
    )

    private fun ActivePointEntity.toTripPoint() = TripPoint(
        timestampMillis = timestampMillis,
        speedMps = speedMps,
        distanceFromPreviousMeters = distanceMeters,
        moving = moving,
    )

    private fun CompletedPointEntity.toTripPoint() = TripPoint(
        timestampMillis = timestampMillis,
        speedMps = speedMps,
        distanceFromPreviousMeters = distanceMeters,
        moving = moving,
    )

    private fun CompletedTripRecord.toEntity() = CompletedTripEntity(
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        distanceMeters = stats.distanceMeters,
        elapsedMillis = stats.elapsedMillis,
        movingMillis = stats.movingMillis,
        stoppedMillis = stats.stoppedMillis,
        tripAverageMps = stats.tripAverageMps,
        movingAverageMps = stats.movingAverageMps,
        maxSpeedMps = stats.maxSpeedMps,
    )

    private fun CompletedTripEntity.toRecord() = CompletedTripRecord(
        id = id,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        stats = TripStats(
            distanceMeters = distanceMeters,
            elapsedMillis = elapsedMillis,
            movingMillis = movingMillis,
            stoppedMillis = stoppedMillis,
            tripAverageMps = tripAverageMps,
            movingAverageMps = movingAverageMps,
            maxSpeedMps = maxSpeedMps,
        ),
    )

    companion object {
        private const val DEFAULT_DATABASE_NAME = "cargps-trips.db"
    }
}

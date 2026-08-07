package com.cargps.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats

class SqliteTripStorage(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), TripStorage {

    override fun close() = super<SQLiteOpenHelper>.close()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE active_trip (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                mode TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                paused_at INTEGER,
                total_paused INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE active_point (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                speed REAL NOT NULL,
                distance REAL NOT NULL,
                moving INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE completed_trip (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                distance REAL NOT NULL,
                elapsed INTEGER NOT NULL,
                moving INTEGER NOT NULL,
                stopped INTEGER NOT NULL,
                trip_average REAL NOT NULL,
                moving_average REAL NOT NULL,
                max_speed REAL NOT NULL
            )
            """.trimIndent(),
        )
        createCompletedPointTable(db)
        createCompletedTripIndex(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 作者：long｜v1 已保存活动点，升级只新增历史轨迹表，不能通过重建数据库丢弃用户未结束的行程。
            createCompletedPointTable(db)
        }
        if (oldVersion < 3) {
            // 作者：long｜历史列表始终按结束时间倒序读取，升级时补索引避免记录增长后每次全表排序。
            createCompletedTripIndex(db)
        }
    }

    @Synchronized
    override fun loadActiveTrip(): ActiveTripRecord? {
        val active = readableDatabase.query(
            TABLE_ACTIVE_TRIP,
            ACTIVE_COLUMNS,
            "id = 1",
            null,
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val mode = runCatching { TripMode.valueOf(cursor.string("mode")) }.getOrNull() ?: return null
            ActiveTripRecord(
                mode = mode,
                startedAtMillis = cursor.long("started_at"),
                pausedAtMillis = cursor.nullableLong("paused_at"),
                totalPausedMillis = cursor.long("total_paused"),
                points = emptyList(),
            )
        }
        val points = readableDatabase.query(
            TABLE_ACTIVE_POINT,
            POINT_COLUMNS,
            null,
            null,
            null,
            null,
            "sequence ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TripPoint(
                            timestampMillis = cursor.long("timestamp"),
                            speedMps = cursor.double("speed"),
                            distanceFromPreviousMeters = cursor.double("distance"),
                            moving = cursor.int("moving") == 1,
                        ),
                    )
                }
            }
        }
        return active.copy(points = points)
    }

    @Synchronized
    override fun startTrip(startedAtMillis: Long) {
        writableDatabase.inTransaction { db ->
            db.delete(TABLE_ACTIVE_POINT, null, null)
            db.delete(TABLE_ACTIVE_TRIP, null, null)
            db.insertOrThrow(
                TABLE_ACTIVE_TRIP,
                null,
                ContentValues().apply {
                    put("id", 1)
                    put("mode", TripMode.RECORDING.name)
                    put("started_at", startedAtMillis)
                    putNull("paused_at")
                    put("total_paused", 0L)
                },
            )
        }
    }

    @Synchronized
    override fun appendPoint(point: TripPoint) {
        insertPoint(writableDatabase, point)
    }

    @Synchronized
    override fun appendPoints(points: List<TripPoint>) {
        if (points.isEmpty()) return
        writableDatabase.inTransaction { db ->
            points.forEach { point -> insertPoint(db, point) }
        }
    }

    private fun insertPoint(db: SQLiteDatabase, point: TripPoint) {
        db.insertOrThrow(
            TABLE_ACTIVE_POINT,
            null,
            ContentValues().apply {
                put("timestamp", point.timestampMillis)
                put("speed", point.speedMps)
                put("distance", point.distanceFromPreviousMeters)
                put("moving", if (point.moving) 1 else 0)
            },
        )
    }

    @Synchronized
    override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) {
        writableDatabase.update(
            TABLE_ACTIVE_TRIP,
            ContentValues().apply {
                put("mode", mode.name)
                if (pausedAtMillis == null) putNull("paused_at") else put("paused_at", pausedAtMillis)
                put("total_paused", totalPausedMillis)
            },
            "id = 1",
            null,
        )
    }

    @Synchronized
    override fun completeTrip(record: CompletedTripRecord) {
        writableDatabase.inTransaction { db ->
            val completedTripId = db.insertOrThrow(
                TABLE_COMPLETED_TRIP,
                null,
                record.toContentValues(),
            )
            db.execSQL(
                """
                INSERT INTO completed_point (
                    trip_id, sequence, timestamp, speed, distance, moving
                )
                SELECT ?, sequence, timestamp, speed, distance, moving
                FROM active_point
                ORDER BY sequence ASC
                """.trimIndent(),
                arrayOf(completedTripId),
            )
            // 作者：long｜统计和轨迹都写入历史后再清活动行程，事务中断时仍保留完整可恢复数据。
            db.delete(TABLE_ACTIVE_POINT, null, null)
            db.delete(TABLE_ACTIVE_TRIP, null, null)
        }
    }

    @Synchronized
    override fun recentTrips(limit: Int): List<CompletedTripRecord> = readableDatabase.query(
        TABLE_COMPLETED_TRIP,
        COMPLETED_COLUMNS,
        null,
        null,
        null,
        null,
        "ended_at DESC, id DESC",
        limit.coerceIn(1, 100).toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    CompletedTripRecord(
                        id = cursor.long("id"),
                        startedAtMillis = cursor.long("started_at"),
                        endedAtMillis = cursor.long("ended_at"),
                        stats = TripStats(
                            distanceMeters = cursor.double("distance"),
                            elapsedMillis = cursor.long("elapsed"),
                            movingMillis = cursor.long("moving"),
                            stoppedMillis = cursor.long("stopped"),
                            tripAverageMps = cursor.double("trip_average"),
                            movingAverageMps = cursor.double("moving_average"),
                            maxSpeedMps = cursor.double("max_speed"),
                        ),
                    ),
                )
            }
        }
    }

    @Synchronized
    override fun completedTripPoints(tripId: Long): List<TripPoint> = readableDatabase.query(
        TABLE_COMPLETED_POINT,
        POINT_COLUMNS,
        "trip_id = ?",
        arrayOf(tripId.toString()),
        null,
        null,
        "sequence ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    TripPoint(
                        timestampMillis = cursor.long("timestamp"),
                        speedMps = cursor.double("speed"),
                        distanceFromPreviousMeters = cursor.double("distance"),
                        moving = cursor.int("moving") == 1,
                    ),
                )
            }
        }
    }

    private fun CompletedTripRecord.toContentValues(): ContentValues = ContentValues().apply {
        put("started_at", startedAtMillis)
        put("ended_at", endedAtMillis)
        put("distance", stats.distanceMeters)
        put("elapsed", stats.elapsedMillis)
        put("moving", stats.movingMillis)
        put("stopped", stats.stoppedMillis)
        put("trip_average", stats.tripAverageMps)
        put("moving_average", stats.movingAverageMps)
        put("max_speed", stats.maxSpeedMps)
    }

    private fun createCompletedPointTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE completed_point (
                trip_id INTEGER NOT NULL,
                sequence INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                speed REAL NOT NULL,
                distance REAL NOT NULL,
                moving INTEGER NOT NULL,
                PRIMARY KEY (trip_id, sequence),
                FOREIGN KEY (trip_id) REFERENCES completed_trip(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun createCompletedTripIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS completed_trip_ended_at_idx ON completed_trip(ended_at DESC, id DESC)",
        )
    }

    private inline fun SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> Unit) {
        beginTransaction()
        try {
            block(this)
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.double(column: String): Double = getDouble(getColumnIndexOrThrow(column))
    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    companion object {
        private const val DEFAULT_DATABASE_NAME = "cargps-trips.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_ACTIVE_TRIP = "active_trip"
        private const val TABLE_ACTIVE_POINT = "active_point"
        private const val TABLE_COMPLETED_TRIP = "completed_trip"
        private const val TABLE_COMPLETED_POINT = "completed_point"

        private val ACTIVE_COLUMNS = arrayOf("mode", "started_at", "paused_at", "total_paused")
        private val POINT_COLUMNS = arrayOf("timestamp", "speed", "distance", "moving")
        private val COMPLETED_COLUMNS = arrayOf(
            "id",
            "started_at",
            "ended_at",
            "distance",
            "elapsed",
            "moving",
            "stopped",
            "trip_average",
            "moving_average",
            "max_speed",
        )
    }
}

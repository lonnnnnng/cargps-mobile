package com.cargps.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "active_trip")
internal data class ActiveTripEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "started_at") val startedAtMillis: Long,
    @ColumnInfo(name = "paused_at") val pausedAtMillis: Long?,
    @ColumnInfo(name = "total_paused") val totalPausedMillis: Long,
)

@Entity(tableName = "active_point")
internal data class ActivePointEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "sequence") val sequence: Long = 0L,
    @ColumnInfo(name = "timestamp") val timestampMillis: Long,
    @ColumnInfo(name = "speed") val speedMps: Double,
    @ColumnInfo(name = "distance") val distanceMeters: Double,
    @ColumnInfo(name = "moving") val moving: Boolean,
)

@Entity(
    tableName = "completed_trip",
    indices = [
        Index(
            name = "completed_trip_ended_at_idx",
            value = ["ended_at", "id"],
            orders = [Index.Order.DESC, Index.Order.DESC],
        ),
    ],
)
internal data class CompletedTripEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "started_at") val startedAtMillis: Long,
    @ColumnInfo(name = "ended_at") val endedAtMillis: Long,
    @ColumnInfo(name = "distance") val distanceMeters: Double,
    @ColumnInfo(name = "elapsed") val elapsedMillis: Long,
    @ColumnInfo(name = "moving") val movingMillis: Long,
    @ColumnInfo(name = "stopped") val stoppedMillis: Long,
    @ColumnInfo(name = "trip_average") val tripAverageMps: Double,
    @ColumnInfo(name = "moving_average") val movingAverageMps: Double,
    @ColumnInfo(name = "max_speed") val maxSpeedMps: Double,
)

@Entity(
    tableName = "completed_point",
    primaryKeys = ["trip_id", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = CompletedTripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class CompletedPointEntity(
    @ColumnInfo(name = "trip_id") val tripId: Long,
    @ColumnInfo(name = "sequence") val sequence: Long,
    @ColumnInfo(name = "timestamp") val timestampMillis: Long,
    @ColumnInfo(name = "speed") val speedMps: Double,
    @ColumnInfo(name = "distance") val distanceMeters: Double,
    @ColumnInfo(name = "moving") val moving: Boolean,
)

internal data class ActiveTripCheckpointRow(
    @ColumnInfo(name = "started_at") val startedAtMillis: Long,
    @ColumnInfo(name = "confirmed_point_count") val confirmedPointCount: Long,
    @ColumnInfo(name = "last_sequence") val lastSequence: Long?,
    @ColumnInfo(name = "last_timestamp") val lastTimestampMillis: Long?,
)

@Dao
internal interface TripDao {
    @Query("SELECT * FROM active_trip WHERE id = 1 LIMIT 1")
    fun activeTrip(): ActiveTripEntity?

    @Query("SELECT * FROM active_point ORDER BY sequence ASC")
    fun activePoints(): List<ActivePointEntity>

    @Query(
        """
        SELECT started_at,
            (SELECT COUNT(*) FROM active_point) AS confirmed_point_count,
            (SELECT sequence FROM active_point ORDER BY sequence DESC LIMIT 1) AS last_sequence,
            (SELECT timestamp FROM active_point ORDER BY sequence DESC LIMIT 1) AS last_timestamp
        FROM active_trip
        WHERE id = 1
        LIMIT 1
        """,
    )
    fun activeTripCheckpoint(): ActiveTripCheckpointRow?

    @Query("DELETE FROM active_point")
    fun deleteActivePoints()

    @Query("DELETE FROM active_trip")
    fun deleteActiveTrip()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertActiveTrip(activeTrip: ActiveTripEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertActivePoint(point: ActivePointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertActivePoints(points: List<ActivePointEntity>)

    @Query(
        """
        UPDATE active_trip
        SET mode = :mode, paused_at = :pausedAtMillis, total_paused = :totalPausedMillis
        WHERE id = 1
        """,
    )
    fun updateActiveTrip(mode: String, pausedAtMillis: Long?, totalPausedMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCompletedTrip(completedTrip: CompletedTripEntity): Long

    @Query(
        """
        INSERT INTO completed_point (trip_id, sequence, timestamp, speed, distance, moving)
        SELECT :tripId, sequence, timestamp, speed, distance, moving
        FROM active_point
        ORDER BY sequence ASC
        """,
    )
    fun archiveActivePoints(tripId: Long)

    @Query("SELECT * FROM completed_trip ORDER BY ended_at DESC, id DESC LIMIT :limit")
    fun recentTrips(limit: Int): List<CompletedTripEntity>

    @Query("SELECT * FROM completed_point WHERE trip_id = :tripId ORDER BY sequence ASC")
    fun completedTripPoints(tripId: Long): List<CompletedPointEntity>
}

@Database(
    entities = [ActiveTripEntity::class, ActivePointEntity::class, CompletedTripEntity::class, CompletedPointEntity::class],
    version = RoomTripDatabase.VERSION,
    exportSchema = true,
)
internal abstract class RoomTripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        const val VERSION = 4

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_COMPLETED_POINT)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_COMPLETED_TRIP_INDEX)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 作者：long｜v4 只规范化旧表以建立 Room schema identity；先改名再复制，迁移失败由事务整体回滚。
                db.execSQL("DROP INDEX IF EXISTS completed_trip_ended_at_idx")
                db.execSQL("ALTER TABLE completed_point RENAME TO legacy_completed_point")
                db.execSQL("ALTER TABLE completed_trip RENAME TO legacy_completed_trip")
                db.execSQL("ALTER TABLE active_point RENAME TO legacy_active_point")
                db.execSQL("ALTER TABLE active_trip RENAME TO legacy_active_trip")

                db.execSQL(CREATE_ACTIVE_TRIP)
                db.execSQL(CREATE_ACTIVE_POINT)
                db.execSQL(CREATE_COMPLETED_TRIP)
                db.execSQL(CREATE_COMPLETED_POINT)
                db.execSQL(CREATE_COMPLETED_TRIP_INDEX)

                db.execSQL(
                    "INSERT INTO active_trip SELECT id, mode, started_at, paused_at, total_paused FROM legacy_active_trip",
                )
                db.execSQL(
                    "INSERT INTO active_point SELECT sequence, timestamp, speed, distance, moving FROM legacy_active_point",
                )
                db.execSQL(
                    """
                    INSERT INTO completed_trip
                    SELECT id, started_at, ended_at, distance, elapsed, moving, stopped,
                        trip_average, moving_average, max_speed
                    FROM legacy_completed_trip
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO completed_point
                    SELECT trip_id, sequence, timestamp, speed, distance, moving
                    FROM legacy_completed_point
                    """.trimIndent(),
                )

                db.execSQL("DROP TABLE legacy_completed_point")
                db.execSQL("DROP TABLE legacy_completed_trip")
                db.execSQL("DROP TABLE legacy_active_point")
                db.execSQL("DROP TABLE legacy_active_trip")
            }
        }

        private const val CREATE_ACTIVE_TRIP =
            "CREATE TABLE IF NOT EXISTS active_trip (id INTEGER NOT NULL, mode TEXT NOT NULL, " +
                "started_at INTEGER NOT NULL, paused_at INTEGER, total_paused INTEGER NOT NULL, PRIMARY KEY(id))"
        private const val CREATE_ACTIVE_POINT =
            "CREATE TABLE IF NOT EXISTS active_point (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "timestamp INTEGER NOT NULL, speed REAL NOT NULL, distance REAL NOT NULL, moving INTEGER NOT NULL)"
        private const val CREATE_COMPLETED_TRIP =
            "CREATE TABLE IF NOT EXISTS completed_trip (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, distance REAL NOT NULL, elapsed INTEGER NOT NULL, " +
                "moving INTEGER NOT NULL, stopped INTEGER NOT NULL, trip_average REAL NOT NULL, " +
                "moving_average REAL NOT NULL, max_speed REAL NOT NULL)"
        private const val CREATE_COMPLETED_POINT =
            "CREATE TABLE IF NOT EXISTS completed_point (trip_id INTEGER NOT NULL, sequence INTEGER NOT NULL, " +
                "timestamp INTEGER NOT NULL, speed REAL NOT NULL, distance REAL NOT NULL, moving INTEGER NOT NULL, " +
                "PRIMARY KEY(trip_id, sequence), FOREIGN KEY(trip_id) REFERENCES completed_trip(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        private const val CREATE_COMPLETED_TRIP_INDEX =
            "CREATE INDEX IF NOT EXISTS completed_trip_ended_at_idx ON completed_trip(ended_at DESC, id DESC)"
    }
}

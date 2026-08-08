package com.cargps.storage

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteTripStorageInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun activeTripSurvivesDatabaseReopen() {
        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(1_000L)
            storage.appendPoint(TripPoint(2_000L, 8.0, 12.5, true))
            storage.updateActiveTrip(TripMode.PAUSED, 3_000L, 400L)
        }

        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            val restored = requireNotNull(storage.loadActiveTrip().activeTripOrNull())
            assertEquals(TripMode.PAUSED, restored.mode)
            assertEquals(1_000L, restored.startedAtMillis)
            assertEquals(3_000L, restored.pausedAtMillis)
            assertEquals(400L, restored.totalPausedMillis)
            assertEquals(listOf(TripPoint(2_000L, 8.0, 12.5, true)), restored.points)
        }
    }

    @Test
    fun activeTripCheckpointReportsLastDurablePointAfterReopen() {
        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(1_000L)
            storage.appendPoints(
                listOf(
                    TripPoint(2_000L, 8.0, 12.5, true),
                    TripPoint(3_000L, 9.0, 14.0, true),
                ),
            )
        }

        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            assertEquals(
                ActiveTripCheckpoint(
                    startedAtMillis = 1_000L,
                    confirmedPointCount = 2L,
                    lastConfirmedPointSequence = 2L,
                    lastConfirmedPointTimestampMillis = 3_000L,
                ),
                storage.loadActiveTripCheckpoint(),
            )
        }
    }

    @Test
    fun invalidModeIsReportedAsCorruptWithoutClearingTheActiveTrip() {
        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(1_000L)
        }
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("UPDATE active_trip SET mode = 'UNKNOWN_MODE' WHERE id = 1")
        }

        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            val result = storage.loadActiveTrip()

            assertTrue(result is ActiveTripLoadResult.Corrupt)
            assertEquals("UNKNOWN_MODE", (result as ActiveTripLoadResult.Corrupt).rawMode)
        }
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
            database.rawQuery("SELECT mode FROM active_trip WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("UNKNOWN_MODE", cursor.getString(0))
            }
        }
    }

    @Test
    fun completeTripStoresHistoryAndClearsActiveTrip() {
        val activePoint = TripPoint(2_000L, 4.0, 10.0, true)
        val completed = CompletedTripRecord(
            startedAtMillis = 1_000L,
            endedAtMillis = 10_000L,
            stats = TripStats(
                distanceMeters = 245.0,
                elapsedMillis = 8_000L,
                movingMillis = 6_000L,
                stoppedMillis = 2_000L,
                tripAverageMps = 3.5,
                movingAverageMps = 4.2,
                maxSpeedMps = 9.0,
            ),
        )

        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(completed.startedAtMillis)
            storage.appendPoint(activePoint)
            storage.completeTrip(completed)

            assertNull(storage.loadActiveTrip().activeTripOrNull())
            val history = storage.recentTrips()
            assertEquals(1, history.size)
            assertEquals(completed.copy(id = history.single().id), history.single())
            assertEquals(listOf(activePoint), storage.completedTripPoints(history.single().id))
        }
    }

    @Test
    fun versionOneDatabaseMigratesWithoutLosingActiveTrip() {
        createVersionOneDatabase()

        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            val restored = requireNotNull(storage.loadActiveTrip().activeTripOrNull())
            assertEquals(TripMode.RECORDING, restored.mode)
            assertEquals(1_000L, restored.startedAtMillis)
            assertEquals(listOf(TripPoint(2_000L, 4.0, 10.0, true)), restored.points)

            val completed = CompletedTripRecord(
                startedAtMillis = restored.startedAtMillis,
                endedAtMillis = 3_000L,
                stats = TripStats(10.0, 2_000L, 1_000L, 1_000L, 5.0, 10.0, 4.0),
            )
            storage.completeTrip(completed)
            val history = storage.recentTrips()
            assertEquals(1, history.size)
            assertEquals(restored.points, storage.completedTripPoints(history.single().id))
            assertTrue(completedTripIndexExists())
        }
    }

    @Test
    fun batchedPointsKeepInsertionOrderAcrossReopen() {
        val points = List(1_000) { index ->
            TripPoint(
                timestampMillis = index * 500L,
                speedMps = 8.0,
                distanceFromPreviousMeters = 4.0,
                moving = true,
            )
        }

        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(1_000L)
            storage.appendPoints(points)
        }

        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            assertEquals(points, requireNotNull(storage.loadActiveTrip().activeTripOrNull()).points)
        }
    }

    @Test
    fun roomStorageMigratesVersionThreeWithoutLosingActiveTrip() {
        val point = TripPoint(2_000L, 8.0, 12.5, true)
        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(1_000L)
            storage.appendPoint(point)
            storage.updateActiveTrip(TripMode.PAUSED, 3_000L, 400L)
        }

        RoomTripStorage(context, DATABASE_NAME).use { storage ->
            assertEquals(
                ActiveTripRecord(TripMode.PAUSED, 1_000L, 3_000L, 400L, listOf(point)),
                storage.loadActiveTrip().activeTripOrNull(),
            )
        }
    }

    @Test
    fun roomStorageMigratesVersionOneThroughEverySchemaWithoutLosingActiveTrip() {
        createVersionOneDatabase()

        RoomTripStorage(context, DATABASE_NAME).use { storage ->
            assertEquals(
                ActiveTripRecord(
                    mode = TripMode.RECORDING,
                    startedAtMillis = 1_000L,
                    pausedAtMillis = null,
                    totalPausedMillis = 0L,
                    points = listOf(TripPoint(2_000L, 4.0, 10.0, true)),
                ),
                storage.loadActiveTrip().activeTripOrNull(),
            )
        }
    }

    @Test
    fun roomStorageMigratesVersionTwoWithoutLosingCompletedTripAndPoints() {
        createVersionTwoDatabase()

        RoomTripStorage(context, DATABASE_NAME).use { storage ->
            val history = storage.recentTrips()

            assertEquals(7L, history.single().id)
            assertEquals(250.0, history.single().stats.distanceMeters, 0.001)
            assertEquals(
                listOf(TripPoint(8_000L, 6.0, 25.0, true)),
                storage.completedTripPoints(7L),
            )
            assertEquals(1_000L, storage.loadActiveTrip().activeTripOrNull()?.startedAtMillis)
        }
        assertTrue(completedTripIndexExists())
    }

    @Test
    fun roomStoragePreservesTheTripStorageTransactionContract() {
        val points = listOf(
            TripPoint(2_000L, 4.0, 10.0, true),
            TripPoint(3_000L, 0.0, 0.0, false),
        )
        val completed = CompletedTripRecord(
            startedAtMillis = 1_000L,
            endedAtMillis = 4_000L,
            stats = TripStats(10.0, 3_000L, 1_000L, 2_000L, 3.33, 10.0, 4.0),
        )

        RoomTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(1_000L)
            storage.appendPoints(points)
            storage.updateActiveTrip(TripMode.PAUSED, 3_500L, 500L)

            assertEquals(
                ActiveTripCheckpoint(1_000L, 2L, 2L, 3_000L),
                storage.loadActiveTripCheckpoint(),
            )

            storage.completeTrip(completed)
            assertNull(storage.loadActiveTrip().activeTripOrNull())
            val history = storage.recentTrips()
            assertEquals(completed.copy(id = history.single().id), history.single())
            assertEquals(points, storage.completedTripPoints(history.single().id))
        }
    }

    @Test
    fun roomStorageReportsCorruptLegacyModeWithoutClearingTheDatabase() {
        SqliteTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(1_000L)
        }
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("UPDATE active_trip SET mode = 'BROKEN_MODE' WHERE id = 1")
        }

        RoomTripStorage(context, DATABASE_NAME).use { storage ->
            val result = storage.loadActiveTrip()

            assertTrue(result is ActiveTripLoadResult.Corrupt)
            assertEquals("BROKEN_MODE", (result as ActiveTripLoadResult.Corrupt).rawMode)
        }
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
            database.rawQuery("SELECT mode FROM active_trip WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("BROKEN_MODE", cursor.getString(0))
            }
        }
    }

    @Test
    fun failedRoomMigrationKeepsTheOriginalVersionThreeDatabase() {
        createVersionThreeDatabaseMissingTotalPaused()

        assertThrows(RuntimeException::class.java) {
            RoomTripStorage(context, DATABASE_NAME).use { storage -> storage.loadActiveTrip() }
        }

        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { database ->
            assertEquals(3, database.version)
            database.rawQuery("SELECT mode FROM active_trip WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RECORDING", cursor.getString(0))
            }
            database.rawQuery("PRAGMA table_info(active_trip)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val columns = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertFalse(columns.contains("total_paused"))
            }
        }
    }

    @Test
    fun roomStorageKeepsActiveTripWhenSqliteConnectionIsReadOnly() {
        val durablePoint = TripPoint(2_000L, 8.0, 12.5, true)
        RoomTripStorage(context, DATABASE_NAME).use { storage ->
            storage.startTrip(1_000L)
            storage.appendPoints(listOf(durablePoint))
        }

        val failure = assertThrows(SQLiteException::class.java) {
            RoomTripStorage(
                context = context,
                databaseName = DATABASE_NAME,
                onDatabaseOpen = { database ->
                    // 作者：long｜只读连接模拟磁盘永久不可写，验证真实 Room 事务失败时不清除活动行程。
                    database.execSQL("PRAGMA query_only = ON")
                },
            ).use { storage ->
                storage.appendPoints(
                    List(16) { index ->
                        TripPoint(
                            timestampMillis = 3_000L + index,
                            speedMps = 4.0,
                            distanceFromPreviousMeters = 1.0,
                            moving = true,
                        )
                    },
                )
            }
        }
        assertTrue(
            failure.message.orEmpty().contains("readonly", ignoreCase = true) ||
                failure.message.orEmpty().contains("read-only", ignoreCase = true),
        )

        RoomTripStorage(context, DATABASE_NAME).use { storage ->
            val restored = requireNotNull(storage.loadActiveTrip().activeTripOrNull())
            assertEquals(listOf(durablePoint), restored.points)
            assertEquals(
                ActiveTripCheckpoint(1_000L, 1L, 1L, 2_000L),
                storage.loadActiveTripCheckpoint(),
            )
        }
    }

    private fun completedTripIndexExists(): Boolean =
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf("completed_trip_ended_at_idx"),
            ).use { cursor -> cursor.moveToFirst() }
        }

    private fun createVersionOneDatabase() {
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE active_trip (id INTEGER PRIMARY KEY, mode TEXT NOT NULL, " +
                    "started_at INTEGER NOT NULL, paused_at INTEGER, total_paused INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE active_point (sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "timestamp INTEGER NOT NULL, speed REAL NOT NULL, distance REAL NOT NULL, " +
                    "moving INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE completed_trip (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, distance REAL NOT NULL, " +
                    "elapsed INTEGER NOT NULL, moving INTEGER NOT NULL, stopped INTEGER NOT NULL, " +
                    "trip_average REAL NOT NULL, moving_average REAL NOT NULL, max_speed REAL NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO active_trip VALUES (1, 'RECORDING', 1000, NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO active_point (timestamp, speed, distance, moving) VALUES (2000, 4.0, 10.0, 1)",
            )
            db.version = 1
        }
    }

    private fun createVersionTwoDatabase() {
        createVersionOneDatabase()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE completed_point (trip_id INTEGER NOT NULL, sequence INTEGER NOT NULL, " +
                    "timestamp INTEGER NOT NULL, speed REAL NOT NULL, distance REAL NOT NULL, moving INTEGER NOT NULL, " +
                    "PRIMARY KEY (trip_id, sequence), " +
                    "FOREIGN KEY (trip_id) REFERENCES completed_trip(id) ON DELETE CASCADE)",
            )
            db.execSQL(
                "INSERT INTO completed_trip VALUES (7, 4000, 9000, 250.0, 5000, 4000, 1000, 50.0, 62.5, 12.0)",
            )
            db.execSQL(
                "INSERT INTO completed_point VALUES (7, 4, 8000, 6.0, 25.0, 1)",
            )
            db.version = 2
        }
    }

    private fun createVersionThreeDatabaseMissingTotalPaused() {
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE active_trip (id INTEGER PRIMARY KEY, mode TEXT NOT NULL, " +
                    "started_at INTEGER NOT NULL, paused_at INTEGER)",
            )
            db.execSQL(
                "CREATE TABLE active_point (sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "timestamp INTEGER NOT NULL, speed REAL NOT NULL, distance REAL NOT NULL, moving INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE completed_trip (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, distance REAL NOT NULL, " +
                    "elapsed INTEGER NOT NULL, moving INTEGER NOT NULL, stopped INTEGER NOT NULL, " +
                    "trip_average REAL NOT NULL, moving_average REAL NOT NULL, max_speed REAL NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE completed_point (trip_id INTEGER NOT NULL, sequence INTEGER NOT NULL, " +
                    "timestamp INTEGER NOT NULL, speed REAL NOT NULL, distance REAL NOT NULL, moving INTEGER NOT NULL, " +
                    "PRIMARY KEY (trip_id, sequence), " +
                    "FOREIGN KEY (trip_id) REFERENCES completed_trip(id) ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX completed_trip_ended_at_idx ON completed_trip(ended_at DESC, id DESC)")
            db.execSQL("INSERT INTO active_trip VALUES (1, 'RECORDING', 1000, NULL)")
            db.version = 3
        }
    }

    companion object {
        private const val DATABASE_NAME = "trip-storage-test.db"
    }
}

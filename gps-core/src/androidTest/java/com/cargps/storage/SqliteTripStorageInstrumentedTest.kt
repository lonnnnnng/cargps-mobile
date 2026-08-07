package com.cargps.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.domain.TripStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            val restored = requireNotNull(storage.loadActiveTrip())
            assertEquals(TripMode.PAUSED, restored.mode)
            assertEquals(1_000L, restored.startedAtMillis)
            assertEquals(3_000L, restored.pausedAtMillis)
            assertEquals(400L, restored.totalPausedMillis)
            assertEquals(listOf(TripPoint(2_000L, 8.0, 12.5, true)), restored.points)
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

            assertNull(storage.loadActiveTrip())
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
            val restored = requireNotNull(storage.loadActiveTrip())
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
        }
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

    companion object {
        private const val DATABASE_NAME = "trip-storage-test.db"
    }
}

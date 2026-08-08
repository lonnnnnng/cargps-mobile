package com.cargps

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cargps.storage.activeTripOrNull
import com.cargps.domain.LocationSample
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.QueuedTripStorage
import com.cargps.storage.RoomTripStorage
import com.cargps.session.TripPersistenceState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 作者：long
 *
 * 通过真实 Room 连接把“永久不可写”推进到运行时背压路径，验证活动行程不能因批量失败被误清除，
 * 且磁盘恢复后同一尾批可以重新确认。该测试不伪造物理 ENOSPC，只覆盖 SQLite 连接级故障。
 */
@RunWith(AndroidJUnit4::class)
class RoomRuntimeBackpressureInstrumentedTest {
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
    fun roomWriteFailureReachesRuntimeBackpressureAndRecoversCheckpoint() = runBlocking {
        val openedDatabase = AtomicReference<SupportSQLiteDatabase?>()
        val roomStorage = RoomTripStorage(
            context = context,
            databaseName = DATABASE_NAME,
            onDatabaseOpen = { database -> openedDatabase.compareAndSet(null, database) },
        )
        val queuedStorage = QueuedTripStorage(roomStorage)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = DashboardRuntime(
            scope = runtimeScope,
            storage = queuedStorage,
            ioDispatcher = Dispatchers.IO,
        )

        try {
            val restored = withTimeout(10_000L) {
                runtime.state.first { state -> state.storageReady || state.storageError != null }
            }
            assertTrue(restored.storageReady)

            val started = runtime.startTripAndAwait(1_000L)
            assertEquals(TripMode.RECORDING, started.tripMode)
            assertEquals(
                ActiveTripCheckpoint(1_000L, 0L, null, null),
                started.confirmedTripCheckpoint,
            )

            val database = withTimeout(10_000L) {
                while (openedDatabase.get() == null) {
                    kotlinx.coroutines.delay(10L)
                }
                requireNotNull(openedDatabase.get())
            }
            database.execSQL("PRAGMA query_only = ON")

            // 作者：long｜第 17 个样本必须由协调器同步拒绝，避免永久写失败时内存尾批无限增长。
            repeat(17) { index ->
                runtime.onLocationSample(
                    sample = LocationSample(
                        timestampMillis = 2_000L + index,
                        latitude = index * 0.00001,
                        longitude = 0.0,
                        accuracyMeters = 5f,
                    ),
                    distanceToPreviousMeters = 1.0,
                )
            }

            val failed = withTimeout(15_000L) {
                runtime.state.first { state -> state.storageBackpressure }
            }
            assertEquals(TripMode.RECORDING, failed.tripMode)
            assertTrue(failed.storageError.orEmpty().isNotBlank())
            assertNotNull(failed.confirmedTripCheckpoint)
            assertEquals(0L, failed.confirmedTripCheckpoint?.confirmedPointCount)

            database.execSQL("PRAGMA query_only = OFF")
            val recovered = runtime.checkpointTripWritesAndAwait()

            assertFalse(recovered.storageBackpressure)
            assertEquals(TripPersistenceState.CONFIRMED, runtimeStatePersistence(runtime))
            assertEquals(
                ActiveTripCheckpoint(1_000L, 16L, 16L, 2_015L),
                recovered.confirmedTripCheckpoint,
            )
            assertEquals(16, roomStorage.loadActiveTrip().activeTripOrNull()?.points?.size)
        } finally {
            runtime.close()
            runtimeScope.cancel()
        }
    }

    private fun runtimeStatePersistence(runtime: DashboardRuntime): com.cargps.session.TripPersistenceState =
        when {
            runtime.state.value.storageBackpressure -> com.cargps.session.TripPersistenceState.FAILED
            runtime.state.value.storageError == null -> com.cargps.session.TripPersistenceState.CONFIRMED
            else -> com.cargps.session.TripPersistenceState.FAILED
        }

    companion object {
        private const val DATABASE_NAME = "runtime-backpressure-test.db"
    }
}

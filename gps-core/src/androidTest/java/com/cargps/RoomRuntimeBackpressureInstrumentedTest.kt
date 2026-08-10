package com.cargps

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cargps.storage.activeTripOrNull
import com.cargps.domain.LocationSample
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.QueuedTripStorage
import com.cargps.storage.RoomTripStorage
import com.cargps.storage.SqliteFullFaultController
import com.cargps.storage.TripStorage
import com.cargps.session.TripPersistenceState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
 * 通过真实 Room 连接把只读故障和受控 SQLITE_FULL 推进到运行时背压路径，
 * 验证活动行程不能因批量失败被误清除，且存储恢复后同一尾批可以重新确认。
 * 该测试不消耗共享磁盘制造物理 ENOSPC。
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

    @Test
    fun sqliteFullReachesRuntimeBackpressureAndRecoversCheckpoint() = runBlocking {
        val openedDatabase = AtomicReference<SupportSQLiteDatabase?>()
        val roomStorage = RoomTripStorage(
            context = context,
            databaseName = DATABASE_NAME,
            onDatabaseOpen = { database -> openedDatabase.compareAndSet(null, database) },
            journalMode = RoomDatabase.JournalMode.TRUNCATE,
        )
        val observedWriteFailures = ConcurrentLinkedQueue<Throwable>()
        val queuedStorage = QueuedTripStorage(roomStorage) { error ->
            observedWriteFailures.add(error)
        }
        val runtimeStorage = object : TripStorage by queuedStorage {
            // 作者：long｜先隔离异步满盘通知，确保测试能在同一真实失败尾批上确定性地投递第 17 点；
            // 原始异常由 onWriteFailure 独立核验，背压异常仍通过会话命令进入 Runtime。
            override val errors: Flow<Throwable> = emptyFlow()
        }
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = DashboardRuntime(
            scope = runtimeScope,
            storage = runtimeStorage,
            ioDispatcher = Dispatchers.IO,
        )

        try {
            val restored = withTimeout(10_000L) {
                runtime.state.first { state -> state.storageReady || state.storageError != null }
            }
            assertTrue(restored.storageReady)

            val started = runtime.startTripAndAwait(1_000L)
            assertEquals(TripMode.RECORDING, started.tripMode)
            val database = withTimeout(10_000L) {
                while (openedDatabase.get() == null) {
                    kotlinx.coroutines.delay(10L)
                }
                requireNotNull(openedDatabase.get())
            }
            val faultState = SqliteFullFaultController.arm(database)

            // 作者：long｜真实 SQLITE_FULL 先让 16 点批次留在内存，确认原始异常后再投第 17 点触发有界背压。
            repeat(16) { index ->
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

            withTimeout(15_000L) {
                while (observedWriteFailures.none { error -> error.containsSqliteFull() }) {
                    kotlinx.coroutines.delay(10L)
                }
            }
            assertEquals(TripMode.RECORDING, runtime.state.value.tripMode)
            assertEquals(0L, runtime.state.value.confirmedTripCheckpoint?.confirmedPointCount)

            runtime.onLocationSample(
                sample = LocationSample(
                    timestampMillis = 2_016L,
                    latitude = 0.00016,
                    longitude = 0.0,
                    accuracyMeters = 5f,
                ),
                distanceToPreviousMeters = 1.0,
            )

            val failed = withTimeout(15_000L) {
                runtime.state.first { state -> state.storageBackpressure }
            }
            assertEquals(TripMode.RECORDING, failed.tripMode)
            assertTrue(failed.storageError.orEmpty().isNotBlank())
            assertEquals(0L, failed.confirmedTripCheckpoint?.confirmedPointCount)

            SqliteFullFaultController.release(database, faultState)
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

    private fun Throwable.containsSqliteFull(): Boolean =
        this is SQLiteFullException ||
            message.orEmpty().contains("database or disk is full", ignoreCase = true) ||
            cause?.takeUnless { cause -> cause === this }?.containsSqliteFull() == true ||
            suppressed.any { error -> error.containsSqliteFull() }

    companion object {
        private const val DATABASE_NAME = "runtime-backpressure-test.db"
    }
}

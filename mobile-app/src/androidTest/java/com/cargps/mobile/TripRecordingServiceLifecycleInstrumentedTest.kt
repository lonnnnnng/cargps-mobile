package com.cargps.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cargps.DashboardRuntime
import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.storage.ActiveTripLoadResult
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.ActiveTripRecord
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.TripStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripRecordingServiceLifecycleInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun start_waits_for_storage_confirmation_before_registering_location() {
        grantForegroundLocationPermissions()
        val storageStarted = CountDownLatch(1)
        val releaseStorage = CountDownLatch(1)
        val storage = BlockingStartTripStorage(storageStarted, releaseStorage)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = DashboardRuntime(scope = runtimeScope, storage = storage)
        awaitCondition("runtime restore", 5_000L) { runtime.state.value.storageReady }

        val location = LocationProbe()
        TripRecordingService.dependenciesFactoryForTests = {
            TripRecordingServiceDependencies(
                runtime = runtime,
                startLocation = location::start,
                stopLocation = location::stop,
                closeLocation = location::close,
                readAccessState = { TripAccessState.Ready },
            )
        }
        try {
            ContextCompat.startForegroundService(
                context,
                TripRecordingService.startTripIntent(context),
            )

            assertTrue(
                "Service 没有进入等待存储确认状态",
                storageStarted.await(5, TimeUnit.SECONDS),
            )
            // 作者：long｜Start 事务尚未得到存储确认时，后台定位不能先行注册，否则首点会落在未确认行程边界之外。
            assertEquals(0, location.starts.get())

            releaseStorage.countDown()
            awaitCondition("confirmed start and location", 5_000L) {
                runtime.state.value.tripMode == TripMode.RECORDING && location.starts.get() == 1
            }
        } finally {
            context.stopService(TripRecordingService.bindIntent(context))
            awaitCondition("service location cleanup", 5_000L) { location.closes.get() > 0 }
            TripRecordingService.dependenciesFactoryForTests = null
            runtime.close()
            runtimeScope.cancel()
        }
    }

    @Test
    fun activity_visibility_rebind_does_not_duplicate_location_registration() {
        val storage = ImmediateEmptyTripStorage()
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = DashboardRuntime(scope = runtimeScope, storage = storage)
        awaitCondition("runtime restore", 5_000L) { runtime.state.value.storageReady }
        val location = LocationProbe()
        TripRecordingService.dependenciesFactoryForTests = {
            TripRecordingServiceDependencies(
                runtime = runtime,
                startLocation = location::start,
                stopLocation = location::stop,
                closeLocation = location::close,
                readAccessState = { TripAccessState.Ready },
            )
        }

        val firstConnection = ServiceConnectionLatch()
        try {
            assertTrue(
                context.bindService(
                    TripRecordingService.bindIntent(context),
                    firstConnection,
                    Context.BIND_AUTO_CREATE,
                ),
            )
            val firstBinder = firstConnection.awaitBinder()
            firstBinder.setClientVisible(true)
            awaitCondition("visible preview start", 5_000L) { location.starts.get() == 1 }

            firstBinder.setClientVisible(false)
            awaitCondition("hidden preview stop", 5_000L) { location.stops.get() >= 1 }
            context.unbindService(firstConnection)
            awaitCondition("first service destroy", 5_000L) { location.closes.get() >= 1 }

            val secondConnection = ServiceConnectionLatch()
            assertTrue(
                context.bindService(
                    TripRecordingService.bindIntent(context),
                    secondConnection,
                    Context.BIND_AUTO_CREATE,
                ),
            )
            val secondBinder = secondConnection.awaitBinder()
            secondBinder.setClientVisible(true)
            awaitCondition("rebound preview start", 5_000L) { location.starts.get() == 2 }
            secondBinder.setClientVisible(false)
            context.unbindService(secondConnection)
            awaitCondition("second service destroy", 5_000L) { location.closes.get() >= 2 }
        } finally {
            runCatching { context.stopService(TripRecordingService.bindIntent(context)) }
            TripRecordingService.dependenciesFactoryForTests = null
            runtime.close()
            runtimeScope.cancel()
        }
    }

    @Test
    fun storage_failure_stops_location_updates_notification_and_checkpoint_restarts() {
        grantForegroundLocationPermissions()
        val storage = RecoverableAppendFailureStorage()
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = DashboardRuntime(scope = runtimeScope, storage = storage)
        awaitCondition("runtime restore", 5_000L) { runtime.state.value.storageReady }
        val location = LocationProbe()
        val serviceReference = AtomicReference<TripRecordingService>()
        TripRecordingService.dependenciesFactoryForTests = { service ->
            serviceReference.set(service)
            TripRecordingServiceDependencies(
                runtime = runtime,
                startLocation = location::start,
                stopLocation = location::stop,
                closeLocation = location::close,
                readAccessState = { TripAccessState.Ready },
            )
        }

        try {
            ContextCompat.startForegroundService(
                context,
                TripRecordingService.startTripIntent(context),
            )
            awaitCondition("recording service start", 5_000L) {
                runtime.state.value.tripMode == TripMode.RECORDING && location.starts.get() == 1
            }
            awaitNotificationTitle("正在记录", 2_000L)

            serviceReference.get().onLocation(
                Location(LocationManager.GPS_PROVIDER).apply {
                    time = System.currentTimeMillis()
                    latitude = 31.2304
                    longitude = 121.4737
                    accuracy = 5f
                    speed = 3f
                },
            )
            awaitCondition("storage failure stops location", 5_000L) {
                runtime.state.value.storageError != null && location.stops.get() >= 1
            }
            // 作者：long｜持久化状态变化必须绕过普通 5 秒通知节流，否则用户会在定位已停止后仍看到“正在记录”。
            val blockedNotification = awaitNotificationTitle("等待存储恢复", 2_000L)
            assertTrue(
                blockedNotification.actions.orEmpty().any { action -> action.title == "结束行程" },
            )

            storage.recover()
            awaitCondition("checkpoint restarts location", 5_000L) {
                runtime.state.value.storageError == null && location.starts.get() == 2
            }
            awaitNotificationTitle("正在记录", 2_000L)
        } finally {
            context.stopService(TripRecordingService.bindIntent(context))
            awaitCondition("service location cleanup", 5_000L) { location.closes.get() > 0 }
            TripRecordingService.dependenciesFactoryForTests = null
            runtime.close()
            runtimeScope.cancel()
        }
    }

    @Test
    fun task_removal_checkpoint_survives_service_scope_cancellation() {
        grantForegroundLocationPermissions()
        val storage = TaskRemovalCheckpointStorage()
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = DashboardRuntime(scope = runtimeScope, storage = storage)
        awaitCondition("runtime restore", 5_000L) { runtime.state.value.storageReady }
        val location = LocationProbe()
        val serviceReference = AtomicReference<TripRecordingService>()
        TripRecordingService.dependenciesFactoryForTests = { service ->
            serviceReference.set(service)
            TripRecordingServiceDependencies(
                runtime = runtime,
                startLocation = location::start,
                stopLocation = location::stop,
                closeLocation = location::close,
                readAccessState = { TripAccessState.Ready },
            )
        }

        try {
            ContextCompat.startForegroundService(
                context,
                TripRecordingService.startTripIntent(context),
            )
            awaitCondition("recording service start", 5_000L) {
                runtime.state.value.tripMode == TripMode.RECORDING && location.starts.get() == 1
            }

            storage.blockCheckpoint = true
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                serviceReference.get().onTaskRemoved(null)
            }
            assertTrue(
                "任务移除没有进入尾批检查点",
                storage.checkpointEntered.await(5, TimeUnit.SECONDS),
            )

            context.stopService(TripRecordingService.bindIntent(context))
            awaitCondition("service scope cancellation", 5_000L) { location.closes.get() > 0 }

            storage.releaseCheckpoint.countDown()
            awaitCondition("checkpoint completion after service destroy", 5_000L) {
                storage.checkpointCompletions.get() == 1 &&
                    runtime.state.value.confirmedTripCheckpoint != null
            }
        } finally {
            storage.releaseCheckpoint.countDown()
            runCatching { context.stopService(TripRecordingService.bindIntent(context)) }
            TripRecordingService.dependenciesFactoryForTests = null
            runtime.close()
            runtimeScope.cancel()
        }
    }

    @Test
    fun end_request_keeps_preceding_tail_and_rejects_later_location() {
        grantForegroundLocationPermissions()
        val storage = BlockingEndTripStorage()
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = DashboardRuntime(scope = runtimeScope, storage = storage)
        awaitCondition("runtime restore", 5_000L) { runtime.state.value.storageReady }
        val location = LocationProbe()
        val serviceReference = AtomicReference<TripRecordingService>()
        TripRecordingService.dependenciesFactoryForTests = { service ->
            serviceReference.set(service)
            TripRecordingServiceDependencies(
                runtime = runtime,
                startLocation = location::start,
                stopLocation = location::stop,
                closeLocation = location::close,
                readAccessState = { TripAccessState.Ready },
            )
        }

        try {
            ContextCompat.startForegroundService(
                context,
                TripRecordingService.startTripIntent(context),
            )
            awaitCondition("recording service start", 5_000L) {
                runtime.state.value.tripMode == TripMode.RECORDING && location.starts.get() == 1
            }

            val firstFixAtMillis = System.currentTimeMillis()
            serviceReference.get().onLocation(testLocation(firstFixAtMillis, 31.2304, 121.4737))
            awaitCondition("tail point before end", 5_000L) { storage.appendCalls.get() == 1 }

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                serviceReference.get().onStartCommand(
                    TripRecordingService.endTripIntent(context),
                    0,
                    2,
                )
            }
            assertTrue("结束事务没有进入存储", storage.completeEntered.await(5, TimeUnit.SECONDS))

            val lateFixAtMillis = firstFixAtMillis + 1_000L
            serviceReference.get().onLocation(testLocation(lateFixAtMillis, 31.2305, 121.4738))
            awaitCondition("late fix remains visible", 5_000L) {
                runtime.state.value.lastFixAtMillis == lateFixAtMillis
            }
            assertEquals(1, storage.appendCalls.get())

            storage.releaseComplete.countDown()
            awaitCondition("end confirmation", 5_000L) {
                runtime.state.value.tripMode == TripMode.IDLE && storage.completedPointCount.get() == 1
            }
            // 作者：long｜等待下一次 Start 作为队列屏障，证明 End 后入队的定位事件已经被消费并拒绝，而不是尚未执行。
            runBlocking {
                runtime.startTripAndAwait(lateFixAtMillis + 1_000L)
            }
            assertEquals(1, storage.appendCalls.get())
            assertEquals(1, storage.completedPointCount.get())
        } finally {
            storage.releaseComplete.countDown()
            runCatching { context.stopService(TripRecordingService.bindIntent(context)) }
            TripRecordingService.dependenciesFactoryForTests = null
            runtime.close()
            runtimeScope.cancel()
        }
    }

    @Test
    fun actor_failure_stops_location_and_recovers_from_confirmed_state() {
        grantForegroundLocationPermissions()
        val storage = RecoverableActorFailureStorage()
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = DashboardRuntime(scope = runtimeScope, storage = storage)
        awaitCondition("runtime restore", 5_000L) { runtime.state.value.storageReady }
        val location = LocationProbe()
        val serviceReference = AtomicReference<TripRecordingService>()
        TripRecordingService.dependenciesFactoryForTests = { service ->
            serviceReference.set(service)
            TripRecordingServiceDependencies(
                runtime = runtime,
                startLocation = location::start,
                stopLocation = location::stop,
                closeLocation = location::close,
                readAccessState = { TripAccessState.Ready },
            )
        }

        try {
            ContextCompat.startForegroundService(
                context,
                TripRecordingService.startTripIntent(context),
            )
            awaitCondition("recording service start", 5_000L) {
                runtime.state.value.tripMode == TripMode.RECORDING && location.starts.get() == 1
            }

            serviceReference.get().onLocation(
                testLocation(System.currentTimeMillis(), 31.2304, 121.4737),
            )
            assertTrue(
                "actor 异常后没有进入确认边界恢复",
                storage.recoveryRestoreEntered.await(5, TimeUnit.SECONDS),
            )
            awaitCondition("actor failure stops location", 5_000L) {
                runtime.state.value.tripRuntimeError != null && location.stops.get() >= 1
            }
            awaitNotificationTitle("行程处理异常", 2_000L)

            storage.releaseRecoveryRestore.countDown()
            awaitCondition("actor restore restarts location", 5_000L) {
                runtime.state.value.storageReady &&
                    runtime.state.value.tripRuntimeError == null &&
                    runtime.state.value.tripMode == TripMode.RECORDING &&
                    location.starts.get() == 2
            }
            awaitNotificationTitle("正在记录", 2_000L)

            serviceReference.get().onLocation(
                testLocation(System.currentTimeMillis() + 1_000L, 31.2305, 121.4738),
            )
            awaitCondition("location accepted after actor restore", 5_000L) {
                storage.appendCalls.get() == 2 && storage.persistedPoints.get() == 1
            }
        } finally {
            storage.releaseRecoveryRestore.countDown()
            runCatching { context.stopService(TripRecordingService.bindIntent(context)) }
            TripRecordingService.dependenciesFactoryForTests = null
            runtime.close()
            runtimeScope.cancel()
        }
    }

    private fun testLocation(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double,
    ): Location = Location(LocationManager.GPS_PROVIDER).apply {
        time = timestampMillis
        this.latitude = latitude
        this.longitude = longitude
        accuracy = 5f
        speed = 3f
    }

    private fun grantForegroundLocationPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissions.forEach { permission ->
            // 作者：long｜API 27 没有 adoptShellPermissionIdentity；instrumentation shell 命令本身已由 shell UID 执行，可直接授权。
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} $permission",
            ).close()
        }
    }

    private fun awaitCondition(description: String, timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25L)
        }
        throw AssertionError("等待超时：$description")
    }

    private fun awaitNotificationTitle(expectedText: String, timeoutMillis: Long): Notification {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        var matched: Notification? = null
        awaitCondition("notification title $expectedText", timeoutMillis) {
            matched = notificationManager.activeNotifications
                .firstOrNull { notification -> notification.id == TripRecordingService.NOTIFICATION_ID }
                ?.notification
                ?.takeIf { notification ->
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                        ?.contains(expectedText) == true
                }
            matched != null
        }
        return checkNotNull(matched)
    }

    private class ServiceConnectionLatch : ServiceConnection {
        private val connected = CountDownLatch(1)
        @Volatile private var binder: TripRecordingService.LocalBinder? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as TripRecordingService.LocalBinder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit

        fun awaitBinder(): TripRecordingService.LocalBinder {
            check(connected.await(5, TimeUnit.SECONDS)) { "Service 绑定超时" }
            return checkNotNull(binder)
        }
    }

    private class LocationProbe {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val closes = AtomicInteger()

        fun start(): Boolean {
            starts.incrementAndGet()
            return true
        }

        fun stop() {
            stops.incrementAndGet()
        }

        fun close() {
            closes.incrementAndGet()
        }
    }

    private class BlockingStartTripStorage(
        private val startEntered: CountDownLatch,
        private val releaseStart: CountDownLatch,
    ) : TripStorage {
        @Volatile private var startedAtMillis: Long? = null

        override fun loadActiveTrip(): ActiveTripLoadResult = ActiveTripLoadResult.Empty

        override fun startTrip(startedAtMillis: Long) {
            startEntered.countDown()
            check(releaseStart.await(5, TimeUnit.SECONDS)) { "测试未释放开始写入" }
            this.startedAtMillis = startedAtMillis
        }

        override fun appendPoint(point: TripPoint) = Unit

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit

        override fun completeTrip(record: CompletedTripRecord) {
            startedAtMillis = null
        }

        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        override fun loadActiveTripCheckpoint() = startedAtMillis?.let {
            com.cargps.storage.ActiveTripCheckpoint(
                startedAtMillis = it,
                confirmedPointCount = 0L,
                lastConfirmedPointSequence = null,
                lastConfirmedPointTimestampMillis = null,
            )
        }
    }

    private class ImmediateEmptyTripStorage : TripStorage {
        override fun loadActiveTrip(): ActiveTripLoadResult = ActiveTripLoadResult.Empty
        override fun startTrip(startedAtMillis: Long) = Unit
        override fun appendPoint(point: TripPoint) = Unit
        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit
        override fun completeTrip(record: CompletedTripRecord) = Unit
        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()
        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()
    }

    private class RecoverableAppendFailureStorage : TripStorage {
        private val checkpoints = MutableSharedFlow<ActiveTripCheckpoint>(replay = 1)
        @Volatile private var startedAtMillis: Long? = null
        @Volatile private var failAppend = true

        override val confirmedCheckpoints: Flow<ActiveTripCheckpoint> = checkpoints

        override fun loadActiveTrip(): ActiveTripLoadResult = ActiveTripLoadResult.Empty

        override fun startTrip(startedAtMillis: Long) {
            this.startedAtMillis = startedAtMillis
        }

        override fun appendPoint(point: TripPoint) {
            if (failAppend) throw IllegalStateException("injected append failure")
        }

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit

        override fun completeTrip(record: CompletedTripRecord) {
            startedAtMillis = null
        }

        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = startedAtMillis?.let(::checkpoint)

        fun recover() {
            val startedAtMillis = checkNotNull(startedAtMillis)
            failAppend = false
            check(checkpoints.tryEmit(checkpoint(startedAtMillis))) { "恢复检查点发送失败" }
        }

        private fun checkpoint(startedAtMillis: Long) = ActiveTripCheckpoint(
            startedAtMillis = startedAtMillis,
            confirmedPointCount = 0L,
            lastConfirmedPointSequence = null,
            lastConfirmedPointTimestampMillis = null,
        )
    }

    private class TaskRemovalCheckpointStorage : TripStorage {
        val checkpointEntered = CountDownLatch(1)
        val releaseCheckpoint = CountDownLatch(1)
        val checkpointCompletions = AtomicInteger()
        @Volatile var blockCheckpoint = false
        @Volatile private var startedAtMillis: Long? = null

        override fun loadActiveTrip(): ActiveTripLoadResult = ActiveTripLoadResult.Empty

        override fun startTrip(startedAtMillis: Long) {
            this.startedAtMillis = startedAtMillis
        }

        override fun appendPoint(point: TripPoint) = Unit

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit

        override fun completeTrip(record: CompletedTripRecord) {
            startedAtMillis = null
        }

        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        override fun awaitPendingWrites() {
            if (!blockCheckpoint) return
            checkpointEntered.countDown()
            check(releaseCheckpoint.await(5, TimeUnit.SECONDS)) { "测试未释放任务移除检查点" }
            checkpointCompletions.incrementAndGet()
        }

        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = startedAtMillis?.let { startedAt ->
            ActiveTripCheckpoint(
                startedAtMillis = startedAt,
                confirmedPointCount = 0L,
                lastConfirmedPointSequence = null,
                lastConfirmedPointTimestampMillis = null,
            )
        }
    }

    private class BlockingEndTripStorage : TripStorage {
        val completeEntered = CountDownLatch(1)
        val releaseComplete = CountDownLatch(1)
        val appendCalls = AtomicInteger()
        val completedPointCount = AtomicInteger(-1)
        private val points = mutableListOf<TripPoint>()
        @Volatile private var startedAtMillis: Long? = null
        @Volatile private var mode: TripMode = TripMode.IDLE
        @Volatile private var completedRecord: CompletedTripRecord? = null

        override fun loadActiveTrip(): ActiveTripLoadResult = startedAtMillis?.let { startedAt ->
            ActiveTripLoadResult.Loaded(
                ActiveTripRecord(
                    mode = mode,
                    startedAtMillis = startedAt,
                    pausedAtMillis = null,
                    totalPausedMillis = 0L,
                    points = synchronized(points) { points.toList() },
                ),
            )
        } ?: ActiveTripLoadResult.Empty

        override fun startTrip(startedAtMillis: Long) {
            this.startedAtMillis = startedAtMillis
            mode = TripMode.RECORDING
            synchronized(points) { points.clear() }
        }

        override fun appendPoint(point: TripPoint) {
            appendCalls.incrementAndGet()
            synchronized(points) { points += point }
        }

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) {
            this.mode = mode
        }

        override fun completeTrip(record: CompletedTripRecord) {
            completeEntered.countDown()
            check(releaseComplete.await(5, TimeUnit.SECONDS)) { "测试未释放结束事务" }
            completedPointCount.set(synchronized(points) { points.size })
            completedRecord = record
            startedAtMillis = null
            mode = TripMode.IDLE
            synchronized(points) { points.clear() }
        }

        override fun recentTrips(limit: Int): List<CompletedTripRecord> =
            completedRecord?.let(::listOf).orEmpty()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = startedAtMillis?.let { startedAt ->
            val snapshot = synchronized(points) { points.toList() }
            ActiveTripCheckpoint(
                startedAtMillis = startedAt,
                confirmedPointCount = snapshot.size.toLong(),
                lastConfirmedPointSequence = snapshot.size.toLong().takeIf { it > 0L },
                lastConfirmedPointTimestampMillis = snapshot.lastOrNull()?.timestampMillis,
            )
        }
    }

    private class RecoverableActorFailureStorage : TripStorage {
        val recoveryRestoreEntered = CountDownLatch(1)
        val releaseRecoveryRestore = CountDownLatch(1)
        val appendCalls = AtomicInteger()
        val persistedPoints = AtomicInteger()
        private val loadCalls = AtomicInteger()
        private val points = mutableListOf<TripPoint>()
        @Volatile private var startedAtMillis: Long? = null
        @Volatile private var failNextAppend = true

        override fun loadActiveTrip(): ActiveTripLoadResult {
            if (loadCalls.incrementAndGet() > 1) {
                recoveryRestoreEntered.countDown()
                check(releaseRecoveryRestore.await(5, TimeUnit.SECONDS)) {
                    "测试未释放 actor 恢复读取"
                }
            }
            return startedAtMillis?.let { startedAt ->
                ActiveTripLoadResult.Loaded(
                    ActiveTripRecord(
                        mode = TripMode.RECORDING,
                        startedAtMillis = startedAt,
                        pausedAtMillis = null,
                        totalPausedMillis = 0L,
                        points = synchronized(points) { points.toList() },
                    ),
                )
            } ?: ActiveTripLoadResult.Empty
        }

        override fun startTrip(startedAtMillis: Long) {
            this.startedAtMillis = startedAtMillis
        }

        override fun appendPoint(point: TripPoint) {
            appendCalls.incrementAndGet()
            if (failNextAppend) {
                failNextAppend = false
                throw CancellationException("injected actor failure")
            }
            synchronized(points) { points += point }
            persistedPoints.incrementAndGet()
        }

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) = Unit

        override fun completeTrip(record: CompletedTripRecord) {
            startedAtMillis = null
            synchronized(points) { points.clear() }
        }

        override fun recentTrips(limit: Int): List<CompletedTripRecord> = emptyList()

        override fun completedTripPoints(tripId: Long): List<TripPoint> = emptyList()

        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = startedAtMillis?.let { startedAt ->
            val snapshot = synchronized(points) { points.toList() }
            ActiveTripCheckpoint(
                startedAtMillis = startedAt,
                confirmedPointCount = snapshot.size.toLong(),
                lastConfirmedPointSequence = snapshot.size.toLong().takeIf { it > 0L },
                lastConfirmedPointTimestampMillis = snapshot.lastOrNull()?.timestampMillis,
            )
        }
    }
}

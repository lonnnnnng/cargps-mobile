package com.cargps.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cargps.DashboardRuntime
import com.cargps.TripMode
import com.cargps.domain.TripPoint
import com.cargps.storage.ActiveTripCheckpoint
import com.cargps.storage.ActiveTripLoadResult
import com.cargps.storage.CompletedTripRecord
import com.cargps.storage.QueuedTripStorage
import com.cargps.storage.RoomTripStorage
import com.cargps.storage.TripStorage
import com.cargps.storage.activeTripOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 作者：long
 *
 * 本地 M3 破坏性验证的 probe-only 入口。它让真实 Room 的 16 点批次停在委托提交前，
 * 再由外层脚本通过应用 UID 发送 SIGKILL；普通 Debug 与 Release APK 都不包含该 Service，也不会改变生产启动路径。
 */
class M3CheckpointProbeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private var runtime: DashboardRuntime? = null
    private var queuedStorage: QueuedTripStorage? = null

    override fun onCreate() {
        super.onCreate()
        createProbeNotificationChannel()
        ServiceCompat.startForeground(
            this,
            PROBE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, PROBE_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle("CarGPS · M3 验证探针")
                .setContentText("正在等待提交前进程回收测试")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (started.compareAndSet(false, true)) {
                serviceScope.launch { prepareBlockingBatch() }
            }
            ACTION_QUERY -> serviceScope.launch { queryPersistedBoundary(startId) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TripRecordingService.dependenciesFactoryForTests = null
        runtime?.close()
        queuedStorage = null
        runtime = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun prepareBlockingBatch() {
        val roomStorage = RoomTripStorage(this, DATABASE_NAME)
        val blockingStorage = BlockingBeforeAppendCommitStorage(roomStorage)
        val queued = QueuedTripStorage(blockingStorage)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val probeRuntime = DashboardRuntime(
            scope = runtimeScope,
            storage = queued,
            ioDispatcher = Dispatchers.IO,
        )
        runtime = probeRuntime
        queuedStorage = queued

        TripRecordingService.dependenciesFactoryForTests = {
            TripRecordingServiceDependencies(
                runtime = probeRuntime,
                startLocation = { true },
                stopLocation = {},
                closeLocation = {},
                readAccessState = { TripAccessState.Ready },
            )
        }

        while (!probeRuntime.state.value.storageReady) delay(25L)
        Log.i(PROBE_TAG, "$READY_SENTINEL pid=${android.os.Process.myPid()}")
        // 作者：long｜location FGS 必须由外层 shell 在系统豁免上下文中显式启动；
        // 探针自身从后台转启会被 Android 14+ while-in-use 规则拒绝。
        while (probeRuntime.state.value.tripMode != TripMode.RECORDING) delay(25L)

        val baseTimestamp = System.currentTimeMillis()
        repeat(POINT_BATCH_SIZE) { index ->
            probeRuntime.onLocation(
                android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                    time = baseTimestamp + index * 1_000L
                    latitude = 31.0 + index * 0.00001
                    longitude = 121.0
                    accuracy = 5f
                },
            )
        }
        check(blockingStorage.appendEntered.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            "16 点批次没有进入 Room 提交前阻塞点"
        }
        val sentinel = "$SENTINEL pending=$POINT_BATCH_SIZE pid=${android.os.Process.myPid()}"
        Log.i(PROBE_TAG, sentinel)
        // 作者：long｜阻塞点发生在真实 Room 事务之前；只有外层 SIGKILL 才会结束该窗口，不能自动释放提交。
        blockingStorage.holdForever.await()
        runtimeScope.cancel()
    }

    private fun queryPersistedBoundary(startId: Int) {
        val storage = RoomTripStorage(this, DATABASE_NAME)
        try {
            val activeTrip = storage.loadActiveTrip().activeTripOrNull()
            val checkpoint = storage.loadActiveTripCheckpoint()
            Log.i(
                PROBE_TAG,
                "$QUERY_SENTINEL active=${activeTrip != null} mode=${activeTrip?.mode?.name ?: "NONE"} " +
                    "points=${activeTrip?.points?.size ?: 0} confirmed=${checkpoint?.confirmedPointCount ?: 0L} " +
                    "last=${checkpoint?.lastConfirmedPointSequence ?: 0L}",
            )
        } finally {
            storage.close()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun createProbeNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                PROBE_NOTIFICATION_CHANNEL,
                "M3 本地验证",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private class BlockingBeforeAppendCommitStorage(
        private val delegate: TripStorage,
    ) : TripStorage {
        val appendEntered = CountDownLatch(1)
        val holdForever = CountDownLatch(1)

        override val errors = delegate.errors
        override val confirmedCheckpoints = delegate.confirmedCheckpoints
        override fun loadActiveTrip(): ActiveTripLoadResult = delegate.loadActiveTrip()
        override fun loadActiveTripCheckpoint(): ActiveTripCheckpoint? = delegate.loadActiveTripCheckpoint()
        override fun startTrip(startedAtMillis: Long) = delegate.startTrip(startedAtMillis)
        override fun appendPoint(point: TripPoint) = delegate.appendPoint(point)

        override fun appendPoints(points: List<TripPoint>) {
            check(points.size == POINT_BATCH_SIZE) {
                "探针预期一次提交 $POINT_BATCH_SIZE 点，实际 ${points.size} 点"
            }
            appendEntered.countDown()
            Log.i(PROBE_TAG, "$SENTINEL pending=${points.size}")
            // 作者：long｜委托 Room 前保持 16 点未确认，确保杀进程后数据库只代表最近确认边界。
            holdForever.await()
            delegate.appendPoints(points)
        }

        override fun updateActiveTrip(mode: TripMode, pausedAtMillis: Long?, totalPausedMillis: Long) =
            delegate.updateActiveTrip(mode, pausedAtMillis, totalPausedMillis)
        override fun completeTrip(record: CompletedTripRecord) = delegate.completeTrip(record)
        override fun recentTrips(limit: Int): List<CompletedTripRecord> = delegate.recentTrips(limit)
        override fun completedTripPoints(tripId: Long): List<TripPoint> = delegate.completedTripPoints(tripId)
        override fun awaitPendingWrites() = delegate.awaitPendingWrites()
        override fun close() = delegate.close()
    }

    companion object {
        const val ACTION_START = "com.cargps.mobile.action.M3_CHECKPOINT_PROBE"
        const val ACTION_QUERY = "com.cargps.mobile.action.M3_CHECKPOINT_QUERY"
        private const val DATABASE_NAME = "cargps-trips.db"
        private const val POINT_BATCH_SIZE = 16
        private const val PROBE_TAG = "CarGpsM3Probe"
        private const val READY_SENTINEL = "CARGPS_M3_PROBE_READY"
        private const val SENTINEL = "CARGPS_M3_CHECKPOINT_BLOCKED"
        private const val QUERY_SENTINEL = "CARGPS_M3_QUERY_RESULT"
        private const val PROBE_NOTIFICATION_CHANNEL = "m3_probe"
        private const val PROBE_NOTIFICATION_ID = 27_121
    }
}

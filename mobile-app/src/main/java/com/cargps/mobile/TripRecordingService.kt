package com.cargps.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cargps.DashboardRuntime
import com.cargps.DashboardState
import com.cargps.LocationEngine
import com.cargps.TripMode
import com.cargps.domain.NmeaFrame
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TripRecordingService : Service(), LocationEngine.Listener {
    inner class LocalBinder : Binder() {
        val state: StateFlow<DashboardState>
            get() = runtime.state

        fun setClientVisible(visible: Boolean) {
            serviceScope.launch { updateClientVisibility(visible) }
        }

        fun refreshLocationAccess() {
            serviceScope.launch { refreshLocationEngine() }
        }

        fun onPermissionRequired() = runtime.onPermissionRequired()

        fun onForegroundServiceError(message: String?) = runtime.onForegroundServiceError(message)

        fun toggleTrip(nowMillis: Long) = runtime.toggleTrip(nowMillis)

        fun endTrip(nowMillis: Long) = runtime.endTrip(nowMillis)

        fun toggleTheme() = runtime.toggleTheme()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private lateinit var runtime: DashboardRuntime
    private lateinit var locationEngine: LocationEngine
    private lateinit var notificationManager: NotificationManager
    private var stateJob: Job? = null
    private var tickerJob: Job? = null
    private var recoveryJob: Job? = null
    private var clientVisible = false
    private var foreground = false
    private var startRequested = false
    private var recoveringStartedService = false
    private var startCommandReceived = false
    private var lastNotificationUpdateAtMillis = 0L
    private var lastNotificationMode: TripMode? = null
    private var lastNotificationDistanceBucket = -1L

    override fun onCreate() {
        super.onCreate()
        runtime = (application as CarGpsApplication).dashboardRuntime()
        locationEngine = LocationEngine(this, this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        stateJob = serviceScope.launch {
            runtime.state.collect(::handleRuntimeState)
        }
        tickerJob = serviceScope.launch {
            while (true) {
                runtime.onTick(System.currentTimeMillis())
                delay(TICK_INTERVAL_MILLIS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startCommandReceived = true
        when (intent?.action) {
            ACTION_START_TRIP -> requestStartTrip()
            ACTION_END_TRIP -> {
                // 作者：long｜通知结束操作可能重建 Service，先升为前台再等待结束事务确认，避免后台启动窗口内被系统终止。
                if (promoteToForeground(runtime.state.value)) {
                    runtime.endTrip(System.currentTimeMillis())
                }
            }
            null -> restoreStartedServiceIfNeeded()
            else -> stopSelfResult(startId)
        }
        return if (runtime.state.value.tripMode != TripMode.IDLE || startRequested || recoveringStartedService) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        updateClientVisibility(false)
        return false
    }

    override fun onDestroy() {
        stateJob?.cancel()
        tickerJob?.cancel()
        recoveryJob?.cancel()
        locationEngine.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 作者：long｜任务移除后尽力请求尾批次检查点；该回调不能阻塞主线程，也不能承诺系统回收前一定完成。
        runtime.checkpointTripWrites()
        super.onTaskRemoved(rootIntent)
    }

    override fun onPermissionRequired() = runtime.onPermissionRequired()

    override fun onProviderDisabled() = runtime.onProviderDisabled()

    override fun onSearching() = runtime.onSearching()

    override fun onLocation(location: Location) = runtime.onLocation(location)

    override fun onSatellitesChanged(inView: Int, usedInFix: Int) =
        runtime.onSatellitesChanged(inView, usedInFix)

    override fun onNmea(frame: NmeaFrame) = runtime.onNmea(frame)

    private fun requestStartTrip() {
        if (!hasFineLocationPermission()) {
            runtime.onPermissionRequired()
            runtime.onForegroundServiceError("允许精确定位后才能开始后台行程")
            stopSelf()
            return
        }
        startRequested = true
        runtime.onForegroundServiceError(null)
        if (!promoteToForeground(runtime.state.value)) {
            startRequested = false
            return
        }
        locationEngine.start()
        runtime.startTrip(System.currentTimeMillis())
    }

    private fun restoreStartedServiceIfNeeded() {
        recoveryJob?.cancel()
        recoveringStartedService = true
        if (!hasFineLocationPermission()) {
            recoveringStartedService = false
            runtime.onPermissionRequired()
            runtime.onForegroundServiceError("精确定位权限已失效，无法恢复后台行程")
            stopSelf()
            return
        }
        if (!promoteToForeground(runtime.state.value)) {
            recoveringStartedService = false
            return
        }
        recoveryJob = serviceScope.launch {
            // 作者：long｜START_STICKY 新进程先显示恢复通知并等待 SQLite 结果，不能把 Runtime 初始 IDLE 误判为没有活动行程。
            val restoredState = runtime.awaitInitialRestore()
            recoveringStartedService = false
            when (decideStartedServiceRecovery(restoredState)) {
                StartedServiceRecoveryAction.RESUME_ACTIVE_TRIP -> {
                    if (hasFineLocationPermission() && promoteToForeground(restoredState)) {
                        locationEngine.start()
                    }
                }
                StartedServiceRecoveryAction.STOP_NO_ACTIVE_TRIP,
                StartedServiceRecoveryAction.STOP_RESTORE_FAILED
                -> handleRuntimeState(restoredState)
                StartedServiceRecoveryAction.WAIT_FOR_STORAGE -> Unit
            }
        }
    }

    private fun updateClientVisibility(visible: Boolean) {
        clientVisible = visible
        refreshLocationEngine()
        handleRuntimeState(runtime.state.value)
    }

    private fun refreshLocationEngine() {
        val activeTrip = runtime.state.value.tripMode != TripMode.IDLE
        if ((clientVisible || activeTrip || startRequested) && hasFineLocationPermission()) {
            runtime.onForegroundServiceError(null)
            locationEngine.start()
        } else {
            locationEngine.stop()
            if (!hasFineLocationPermission()) runtime.onPermissionRequired()
        }
    }

    private fun handleRuntimeState(state: DashboardState) {
        val activeTrip = state.tripMode != TripMode.IDLE
        if (activeTrip) startRequested = false
        if (state.tripMode == TripMode.IDLE && !state.tripCommandInProgress && state.storageError != null) {
            startRequested = false
        }

        val keepForeground = recoveringStartedService || activeTrip || startRequested ||
            (foreground && state.tripCommandInProgress)
        if (keepForeground && hasFineLocationPermission()) {
            if (promoteToForeground(state)) locationEngine.start()
        } else if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
            lastNotificationMode = null
            lastNotificationDistanceBucket = -1L
        }

        if (startCommandReceived && !recoveringStartedService && !activeTrip && !startRequested &&
            !state.tripCommandInProgress && !clientVisible
        ) {
            locationEngine.stop()
            stopSelf()
        } else if (foreground) {
            updateNotificationIfNeeded(state)
        }
    }

    private fun promoteToForeground(state: DashboardState): Boolean {
        if (foreground) return true
        return try {
            val serviceType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(state),
                serviceType,
            )
            foreground = true
            rememberNotificationState(state)
            true
        } catch (error: RuntimeException) {
            runtime.onForegroundServiceError(error.message ?: "定位前台服务启动失败")
            stopSelf()
            false
        }
    }

    private fun updateNotificationIfNeeded(state: DashboardState) {
        val nowMillis = SystemClock.elapsedRealtime()
        val distanceBucket = (state.tripStats.distanceMeters / NOTIFICATION_DISTANCE_STEP_METERS).toLong()
        val stateChanged = state.tripMode != lastNotificationMode ||
            distanceBucket != lastNotificationDistanceBucket
        if (!stateChanged && nowMillis - lastNotificationUpdateAtMillis < NOTIFICATION_UPDATE_INTERVAL_MILLIS) return

        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
        rememberNotificationState(state, nowMillis)
    }

    private fun rememberNotificationState(
        state: DashboardState,
        nowMillis: Long = SystemClock.elapsedRealtime(),
    ) {
        // 作者：long｜通知只在模式、每 10 米里程或 5 秒时间窗口变化时刷新，避免每秒重建 SystemUI 视图和 PendingIntent。
        lastNotificationUpdateAtMillis = nowMillis
        lastNotificationMode = state.tripMode
        lastNotificationDistanceBucket =
            (state.tripStats.distanceMeters / NOTIFICATION_DISTANCE_STEP_METERS).toLong()
    }

    private fun buildNotification(state: DashboardState): Notification {
        val openAppPendingIntent = openAppPendingIntent(this)
        val endTripPendingIntent = endTripPendingIntent(this)
        val statusText = when (state.tripMode) {
            TripMode.IDLE -> "正在确认行程存储"
            TripMode.RECORDING -> "正在记录"
            TripMode.PAUSED -> "行程已暂停"
        }
        val distanceText = String.format(Locale.CHINA, "%.2f km", state.tripStats.distanceMeters / 1_000.0)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("CarGPS · $statusText")
            .setContentText("$distanceText · ${DashboardRuntime.formatDuration(state.tripStats.elapsedMillis)}")
            .setContentIntent(openAppPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_location, "结束行程", endTripPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "行程记录",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示正在进行的 GPS 行程并提供停止操作"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        internal const val ACTION_START_TRIP = "com.cargps.mobile.action.START_TRIP"
        internal const val ACTION_END_TRIP = "com.cargps.mobile.action.END_TRIP"
        internal const val NOTIFICATION_CHANNEL_ID = "trip_recording"
        internal const val NOTIFICATION_ID = 2_710
        private const val REQUEST_OPEN_APP = 2_711
        private const val REQUEST_END_TRIP = 2_712
        private const val TICK_INTERVAL_MILLIS = 1_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 5_000L
        private const val NOTIFICATION_DISTANCE_STEP_METERS = 10.0

        fun bindIntent(context: Context): Intent = Intent(context, TripRecordingService::class.java)

        fun startTripIntent(context: Context): Intent =
            Intent(context, TripRecordingService::class.java).setAction(ACTION_START_TRIP)

        internal fun endTripIntent(context: Context): Intent =
            Intent(context, TripRecordingService::class.java).setAction(ACTION_END_TRIP)

        internal fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun endTripPendingIntent(context: Context): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                REQUEST_END_TRIP,
                endTripIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

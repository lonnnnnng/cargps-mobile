package com.cargps.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cargps.DashboardState
import com.cargps.TripMode
import com.cargps.mobile.ui.MobileDashboardScreen
import com.cargps.mobile.ui.MobileGpsTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val dashboardState = MutableStateFlow(DashboardState())
    private val tripAccessState = MutableStateFlow<TripAccessState>(
        TripAccessState.Blocked(
            blocker = TripAccessBlocker.LOCATION_PERMISSION_REQUIRED,
            resolution = TripAccessResolution.REQUEST_LOCATION_PERMISSION,
        ),
    )
    private var serviceBinder: TripRecordingService.LocalBinder? = null
    private var serviceStateJob: Job? = null
    private var serviceBound = false
    private var activityStarted = false
    private var startTripAfterPermission = false
    private var pendingPermissionResolution: TripAccessResolution? = null
    private var activeTripServiceEnsured = false

    private val providerChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshTripAccess()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as TripRecordingService.LocalBinder
            serviceBinder = binder
            serviceBound = true
            dashboardState.value = binder.state.value
            if (activityStarted) binder.setClientVisible(true)
            syncTripAccessWithService()
            serviceStateJob?.cancel()
            serviceStateJob = lifecycleScope.launch {
                binder.state.collect { state ->
                    dashboardState.value = state
                    ensureStartedServiceForActiveTrip(state)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceStateJob?.cancel()
            serviceStateJob = null
            serviceBinder = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val completedResolution = pendingPermissionResolution
        pendingPermissionResolution = null
        val shouldStartTrip = startTripAfterPermission
        startTripAfterPermission = false
        val access = refreshTripAccess()

        when {
            shouldStartTrip && access.isReady -> startTripFromVisibleActivity()
            shouldStartTrip && completedResolution == TripAccessResolution.REQUEST_LOCATION_PERMISSION &&
                access.blockedOrNull?.resolution == TripAccessResolution.REQUEST_NOTIFICATION_PERMISSION ->
                resolveTripAccess(access, startAfterResolution = true)
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        startTripAfterPermission = false
        refreshTripAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshTripAccess()
        setContent {
            val state = dashboardState.collectAsStateWithLifecycle().value
            val access = tripAccessState.collectAsStateWithLifecycle().value
            MobileGpsTheme(darkTheme = state.darkTheme) {
                MobileDashboardScreen(
                    state = state,
                    tripAccessState = access,
                    onResolveTripAccess = {
                        resolveTripAccess(access, startAfterResolution = state.tripMode == TripMode.IDLE)
                    },
                    onToggleTrip = {
                        if (state.tripMode == TripMode.IDLE) {
                            startTripFromVisibleActivity()
                        } else {
                            serviceBinder?.toggleTrip(System.currentTimeMillis())
                        }
                    },
                    onEndTrip = { serviceBinder?.endTrip(System.currentTimeMillis()) },
                    onToggleTheme = { serviceBinder?.toggleTheme() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        refreshTripAccess()
        ContextCompat.registerReceiver(
            this,
            providerChangedReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (!serviceBound) {
            val bound = bindService(
                TripRecordingService.bindIntent(this),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) dashboardState.value = dashboardState.value.copy(
                foregroundServiceError = "定位服务连接失败",
            )
        } else {
            serviceBinder?.setClientVisible(true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTripAccess()
    }

    override fun onStop() {
        activityStarted = false
        unregisterReceiver(providerChangedReceiver)
        serviceBinder?.setClientVisible(false)
        serviceStateJob?.cancel()
        serviceStateJob = null
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            serviceBinder = null
        }
        super.onStop()
    }

    private fun startTripFromVisibleActivity() {
        val access = refreshTripAccess()
        if (!access.isReady) {
            resolveTripAccess(access, startAfterResolution = true)
            return
        }
        startTripAfterPermission = false
        activeTripServiceEnsured = true
        serviceBinder?.onForegroundServiceError(null)
        // 作者：long｜Android 12+ 只允许在可见 Activity 的明确用户操作中启动定位前台服务，禁止从后台恢复路径偷启。
        ContextCompat.startForegroundService(this, TripRecordingService.startTripIntent(this))
    }

    private fun resolveTripAccess(access: TripAccessState, startAfterResolution: Boolean) {
        val blocked = access.blockedOrNull ?: run {
            if (startAfterResolution) startTripFromVisibleActivity()
            return
        }
        startTripAfterPermission = startAfterResolution
        when (blocked.resolution) {
            TripAccessResolution.REQUEST_LOCATION_PERMISSION -> {
                markLocationPermissionRequested()
                if (blocked.blocker == TripAccessBlocker.PRECISE_LOCATION_REQUIRED) {
                    markPreciseLocationUpgradeRequested()
                }
                pendingPermissionResolution = blocked.resolution
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
            TripAccessResolution.REQUEST_NOTIFICATION_PERMISSION -> {
                markNotificationPermissionRequested()
                pendingPermissionResolution = blocked.resolution
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            TripAccessResolution.OPEN_APP_SETTINGS -> openSettings(appSettingsIntent(this))
            TripAccessResolution.OPEN_LOCATION_SETTINGS -> openSettings(locationSettingsIntent())
            TripAccessResolution.OPEN_NOTIFICATION_SETTINGS -> openSettings(notificationSettingsIntent(this))
        }
    }

    private fun openSettings(intent: Intent) {
        // 作者：long｜设置页不会承诺用户一定修改成功，返回后只刷新状态，不自动开始行程。
        startTripAfterPermission = false
        pendingPermissionResolution = null
        settingsLauncher.launch(intent)
    }

    private fun refreshTripAccess(): TripAccessState {
        val access = evaluateTripAccess(readTripAccessSnapshot(this))
        tripAccessState.value = access
        if (!access.isReady) activeTripServiceEnsured = false
        syncTripAccessWithService(access)
        ensureStartedServiceForActiveTrip(dashboardState.value)
        return access
    }

    private fun syncTripAccessWithService(access: TripAccessState = tripAccessState.value) {
        if (access.blocksLocation) serviceBinder?.onPermissionRequired()
        serviceBinder?.onForegroundServiceError(access.serviceErrorMessage())
        serviceBinder?.refreshLocationAccess()
    }

    private fun ensureStartedServiceForActiveTrip(state: DashboardState) {
        if (state.tripMode == TripMode.IDLE) {
            activeTripServiceEnsured = false
            return
        }
        if (!activityStarted || !tripAccessState.value.isReady || activeTripServiceEnsured) return

        activeTripServiceEnsured = true
        runCatching {
            ContextCompat.startForegroundService(this, TripRecordingService.ensureActiveTripIntent(this))
        }.onFailure { error ->
            activeTripServiceEnsured = false
            serviceBinder?.onForegroundServiceError(error.message ?: "定位前台服务恢复失败")
        }
    }
}

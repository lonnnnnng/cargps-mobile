package com.cargps.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
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
    private var serviceBinder: TripRecordingService.LocalBinder? = null
    private var serviceStateJob: Job? = null
    private var serviceBound = false
    private var activityStarted = false
    private var startTripAfterPermission = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as TripRecordingService.LocalBinder
            serviceBinder = binder
            serviceBound = true
            dashboardState.value = binder.state.value
            if (activityStarted) binder.setClientVisible(true)
            serviceStateJob?.cancel()
            serviceStateJob = lifecycleScope.launch {
                binder.state.collect { state -> dashboardState.value = state }
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
        val fineLocationGranted = hasFineLocationPermission()
        val notificationGranted = hasNotificationPermission()
        if (fineLocationGranted) {
            serviceBinder?.refreshLocationAccess()
        } else {
            serviceBinder?.onPermissionRequired()
        }
        serviceBinder?.onForegroundServiceError(
            if (notificationGranted) null else "允许通知后才能显示后台行程状态",
        )
        if (startTripAfterPermission && fineLocationGranted && notificationGranted) {
            startTripAfterPermission = false
            startTripFromVisibleActivity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = dashboardState.collectAsStateWithLifecycle().value
            MobileGpsTheme(darkTheme = state.darkTheme) {
                MobileDashboardScreen(
                    state = state,
                    onRequestPermission = { requestRequiredPermissions(startAfterGrant = true) },
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

    override fun onStop() {
        activityStarted = false
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
        if (!hasFineLocationPermission() || !hasNotificationPermission()) {
            requestRequiredPermissions(startAfterGrant = true)
            return
        }
        startTripAfterPermission = false
        serviceBinder?.onForegroundServiceError(null)
        // 作者：long｜Android 12+ 只允许在可见 Activity 的明确用户操作中启动定位前台服务，禁止从后台恢复路径偷启。
        ContextCompat.startForegroundService(this, TripRecordingService.startTripIntent(this))
    }

    private fun requestRequiredPermissions(startAfterGrant: Boolean) {
        startTripAfterPermission = startAfterGrant
        val permissions = buildList {
            if (!hasFineLocationPermission()) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isEmpty()) {
            if (startAfterGrant) startTripFromVisibleActivity()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

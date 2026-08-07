package com.cargps.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

internal fun Context.readTripAccessSnapshot(activity: Activity? = null): TripAccessSnapshot {
    val preferences = getSharedPreferences(ACCESS_PREFERENCES, Context.MODE_PRIVATE)
    val notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    return TripAccessSnapshot(
        preciseLocationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        approximateLocationGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        locationPermissionRequestedBefore = preferences.getBoolean(KEY_LOCATION_REQUESTED, false),
        preciseLocationUpgradeRequestedBefore = preferences.getBoolean(KEY_PRECISE_UPGRADE_REQUESTED, false),
        canRequestPreciseLocationAgain = activity?.let { currentActivity ->
            ActivityCompat.shouldShowRequestPermissionRationale(
                currentActivity,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } ?: false,
        systemLocationEnabled = runCatching {
            getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false),
        notificationPermissionRequired = notificationPermissionRequired,
        notificationPermissionGranted = !notificationPermissionRequired ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS),
        notificationPermissionRequestedBefore = preferences.getBoolean(KEY_NOTIFICATION_REQUESTED, false),
        canRequestNotificationAgain = notificationPermissionRequired && activity?.let { currentActivity ->
            ActivityCompat.shouldShowRequestPermissionRationale(
                currentActivity,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } == true,
    )
}

internal fun Context.markLocationPermissionRequested() {
    // 作者：long｜系统在首次请求前和永久拒绝后都会返回“不显示说明”，必须持久化请求历史才能避免循环弹窗。
    getSharedPreferences(ACCESS_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_LOCATION_REQUESTED, true)
        .apply()
}

internal fun Context.markPreciseLocationUpgradeRequested() {
    // 作者：long｜近似授权后的首次精度升级由应用内请求完成；只有升级已尝试且系统不再允许请求时才引导设置。
    getSharedPreferences(ACCESS_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_PRECISE_UPGRADE_REQUESTED, true)
        .apply()
}

internal fun Context.markNotificationPermissionRequested() {
    getSharedPreferences(ACCESS_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_NOTIFICATION_REQUESTED, true)
        .apply()
}

internal fun appSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)

internal fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

internal fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private const val ACCESS_PREFERENCES = "trip_access"
private const val KEY_LOCATION_REQUESTED = "location_requested"
private const val KEY_PRECISE_UPGRADE_REQUESTED = "precise_upgrade_requested"
private const val KEY_NOTIFICATION_REQUESTED = "notification_requested"

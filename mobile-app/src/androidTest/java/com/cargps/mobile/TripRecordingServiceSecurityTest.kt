package com.cargps.mobile

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripRecordingServiceSecurityTest {
    private val context = ApplicationProvider.getApplicationContext<CarGpsApplication>()

    @Test
    fun serviceIsPrivateAndDeclaresLocationForegroundType() {
        val component = ComponentName(context, TripRecordingService::class.java)
        val serviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(component, 0)
        }

        assertFalse(serviceInfo.exported)
        assertTrue(serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0)

        val requestedPermissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requestedPermissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_LOCATION in requestedPermissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in requestedPermissions)
    }

    @Test
    fun notificationPendingIntentsAreExplicitAndImmutable() {
        val startIntent = TripRecordingService.startTripIntent(context)
        val ensureIntent = TripRecordingService.ensureActiveTripIntent(context)
        val endIntent = TripRecordingService.endTripIntent(context)

        assertEquals(ComponentName(context, TripRecordingService::class.java), startIntent.component)
        assertEquals(TripRecordingService.ACTION_START_TRIP, startIntent.action)
        assertEquals(ComponentName(context, TripRecordingService::class.java), ensureIntent.component)
        assertEquals(TripRecordingService.ACTION_ENSURE_ACTIVE_TRIP, ensureIntent.action)
        assertEquals(ComponentName(context, TripRecordingService::class.java), endIntent.component)
        assertEquals(TripRecordingService.ACTION_END_TRIP, endIntent.action)

        val openApp = TripRecordingService.openAppPendingIntent(context)
        val endTrip = TripRecordingService.endTripPendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(openApp.isImmutable)
            assertTrue(endTrip.isImmutable)
        }
        assertEquals(context.packageName, openApp.creatorPackage)
        assertEquals(context.packageName, endTrip.creatorPackage)

        openApp.cancel()
        endTrip.cancel()
    }
}

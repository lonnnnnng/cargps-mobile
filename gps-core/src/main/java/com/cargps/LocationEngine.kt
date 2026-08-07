package com.cargps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import com.cargps.domain.NmeaFrame
import com.cargps.domain.NmeaParser
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class LocationEngine(
    context: Context,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onPermissionRequired()
        fun onProviderDisabled()
        fun onSearching()
        fun onLocation(location: Location)
        fun onSatellitesChanged(inView: Int, usedInFix: Int)
        fun onNmea(frame: NmeaFrame)
    }

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackThread = HandlerThread(WORKER_THREAD_NAME).apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val generation = AtomicInteger(0)
    @Volatile private var started = false
    @Volatile private var closed = false
    private var pendingNmeaFrame: NmeaFrame? = null
    private var nmeaDispatchScheduled = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val snapshot = Location(location)
            dispatchToMain { listener.onLocation(snapshot) }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) dispatchToMain(listener::onProviderDisabled)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) dispatchToMain(listener::onSearching)
        }

        @Deprecated("Android 8.1 仍可能回调该方法，保留空实现以兼容 API 27")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        NmeaParser.parse(message)?.let { frame ->
            pendingNmeaFrame = mergeNmeaFrames(pendingNmeaFrame, frame)
            if (!nmeaDispatchScheduled) {
                nmeaDispatchScheduled = true
                callbackHandler.postDelayed(nmeaDispatchRunnable, NMEA_UI_INTERVAL_MILLIS)
            }
        }
    }

    private val nmeaDispatchRunnable = Runnable {
        nmeaDispatchScheduled = false
        val frame = pendingNmeaFrame ?: return@Runnable
        pendingNmeaFrame = null
        dispatchToMain { listener.onNmea(frame) }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var usedInFix = 0
            repeat(status.satelliteCount) { index ->
                if (status.usedInFix(index)) usedInFix += 1
            }
            dispatchToMain { listener.onSatellitesChanged(status.satelliteCount, usedInFix) }
        }
    }

    @Synchronized
    fun start() {
        check(!closed) { "定位引擎已关闭" }
        if (started) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onPermissionRequired()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            listener.onProviderDisabled()
            return
        }

        generation.incrementAndGet()
        started = true
        try {
            // 作者：long｜API 27 使用 Looper/Handler 重载，把 NMEA 校验和卫星遍历放到专用线程，主线程只接收聚合结果。
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                locationListener,
                callbackThread.looper,
            )
            locationManager.addNmeaListener(nmeaListener, callbackHandler)
            locationManager.registerGnssStatusCallback(gnssCallback, callbackHandler)
            listener.onSearching()
        } catch (_: SecurityException) {
            rollbackFailedStart()
            listener.onPermissionRequired()
        } catch (error: RuntimeException) {
            rollbackFailedStart()
            throw error
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        generation.incrementAndGet()
        unregisterCallbacks()
        callbackHandler.removeCallbacks(nmeaDispatchRunnable)
        pendingNmeaFrame = null
        nmeaDispatchScheduled = false
    }

    @Synchronized
    override fun close() {
        if (closed) return
        stop()
        closed = true
        callbackThread.quitSafely()
    }

    private fun rollbackFailedStart() {
        started = false
        generation.incrementAndGet()
        unregisterCallbacks()
        callbackHandler.removeCallbacks(nmeaDispatchRunnable)
        pendingNmeaFrame = null
        nmeaDispatchScheduled = false
    }

    private fun unregisterCallbacks() {
        // 作者：long｜启动可能只完成部分系统注册；统一按幂等方式回收，避免权限变化或框架异常留下后台回调。
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { locationManager.removeNmeaListener(nmeaListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
    }

    private fun dispatchToMain(block: () -> Unit) {
        val expectedGeneration = generation.get()
        mainHandler.post {
            if (started && generation.get() == expectedGeneration) block()
        }
    }

    companion object {
        private const val WORKER_THREAD_NAME = "cargps-location-callbacks"
        private const val NMEA_UI_INTERVAL_MILLIS = 500L
    }
}

internal fun mergeNmeaFrames(previous: NmeaFrame?, current: NmeaFrame): NmeaFrame = NmeaFrame(
    type = current.type,
    latitude = current.latitude ?: previous?.latitude,
    longitude = current.longitude ?: previous?.longitude,
    fixQuality = current.fixQuality ?: previous?.fixQuality,
    validFix = current.validFix ?: previous?.validFix,
    satellitesUsed = current.satellitesUsed ?: previous?.satellitesUsed,
    satellitesInView = current.satellitesInView ?: previous?.satellitesInView,
    hdop = current.hdop ?: previous?.hdop,
    vdop = current.vdop ?: previous?.vdop,
    pdop = current.pdop ?: previous?.pdop,
    altitudeMeters = current.altitudeMeters ?: previous?.altitudeMeters,
    speedMps = current.speedMps ?: previous?.speedMps,
    bearingDegrees = current.bearingDegrees ?: previous?.bearingDegrees,
)

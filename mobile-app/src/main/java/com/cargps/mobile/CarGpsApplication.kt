package com.cargps.mobile

import android.app.Application
import android.util.Log
import com.cargps.DashboardRuntime
import com.cargps.storage.QueuedTripStorage
import com.cargps.storage.RoomTripStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CarGpsApplication : Application() {
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var runtime: DashboardRuntime? = null

    @Synchronized
    fun dashboardRuntime(): DashboardRuntime = runtime ?: DashboardRuntime(
        scope = runtimeScope,
        storage = QueuedTripStorage(
            delegate = RoomTripStorage(applicationContext),
            onWriteFailure = { error ->
                Log.e(STORAGE_LOG_TAG, "行程持久化写入失败", error)
            },
        ),
    ).also { created ->
        // 作者：long｜Activity 重建和 Service 重连必须复用同一协调器，避免出现两套活动行程和两条 SQLite 写队列。
        runtime = created
    }

    override fun onTerminate() {
        runtime?.close()
        runtimeScope.cancel()
        super.onTerminate()
    }

    companion object {
        private const val STORAGE_LOG_TAG = "CarGpsTripStorage"
    }
}

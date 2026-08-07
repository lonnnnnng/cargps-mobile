package com.cargps

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cargps.storage.QueuedTripStorage
import com.cargps.storage.SqliteTripStorage

class DashboardViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
            "不支持的 ViewModel 类型：${modelClass.name}"
        }
        return DashboardViewModel(
            storage = QueuedTripStorage(
                delegate = SqliteTripStorage(appContext),
                onWriteFailure = { error ->
                    Log.e(STORAGE_LOG_TAG, "行程持久化写入失败", error)
                },
            ),
        ) as T
    }

    companion object {
        private const val STORAGE_LOG_TAG = "CarGpsTripStorage"
    }
}

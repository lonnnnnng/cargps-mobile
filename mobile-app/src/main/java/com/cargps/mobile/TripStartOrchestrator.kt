package com.cargps.mobile

import com.cargps.DashboardState
import com.cargps.TripMode

/**
 * 作者：long
 *
 * 让显式传入的启动回调等待活动行程持久化确认；Service 已存在的定位预览与可见性刷新需要在外围单独约束。
 */
internal suspend fun completeTripStart(
    requestedAtMillis: Long,
    awaitStart: suspend (Long) -> DashboardState,
    currentAccess: () -> TripAccessState,
    onRequestFinished: () -> Unit,
    startLocation: () -> Unit,
    handleState: (DashboardState) -> Unit,
) {
    val startedState = try {
        awaitStart(requestedAtMillis)
    } finally {
        // 作者：long｜即使存储确认被取消或异常终止，也要清除请求中标记，让 Service 能退出恢复通知状态。
        onRequestFinished()
    }
    if (startedState.tripMode != TripMode.IDLE && currentAccess().isReady) {
        startLocation()
    }
    handleState(startedState)
}

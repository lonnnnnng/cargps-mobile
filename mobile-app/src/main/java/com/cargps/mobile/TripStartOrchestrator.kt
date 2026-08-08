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
    } catch (error: Throwable) {
        // 作者：long｜存储确认异常时仍需清除请求中标记，否则 Service 会永久停留在恢复通知状态。
        onRequestFinished()
        throw error
    }
    try {
        if (startedState.tripMode != TripMode.IDLE && currentAccess().isReady) {
            startLocation()
        }
        // 作者：long｜先发布最终状态再清除请求标记，避免状态收集器在 IDLE 快照下误回收仍在确认的 Service。
        handleState(startedState)
    } finally {
        onRequestFinished()
    }
}

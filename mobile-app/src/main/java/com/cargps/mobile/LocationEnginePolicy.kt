package com.cargps.mobile

internal enum class LocationSessionPhase {
    IDLE,
    START_PENDING,
    ACTIVE,
}

internal enum class LocationEngineAction {
    START,
    STOP,
}

/**
 * 作者：long
 *
 * 把可见定位预览与活动行程记录合并为唯一引擎决策，避免 Service 的不同生命周期入口各自启动或停止定位。
 */
internal fun decideLocationEngineAction(
    clientVisible: Boolean,
    sessionPhase: LocationSessionPhase,
    access: TripAccessState,
    storageBackpressure: Boolean = false,
): LocationEngineAction {
    // 作者：long｜未确认尾批达到内存上限时停止定位，保留活动行程和结束入口，等磁盘恢复后再继续，避免继续制造不可恢复点。
    if (storageBackpressure) return LocationEngineAction.STOP
    val canPreviewLocation = clientVisible && !access.blocksLocation
    // 作者：long｜等待 Start 落库时只保留前台确认通知；客户端不可见时必须等活动行程成立后才进入后台定位。
    val canRecordTrip = sessionPhase == LocationSessionPhase.ACTIVE && access.isReady
    return if (canPreviewLocation || canRecordTrip) {
        LocationEngineAction.START
    } else {
        LocationEngineAction.STOP
    }
}

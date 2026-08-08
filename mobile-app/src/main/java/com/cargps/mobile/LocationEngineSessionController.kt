package com.cargps.mobile

/**
 * 作者：long
 *
 * Service 内所有定位生命周期入口共用的最小控制器。它只记住已经执行的动作，
 * 这样 Activity 重绑、START_STICKY 恢复和存储错误回调同时到达时，不会让不同入口
 * 交替注册/注销定位监听；真正的 Android LocationEngine 仍由调用方注入。
 */
internal class LocationEngineSessionController(
    private val startLocation: () -> Boolean,
    private val stopLocation: () -> Unit,
) {
    private var appliedAction: LocationEngineAction? = null

    fun reconcile(
        clientVisible: Boolean,
        sessionPhase: LocationSessionPhase,
        access: TripAccessState,
        storageBackpressure: Boolean,
        storageFailure: Boolean,
    ): LocationEngineAction {
        val desiredAction = decideLocationEngineAction(
            clientVisible = clientVisible,
            sessionPhase = sessionPhase,
            access = access,
            storageBackpressure = storageBackpressure,
            storageFailure = storageFailure,
        )
        if (desiredAction == appliedAction) return desiredAction

        // 作者：long｜只有决策真正变化时才触碰系统定位注册；调用失败不更新缓存，下一次生命周期事件仍可重试。
        when (desiredAction) {
            LocationEngineAction.START -> {
                if (!startLocation()) return desiredAction
            }
            LocationEngineAction.STOP -> stopLocation()
        }
        appliedAction = desiredAction
        return desiredAction
    }

    fun reset() {
        // 作者：long｜Service 销毁后 Android 引擎已被关闭，清除动作缓存，下一次新 Service 必须重新执行首个决策。
        appliedAction = null
    }
}

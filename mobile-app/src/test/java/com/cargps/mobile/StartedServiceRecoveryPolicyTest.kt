package com.cargps.mobile

import com.cargps.DashboardState
import com.cargps.TripMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StartedServiceRecoveryPolicyTest {
    @Test
    fun `存储恢复完成前保持前台等待而不是按初始空闲状态停止`() {
        assertEquals(
            StartedServiceRecoveryAction.WAIT_FOR_STORAGE,
            decideStartedServiceRecovery(DashboardState()),
        )
    }

    @Test
    fun `恢复出活动行程后重新启动定位`() {
        assertEquals(
            StartedServiceRecoveryAction.RESUME_ACTIVE_TRIP,
            decideStartedServiceRecovery(
                DashboardState(storageReady = true, tripMode = TripMode.RECORDING),
            ),
        )
    }

    @Test
    fun `确认没有活动行程后停止启动型服务`() {
        assertEquals(
            StartedServiceRecoveryAction.STOP_NO_ACTIVE_TRIP,
            decideStartedServiceRecovery(DashboardState(storageReady = true)),
        )
    }

    @Test
    fun `存储恢复失败后停止服务并保留运行时错误`() {
        assertEquals(
            StartedServiceRecoveryAction.STOP_RESTORE_FAILED,
            decideStartedServiceRecovery(DashboardState(storageError = "database unavailable")),
        )
    }

    @Test
    fun `事件 actor 恢复失败时不继续等待或误启定位`() {
        assertEquals(
            StartedServiceRecoveryAction.STOP_RESTORE_FAILED,
            decideStartedServiceRecovery(DashboardState(tripRuntimeError = "行程事件处理异常")),
        )
    }

    @Test
    fun `事件 actor 自动重建期间继续保持恢复前台状态`() {
        assertEquals(
            StartedServiceRecoveryAction.WAIT_FOR_STORAGE,
            decideStartedServiceRecovery(
                DashboardState(
                    tripRuntimeError = "行程事件处理异常",
                    tripRuntimeRecovering = true,
                ),
            ),
        )
    }
}

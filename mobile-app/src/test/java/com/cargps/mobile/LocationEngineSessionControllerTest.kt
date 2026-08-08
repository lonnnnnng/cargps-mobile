package com.cargps.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationEngineSessionControllerTest {
    @Test
    fun `开始等待且页面离开时不会提前启动定位`() {
        val events = mutableListOf<String>()
        val controller = LocationEngineSessionController(
            startLocation = { events += "start"; true },
            stopLocation = { events += "stop" },
        )

        assertEquals(
            LocationEngineAction.STOP,
            controller.reconcile(
                clientVisible = false,
                sessionPhase = LocationSessionPhase.START_PENDING,
                access = TripAccessState.Ready,
                storageBackpressure = false,
                storageFailure = false,
            ),
        )
        assertEquals(listOf("stop"), events)
    }

    @Test
    fun `Activity 重绑和恢复重复到达时只执行一次启动`() {
        var starts = 0
        var stops = 0
        val controller = LocationEngineSessionController(
            startLocation = { starts += 1; true },
            stopLocation = { stops += 1 },
        )

        repeat(3) {
            controller.reconcile(
                clientVisible = false,
                sessionPhase = LocationSessionPhase.ACTIVE,
                access = TripAccessState.Ready,
                storageBackpressure = false,
                storageFailure = false,
            )
        }

        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test
    fun `存储失败优先停止定位并在检查点恢复后重新启动`() {
        var starts = 0
        var stops = 0
        val controller = LocationEngineSessionController(
            startLocation = { starts += 1; true },
            stopLocation = { stops += 1 },
        )

        controller.reconcile(
            clientVisible = false,
            sessionPhase = LocationSessionPhase.ACTIVE,
            access = TripAccessState.Ready,
            storageBackpressure = false,
            storageFailure = false,
        )
        controller.reconcile(
            clientVisible = false,
            sessionPhase = LocationSessionPhase.ACTIVE,
            access = TripAccessState.Ready,
            storageBackpressure = false,
            storageFailure = true,
        )
        controller.reconcile(
            clientVisible = false,
            sessionPhase = LocationSessionPhase.ACTIVE,
            access = TripAccessState.Ready,
            storageBackpressure = false,
            storageFailure = false,
        )

        assertEquals(2, starts)
        assertEquals(1, stops)
    }

    @Test
    fun `系统启动失败时不缓存未执行的启动动作`() {
        var attempts = 0
        val expected = IllegalStateException("location start failed")
        val controller = LocationEngineSessionController(
            startLocation = {
                attempts += 1
                if (attempts == 1) throw expected
                true
            },
            stopLocation = {},
        )

        assertThrows(IllegalStateException::class.java) {
            controller.reconcile(
                clientVisible = true,
                sessionPhase = LocationSessionPhase.IDLE,
                access = TripAccessState.Ready,
                storageBackpressure = false,
                storageFailure = false,
            )
        }
        controller.reconcile(
            clientVisible = true,
            sessionPhase = LocationSessionPhase.IDLE,
            access = TripAccessState.Ready,
            storageBackpressure = false,
            storageFailure = false,
        )

        assertEquals(2, attempts)
    }
}

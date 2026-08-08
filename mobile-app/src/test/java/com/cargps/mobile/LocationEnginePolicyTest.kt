package com.cargps.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationEnginePolicyTest {
    @Test
    fun `开始等待确认且客户端不可见时不启动定位`() {
        assertEquals(
            LocationEngineAction.STOP,
            decideLocationEngineAction(
                clientVisible = false,
                sessionPhase = LocationSessionPhase.START_PENDING,
                access = TripAccessState.Ready,
            ),
        )
    }

    @Test
    fun `客户端可见时保持空闲定位预览`() {
        assertEquals(
            LocationEngineAction.START,
            decideLocationEngineAction(
                clientVisible = true,
                sessionPhase = LocationSessionPhase.IDLE,
                access = TripAccessState.Ready,
            ),
        )
    }

    @Test
    fun `开始等待确认时客户端可见仍保持定位预览`() {
        assertEquals(
            LocationEngineAction.START,
            decideLocationEngineAction(
                clientVisible = true,
                sessionPhase = LocationSessionPhase.START_PENDING,
                access = TripAccessState.Ready,
            ),
        )
    }

    @Test
    fun `活动行程在客户端不可见时继续定位`() {
        assertEquals(
            LocationEngineAction.START,
            decideLocationEngineAction(
                clientVisible = false,
                sessionPhase = LocationSessionPhase.ACTIVE,
                access = TripAccessState.Ready,
            ),
        )
    }

    @Test
    fun `定位权限受阻时停止空闲预览`() {
        val blocked = TripAccessState.Blocked(
            blocker = TripAccessBlocker.LOCATION_PERMISSION_REQUIRED,
            resolution = TripAccessResolution.REQUEST_LOCATION_PERMISSION,
        )

        assertEquals(
            LocationEngineAction.STOP,
            decideLocationEngineAction(
                clientVisible = true,
                sessionPhase = LocationSessionPhase.IDLE,
                access = blocked,
            ),
        )
    }

    @Test
    fun `未确认尾批达到上限时停止定位并保留行程`() {
        assertEquals(
            LocationEngineAction.STOP,
            decideLocationEngineAction(
                clientVisible = true,
                sessionPhase = LocationSessionPhase.ACTIVE,
                access = TripAccessState.Ready,
                storageBackpressure = true,
            ),
        )
    }

    @Test
    fun `一般存储失败时也停止定位并等待确认恢复`() {
        assertEquals(
            LocationEngineAction.STOP,
            decideLocationEngineAction(
                clientVisible = true,
                sessionPhase = LocationSessionPhase.ACTIVE,
                access = TripAccessState.Ready,
                storageFailure = true,
            ),
        )
    }
}

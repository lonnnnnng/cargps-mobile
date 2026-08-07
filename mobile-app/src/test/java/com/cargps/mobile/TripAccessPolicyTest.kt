package com.cargps.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class TripAccessPolicyTest {
    @Test
    fun `精确定位系统定位和通知均可用时允许启动行程`() {
        assertEquals(
            TripAccessState.Ready,
            evaluateTripAccess(
                TripAccessSnapshot(
                    preciseLocationGranted = true,
                    approximateLocationGranted = true,
                    locationPermissionRequestedBefore = true,
                    preciseLocationUpgradeRequestedBefore = true,
                    canRequestPreciseLocationAgain = false,
                    systemLocationEnabled = true,
                    notificationPermissionRequired = true,
                    notificationPermissionGranted = true,
                    notificationPermissionRequestedBefore = true,
                    canRequestNotificationAgain = false,
                ),
            ),
        )
    }

    @Test
    fun `首次仅有近似定位时允许在应用内升级精确定位`() {
        assertEquals(
            TripAccessState.Blocked(
                blocker = TripAccessBlocker.PRECISE_LOCATION_REQUIRED,
                resolution = TripAccessResolution.REQUEST_LOCATION_PERMISSION,
            ),
            evaluateTripAccess(
                readySnapshot().copy(
                    preciseLocationGranted = false,
                    approximateLocationGranted = true,
                    preciseLocationUpgradeRequestedBefore = false,
                    canRequestPreciseLocationAgain = false,
                ),
            ),
        )
    }

    @Test
    fun `精确定位升级被拒但仍可说明时保留请求入口`() {
        assertEquals(
            TripAccessState.Blocked(
                blocker = TripAccessBlocker.PRECISE_LOCATION_REQUIRED,
                resolution = TripAccessResolution.REQUEST_LOCATION_PERMISSION,
            ),
            evaluateTripAccess(
                readySnapshot().copy(
                    preciseLocationGranted = false,
                    approximateLocationGranted = true,
                    preciseLocationUpgradeRequestedBefore = true,
                    canRequestPreciseLocationAgain = true,
                ),
            ),
        )
    }

    @Test
    fun `精确定位升级已拒绝且不可再请求时打开应用设置`() {
        assertEquals(
            TripAccessState.Blocked(
                blocker = TripAccessBlocker.PRECISE_LOCATION_REQUIRED,
                resolution = TripAccessResolution.OPEN_APP_SETTINGS,
            ),
            evaluateTripAccess(
                readySnapshot().copy(
                    preciseLocationGranted = false,
                    approximateLocationGranted = true,
                    preciseLocationUpgradeRequestedBefore = true,
                    canRequestPreciseLocationAgain = false,
                ),
            ),
        )
    }

    @Test
    fun `首次缺少定位权限时允许发起系统授权请求`() {
        assertEquals(
            TripAccessState.Blocked(
                blocker = TripAccessBlocker.LOCATION_PERMISSION_REQUIRED,
                resolution = TripAccessResolution.REQUEST_LOCATION_PERMISSION,
            ),
            evaluateTripAccess(
                readySnapshot().copy(
                    preciseLocationGranted = false,
                    approximateLocationGranted = false,
                    locationPermissionRequestedBefore = false,
                    canRequestPreciseLocationAgain = false,
                ),
            ),
        )
    }

    @Test
    fun `定位权限永久拒绝后改为打开应用设置而不循环弹窗`() {
        assertEquals(
            TripAccessState.Blocked(
                blocker = TripAccessBlocker.LOCATION_PERMISSION_PERMANENTLY_DENIED,
                resolution = TripAccessResolution.OPEN_APP_SETTINGS,
            ),
            evaluateTripAccess(
                readySnapshot().copy(
                    preciseLocationGranted = false,
                    approximateLocationGranted = false,
                    locationPermissionRequestedBefore = true,
                    canRequestPreciseLocationAgain = false,
                ),
            ),
        )
    }

    @Test
    fun `系统定位关闭时引导到定位设置`() {
        assertEquals(
            TripAccessState.Blocked(
                blocker = TripAccessBlocker.SYSTEM_LOCATION_DISABLED,
                resolution = TripAccessResolution.OPEN_LOCATION_SETTINGS,
            ),
            evaluateTripAccess(readySnapshot().copy(systemLocationEnabled = false)),
        )
    }

    @Test
    fun `Android十三以上首次缺少通知权限时发起通知授权请求`() {
        assertEquals(
            TripAccessState.Blocked(
                blocker = TripAccessBlocker.NOTIFICATION_PERMISSION_REQUIRED,
                resolution = TripAccessResolution.REQUEST_NOTIFICATION_PERMISSION,
            ),
            evaluateTripAccess(
                readySnapshot().copy(
                    notificationPermissionGranted = false,
                    notificationPermissionRequestedBefore = false,
                    canRequestNotificationAgain = false,
                ),
            ),
        )
    }

    @Test
    fun `通知权限永久拒绝后改为打开应用设置`() {
        assertEquals(
            TripAccessState.Blocked(
                blocker = TripAccessBlocker.NOTIFICATION_PERMISSION_PERMANENTLY_DENIED,
                resolution = TripAccessResolution.OPEN_NOTIFICATION_SETTINGS,
            ),
            evaluateTripAccess(
                readySnapshot().copy(
                    notificationPermissionGranted = false,
                    notificationPermissionRequestedBefore = true,
                    canRequestNotificationAgain = false,
                ),
            ),
        )
    }

    @Test
    fun `API二十七不把通知运行时权限作为行程前置条件`() {
        assertEquals(
            TripAccessState.Ready,
            evaluateTripAccess(
                readySnapshot().copy(
                    notificationPermissionRequired = false,
                    notificationPermissionGranted = false,
                    notificationPermissionRequestedBefore = false,
                ),
            ),
        )
    }

    @Test
    fun `从系统设置补齐权限和定位开关后状态收敛为可启动`() {
        val blocked = readySnapshot().copy(
            preciseLocationGranted = false,
            approximateLocationGranted = false,
            locationPermissionRequestedBefore = true,
            canRequestPreciseLocationAgain = false,
            systemLocationEnabled = false,
        )

        assertEquals(
            TripAccessState.Ready,
            evaluateTripAccess(
                blocked.copy(
                    preciseLocationGranted = true,
                    approximateLocationGranted = true,
                    systemLocationEnabled = true,
                ),
            ),
        )
    }

    private fun readySnapshot() = TripAccessSnapshot(
        preciseLocationGranted = true,
        approximateLocationGranted = true,
        locationPermissionRequestedBefore = true,
        preciseLocationUpgradeRequestedBefore = true,
        canRequestPreciseLocationAgain = false,
        systemLocationEnabled = true,
        notificationPermissionRequired = true,
        notificationPermissionGranted = true,
        notificationPermissionRequestedBefore = true,
        canRequestNotificationAgain = false,
    )
}

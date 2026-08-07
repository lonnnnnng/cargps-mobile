package com.cargps.mobile

internal data class TripAccessSnapshot(
    val preciseLocationGranted: Boolean,
    val approximateLocationGranted: Boolean,
    val locationPermissionRequestedBefore: Boolean,
    val preciseLocationUpgradeRequestedBefore: Boolean,
    val canRequestPreciseLocationAgain: Boolean,
    val systemLocationEnabled: Boolean,
    val notificationPermissionRequired: Boolean,
    val notificationPermissionGranted: Boolean,
    val notificationPermissionRequestedBefore: Boolean,
    val canRequestNotificationAgain: Boolean,
)

internal sealed interface TripAccessState {
    data object Ready : TripAccessState

    data class Blocked(
        val blocker: TripAccessBlocker,
        val resolution: TripAccessResolution,
    ) : TripAccessState
}

internal enum class TripAccessBlocker {
    LOCATION_PERMISSION_REQUIRED,
    LOCATION_PERMISSION_PERMANENTLY_DENIED,
    PRECISE_LOCATION_REQUIRED,
    SYSTEM_LOCATION_DISABLED,
    NOTIFICATION_PERMISSION_REQUIRED,
    NOTIFICATION_PERMISSION_PERMANENTLY_DENIED,
}

internal enum class TripAccessResolution {
    REQUEST_LOCATION_PERMISSION,
    OPEN_APP_SETTINGS,
    OPEN_LOCATION_SETTINGS,
    OPEN_NOTIFICATION_SETTINGS,
    REQUEST_NOTIFICATION_PERMISSION,
}

internal fun evaluateTripAccess(snapshot: TripAccessSnapshot): TripAccessState = when {
    !snapshot.preciseLocationGranted && snapshot.approximateLocationGranted -> TripAccessState.Blocked(
        blocker = TripAccessBlocker.PRECISE_LOCATION_REQUIRED,
        resolution = if (
            !snapshot.preciseLocationUpgradeRequestedBefore ||
            snapshot.canRequestPreciseLocationAgain
        ) {
            TripAccessResolution.REQUEST_LOCATION_PERMISSION
        } else {
            TripAccessResolution.OPEN_APP_SETTINGS
        },
    )
    !snapshot.preciseLocationGranted && snapshot.locationPermissionRequestedBefore &&
        !snapshot.canRequestPreciseLocationAgain -> TripAccessState.Blocked(
        blocker = TripAccessBlocker.LOCATION_PERMISSION_PERMANENTLY_DENIED,
        resolution = TripAccessResolution.OPEN_APP_SETTINGS,
    )
    !snapshot.preciseLocationGranted -> TripAccessState.Blocked(
        blocker = TripAccessBlocker.LOCATION_PERMISSION_REQUIRED,
        resolution = TripAccessResolution.REQUEST_LOCATION_PERMISSION,
    )
    !snapshot.systemLocationEnabled -> TripAccessState.Blocked(
        blocker = TripAccessBlocker.SYSTEM_LOCATION_DISABLED,
        resolution = TripAccessResolution.OPEN_LOCATION_SETTINGS,
    )
    snapshot.notificationPermissionRequired && !snapshot.notificationPermissionGranted &&
        snapshot.notificationPermissionRequestedBefore && !snapshot.canRequestNotificationAgain -> TripAccessState.Blocked(
        blocker = TripAccessBlocker.NOTIFICATION_PERMISSION_PERMANENTLY_DENIED,
        resolution = TripAccessResolution.OPEN_NOTIFICATION_SETTINGS,
    )
    snapshot.notificationPermissionRequired && !snapshot.notificationPermissionGranted -> TripAccessState.Blocked(
        blocker = TripAccessBlocker.NOTIFICATION_PERMISSION_REQUIRED,
        resolution = TripAccessResolution.REQUEST_NOTIFICATION_PERMISSION,
    )
    else -> TripAccessState.Ready
}

internal val TripAccessState.isReady: Boolean
    get() = this is TripAccessState.Ready

internal val TripAccessState.blockedOrNull: TripAccessState.Blocked?
    get() = this as? TripAccessState.Blocked

internal val TripAccessState.blocksLocation: Boolean
    get() = blockedOrNull?.blocker in setOf(
        TripAccessBlocker.LOCATION_PERMISSION_REQUIRED,
        TripAccessBlocker.LOCATION_PERMISSION_PERMANENTLY_DENIED,
        TripAccessBlocker.PRECISE_LOCATION_REQUIRED,
        TripAccessBlocker.SYSTEM_LOCATION_DISABLED,
    )

internal fun TripAccessState.serviceErrorMessage(): String? = when (blockedOrNull?.blocker) {
    TripAccessBlocker.LOCATION_PERMISSION_REQUIRED -> "允许精确定位后才能记录行程"
    TripAccessBlocker.LOCATION_PERMISSION_PERMANENTLY_DENIED -> "精确定位已被永久拒绝，请在应用设置中开启"
    TripAccessBlocker.PRECISE_LOCATION_REQUIRED -> "当前只有大致位置，请改为精确定位"
    TripAccessBlocker.SYSTEM_LOCATION_DISABLED -> "系统定位已关闭，请先开启位置信息"
    TripAccessBlocker.NOTIFICATION_PERMISSION_REQUIRED -> "允许通知后才能显示后台行程状态"
    TripAccessBlocker.NOTIFICATION_PERMISSION_PERMANENTLY_DENIED -> "行程通知已关闭，请在通知设置中开启"
    null -> null
}

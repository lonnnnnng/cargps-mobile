package com.cargps.mobile

import com.cargps.DashboardState
import com.cargps.TripMode

internal enum class StartedServiceRecoveryAction {
    WAIT_FOR_STORAGE,
    RESUME_ACTIVE_TRIP,
    STOP_NO_ACTIVE_TRIP,
    STOP_RESTORE_FAILED,
}

internal fun decideStartedServiceRecovery(state: DashboardState): StartedServiceRecoveryAction = when {
    state.storageError != null -> StartedServiceRecoveryAction.STOP_RESTORE_FAILED
    !state.storageReady -> StartedServiceRecoveryAction.WAIT_FOR_STORAGE
    state.tripMode != TripMode.IDLE -> StartedServiceRecoveryAction.RESUME_ACTIVE_TRIP
    else -> StartedServiceRecoveryAction.STOP_NO_ACTIVE_TRIP
}

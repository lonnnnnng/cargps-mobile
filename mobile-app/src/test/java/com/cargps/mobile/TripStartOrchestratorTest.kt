package com.cargps.mobile

import com.cargps.DashboardState
import com.cargps.TripMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStartOrchestratorTest {
    @Test
    fun `开始确认前不启动定位或处理活动状态`() = runBlocking {
        val confirmedState = CompletableDeferred<DashboardState>()
        val events = mutableListOf<String>()
        val start = launch(start = CoroutineStart.UNDISPATCHED) {
            completeTripStart(
                requestedAtMillis = 1_000L,
                awaitStart = { confirmedState.await() },
                currentAccess = { TripAccessState.Ready },
                onRequestFinished = { events += "请求结束" },
                startLocation = { events += "启动定位" },
                handleState = { events += "处理状态" },
            )
        }

        assertTrue(events.isEmpty())

        confirmedState.complete(DashboardState(tripMode = TripMode.RECORDING, storageReady = true))
        start.join()

        assertEquals(listOf("启动定位", "处理状态", "请求结束"), events)
    }

    @Test
    fun `开始失败返回空闲状态时清理请求但不启动定位`() = runBlocking {
        val events = mutableListOf<String>()

        completeTripStart(
            requestedAtMillis = 1_000L,
            awaitStart = {
                DashboardState(
                    tripMode = TripMode.IDLE,
                    storageReady = true,
                    storageError = "start failed",
                )
            },
            currentAccess = { TripAccessState.Ready },
            onRequestFinished = { events += "请求结束" },
            startLocation = { events += "启动定位" },
            handleState = { events += "处理状态" },
        )

        assertEquals(listOf("处理状态", "请求结束"), events)
    }

    @Test
    fun `等待期间权限失效时不启动定位`() = runBlocking {
        val events = mutableListOf<String>()
        val blocked = TripAccessState.Blocked(
            blocker = TripAccessBlocker.SYSTEM_LOCATION_DISABLED,
            resolution = TripAccessResolution.OPEN_LOCATION_SETTINGS,
        )

        completeTripStart(
            requestedAtMillis = 1_000L,
            awaitStart = { DashboardState(tripMode = TripMode.RECORDING, storageReady = true) },
            currentAccess = { blocked },
            onRequestFinished = { events += "请求结束" },
            startLocation = { events += "启动定位" },
            handleState = { events += "处理状态" },
        )

        assertEquals(listOf("处理状态", "请求结束"), events)
    }

    @Test
    fun `等待异常时仍清理请求中状态`() = runBlocking {
        val expected = IllegalStateException("queue failed")
        val events = mutableListOf<String>()

        val failure = try {
            completeTripStart(
                requestedAtMillis = 1_000L,
                awaitStart = { throw expected },
                currentAccess = { TripAccessState.Ready },
                onRequestFinished = { events += "请求结束" },
                startLocation = { events += "启动定位" },
                handleState = { events += "处理状态" },
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertEquals(expected, failure)
        assertEquals(listOf("请求结束"), events)
    }

    @Test
    fun `清除启动请求前必须先处理最终状态`() = runBlocking {
        var stateHandled = false

        completeTripStart(
            requestedAtMillis = 1_000L,
            awaitStart = { DashboardState(tripMode = TripMode.RECORDING, storageReady = true) },
            currentAccess = { TripAccessState.Ready },
            onRequestFinished = { assertTrue(stateHandled) },
            startLocation = {},
            handleState = { stateHandled = true },
        )
    }
}

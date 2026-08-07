package com.cargps.session

import com.cargps.TripMode
import com.cargps.domain.TripPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripSessionEventQueueTest {
    @Test
    fun `恢复固定先于并发进入队列的行程事件`() = runTest {
        val commands = mutableListOf<TripSessionCommand>()
        val queue = eventQueue(commands)

        queue.tryDispatch(TripSessionCommand.Start(1_000L))
        queue.tryDispatch(TripSessionCommand.AppendPoint(TripPoint(2_000L, 3.0, 5.0, true)))
        queue.tryDispatch(TripSessionCommand.Tick(2_500L))
        queue.tryDispatch(TripSessionCommand.Pause(3_000L))
        queue.tryDispatch(TripSessionCommand.Resume(4_000L))
        queue.tryDispatch(TripSessionCommand.End(5_000L))
        val barrier = async { queue.dispatchAndAwait(TripSessionCommand.Checkpoint) }
        advanceUntilIdle()
        barrier.await()

        assertEquals(
            listOf(
                TripSessionCommand.Restore,
                TripSessionCommand.Start(1_000L),
                TripSessionCommand.AppendPoint(TripPoint(2_000L, 3.0, 5.0, true)),
                TripSessionCommand.Tick(2_500L),
                TripSessionCommand.Pause(3_000L),
                TripSessionCommand.Resume(4_000L),
                TripSessionCommand.End(5_000L),
                TripSessionCommand.Checkpoint,
            ),
            commands,
        )
        queue.close()
    }

    @Test
    fun `等待型开始只有在队列完成存储确认后返回`() = runTest {
        val commands = mutableListOf<TripSessionCommand>()
        val releaseStart = CompletableDeferred<Unit>()
        val queue = TripSessionEventQueue(
            scope = backgroundScope,
            currentMode = { TripMode.IDLE },
            dispatch = { command ->
                commands += command
                if (command is TripSessionCommand.Start) {
                    releaseStart.await()
                }
                TripSessionResult.Confirmed(TripSessionState(storageReady = true))
            },
            onResult = {},
        )
        runCurrent()

        val start = async { queue.dispatchAndAwait(TripSessionCommand.Start(1_000L)) }
        runCurrent()
        assertFalse(start.isCompleted)

        releaseStart.complete(Unit)
        advanceUntilIdle()

        assertTrue(start.await() is TripSessionResult.Confirmed)
        assertEquals(listOf(TripSessionCommand.Restore, TripSessionCommand.Start(1_000L)), commands)
        queue.close()
    }

    @Test
    fun `关闭队列会取消尚未完成的等待型命令`() = runTest {
        val releaseStart = CompletableDeferred<Unit>()
        val queue = TripSessionEventQueue(
            scope = backgroundScope,
            currentMode = { TripMode.IDLE },
            dispatch = { command ->
                if (command is TripSessionCommand.Start) releaseStart.await()
                TripSessionResult.Confirmed(TripSessionState(storageReady = true))
            },
            onResult = {},
        )
        runCurrent()
        val start = async { queue.dispatchAndAwait(TripSessionCommand.Start(1_000L)) }
        runCurrent()

        queue.close()

        val failure = try {
            withTimeout(1_000L) { start.await() }
            null
        } catch (error: Throwable) {
            error
        }
        assertTrue(failure is CancellationException)
        assertFalse(failure is TimeoutCancellationException)
    }

    @Test
    fun `命令异常后队列拒绝新事件并向等待者传播原始错误`() = runTest {
        val expected = IllegalStateException("unexpected dispatch failure")
        val uncaught = CompletableDeferred<Throwable>()
        val queueScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> uncaught.complete(error) },
        )
        val queue = TripSessionEventQueue(
            scope = queueScope,
            currentMode = { TripMode.IDLE },
            dispatch = { command ->
                if (command is TripSessionCommand.Start) throw expected
                TripSessionResult.Confirmed(TripSessionState(storageReady = true))
            },
            onResult = {},
        )
        runCurrent()
        val start = queueScope.async { queue.dispatchAndAwait(TripSessionCommand.Start(1_000L)) }

        runCurrent()

        val failure = try {
            start.await()
            null
        } catch (error: Throwable) {
            error
        }
        assertTrue(failure is IllegalStateException)
        assertEquals(expected.message, failure?.message)
        assertSame(expected, uncaught.await())
        assertFalse(queue.tryDispatch(TripSessionCommand.Checkpoint))
        queue.close()
    }

    @Test
    fun `连续切换在消费时按最新模式依次开始暂停和继续`() = runTest {
        var mode = TripMode.IDLE
        val commands = mutableListOf<TripSessionCommand>()
        val queue = TripSessionEventQueue(
            scope = backgroundScope,
            currentMode = { mode },
            dispatch = { command ->
                commands += command
                mode = when (command) {
                    is TripSessionCommand.Start -> TripMode.RECORDING
                    is TripSessionCommand.Pause -> TripMode.PAUSED
                    is TripSessionCommand.Resume -> TripMode.RECORDING
                    else -> mode
                }
                TripSessionResult.Confirmed(TripSessionState(mode = mode, storageReady = true))
            },
            onResult = {},
        )

        queue.tryToggle(1_000L)
        queue.tryToggle(2_000L)
        queue.tryToggle(3_000L)
        val barrier = async { queue.dispatchAndAwait(TripSessionCommand.Checkpoint) }
        advanceUntilIdle()
        barrier.await()

        assertEquals(
            listOf(
                TripSessionCommand.Restore,
                TripSessionCommand.Start(1_000L),
                TripSessionCommand.Pause(2_000L),
                TripSessionCommand.Resume(3_000L),
                TripSessionCommand.Checkpoint,
            ),
            commands,
        )
        queue.close()
    }

    private fun kotlinx.coroutines.test.TestScope.eventQueue(
        commands: MutableList<TripSessionCommand>,
    ) = TripSessionEventQueue(
        scope = backgroundScope,
        currentMode = { TripMode.IDLE },
        dispatch = { command ->
            commands += command
            TripSessionResult.Accepted(TripSessionState(storageReady = true))
        },
        onResult = {},
    )
}

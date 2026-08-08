package com.cargps.session

import com.cargps.TripMode
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 作者：long
 *
 * 行程运行时的唯一事件入口。Restore 固定先执行，其余命令按进入 Channel 的顺序串行交给会话协调器。
 */
internal class TripSessionEventQueue(
    scope: CoroutineScope,
    private val currentMode: () -> TripMode,
    private val dispatch: suspend (TripSessionCommand) -> TripSessionResult,
    private val onResult: (TripSessionResult) -> Unit,
) : Closeable {
    private val terminalCause = AtomicReference<Throwable?>(null)
    private val events = Channel<QueuedEvent>(Channel.UNLIMITED)
    private val eventJob: Job = scope.launch {
        try {
            process(QueuedEvent.Command(TripSessionCommand.Restore))
            for (event in events) process(event)
        } catch (error: Throwable) {
            // 作者：long｜actor 异常后先关闭入口，避免调用方收到“已入队”但事件永远无人消费。
            terminate(error)
            throw error
        } finally {
            terminate(CancellationException("行程事件队列已停止"))
        }
    }

    fun tryDispatch(command: TripSessionCommand): Boolean =
        events.trySend(QueuedEvent.Command(command)).isSuccess

    fun tryToggle(atMillis: Long): Boolean =
        events.trySend(QueuedEvent.Toggle(atMillis)).isSuccess

    suspend fun dispatchAndAwait(command: TripSessionCommand): TripSessionResult {
        val completion = CompletableDeferred<TripSessionResult>()
        return try {
            events.send(QueuedEvent.Command(command, completion))
            completion.await()
        } catch (error: CancellationException) {
            // 作者：long｜Service/Activity 销毁时取消等待不能让尚未消费的命令在恢复后继续产生行程副作用。
            completion.cancel(error)
            throw error
        }
    }

    override fun close() {
        val cause = CancellationException("行程事件队列已关闭")
        terminate(cause)
        eventJob.cancel(cause)
    }

    private suspend fun process(event: QueuedEvent) {
        try {
            if (event.completion?.isActive == false) {
                // 作者：long｜等待者已经取消时直接丢弃尚未开始的命令；已经进入 dispatch 的事务仍由其协程取消语义负责。
                return
            }
            val command = when (event) {
                is QueuedEvent.Command -> event.command
                is QueuedEvent.Toggle -> when (currentMode()) {
                    TripMode.IDLE -> TripSessionCommand.Start(event.atMillis)
                    TripMode.RECORDING -> TripSessionCommand.Pause(event.atMillis)
                    TripMode.PAUSED -> TripSessionCommand.Resume(event.atMillis)
                }
            }
            val result = dispatch(command)
            onResult(result)
            event.completion?.complete(result)
        } catch (error: Throwable) {
            event.completion?.completeExceptionally(error)
            throw error
        }
    }

    private fun failPendingEvents(cause: Throwable) {
        // 作者：long｜关闭后必须唤醒所有等待存储确认的调用方，不能让 Service 永久停在“正在确认行程”。
        while (true) {
            val pending = events.tryReceive().getOrNull() ?: return
            pending.completion?.completeExceptionally(cause)
        }
    }

    private fun terminate(cause: Throwable) {
        terminalCause.compareAndSet(null, cause)
        val terminal = checkNotNull(terminalCause.get())
        events.close(terminal)
        failPendingEvents(terminal)
    }

    private sealed interface QueuedEvent {
        val completion: CompletableDeferred<TripSessionResult>?

        data class Command(
            val command: TripSessionCommand,
            override val completion: CompletableDeferred<TripSessionResult>? = null,
        ) : QueuedEvent

        data class Toggle(
            val atMillis: Long,
            override val completion: CompletableDeferred<TripSessionResult>? = null,
        ) : QueuedEvent
    }
}

package com.loanagent.agent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class TaskCancellationReason {
    TIMEOUT,
    SHUTDOWN,
}

class TaskExecutionContext(
    val generation: Long,
    val deadlineMillis: Long,
    private val clock: MonotonicClock,
) {
    private enum class State {
        RUNNING,
        TIMED_OUT,
        SHUTDOWN,
        COMPLETED,
    }

    private val state = AtomicReference(State.RUNNING)
    private val effectPossible = AtomicBoolean(false)

    fun check() {
        expireFromClock()
        when (state.get()) {
            State.TIMED_OUT -> throw TaskExecutionCancelledException(TaskCancellationReason.TIMEOUT)
            State.SHUTDOWN -> throw TaskExecutionCancelledException(TaskCancellationReason.SHUTDOWN)
            State.RUNNING,
            State.COMPLETED,
            -> Unit
        }
    }

    fun remainingMillis(): Long {
        expireFromClock()
        if (state.get() != State.RUNNING) return 0
        if (deadlineMillis == Long.MAX_VALUE) return Long.MAX_VALUE
        return (deadlineMillis - clock.nowMillis()).coerceAtLeast(0)
    }

    fun timeout(expectedGeneration: Long) {
        if (expectedGeneration != generation) return
        cancel(State.TIMED_OUT)
    }

    fun cancelForShutdown() {
        cancel(State.SHUTDOWN)
    }

    fun beginSideEffect() {
        check()
        effectPossible.set(true)
    }

    fun markEffectCommitted() {
        effectPossible.set(true)
    }

    fun effectMayHaveOccurred(): Boolean = effectPossible.get()

    fun cancellationReason(): TaskCancellationReason? {
        expireFromClock()
        return when (state.get()) {
            State.TIMED_OUT -> TaskCancellationReason.TIMEOUT
            State.SHUTDOWN -> TaskCancellationReason.SHUTDOWN
            State.RUNNING,
            State.COMPLETED,
            -> null
        }
    }

    /** Exactly one of normal completion or timeout/shutdown cancellation may win. */
    fun complete(): Boolean = state.compareAndSet(State.RUNNING, State.COMPLETED)

    fun close() {
        complete()
    }

    private fun expireFromClock() {
        if (
            state.get() == State.RUNNING &&
            deadlineMillis != Long.MAX_VALUE &&
            clock.nowMillis() >= deadlineMillis
        ) {
            cancel(State.TIMED_OUT)
        }
    }

    private fun cancel(cancelledState: State) {
        state.compareAndSet(State.RUNNING, cancelledState)
    }
}

class TaskExecutionCancelledException(
    val reason: TaskCancellationReason,
) : RuntimeException()

interface TaskExecutionContextBindable {
    fun bindExecutionContext(context: TaskExecutionContext): RequestHandle
}

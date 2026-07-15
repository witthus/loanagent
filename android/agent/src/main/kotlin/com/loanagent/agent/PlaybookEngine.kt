package com.loanagent.agent

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class PlaybookEngine(
    private val runtime: PlaybookRuntime,
    private val registry: PlaybookRegistry,
    private val ledger: EffectLedger,
    private val clock: MonotonicClock = SystemMonotonicClock,
) {
    private val busy = AtomicBoolean(false)
    private val active = AtomicReference<TaskExecutionContext?>()

    /**
     * @return null when the same task_id was already acknowledged (silent skip).
     */
    fun run(command: PlaybookCommand): PlaybookResult? {
        val taskId = command.taskId.trim()
        if (taskId.isEmpty()) return null
        return when (claim(command)) {
            LedgerClaim.Acquired -> try {
                executeClaimed(command, Long.MAX_VALUE)
            } finally {
                ledger.release(taskId)
            }
            LedgerClaim.CommitFailed -> PlaybookResult.failed(taskId, "LEDGER_COMMIT_FAILED")
            LedgerClaim.InFlight,
            is LedgerClaim.Existing,
            is LedgerClaim.Corrupt,
            -> null
        }
    }

    fun claim(command: PlaybookCommand): LedgerClaim = ledger.claim(command)

    fun executeClaimed(command: PlaybookCommand, deadlineMillis: Long): PlaybookResult =
        executeClaimed(
            command,
            TaskExecutionContext(
                generation = 0,
                deadlineMillis = deadlineMillis,
                clock = clock,
            ),
        )

    fun executeClaimed(
        command: PlaybookCommand,
        context: TaskExecutionContext,
    ): PlaybookResult {
        val taskId = command.taskId.trim()
        if (!busy.compareAndSet(false, true)) {
            return PlaybookResult.failed(taskId, "BUSY")
        }
        active.set(context)
        val runtimeBinding =
            (runtime as? TaskExecutionContextBindable)?.bindExecutionContext(context)
        return try {
            context.check()
            val base = command.playbookBase()
            val playbook = registry.get(base)
            if (playbook == null) {
                PlaybookResult.failed(taskId, "UNSUPPORTED_PLAYBOOK")
            } else {
                val result = playbook.run(command, GuardedPlaybookRuntime(runtime, context))
                context.check()
                result
            }
        } catch (_: TaskExecutionCancelledException) {
            resultForCancellation(command, context)
        } catch (_: InterruptedException) {
            Thread.interrupted()
            context.cancelForShutdown()
            resultForCancellation(command, context)
        } finally {
            runtimeBinding?.cancel()
            active.compareAndSet(context, null)
            busy.set(false)
        }
    }

    fun cancelActive() {
        active.get()?.cancelForShutdown()
    }

    fun ledgerEntry(taskId: String): LedgerEntry? = ledger.entry(taskId)

    fun pendingRecovery(): List<LedgerEntry> = ledger.pendingRecovery()

    fun storeEffectCommitted(command: PlaybookCommand, result: PlaybookResult): Boolean =
        ledger.storeEffectCommitted(command, result)

    fun storeFinal(command: PlaybookCommand, result: PlaybookResult): Boolean =
        ledger.storeFinal(command, result)

    fun markReported(taskId: String): Boolean = ledger.markReported(taskId)

    fun releaseClaim(taskId: String) = ledger.release(taskId)

    fun acknowledge(command: PlaybookCommand, result: PlaybookResult): Boolean {
        return try {
            if (result.effectCommitted && !ledger.storeEffectCommitted(command, result)) {
                return false
            }
            ledger.storeFinal(command, result)
        } finally {
            ledger.release(result.taskId)
        }
    }

    fun resultForCancellation(
        command: PlaybookCommand,
        context: TaskExecutionContext,
    ): PlaybookResult {
        val possibleEffect = context.effectMayHaveOccurred()
        return if (
            command.effectClass == EffectClass.NON_IDEMPOTENT &&
            possibleEffect
        ) {
            PlaybookResult.unknown(command.taskId)
        } else {
            PlaybookResult.failed(
                command.taskId,
                if (context.cancellationReason() == TaskCancellationReason.TIMEOUT) {
                    "EXECUTION_TIMEOUT"
                } else {
                    "EXECUTION_INTERRUPTED"
                },
            )
        }
    }
}

private class GuardedPlaybookRuntime(
    private val delegate: PlaybookRuntime,
    private val context: TaskExecutionContext,
) : PlaybookRuntime {
    override fun beginSideEffect() = context.beginSideEffect()
    override fun accessibilityAlive(): Boolean = guarded { delegate.accessibilityAlive() }
    override fun launchXhs(): Boolean = guarded { delegate.launchXhs() }
    override fun waitForXhsForeground(timeoutMs: Long): Boolean =
        guarded { delegate.waitForXhsForeground(timeoutMs) }

    override fun ensureScreenReady(timeoutMs: Long): String? =
        guarded { delegate.ensureScreenReady(timeoutMs) }

    override fun currentPageHint(): PageHint? = guarded { delegate.currentPageHint() }
    override fun currentLease(): TargetLease? = guarded { delegate.currentLease() }
    override fun observe(): UiSnapshot? = guarded { delegate.observe() }
    override fun extractComments(maxItems: Int): List<ExtractedComment> =
        guarded { delegate.extractComments(maxItems) }

    override fun extractInboxThreads(maxItems: Int): List<ExtractedInboxThread> =
        guarded { delegate.extractInboxThreads(maxItems) }

    override fun extractDmMessages(maxItems: Int): List<ExtractedDmMessage> =
        guarded { delegate.extractDmMessages(maxItems) }

    override fun extractProfileNotes(maxItems: Int): List<ExtractedProfileNote> =
        guarded { delegate.extractProfileNotes(maxItems) }

    override fun looksLikeInboxListSurface(): Boolean =
        guarded { delegate.looksLikeInboxListSurface() }

    override fun looksLikeOpenDmThreadSurface(): Boolean =
        guarded { delegate.looksLikeOpenDmThreadSurface() }

    override fun looksLikeCommentsSurface(): Boolean =
        guarded { delegate.looksLikeCommentsSurface() }

    override fun looksLikeProfileSurface(): Boolean =
        guarded { delegate.looksLikeProfileSurface() }

    override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean {
        context.check()
        return delegate.click(selector, allowFinal, timeoutMs).also { context.check() }
    }

    override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean =
        guarded { delegate.clickTextContaining(fragment, timeoutMs) }

    override fun setText(selector: String, text: String, timeoutMs: Long): Boolean =
        guarded { delegate.setText(selector, text, timeoutMs) }

    override fun tap(x: Int, y: Int, durationMs: Long): Boolean =
        guarded { delegate.tap(x, y, durationMs) }

    override fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean = guarded { delegate.swipe(startX, startY, endX, endY, durationMs) }

    override fun globalBack(): Boolean = guarded { delegate.globalBack() }

    override fun sleep(ms: Long) {
        var remaining = ms.coerceAtLeast(0)
        while (remaining > 0) {
            context.check()
            val slice = minOf(remaining, SLEEP_SLICE_MS)
            delegate.sleep(slice)
            remaining -= slice
            context.check()
        }
    }

    private inline fun <T> guarded(block: () -> T): T {
        context.check()
        return block().also { context.check() }
    }

    private companion object {
        const val SLEEP_SLICE_MS = 100L
    }
}

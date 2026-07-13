package com.loanagent.agent

import java.util.concurrent.atomic.AtomicBoolean

class PlaybookEngine(
    private val runtime: PlaybookRuntime,
    private val registry: PlaybookRegistry,
    private val ledger: EffectLedger,
) {
    private val busy = AtomicBoolean(false)

    /**
     * @return null when the same task_id was already acknowledged (silent skip).
     */
    fun run(command: PlaybookCommand): PlaybookResult? {
        val taskId = command.taskId.trim()
        if (taskId.isEmpty()) return null

        if (ledger.hasSeen(taskId)) {
            if (
                command.effectClass == EffectClass.NON_IDEMPOTENT &&
                ledger.effectCommitted(taskId)
            ) {
                return PlaybookResult.succeeded(taskId, effectCommitted = true)
            }
            return null
        }

        if (!busy.compareAndSet(false, true)) {
            return PlaybookResult.failed(taskId, "BUSY")
        }
        return try {
            val base = command.playbookBase()
            val playbook = registry.get(base)
            if (playbook == null) {
                PlaybookResult.failed(taskId, "UNSUPPORTED_PLAYBOOK")
            } else {
                playbook.run(command, runtime)
            }
        } finally {
            busy.set(false)
        }
    }

    /** Persist after a successful cloud/event report so failed reports can retry. */
    fun acknowledge(command: PlaybookCommand, result: PlaybookResult) {
        val committed = result.effectCommitted ||
            (result.success && command.effectClass != EffectClass.NON_IDEMPOTENT)
        ledger.record(result.taskId, command.playbook, effectCommitted = committed)
    }
}

package com.loanagent.agent

class EnsureAppReadyPlaybook(
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : Playbook {
    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult {
        val taskId = command.taskId
        val timeoutMs = command.timeoutSec?.let { it * 1000L }?.coerceAtLeast(1_000L)
            ?: defaultTimeoutMs
        if (!runtime.accessibilityAlive()) {
            return PlaybookResult.failed(taskId, "A11Y_DOWN")
        }
        runtime.launchXhs()
        if (!runtime.waitForXhsForeground(timeoutMs)) {
            return PlaybookResult.failed(taskId, "XHS_NOT_FOREGROUND")
        }
        return when (runtime.currentPageHint()) {
            PageHint.LOGIN_REQUIRED -> PlaybookResult.failed(taskId, "LOGIN_REQUIRED")
            PageHint.BUSINESS_BLOCKED -> PlaybookResult.failed(taskId, "BUSINESS_BLOCKED")
            null -> PlaybookResult.failed(taskId, "OBSERVE_FAILED")
            else -> PlaybookResult.succeeded(taskId)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}

package com.loanagent.agent

enum class EffectClass {
    READONLY,
    IDEMPOTENT,
    NON_IDEMPOTENT,
}

data class PlaybookCommand(
    val taskId: String,
    val playbook: String,
    val params: Map<String, Any?> = emptyMap(),
    val effectClass: EffectClass = EffectClass.IDEMPOTENT,
    val timeoutSec: Int? = null,
    val operationId: String? = null,
    val accountId: String? = null,
) {
    fun playbookBase(): String = playbook.substringBefore('@').trim()
}

data class PlaybookResult(
    val taskId: String,
    val success: Boolean,
    val status: String,
    val errorCode: String? = null,
    val effectCommitted: Boolean = false,
    val resultPayload: Map<String, Any?>? = null,
) {
    companion object {
        fun succeeded(
            taskId: String,
            effectCommitted: Boolean = false,
            resultPayload: Map<String, Any?>? = null,
        ): PlaybookResult =
            PlaybookResult(
                taskId = taskId,
                success = true,
                status = "succeeded",
                effectCommitted = effectCommitted,
                resultPayload = resultPayload,
            )

        fun failed(
            taskId: String,
            errorCode: String,
            effectCommitted: Boolean = false,
            resultPayload: Map<String, Any?>? = null,
        ): PlaybookResult =
            PlaybookResult(
                taskId = taskId,
                success = false,
                status = "failed",
                errorCode = errorCode,
                effectCommitted = effectCommitted,
                resultPayload = resultPayload,
            )

        fun unknown(
            taskId: String,
            errorCode: String = "EFFECT_UNKNOWN",
            effectCommitted: Boolean = false,
        ): PlaybookResult =
            PlaybookResult(
                taskId = taskId,
                success = false,
                status = "unknown",
                errorCode = errorCode,
                effectCommitted = effectCommitted,
            )
    }
}

fun interface Playbook {
    fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult
}

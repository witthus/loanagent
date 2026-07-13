package com.loanagent.agent

import android.content.Context
import org.json.JSONObject

class TaskCommandDispatcher(
    private val engine: PlaybookEngine,
    private val reporter: TaskEventSink,
    private val preparePublishParams: ((Map<String, Any?>) -> Map<String, Any?>?)? = null,
) {
    constructor(
        engine: PlaybookEngine,
        reporter: TaskEventSink,
        context: Context,
    ) : this(
        engine = engine,
        reporter = reporter,
        preparePublishParams = { params -> MediaBridge.preparePublishParams(context, params) },
    )

    fun handleMqttPayload(payload: String): PlaybookResult? {
        val json = JSONObject(payload)
        val taskId = json.optString("task_id").trim()
        if (taskId.isEmpty()) return null
        val playbook = json.optString("playbook").trim()
        if (playbook.isEmpty()) return null
        val effectClass = parseEffectClass(json.optString("effect_class"))
        val timeoutSec = if (json.has("timeout_sec")) json.optInt("timeout_sec") else null
        val params = linkedMapOf<String, Any?>()
        json.optJSONObject("params")?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                params[key] = obj.get(key)
            }
        }

        val preparedParams = if (playbook.substringBefore('@').trim() == "publish_note") {
            val preparer = preparePublishParams
            if (preparer != null) {
                preparer(params) ?: run {
                    val failed = PlaybookResult.failed(taskId, "MEDIA_MISSING")
                    val reported = reporter.report(
                        taskId = failed.taskId,
                        status = failed.status,
                        errorCode = failed.errorCode,
                        resultPayload = failed.resultPayload,
                    )
                    if (reported) {
                        // No engine run — still ack so the same task_id is not retried forever.
                        engine.acknowledge(
                            PlaybookCommand(
                                taskId = taskId,
                                playbook = playbook,
                                params = params,
                                effectClass = effectClass,
                                timeoutSec = timeoutSec,
                            ),
                            failed,
                        )
                    }
                    return failed
                }
            } else {
                params
            }
        } else {
            params
        }

        val command = PlaybookCommand(
            taskId = taskId,
            playbook = playbook,
            params = preparedParams,
            effectClass = effectClass,
            timeoutSec = timeoutSec,
            operationId = json.optString("operation_id").trim().ifEmpty { null },
            accountId = json.optString("account_id").trim().ifEmpty { null },
        )
        val result = engine.run(command) ?: return null
        val reported = reporter.report(
            taskId = result.taskId,
            status = result.status,
            errorCode = result.errorCode,
            resultPayload = result.resultPayload,
        )
        if (reported) {
            engine.acknowledge(command, result)
        }
        return result
    }

    private fun parseEffectClass(raw: String): EffectClass =
        when (raw.trim().lowercase()) {
            "readonly" -> EffectClass.READONLY
            "non_idempotent" -> EffectClass.NON_IDEMPOTENT
            else -> EffectClass.IDEMPOTENT
        }
}

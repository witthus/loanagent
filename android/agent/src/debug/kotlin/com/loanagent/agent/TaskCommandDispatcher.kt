package com.loanagent.agent

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TaskCommandDispatcher(
    private val engine: PlaybookEngine,
    private val reporter: TaskEventSink,
    private val preparePublishParams: ((Map<String, Any?>) -> Map<String, Any?>?)? = null,
    private val preparePublishParamsWithContext:
        ((Map<String, Any?>, TaskExecutionContext) -> Map<String, Any?>?)? = null,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val timeoutScheduler: CommandTimeoutScheduler = SharedCommandTimeoutScheduler,
) {
    private val generations = AtomicLong()

    constructor(
        engine: PlaybookEngine,
        reporter: TaskEventSink,
        context: Context,
    ) : this(
        engine = engine,
        reporter = reporter,
        preparePublishParamsWithContext = { params, execution ->
            MediaBridge.preparePublishParams(context, params, execution)
        },
    )

    fun handleMqttPayload(
        payload: String,
        receivedAtMillis: Long = clock.nowMillis(),
    ): PlaybookResult? {
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

        val command = PlaybookCommand(
            taskId = taskId,
            playbook = playbook,
            params = params,
            effectClass = effectClass ?: EffectClass.NON_IDEMPOTENT,
            timeoutSec = timeoutSec,
            operationId = json.optString("operation_id").trim().ifEmpty { null },
            accountId = json.optString("account_id").trim().ifEmpty { null },
        )

        val timeoutMillis = resolveTimeoutMillis(playbook, timeoutSec)
        val deadline = saturatingAdd(receivedAtMillis, timeoutMillis)
        val generation = generations.incrementAndGet()
        val execution = TaskExecutionContext(generation, deadline, clock)
        val timeoutHandle = timeoutScheduler.schedule(
            (deadline - clock.nowMillis()).coerceAtLeast(0),
        ) {
            execution.timeout(generation)
        }
        return try {
            when (val claim = engine.claim(command)) {
                LedgerClaim.InFlight -> null
                LedgerClaim.CommitFailed -> PlaybookResult.failed(taskId, "LEDGER_COMMIT_FAILED")
                is LedgerClaim.Corrupt -> {
                    try {
                        val unknown = PlaybookResult.unknown(taskId)
                        try {
                            persistFinalAndReport(command, unknown, execution)
                        } catch (_: TaskExecutionCancelledException) {
                            persistFinalAndReport(command, unknown, cancellationWinner = true)
                        }
                        unknown
                    } finally {
                        engine.releaseClaim(taskId)
                    }
                }
                is LedgerClaim.Existing -> {
                    try {
                        recover(command, claim.entry, execution)
                    } finally {
                        engine.releaseClaim(taskId)
                    }
                }
                LedgerClaim.Acquired -> {
                    try {
                        if (effectClass == null) {
                            val failed = PlaybookResult.failed(taskId, "INVALID_EFFECT_CLASS")
                            try {
                                persistFinalAndReport(command, failed, execution)
                                failed
                            } catch (_: TaskExecutionCancelledException) {
                                val cancelled = engine.resultForCancellation(command, execution)
                                persistFinalAndReport(
                                    command,
                                    cancelled,
                                    cancellationWinner = true,
                                )
                                cancelled
                            }
                        } else {
                            execute(command, execution)
                        }
                    } finally {
                        engine.releaseClaim(taskId)
                    }
                }
            }
        } finally {
            execution.close()
            timeoutHandle.cancel()
        }
    }

    fun recoverPending() {
        engine.pendingRecovery().forEach { pending ->
            val command = PlaybookCommand(
                taskId = pending.taskId,
                playbook = pending.playbook,
                effectClass = pending.effectClass,
            )
            when (val claim = engine.claim(command)) {
                is LedgerClaim.Existing -> {
                    try {
                        recoverAtStartup(command, claim.entry)
                    } finally {
                        engine.releaseClaim(command.taskId)
                    }
                }
                LedgerClaim.Acquired,
                LedgerClaim.CommitFailed,
                LedgerClaim.InFlight,
                -> Unit
                is LedgerClaim.Corrupt -> {
                    try {
                        persistFinalAndReport(
                            command,
                            PlaybookResult.unknown(command.taskId),
                        )
                    } finally {
                        engine.releaseClaim(command.taskId)
                    }
                }
            }
        }
    }

    private fun execute(
        original: PlaybookCommand,
        execution: TaskExecutionContext,
    ): PlaybookResult {
        try {
            execution.check()
            val executingReported = reporter.report(
                original.taskId,
                "executing",
                null,
                null,
                execution,
            )
            execution.check()
            if (!executingReported) {
                val failed = PlaybookResult.failed(original.taskId, "EXECUTING_REPORT_FAILED")
                persistFinalAndReport(original, failed, execution)
                return failed
            }

            val preparedParams = prepareParams(original, execution)
            execution.check()
            val command: PlaybookCommand
            val result: PlaybookResult
            if (preparedParams == null) {
                command = original
                result = PlaybookResult.failed(
                    original.taskId,
                    "MEDIA_MISSING",
                )
            } else {
                command = original.copy(params = preparedParams)
                result = normalizeResult(command, engine.executeClaimed(command, execution))
            }

            if (result.effectCommitted) {
                execution.markEffectCommitted()
                execution.check()
                if (!engine.storeEffectCommitted(command, result)) {
                    return PlaybookResult.unknown(command.taskId, effectCommitted = true)
                }
                execution.check()
                if (
                    !reporter.report(
                        command.taskId,
                        "effect_committed",
                        null,
                        null,
                        execution,
                    )
                ) {
                    return result
                }
                execution.check()
            }

            execution.check()
            if (!engine.storeFinal(command, result)) {
                return result
            }
            // Win completion CAS before any terminal HTTP report so a late timeout cannot
            // claim cancellation after the cloud already observed a durable terminal status.
            execution.check()
            if (!execution.complete()) {
                execution.check()
                return result
            }
            reportDurableResult(result, execution)
            return result
        } catch (_: TaskExecutionCancelledException) {
            engine.ledgerEntry(original.taskId)
                ?.takeIf { it.stage == LedgerStage.FINAL || it.stage == LedgerStage.CORRUPT }
                ?.result
                ?.let { return it }
            val cancelled = engine.resultForCancellation(original, execution)
            persistFinalAndReport(original, cancelled, cancellationWinner = true)
            return cancelled
        }
    }

    private fun recover(
        command: PlaybookCommand,
        entry: LedgerEntry,
        execution: TaskExecutionContext,
    ): PlaybookResult {
        val persistedCommand = command.copy(
            playbook = entry.playbook,
            effectClass = entry.effectClass,
        )
        return try {
            execution.check()
            when (entry.stage) {
                LedgerStage.STARTED -> {
                    val recovered = if (entry.effectClass == EffectClass.NON_IDEMPOTENT) {
                        PlaybookResult.unknown(command.taskId)
                    } else {
                        PlaybookResult.failed(command.taskId, "EXECUTION_INTERRUPTED")
                    }
                    persistFinalAndReport(persistedCommand, recovered, execution)
                    recovered
                }
                LedgerStage.EFFECT_COMMITTED -> {
                    val saved = normalizeResult(
                        persistedCommand,
                        entry.result ?: PlaybookResult.unknown(
                            command.taskId,
                            effectCommitted = true,
                        ),
                    )
                    execution.check()
                    if (
                        reporter.report(
                            command.taskId,
                            "effect_committed",
                            null,
                            null,
                            execution,
                        )
                    ) {
                        execution.check()
                        persistFinalAndReport(persistedCommand, saved, execution)
                    }
                    saved
                }
                LedgerStage.FINAL -> {
                    val saved = normalizeResult(
                        persistedCommand,
                        entry.result ?: PlaybookResult.failed(
                            command.taskId,
                            "RECOVERY_RESULT_MISSING",
                        ),
                    )
                    execution.check()
                    if (!execution.complete()) execution.check()
                    reportDurableResult(saved, execution)
                    saved
                }
                LedgerStage.CORRUPT -> {
                    val saved = entry.result ?: PlaybookResult.unknown(command.taskId)
                    execution.check()
                    if (!execution.complete()) execution.check()
                    reportDurableResult(saved, execution)
                    saved
                }
            }
        } catch (_: TaskExecutionCancelledException) {
            if (entry.stage == LedgerStage.FINAL || entry.stage == LedgerStage.CORRUPT) {
                return entry.result ?: PlaybookResult.unknown(command.taskId)
            }
            val cancelled = engine.resultForCancellation(persistedCommand, execution)
            persistFinalAndReport(
                persistedCommand,
                cancelled,
                cancellationWinner = true,
            )
            cancelled
        }
    }

    private fun recoverAtStartup(
        command: PlaybookCommand,
        entry: LedgerEntry,
    ) {
        when (entry.stage) {
            LedgerStage.STARTED -> {
                val recovered = if (entry.effectClass == EffectClass.NON_IDEMPOTENT) {
                    PlaybookResult.unknown(command.taskId)
                } else {
                    PlaybookResult.failed(command.taskId, "EXECUTION_INTERRUPTED")
                }
                persistFinalAndReport(command, recovered)
            }
            LedgerStage.EFFECT_COMMITTED -> {
                val saved = entry.result ?: PlaybookResult.unknown(
                    command.taskId,
                    effectCommitted = true,
                )
                if (reporter.report(command.taskId, "effect_committed", null, null)) {
                    persistFinalAndReport(command, saved)
                }
            }
            LedgerStage.FINAL -> {
                val saved = entry.result ?: PlaybookResult.failed(
                    command.taskId,
                    "RECOVERY_RESULT_MISSING",
                )
                reportDurableResult(saved)
            }
            LedgerStage.CORRUPT -> {
                reportDurableResult(entry.result ?: PlaybookResult.unknown(command.taskId))
            }
        }
    }

    private fun prepareParams(
        command: PlaybookCommand,
        execution: TaskExecutionContext,
    ): Map<String, Any?>? {
        if (command.playbookBase() != "publish_note") return command.params
        preparePublishParamsWithContext?.let { return it(command.params, execution) }
        return preparePublishParams?.invoke(command.params) ?: command.params.takeIf {
            preparePublishParams == null
        }
    }

    private fun normalizeResult(
        command: PlaybookCommand,
        result: PlaybookResult,
    ): PlaybookResult {
        val payload = result.resultPayload ?: return result
        if (result.status != "succeeded") return result.copy(resultPayload = null)
        return if (TaskResultPayloadPolicy.accepts(command.playbookBase(), payload)) {
            result
        } else {
            PlaybookResult.failed(
                command.taskId,
                "INVALID_RESULT_PAYLOAD",
                effectCommitted = result.effectCommitted,
            )
        }
    }

    private fun reportResult(
        result: PlaybookResult,
        execution: TaskExecutionContext? = null,
    ): Boolean =
        if (execution == null) {
            reporter.report(
                taskId = result.taskId,
                status = result.status,
                errorCode = result.errorCode,
                resultPayload = result.resultPayload.takeIf { result.status == "succeeded" },
            )
        } else {
            reporter.report(
                taskId = result.taskId,
                status = result.status,
                errorCode = result.errorCode,
                resultPayload = result.resultPayload.takeIf { result.status == "succeeded" },
                execution = execution,
            )
        }

    private fun persistFinalAndReport(
        command: PlaybookCommand,
        result: PlaybookResult,
        execution: TaskExecutionContext? = null,
        cancellationWinner: Boolean = false,
    ): Boolean {
        if (!cancellationWinner) execution?.check()
        if (!engine.storeFinal(command, result)) return false
        if (!cancellationWinner && execution != null) {
            execution.check()
            if (!execution.complete()) {
                execution.check()
                return false
            }
            return reportDurableResult(result, execution)
        }
        return reportDurableResult(result)
    }

    private fun reportDurableResult(
        result: PlaybookResult,
        execution: TaskExecutionContext? = null,
    ): Boolean {
        if (!reportResult(result, execution)) return false
        return engine.markReported(result.taskId)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun resolveTimeoutMillis(playbook: String, timeoutSec: Int?): Long {
        val requestedSec = (timeoutSec ?: DEFAULT_TIMEOUT_SEC).coerceAtLeast(1)
        val cappedSec = when (playbook.substringBefore('@')) {
            // Server default is the global execution budget (900s). Quick readiness checks
            // must not hold the device executor that long when XHS is wedged.
            "ensure_app_ready", "dismiss_interruptions" ->
                minOf(requestedSec, ENSURE_APP_READY_TIMEOUT_SEC)
            else -> requestedSec
        }
        return cappedSec.toLong().coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SEC = 900
        private const val ENSURE_APP_READY_TIMEOUT_SEC = 60
    }

    private fun parseEffectClass(raw: String): EffectClass? =
        when (raw.trim().lowercase()) {
            "readonly" -> EffectClass.READONLY
            "idempotent" -> EffectClass.IDEMPOTENT
            "non_idempotent" -> EffectClass.NON_IDEMPOTENT
            else -> null
        }
}

fun interface CommandTimeoutScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): RequestHandle
}

class ExecutorCommandTimeoutScheduler(
    private val scheduler: ScheduledExecutorService,
) : CommandTimeoutScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): RequestHandle {
        val future = scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return RequestHandle { future.cancel(false) }
    }
}

private object SharedCommandTimeoutScheduler : CommandTimeoutScheduler {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "command-timeout").apply { isDaemon = true }
    }
    private val delegate = ExecutorCommandTimeoutScheduler(scheduler)

    override fun schedule(delayMs: Long, task: () -> Unit): RequestHandle =
        delegate.schedule(delayMs, task)
}

private object TaskResultPayloadPolicy {
    private const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private const val MAX_ITEMS = 100
    private const val MAX_MESSAGES = 200
    private const val MAX_REPLIES = 100
    private const val MAX_SUMMARY = 512
    private const val MAX_REFERENCE = 256
    private const val MAX_LOCATOR = 512

    fun accepts(playbook: String, payload: Map<String, Any?>): Boolean =
        runCatching {
            require(
                TaskEventReporter.toJsonValue(payload)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                    .size <= MAX_PAYLOAD_BYTES,
            )
            when (playbook) {
                "read_comments" -> validateComments(payload)
                "sync_notes" -> validateNotes(payload)
                "inbox_sync" -> validateInbox(payload)
                "inbox_open_thread" -> validateThread(payload)
                "publish_note" -> validatePublish(payload)
                else -> error("playbook does not allow result payload")
            }
        }.isSuccess

    private fun validateComments(payload: Map<String, Any?>) {
        require(payload["kind"] == "comments")
        requireAllowed(payload, setOf("kind", "items", "note_ref", "note_id"))
        optionalString(payload, "note_ref", MAX_REFERENCE)
        optionalString(payload, "note_id", MAX_REFERENCE)
        objectList(payload, "items", MAX_ITEMS).forEach { validateComment(it, allowReplies = true) }
    }

    private fun validateNotes(payload: Map<String, Any?>) {
        require(payload["kind"] == "notes")
        requireAllowed(payload, setOf("kind", "items"))
        objectList(payload, "items", MAX_ITEMS).forEach { item ->
            requireAllowed(
                item,
                setOf(
                    "title_summary",
                    "like_count",
                    "collect_count",
                    "read_count",
                    "locator_hint",
                ),
            )
            requiredString(item, "title_summary", MAX_SUMMARY)
            optionalString(item, "locator_hint", MAX_LOCATOR)
            for (key in listOf("like_count", "collect_count", "read_count")) {
                optionalNonnegativeInt(item, key)
            }
        }
    }

    private fun validateInbox(payload: Map<String, Any?>) {
        require(payload["kind"] == "inbox")
        requireAllowed(payload, setOf("kind", "threads"))
        objectList(payload, "threads", MAX_ITEMS).forEach { thread ->
            requireAllowed(
                thread,
                setOf(
                    "title_summary",
                    "preview_summary",
                    "unread",
                    "locator_hint",
                    "messages",
                ),
            )
            requiredString(thread, "title_summary", MAX_SUMMARY)
            optionalString(thread, "preview_summary", MAX_SUMMARY)
            optionalString(thread, "locator_hint", MAX_LOCATOR)
            if ("unread" in thread) require(thread["unread"] is Boolean)
            if ("messages" in thread) validateMessages(thread, "messages")
        }
    }

    private fun validateThread(payload: Map<String, Any?>) {
        require(payload["kind"] == "thread")
        requireAllowed(payload, setOf("kind", "thread_id", "messages"))
        optionalString(payload, "thread_id", MAX_REFERENCE)
        require(validateMessages(payload, "messages").isNotEmpty())
    }

    private fun validatePublish(payload: Map<String, Any?>) {
        require(payload["kind"] == "publish")
        requireAllowed(
            payload,
            setOf("kind", "note_id", "note_ref", "title_summary", "content_id", "xhs_hint"),
        )
        requiredString(payload, "title_summary", MAX_SUMMARY)
        for (key in listOf("note_id", "note_ref", "content_id", "xhs_hint")) {
            optionalString(payload, key, MAX_REFERENCE)
        }
    }

    private fun validateComment(comment: Map<String, Any?>, allowReplies: Boolean) {
        val allowed = mutableSetOf(
            "author_summary",
            "body_summary",
            "locator_hint",
            "posted_at_text",
            "reply_to_author",
        )
        if (allowReplies) allowed += "replies"
        requireAllowed(comment, allowed)
        optionalString(comment, "author_summary", MAX_SUMMARY)
        optionalString(comment, "body_summary", MAX_SUMMARY)
        optionalString(comment, "locator_hint", MAX_LOCATOR)
        optionalString(comment, "posted_at_text", MAX_REFERENCE)
        optionalString(comment, "reply_to_author", MAX_SUMMARY)
        require(
            listOf("author_summary", "body_summary").any {
                (comment[it] as? String)?.isNotBlank() == true
            },
        )
        if (allowReplies && "replies" in comment) {
            objectList(comment, "replies", MAX_REPLIES).forEach {
                validateComment(it, allowReplies = false)
            }
        }
    }

    private fun validateMessages(
        value: Map<String, Any?>,
        key: String,
    ): List<Map<String, Any?>> =
        objectList(value, key, MAX_MESSAGES).also { messages ->
            messages.forEach { message ->
                requireAllowed(message, setOf("sender_summary", "body_summary", "posted_at_text"))
                optionalString(message, "sender_summary", MAX_SUMMARY)
                requiredString(message, "body_summary", MAX_SUMMARY)
                optionalString(message, "posted_at_text", MAX_REFERENCE)
            }
        }

    private fun objectList(
        value: Map<String, Any?>,
        key: String,
        maximum: Int,
    ): List<Map<String, Any?>> {
        val items = value[key] as? List<*> ?: error("$key must be a list")
        require(items.size <= maximum)
        return items.map { item ->
            val raw = item as? Map<*, *> ?: error("$key item must be an object")
            require(raw.keys.all { it is String })
            @Suppress("UNCHECKED_CAST")
            raw as Map<String, Any?>
        }
    }

    private fun requireAllowed(value: Map<String, Any?>, allowed: Set<String>) {
        require(value.keys.all { it in allowed })
    }

    private fun requiredString(value: Map<String, Any?>, key: String, maximum: Int) {
        val field = value[key] as? String ?: error("$key is required")
        require(field.isNotBlank())
        require(field.length <= maximum)
    }

    private fun optionalString(value: Map<String, Any?>, key: String, maximum: Int) {
        if (key !in value || value[key] == null) return
        val field = value[key] as? String ?: error("$key must be a string")
        require(field.length <= maximum)
    }

    private fun optionalNonnegativeInt(value: Map<String, Any?>, key: String) {
        if (key !in value || value[key] == null) return
        val field = value[key]
        val integer = when (field) {
            is Byte -> field.toLong()
            is Short -> field.toLong()
            is Int -> field.toLong()
            is Long -> field
            else -> error("$key must be an integer")
        }
        require(integer in 0..Int.MAX_VALUE.toLong())
    }
}

package com.loanagent.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class M0DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        M0DebugKeepAliveService.start(context)
        val pendingResult = goAsync()
        M0DebugBridge(
            controllerProvider = { M0AccessibilityService.instance },
            writer = AtomicDebugResultWriter(File(context.filesDir, RESULT_FILE_NAME)),
            scheduler = HandlerDebugTimeoutScheduler(Handler(Looper.getMainLooper())),
            cacheClearer = { DiagnosticCache(context).clear() },
        ).execute(intent) {
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION = "com.loanagent.agent.action.M0_DEBUG_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SELECTOR = "selector"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CONDITION = "condition"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        const val EXTRA_CONFIRMED = "confirmed"
        const val EXTRA_ALLOW_FINAL_ACTION = "allow_final_action"
        const val EXTRA_START_X = "start_x"
        const val EXTRA_START_Y = "start_y"
        const val EXTRA_END_X = "end_x"
        const val EXTRA_END_Y = "end_y"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val RESULT_FILE_NAME = "m0-debug-result.json"
    }
}

internal fun interface DebugResultWriter {
    fun write(json: String)
}

internal class AtomicDebugResultWriter(
    resultFile: File,
) : DebugResultWriter {
    private val atomicFile = AtomicFile(resultFile)

    override fun write(json: String) {
        resultFileParent().mkdirs()
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(json.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: IOException) {
            output?.let(atomicFile::failWrite)
            throw error
        } catch (error: RuntimeException) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun resultFileParent(): File =
        requireNotNull(atomicFile.baseFile.parentFile) { "Result file must have a parent" }
}

internal fun interface DebugTimeoutScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): RequestHandle
}

private class HandlerDebugTimeoutScheduler(
    private val handler: Handler,
) : DebugTimeoutScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): RequestHandle {
        val runnable = Runnable(task)
        if (!handler.postDelayed(runnable, delayMs)) {
            task()
            return CompletedRequestHandle
        }
        return RequestHandle { handler.removeCallbacks(runnable) }
    }
}

internal class M0DebugBridge(
    private val controllerProvider: () -> M0DiagnosticController?,
    private val writer: DebugResultWriter,
    private val scheduler: DebugTimeoutScheduler,
    private val cacheClearer: (() -> Int)? = null,
) {
    fun execute(intent: Intent, onFinished: () -> Unit) {
        val commandName = intent.getStringExtra(M0DebugCommandReceiver.EXTRA_COMMAND)
            ?.trim()
            ?.uppercase(Locale.ROOT)
        val safeCommandName = commandName?.takeIf { candidate ->
            DebugCommand.entries.any { it.name == candidate }
        }
        val activeRequest = AtomicReference<RequestHandle?>()
        val watchdog = AtomicReference<RequestHandle?>()
        val timedOut = AtomicBoolean(false)
        val terminal = OnceTerminal<String> { json ->
            watchdog.getAndSet(null)?.cancel()
            activeRequest.getAndSet(null)
            try {
                writer.write(json)
            } catch (_: IOException) {
                // The bridge has no public or log fallback for sensitive diagnostic results.
            } catch (_: RuntimeException) {
                // The bridge has no public or log fallback for sensitive diagnostic results.
            } finally {
                onFinished()
            }
        }

        fun finish(status: String, payload: String? = null) {
            terminal.complete(DebugBridgeJson.result(safeCommandName, status, payload))
        }

        if (intent.action != M0DebugCommandReceiver.ACTION) {
            finish("INVALID_ACTION")
            return
        }
        val command = try {
            DebugCommand.valueOf(commandName ?: "")
        } catch (_: IllegalArgumentException) {
            finish("UNSUPPORTED_COMMAND")
            return
        }
        if (
            (
                command == DebugCommand.CLICK ||
                    command == DebugCommand.SET_TEXT ||
                    command == DebugCommand.SWIPE ||
                    command == DebugCommand.OCR_SAVE
                ) &&
            !intent.getBooleanExtra(M0DebugCommandReceiver.EXTRA_CONFIRMED, false)
        ) {
            finish("CONFIRMATION_REQUIRED")
            return
        }

        if (command == DebugCommand.CLEAR_CACHE) {
            val clearer = cacheClearer
            if (clearer == null) {
                finish("CACHE_CLEAR_UNAVAILABLE")
                return
            }
            finish("SUCCESS", "\"cleared\":${clearer()}")
            return
        }

        val timeoutMs = try {
            parseTimeout(intent)
        } catch (_: IllegalArgumentException) {
            finish("INVALID_ARGUMENT")
            return
        }
        val watchdogHandle = scheduler.schedule((timeoutMs + WATCHDOG_GRACE_MS).coerceAtMost(MAX_WATCHDOG_MS)) {
            timedOut.set(true)
            val request = activeRequest.getAndSet(null)
            if (terminal.complete(DebugBridgeJson.result(safeCommandName, "BRIDGE_TIMEOUT", null))) {
                request?.cancel()
            }
        }
        watchdog.set(watchdogHandle)
        if (terminal.isCompleted()) {
            watchdog.getAndSet(null)?.cancel()
            return
        }

        val controller = controllerProvider()
        if (controller == null) {
            finish("ACCESSIBILITY_DISABLED")
            return
        }
        val lease = controller.currentLease()
        if (lease == null || lease.packageName !in ALLOWED_TARGETS) {
            finish("NO_TARGET_LEASE")
            return
        }

        val handle = try {
            when (command) {
                DebugCommand.OBSERVE -> {
                    val snapshot = controller.observe(lease)
                    if (snapshot == null) {
                        finish("TARGET_LEASE_LOST")
                    } else {
                        finish("SUCCESS", "\"snapshot\":${SnapshotJson.encode(snapshot)}")
                    }
                    CompletedRequestHandle
                }
                DebugCommand.EXTRACT_COMMENTS,
                DebugCommand.EXTRACT_INBOX,
                DebugCommand.EXTRACT_THREAD,
                -> executeExtraction(command, controller, lease, ::finish)
                DebugCommand.CLICK,
                DebugCommand.SET_TEXT,
                -> executeAction(command, intent, controller, lease, timeoutMs, ::finish)
                DebugCommand.SWIPE -> executeSwipe(intent, controller, lease, timeoutMs, ::finish)
                DebugCommand.WAIT -> executeWait(intent, controller, lease, timeoutMs, ::finish)
                DebugCommand.OCR_MEMORY,
                DebugCommand.OCR_SAVE,
                -> controller.runVisualDiagnostic(
                    VisualDiagnosticRequest(
                        expectedLease = lease,
                        saveOriginal = command == DebugCommand.OCR_SAVE,
                        timeoutMs = timeoutMs,
                    ),
                ) { result ->
                    // Never persist OCR body in the debug result file; length + status only.
                    val redactedLength = SensitiveTextRedactor()
                        .redact(result.ocrText, false)
                        ?.take(MAX_OCR_RESULT_LENGTH)
                        ?.length
                        ?: 0
                    val payload = buildString {
                        append("\"ocr_status\":")
                        DebugBridgeJson.appendQuoted(this, result.status)
                        append(",\"ocr_text_length\":")
                        append(redactedLength)
                        append(",\"saved_file\":")
                        DebugBridgeJson.appendNullableQuoted(this, result.screenshot?.name)
                    }
                    finish(if (result.status == "SUCCESS") "SUCCESS" else "OCR_FAILED", payload)
                }
                DebugCommand.CLEAR_CACHE -> CompletedRequestHandle
            }
        } catch (_: IllegalArgumentException) {
            finish("INVALID_ARGUMENT")
            CompletedRequestHandle
        } catch (_: RuntimeException) {
            finish("COMMAND_FAILED")
            CompletedRequestHandle
        }
        if (!terminal.isCompleted()) {
            activeRequest.set(handle)
        } else if (timedOut.get()) {
            handle.cancel()
        }
    }

    private fun executeExtraction(
        command: DebugCommand,
        controller: M0DiagnosticController,
        lease: TargetLease,
        finish: (String, String?) -> Unit,
    ): RequestHandle {
        val snapshot = controller.observe(lease)
        if (snapshot == null) {
            finish("TARGET_LEASE_LOST", null)
            return CompletedRequestHandle
        }
        val extractor = ContentExtractors()
        val payload = buildString {
            append("\"page_hint\":")
            DebugBridgeJson.appendQuoted(this, snapshot.pageHint.name)
            append(",")
            when (command) {
                DebugCommand.EXTRACT_COMMENTS -> appendComments(
                    extractor.extractComments(snapshot.nodes, MAX_EXTRACT_ITEMS),
                )
                DebugCommand.EXTRACT_INBOX -> appendInboxThreads(
                    extractor.extractInboxThreads(snapshot.nodes, MAX_EXTRACT_ITEMS),
                )
                DebugCommand.EXTRACT_THREAD -> appendDmMessages(
                    extractor.extractDmMessages(snapshot.nodes, MAX_EXTRACT_ITEMS),
                )
                else -> throw IllegalArgumentException("Unsupported extract command")
            }
        }
        finish("SUCCESS", payload)
        return CompletedRequestHandle
    }

    private fun StringBuilder.appendComments(items: List<ExtractedComment>) {
        append("\"count\":")
        append(items.size)
        append(",\"items\":[")
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append('{')
            append("\"author_summary\":")
            DebugBridgeJson.appendQuoted(this, item.authorSummary)
            append(",\"body_summary\":")
            DebugBridgeJson.appendQuoted(this, item.bodySummary)
            append(",\"locator_hint\":")
            DebugBridgeJson.appendNullableQuoted(this, item.locatorHint)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendInboxThreads(items: List<ExtractedInboxThread>) {
        append("\"count\":")
        append(items.size)
        append(",\"items\":[")
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append('{')
            append("\"title_summary\":")
            DebugBridgeJson.appendQuoted(this, item.titleSummary)
            append(",\"preview_summary\":")
            DebugBridgeJson.appendQuoted(this, item.previewSummary)
            append(",\"unread_hint\":")
            append(item.unreadHint)
            append(",\"locator_hint\":")
            DebugBridgeJson.appendNullableQuoted(this, item.locatorHint)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendDmMessages(items: List<ExtractedDmMessage>) {
        append("\"count\":")
        append(items.size)
        append(",\"items\":[")
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append('{')
            append("\"sender_summary\":")
            DebugBridgeJson.appendQuoted(this, item.senderSummary)
            append(",\"body_summary\":")
            DebugBridgeJson.appendQuoted(this, item.bodySummary)
            append('}')
        }
        append(']')
    }

    private fun executeAction(
        command: DebugCommand,
        intent: Intent,
        controller: M0DiagnosticController,
        lease: TargetLease,
        timeoutMs: Long,
        finish: (String, String?) -> Unit,
    ): RequestHandle {
        val rawSelector = intent.getStringExtra(M0DebugCommandReceiver.EXTRA_SELECTOR)
            ?: throw IllegalArgumentException("Missing selector")
        val selector = StrictSelectorParser.parse(rawSelector)
        if (command == DebugCommand.CLICK && FinalActionPolicy.blocks(selector)) {
            val allowFinal = intent.getBooleanExtra(M0DebugCommandReceiver.EXTRA_ALLOW_FINAL_ACTION, false)
            val confirmed = intent.getBooleanExtra(M0DebugCommandReceiver.EXTRA_CONFIRMED, false)
            if (!(allowFinal && confirmed)) {
                finish("FINAL_ACTION_UNSUPPORTED", null)
                return CompletedRequestHandle
            }
        }
        val text = if (command == DebugCommand.SET_TEXT) {
            intent.getStringExtra(M0DebugCommandReceiver.EXTRA_TEXT)
                ?: throw IllegalArgumentException("Missing text")
        } else {
            null
        }
        return controller.executeAction(
            ActionRequest(
                action = if (command == DebugCommand.CLICK) M0Action.CLICK else M0Action.SET_TEXT,
                expectedLease = lease,
                selector = selector,
                text = text,
                timeoutMs = timeoutMs,
            ),
        ) { result ->
            val payload = buildString {
                append("\"action_status\":")
                DebugBridgeJson.appendQuoted(this, result.status.name)
                append(",\"stage\":")
                DebugBridgeJson.appendQuoted(this, result.stage.name)
                append(",\"path\":")
                DebugBridgeJson.appendQuoted(this, result.path.name)
                append(",\"fallback\":${result.fallbackUsed},\"message\":")
                DebugBridgeJson.appendQuoted(this, result.message)
            }
            finish(if (result.status == ActionStatus.SUCCESS) "SUCCESS" else "ACTION_REJECTED", payload)
        }
    }

    private fun executeSwipe(
        intent: Intent,
        controller: M0DiagnosticController,
        lease: TargetLease,
        timeoutMs: Long,
        finish: (String, String?) -> Unit,
    ): RequestHandle {
        val swipe = SwipeSpec(
            startX = requireCoordinate(intent, M0DebugCommandReceiver.EXTRA_START_X),
            startY = requireCoordinate(intent, M0DebugCommandReceiver.EXTRA_START_Y),
            endX = requireCoordinate(intent, M0DebugCommandReceiver.EXTRA_END_X),
            endY = requireCoordinate(intent, M0DebugCommandReceiver.EXTRA_END_Y),
            durationMs = parseDuration(intent),
        )
        return controller.executeAction(
            ActionRequest(
                action = M0Action.SWIPE,
                expectedLease = lease,
                swipe = swipe,
                timeoutMs = timeoutMs,
            ),
        ) { result ->
            val payload = buildString {
                append("\"action_status\":")
                DebugBridgeJson.appendQuoted(this, result.status.name)
                append(",\"stage\":")
                DebugBridgeJson.appendQuoted(this, result.stage.name)
                append(",\"path\":")
                DebugBridgeJson.appendQuoted(this, result.path.name)
                append(",\"fallback\":${result.fallbackUsed},\"message\":")
                DebugBridgeJson.appendQuoted(this, result.message)
            }
            finish(if (result.status == ActionStatus.SUCCESS) "SUCCESS" else "ACTION_REJECTED", payload)
        }
    }

    private fun executeWait(
        intent: Intent,
        controller: M0DiagnosticController,
        lease: TargetLease,
        timeoutMs: Long,
        finish: (String, String?) -> Unit,
    ): RequestHandle {
        val rawCondition = intent.getStringExtra(M0DebugCommandReceiver.EXTRA_CONDITION)
            ?.trim()
            ?: throw IllegalArgumentException("Missing condition")
        val condition = when {
            rawCondition == "appears" -> WaitCondition.SelectorAppears(
                StrictSelectorParser.parse(
                    intent.getStringExtra(M0DebugCommandReceiver.EXTRA_SELECTOR)
                        ?: throw IllegalArgumentException("Missing selector"),
                ),
            )
            rawCondition == "disappears" -> WaitCondition.SelectorDisappears(
                StrictSelectorParser.parse(
                    intent.getStringExtra(M0DebugCommandReceiver.EXTRA_SELECTOR)
                        ?: throw IllegalArgumentException("Missing selector"),
                ),
            )
            rawCondition.startsWith("pageChange:") -> WaitCondition.PageHintChanges(
                PageHint.valueOf(rawCondition.substringAfter(':').trim()),
            )
            else -> throw IllegalArgumentException("Unknown wait condition")
        }
        return controller.waitForCondition(lease, condition, timeoutMs) { result ->
            val payload = "\"wait_status\":\"${result.status.name}\"," +
                "\"checks\":${result.checks},\"elapsed_ms\":${result.elapsedMs}"
            finish(if (result.status == WaitStatus.MET) "SUCCESS" else "WAIT_ENDED", payload)
        }
    }

    @Suppress("DEPRECATION")
    private fun parseTimeout(intent: Intent): Long {
        val raw = intent.extras?.get(M0DebugCommandReceiver.EXTRA_TIMEOUT_MS)
        return when (raw) {
            null -> DEFAULT_TIMEOUT_MS
            is Number -> raw.toLong().coerceIn(MIN_TIMEOUT_MS, MAX_COMMAND_TIMEOUT_MS)
            else -> throw IllegalArgumentException("Invalid timeout")
        }
    }

    @Suppress("DEPRECATION")
    private fun requireCoordinate(intent: Intent, key: String): Int {
        val raw = intent.extras?.get(key) ?: throw IllegalArgumentException("Missing $key")
        return when (raw) {
            is Number -> raw.toInt()
            else -> throw IllegalArgumentException("Invalid $key")
        }
    }

    @Suppress("DEPRECATION")
    private fun parseDuration(intent: Intent): Long {
        val raw = intent.extras?.get(M0DebugCommandReceiver.EXTRA_DURATION_MS)
        return when (raw) {
            null -> DEFAULT_SWIPE_DURATION_MS
            is Number -> raw.toLong().coerceIn(50L, 2_000L)
            else -> throw IllegalArgumentException("Invalid duration")
        }
    }

    private enum class DebugCommand {
        OBSERVE,
        EXTRACT_COMMENTS,
        EXTRACT_INBOX,
        EXTRACT_THREAD,
        CLICK,
        SET_TEXT,
        SWIPE,
        WAIT,
        OCR_MEMORY,
        OCR_SAVE,
        CLEAR_CACHE,
    }

    private object FinalActionPolicy {
        private val exactTerms = setOf(
            "发布",
            "发布笔记",
            "确认发布",
            "发送",
            "发送评论",
            "发送私信",
            "发表",
            "提交",
        )
        private val idTerms = listOf("publish", "send", "submit")

        fun blocks(selector: Selector): Boolean =
            selector.text?.trim() in exactTerms ||
                selector.contentDescription?.trim() in exactTerms ||
                selector.viewId?.lowercase(Locale.ROOT)?.let { id ->
                    idTerms.any(id::contains)
                } == true
    }

    private companion object {
        val ALLOWED_TARGETS = setOf(AllowedPackagePolicy.XHS, AllowedPackagePolicy.FIXTURE)
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val DEFAULT_SWIPE_DURATION_MS = 500L
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_COMMAND_TIMEOUT_MS = 12_000L
        const val WATCHDOG_GRACE_MS = 2_000L
        const val MAX_WATCHDOG_MS = 15_000L
        const val MAX_OCR_RESULT_LENGTH = 4_000
        const val MAX_EXTRACT_ITEMS = 20
    }
}

private object DebugBridgeJson {
    fun result(command: String?, status: String, payload: String?): String = buildString {
        append("{\"schema_version\":\"m0-debug-1\",\"command\":")
        appendNullableQuoted(this, command)
        append(",\"status\":")
        appendQuoted(this, status)
        if (payload != null) {
            append(',')
            append(payload)
        }
        append('}')
    }

    fun appendNullableQuoted(builder: StringBuilder, value: String?) {
        if (value == null) builder.append("null") else appendQuoted(builder, value)
    }

    fun appendQuoted(builder: StringBuilder, value: String) {
        builder.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (character.code < 0x20) {
                    builder.append("\\u%04x".format(character.code))
                } else {
                    builder.append(character)
                }
            }
        }
        builder.append('"')
    }
}

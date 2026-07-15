package com.loanagent.agent

import android.util.Log

/**
 * Graph-note publish. Params: title, body; optional start_in_editor (skip album),
 * album_tap_x / album_tap_y (absolute px for first-grid pick when not start_in_editor).
 */
class PublishNotePlaybook : Playbook {
    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult {
        val taskId = command.taskId
        val title = command.stringParam("title")
            ?: return PlaybookResult.failed(taskId, "MISSING_TITLE")
        val body = command.stringParam("body")
            ?: return PlaybookResult.failed(taskId, "MISSING_BODY")
        if (!runtime.accessibilityAlive()) {
            return PlaybookResult.failed(taskId, "A11Y_DOWN")
        }
        when (val nav = SurfaceNavigator.ensureForeground(runtime)) {
            is NavResult.Failed -> return PlaybookResult.failed(taskId, nav.errorCode)
            NavResult.Ok -> Unit
        }

        val startInEditor = command.boolParam("start_in_editor")
        if (!startInEditor) {
            // Comments / inbox sync often leave XHS on note-detail; publish sheet only opens from
            // the main tab chrome. Back out to home before tapping the center publish tab.
            resetToHomeFeed(runtime)
            if (!openPublishEntry(runtime)) {
                return PlaybookResult.failed(taskId, "PUBLISH_ENTRY_FAILED")
            }
            runtime.sleep(700)
            // Prefer geometric tap — a11y click on sheet rows is unreliable on HyperOS.
            val albumOpened =
                tapLabeled(runtime, "从相册选择") ||
                    runtime.click("text=从相册选择", allowFinal = false, timeoutMs = 3_000) ||
                    runtime.click("text=相册", allowFinal = false, timeoutMs = 2_000) ||
                    runtime.tap(540, 1757)
            if (!albumOpened) {
                return PlaybookResult.failed(taskId, "PUBLISH_ENTRY_FAILED")
            }
            // Wait once for picker + MediaStore refresh (was 2s + 1.5s + 1s).
            runtime.sleep(1_200)
            // Dismiss permission prompts if present.
            runtime.click("text=允许", allowFinal = false, timeoutMs = 1_500) ||
                runtime.click("text=始终允许", allowFinal = false, timeoutMs = 1_500) ||
                runtime.click("text=仅使用期间允许", allowFinal = false, timeoutMs = 1_500)
            runtime.sleep(400)
            val tapX = command.intParam("album_tap_x", 177)
            val tapY = command.intParam("album_tap_y", 532)
            if (!runtime.tap(tapX, tapY)) {
                return PlaybookResult.failed(taskId, "MEDIA_MISSING")
            }
            runtime.sleep(1_000)
            tapLabeled(runtime, "全部")
            runtime.sleep(400)
            var rounds = 0
            while (rounds < 6 && !editorReady(runtime)) {
                val advanced = advancePublishWizard(runtime)
                if (!advanced) break
                runtime.sleep(1_000)
                rounds += 1
            }
        }

        if (!fillPublishField(runtime, TITLE_HINTS, title, editableIndex = 0)) {
            return PlaybookResult.failed(
                taskId,
                if (startInEditor || editorReady(runtime)) "SET_TEXT_FAILED" else "EDITOR_NOT_READY",
            )
        }
        runtime.sleep(200)
        if (!fillPublishField(runtime, BODY_HINTS, body, editableIndex = 1)) {
            return PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
        }
        runtime.sleep(200)
        if (!fieldLooksFilled(runtime, body)) {
            return PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
        }
        runtime.sleep(300)
        runtime.beginSideEffect()
        if (!runtime.click("text=发布笔记", allowFinal = true, timeoutMs = 12_000)) {
            return PlaybookResult.failed(taskId, "FINAL_ACTION_BLOCKED")
        }
        runtime.sleep(1_500)
        return PlaybookResult.succeeded(
            taskId,
            effectCommitted = true,
            resultPayload = mapOf(
                "kind" to "publish",
                "title_summary" to title.take(TITLE_SUMMARY_LIMIT),
            ),
        )
    }

    /**
     * Fill title/body. Prefer already-correct + nth EditText (Wi‑Fi: watch tag PublishFill).
     * Hint matching is a short fallback — XHS often autofills and removes hint labels.
     */
    private fun fillPublishField(
        runtime: PlaybookRuntime,
        hints: List<String>,
        text: String,
        editableIndex: Int,
    ): Boolean {
        val field = if (editableIndex == 0) "title" else "body"
        if (fieldLooksFilled(runtime, text)) {
            Log.i(TAG, "field=$field strategy=already_filled ok=true")
            return true
        }

        if (setTextOnNthEditable(runtime, editableIndex, text, hints, field)) {
            return true
        }
        if (fieldLooksFilled(runtime, text)) {
            Log.i(TAG, "field=$field strategy=already_filled_after_nth ok=true")
            return true
        }

        val hintOk = tryHintSelectors(runtime, hints, text, field, timeoutMs = 2_000)
        if (hintOk && fieldLooksFilled(runtime, text)) return true

        for (hint in hints.take(3)) {
            val focused =
                tapLabeled(runtime, hint) ||
                    runtime.click("text=$hint", allowFinal = false, timeoutMs = 1_200) ||
                    runtime.click("contentDescription=$hint", allowFinal = false, timeoutMs = 1_200)
            if (!focused) continue
            runtime.sleep(350)
            if (tryHintSelectors(runtime, listOf(hint), text, field, timeoutMs = 2_000) &&
                fieldLooksFilled(runtime, text)
            ) {
                return true
            }
            if (
                attemptSetText(
                    runtime,
                    "className=android.widget.EditText",
                    text,
                    field,
                    "focus_classname",
                    timeoutMs = 4_000,
                ) && fieldLooksFilled(runtime, text)
            ) {
                return true
            }
        }

        val finalOk = fieldLooksFilled(runtime, text)
        Log.i(TAG, "field=$field strategy=exhausted ok=$finalOk")
        return finalOk
    }

    private fun tryHintSelectors(
        runtime: PlaybookRuntime,
        hints: List<String>,
        text: String,
        field: String,
        timeoutMs: Long,
    ): Boolean =
        hints.any { hint ->
            attemptSetText(runtime, "text=$hint", text, field, "hint_text", timeoutMs) ||
                attemptSetText(
                    runtime,
                    "contentDescription=$hint",
                    text,
                    field,
                    "hint_desc",
                    timeoutMs = (timeoutMs * 3 / 4).coerceAtLeast(1_000),
                )
        }

    private fun setTextOnNthEditable(
        runtime: PlaybookRuntime,
        index: Int,
        text: String,
        hints: List<String>,
        field: String,
    ): Boolean {
        val snapshot = runtime.observe() ?: return false
        val editables = snapshot.nodes
            .filter { it.editable && it.bounds?.isUsable == true }
            .sortedWith(compareBy({ it.bounds!!.top }, { it.bounds!!.left }))
        val target = editables.getOrNull(index)
            ?: editables.lastOrNull { node ->
                val label = node.text?.trim().orEmpty()
                label.isEmpty() || hints.any { it == label }
            }
        if (target == null) {
            Log.i(TAG, "field=$field strategy=nth index=$index editables=${editables.size} ok=false")
            return false
        }
        val bounds = target.bounds ?: return false
        runtime.tap(bounds.centerX, bounds.centerY)
        runtime.sleep(400)

        val label = target.text?.trim().orEmpty()
        if (label.isNotEmpty() &&
            attemptSetText(runtime, "text=$label", text, field, "nth_label", timeoutMs = 4_000) &&
            fieldLooksFilled(runtime, text)
        ) {
            return true
        }
        if (
            attemptSetText(
                runtime,
                "className=android.widget.EditText",
                text,
                field,
                "nth_classname",
                timeoutMs = 4_000,
            ) && fieldLooksFilled(runtime, text)
        ) {
            return true
        }
        return attemptSetText(
            runtime,
            "className=android.widget.EditText;clickable=true",
            text,
            field,
            "nth_classname_clickable",
            timeoutMs = 3_000,
        ) && fieldLooksFilled(runtime, text)
    }

    private fun attemptSetText(
        runtime: PlaybookRuntime,
        selector: String,
        text: String,
        field: String,
        strategy: String,
        timeoutMs: Long,
    ): Boolean {
        val started = System.nanoTime()
        val ok = runtime.setText(selector, text, timeoutMs = timeoutMs)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "field=$field strategy=$strategy selector=$selector ok=$ok ${elapsedMs}ms")
        return ok
    }

    /** Confirm the typed value actually appears (avoid false SUCCESS on empty body publish). */
    private fun fieldLooksFilled(runtime: PlaybookRuntime, expected: String): Boolean {
        val needle = expected.trim().take(12)
        if (needle.isEmpty()) return true
        val snapshot = runtime.observe() ?: return false
        return snapshot.nodes.any { node ->
            val value = node.text?.trim().orEmpty()
            value.contains(needle) || value == expected.trim()
        }
    }

    /**
     * Open the bottom-tab publish entry. Prefer a geometric tab tap — selector clicks on
     * contentDescription=发布 are treated as final-action labels and often miss the tab.
     * Always verify the create sheet opened; a blind center tap on note-detail "succeeds"
     * without showing 从相册选择.
     */
    private fun openPublishEntry(runtime: PlaybookRuntime): Boolean {
        val attempts: List<() -> Boolean> = listOf(
            { SurfaceNavigator.tapBottomTab(runtime, "发布") },
            { runtime.tap(540, 2294) },
            { runtime.click("contentDescription=发布", allowFinal = true, timeoutMs = 3_000) },
            { runtime.click("text=发布", allowFinal = true, timeoutMs = 2_500) },
        )
        for (attempt in attempts) {
            if (!attempt()) continue
            runtime.sleep(700)
            if (publishSheetOpen(runtime)) return true
        }
        return false
    }

    private fun publishSheetOpen(runtime: PlaybookRuntime): Boolean {
        val snapshot = runtime.observe() ?: return false
        return snapshot.nodes.any { n ->
            val t = n.text.orEmpty()
            val d = n.contentDescription.orEmpty()
            t == "从相册选择" || t == "写文字" || t.contains("拍摄") ||
                d == "从相册选择" || d == "写文字"
        }
    }

    private fun resetToHomeFeed(runtime: PlaybookRuntime) {
        // Leave note-detail / capa mid-flows. Launch clears back to the main index when possible.
        runtime.launchXhs()
        runtime.sleep(900)
        for (i in 0 until 6) {
            if (runtime.currentPageHint() == PageHint.HOME) break
            runtime.globalBack()
            runtime.sleep(250)
        }
        SurfaceNavigator.tapBottomTab(runtime, "首页") ||
            runtime.click("contentDescription=首页", allowFinal = false, timeoutMs = 1_500) ||
            runtime.click("text=首页", allowFinal = false, timeoutMs = 1_500) ||
            runtime.tap(108, 2294)
        runtime.sleep(700)
        if (runtime.currentPageHint() != PageHint.HOME) {
            runtime.launchXhs()
            runtime.sleep(1_200)
            SurfaceNavigator.tapBottomTab(runtime, "首页") || runtime.tap(108, 2294)
            runtime.sleep(700)
        }
    }

    /** Tap a visible label by bounds center — more reliable than ACTION_CLICK on sheet CTAs. */
    private fun tapLabeled(runtime: PlaybookRuntime, label: String): Boolean {
        val bounds = findLabelBounds(runtime, label) ?: return false
        return runtime.tap(bounds.centerX, bounds.centerY)
    }

    private fun findLabelBounds(runtime: PlaybookRuntime, label: String): UiBounds? {
        val snapshot = runtime.observe() ?: return null
        val node = snapshot.nodes.firstOrNull { n ->
            n.text == label || n.contentDescription == label
        } ?: return null
        return node.bounds
    }

    /**
     * Advance album-select / filter wizard. Only taps when 下一步/开始创作 is visible —
     * blind coordinate taps always "succeed" and burn the retry budget.
     */
    private fun advancePublishWizard(runtime: PlaybookRuntime): Boolean {
        for (label in listOf("下一步", "开始创作")) {
            val bounds = findLabelBounds(runtime, label)
            if (bounds != null) {
                return runtime.tap(bounds.centerX, bounds.centerY)
            }
        }
        return runtime.click("text=下一步", allowFinal = false, timeoutMs = 2_500) ||
            runtime.click("text=开始创作", allowFinal = false, timeoutMs = 2_000)
    }

    private fun editorReady(runtime: PlaybookRuntime): Boolean {
        if (runtime.currentPageHint() == PageHint.EDITOR) return true
        val snapshot = runtime.observe() ?: return false
        val hasPublishCta = snapshot.nodes.any { n ->
            val t = n.text.orEmpty()
            val d = n.contentDescription.orEmpty()
            t == "发布笔记" || d == "发布笔记" || t == "存草稿"
        }
        val editableCount = snapshot.nodes.count { it.editable && it.bounds?.isUsable == true }
        if (hasPublishCta && editableCount >= 1) return true
        return snapshot.nodes.any { n ->
            val t = n.text.orEmpty()
            val d = n.contentDescription.orEmpty()
            TITLE_HINTS.any { it == t || it == d } ||
                BODY_HINTS.any { it == t || it == d }
        }
    }

    companion object {
        private const val TAG = "PublishFill"
        private const val TITLE_SUMMARY_LIMIT = 64
        private val TITLE_HINTS = listOf("添加标题", "填写标题", "标题")
        private val BODY_HINTS = listOf(
            "添加正文",
            "添加正文描述",
            "填写正文",
            "正文",
            "说点什么",
            "说点什么…",
        )
    }
}

class ReplyCommentPlaybook : Playbook {
    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult {
        val taskId = command.taskId
        val text = command.stringParam("text")
            ?: return PlaybookResult.failed(taskId, "MISSING_TEXT")
        if (!runtime.accessibilityAlive()) {
            return PlaybookResult.failed(taskId, "A11Y_DOWN")
        }
        val nav = SurfaceNavigator.goNoteComments(
            runtime,
            titleSummary = command.stringParam("title_summary"),
            xhsHint = command.stringParam("xhs_hint"),
            locatorHint = command.stringParam("note_locator"),
        )
        if (nav is NavResult.Failed) {
            return PlaybookResult.failed(taskId, nav.errorCode)
        }
        var hint = runtime.currentPageHint()
        val knownCommentSurface =
            hint == PageHint.COMMENTS || hint == PageHint.NOTE_DETAIL
        if (!knownCommentSurface && hint != PageHint.UNKNOWN && hint != null) {
            return PlaybookResult.failed(taskId, "WRONG_PAGE")
        }
        if (hint == PageHint.NOTE_DETAIL) {
            runtime.click("contentDescription=评论", allowFinal = false, timeoutMs = 3_000) ||
                runtime.click("text=评论", allowFinal = false, timeoutMs = 3_000)
            runtime.sleep(800)
            hint = runtime.currentPageHint()
        }

        // Nested reply: focus the target comment (locator center) then open its 回复 composer.
        val locatorHint = command.stringParam("locator_hint")
        val center = parseCenter(locatorHint)
        if (center != null) {
            runtime.tap(center.first, center.second)
            runtime.sleep(600)
            runtime.click("text=回复", allowFinal = false, timeoutMs = 2_000) ||
                runtime.clickTextContaining("回复", timeoutMs = 2_000)
            runtime.sleep(700)
        }

        val selectors = buildList {
            command.stringParam("input_selector")?.let(::add)
            add("text=留下你的想法吧")
            add("text=有话要说，快来评论")
            add("text=让大家听到你的声音")
            add("text=说点什么")
            add("text=说点什么…")
            add("text=留下你的评论吧")
            command.stringParam("alt_input_selector")?.let(::add)
        }

        fun hasSendControl(): Boolean {
            val snapshot = runtime.observe() ?: return false
            return snapshot.nodes.any { node ->
                val labels = listOf(node.text, node.contentDescription).mapNotNull { it?.trim() }
                labels.any { it == "发送" || it.startsWith("发送") }
            }
        }

        fun tryType(): Boolean {
            if (selectors.any { runtime.setText(it, text, timeoutMs = 4_000) } &&
                (fieldLooksFilled(runtime, text) || hasSendControl())
            ) {
                return true
            }
            if (fillFocusedEditable(runtime, text, selectors.map { it.removePrefix("text=") })) {
                return true
            }
            // Loanagent IME InputConnection: a11y often lags (placeholder still visible,
            // 发送 not yet in the tree). Treat IC success as typed and let send retries
            // confirm the composer is live.
            if (M0InputMethodService.commitIntoFocusedEditor(text)) {
                runtime.sleep(600)
                return true
            }
            if (commitViaLoanagentIme(runtime, text)) {
                runtime.sleep(400)
                return fieldLooksFilled(runtime, text) || hasSendControl()
            }
            return false
        }

        fun openComposer(): Boolean {
            val labels = listOf(
                "留下你的想法吧",
                "有话要说，快来评论",
                "让大家听到你的声音",
                "说点什么",
                "说点什么…",
            )
            for (label in labels) {
                if (runtime.click("text=$label", allowFinal = false, timeoutMs = 2_000) ||
                    runtime.clickTextContaining(label.take(4), timeoutMs = 2_000)
                ) {
                    runtime.sleep(900)
                    return true
                }
            }
            if (runtime.tap(997, 2279) || runtime.tap(540, 2280)) {
                runtime.sleep(900)
                return true
            }
            val tapX = command.intParam("composer_tap_x", 350)
            val tapY = command.intParam("composer_tap_y", 1900)
            if (runtime.tap(tapX, tapY)) {
                runtime.sleep(900)
                return true
            }
            return false
        }

        fun ensureComposerTyped(): Boolean {
            openComposer()
            if (tryType()) return true
            runtime.tap(350, 1906)
            runtime.sleep(800)
            openComposer()
            return tryType()
        }

        fun trySend(): Boolean {
            if (runtime.click("text=发送", allowFinal = true, timeoutMs = 5_000)) return true
            if (runtime.click("contentDescription=发送", allowFinal = true, timeoutMs = 2_000)) {
                return true
            }
            return false
        }

        if (!ensureComposerTyped()) {
            return if (knownCommentSurface || hint == PageHint.COMMENTS || hint == PageHint.UNKNOWN ||
                hint == PageHint.NOTE_DETAIL
            ) {
                PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
            } else {
                PlaybookResult.failed(taskId, "WRONG_PAGE")
            }
        }
        runtime.sleep(400)
        runtime.beginSideEffect()
        if (!trySend()) {
            // Composer may have collapsed; reopen, retype, retry send once.
            openComposer()
            M0InputMethodService.commitIntoFocusedEditor(text)
            runtime.sleep(500)
            if (!trySend()) {
                return PlaybookResult.failed(taskId, "FINAL_ACTION_BLOCKED")
            }
        }
        runtime.sleep(1_000)
        return PlaybookResult.succeeded(taskId, effectCommitted = true)
    }

    private fun parseCenter(locatorHint: String?): Pair<Int, Int>? {
        if (locatorHint.isNullOrBlank()) return null
        val match = Regex("""center\s*=\s*(\d+)\s*,\s*(\d+)""").find(locatorHint) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun fieldLooksFilled(runtime: PlaybookRuntime, expected: String): Boolean {
        val needle = expected.trim().take(12)
        if (needle.isEmpty()) return true
        val snapshot = runtime.observe() ?: return false
        return snapshot.nodes.any { node ->
            val value = node.text?.trim().orEmpty()
            value.contains(needle) || value == expected.trim()
        }
    }

    private fun fillFocusedEditable(
        runtime: PlaybookRuntime,
        text: String,
        hints: List<String>,
    ): Boolean {
        val snapshot = runtime.observe() ?: return false
        val editables = snapshot.nodes
            .filter { it.editable && it.bounds?.isUsable == true }
            .sortedWith(compareBy({ it.bounds!!.top }, { it.bounds!!.left }))
        val target = editables.lastOrNull { node ->
            val label = node.text?.trim().orEmpty()
            label.isEmpty() || hints.any { hint -> label.contains(hint) || hint.contains(label) }
        } ?: editables.lastOrNull() ?: return false
        val bounds = target.bounds ?: return false
        runtime.tap(bounds.centerX, bounds.centerY)
        runtime.sleep(500)
        val label = target.text?.trim().orEmpty()
        if (label.isNotEmpty() &&
            runtime.setText("text=$label", text, timeoutMs = 6_000) &&
            fieldLooksFilled(runtime, text)
        ) {
            return true
        }
        if (
            runtime.setText("className=android.widget.EditText", text, timeoutMs = 6_000) &&
            fieldLooksFilled(runtime, text)
        ) {
            return true
        }
        return false
    }

    private fun commitViaLoanagentIme(runtime: PlaybookRuntime, text: String): Boolean {
        val snapshot = runtime.observe() ?: return false
        val imeSurface = snapshot.nodes.any { node ->
            val label = listOf(node.text, node.contentDescription).mapNotNull { it?.trim() }
            label.any { it.contains("M0 手动输入") || it == "Commit text" || it == "COMMIT TEXT" }
        }
        if (!imeSurface) return false
        // Fill the IME's own EditText then press Commit.
        if (!runtime.setText("className=android.widget.EditText", text, timeoutMs = 4_000) &&
            !runtime.setText("text=M0 手动输入（不保存）", text, timeoutMs = 3_000)
        ) {
            // Tap the IME edit area roughly and retry class selector.
            runtime.tap(540, 2050)
            runtime.sleep(400)
            if (!runtime.setText("className=android.widget.EditText", text, timeoutMs = 4_000)) {
                return false
            }
        }
        runtime.sleep(300)
        val committed =
            runtime.click("text=Commit text", allowFinal = false, timeoutMs = 3_000) ||
                runtime.click("text=COMMIT TEXT", allowFinal = false, timeoutMs = 2_000)
        if (!committed) return false
        runtime.sleep(500)
        return fieldLooksFilled(runtime, text)
    }
}

/** Top-level note comment. Same composer surface as [ReplyCommentPlaybook]. */
class PostCommentPlaybook : Playbook {
    private val delegate = ReplyCommentPlaybook()

    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult =
        delegate.run(command, runtime)
}

class ReplyDmPlaybook : Playbook {
    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult {
        val taskId = command.taskId
        val text = command.stringParam("text")
            ?: return PlaybookResult.failed(taskId, "MISSING_TEXT")
        if (!runtime.accessibilityAlive()) {
            return PlaybookResult.failed(taskId, "A11Y_DOWN")
        }
        val titleHint = command.stringParam("open_title_hint")
            ?: command.stringParam("thread_title")
            ?: return PlaybookResult.failed(taskId, "NAV_MISSING_HINT")
        // Always navigate home → inbox → thread; never reuse the current chat surface.
        val nav = SurfaceNavigator.goDmThread(runtime, titleHint)
        if (nav is NavResult.Failed) {
            return PlaybookResult.failed(taskId, nav.errorCode)
        }
        val hint = runtime.currentPageHint()
        val inputSelectors = listOf(
            command.stringParam("input_selector") ?: "text=发消息…",
            "text=发消息",
            "text=发送消息",
            "text=请输入消息",
            "className=android.widget.EditText",
        )
        fun tryType(): Boolean = inputSelectors.any { runtime.setText(it, text, timeoutMs = 5_000) }
        if (!tryType()) {
            // Composer may need a tap before ACTION_SET_TEXT on some skins.
            runtime.tap(command.intParam("composer_tap_x", 540), command.intParam("composer_tap_y", 2200))
            runtime.sleep(800)
            if (!tryType()) {
                return if (hint == PageHint.INBOX || hint == PageHint.UNKNOWN || runtime.looksLikeOpenDmThreadSurface()) {
                    PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
                } else {
                    PlaybookResult.failed(taskId, "WRONG_PAGE")
                }
            }
        }
        runtime.sleep(400)
        runtime.beginSideEffect()
        if (!runtime.click("text=发送", allowFinal = true, timeoutMs = 10_000)) {
            return PlaybookResult.failed(taskId, "FINAL_ACTION_BLOCKED")
        }
        runtime.sleep(1_000)
        return PlaybookResult.succeeded(taskId, effectCommitted = true)
    }
}

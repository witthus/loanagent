package com.loanagent.agent

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
        if (!runtime.waitForXhsForeground(8_000)) {
            runtime.launchXhs()
            if (!runtime.waitForXhsForeground(15_000)) {
                return PlaybookResult.failed(taskId, "XHS_NOT_FOREGROUND")
            }
        }

        val startInEditor = command.boolParam("start_in_editor")
        if (!startInEditor) {
            // Comments / inbox sync often leave XHS on note-detail; publish sheet only opens from
            // the main tab chrome. Back out to home before tapping the center publish tab.
            resetToHomeFeed(runtime)
            if (!openPublishEntry(runtime)) {
                return PlaybookResult.failed(taskId, "PUBLISH_ENTRY_FAILED")
            }
            runtime.sleep(1_000)
            // Prefer geometric tap — a11y click on sheet rows is unreliable on HyperOS.
            val albumOpened =
                tapLabeled(runtime, "从相册选择") ||
                    runtime.click("text=从相册选择", allowFinal = false, timeoutMs = 4_000) ||
                    runtime.click("text=相册", allowFinal = false, timeoutMs = 3_000) ||
                    runtime.tap(540, 1757)
            if (!albumOpened) {
                return PlaybookResult.failed(taskId, "PUBLISH_ENTRY_FAILED")
            }
            runtime.sleep(2_000)
            // Dismiss permission prompts if present.
            runtime.click("text=允许", allowFinal = false, timeoutMs = 2_000) ||
                runtime.click("text=始终允许", allowFinal = false, timeoutMs = 2_000) ||
                runtime.click("text=仅使用期间允许", allowFinal = false, timeoutMs = 2_000)
            runtime.sleep(800)
            val tapX = command.intParam("album_tap_x", 177)
            val tapY = command.intParam("album_tap_y", 532)
            if (!runtime.tap(tapX, tapY)) {
                return PlaybookResult.failed(taskId, "MEDIA_MISSING")
            }
            runtime.sleep(1_500)
            // Newly inserted MediaStore items may need a moment before the picker lists them.
            runtime.sleep(1_000)
            tapLabeled(runtime, "全部")
            runtime.sleep(500)
            var rounds = 0
            while (rounds < 6 && !editorReady(runtime)) {
                val advanced = advancePublishWizard(runtime)
                if (!advanced) break
                runtime.sleep(1_500)
                rounds += 1
            }
        }

        if (!runtime.setText("text=添加标题", title, timeoutMs = 12_000) &&
            !runtime.setText("contentDescription=添加标题", title, timeoutMs = 8_000)
        ) {
            return PlaybookResult.failed(
                taskId,
                if (startInEditor) "SET_TEXT_FAILED" else "EDITOR_NOT_READY",
            )
        }
        runtime.sleep(400)
        if (!runtime.setText("text=添加正文", body, timeoutMs = 12_000) &&
            !runtime.setText("contentDescription=添加正文", body, timeoutMs = 8_000)
        ) {
            return PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
        }
        runtime.sleep(500)
        if (!runtime.click("text=发布笔记", allowFinal = true, timeoutMs = 15_000)) {
            return PlaybookResult.failed(taskId, "FINAL_ACTION_BLOCKED")
        }
        runtime.sleep(2_000)
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
     * Open the bottom-tab publish entry. Prefer a geometric tab tap — selector clicks on
     * contentDescription=发布 are treated as final-action labels and often miss the tab.
     * Always verify the create sheet opened; a blind center tap on note-detail "succeeds"
     * without showing 从相册选择.
     */
    private fun openPublishEntry(runtime: PlaybookRuntime): Boolean {
        val attempts: List<() -> Boolean> = listOf(
            { SurfaceNavigator.tapBottomTab(runtime, "发布") },
            { runtime.tap(540, 2294) },
            { runtime.click("contentDescription=发布", allowFinal = true, timeoutMs = 4_000) },
            { runtime.click("text=发布", allowFinal = true, timeoutMs = 3_000) },
        )
        for (attempt in attempts) {
            if (!attempt()) continue
            runtime.sleep(1_000)
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
        runtime.sleep(1_200)
        for (i in 0 until 6) {
            if (runtime.currentPageHint() == PageHint.HOME) break
            runtime.globalBack()
            runtime.sleep(300)
        }
        SurfaceNavigator.tapBottomTab(runtime, "首页") ||
            runtime.click("contentDescription=首页", allowFinal = false, timeoutMs = 2_000) ||
            runtime.click("text=首页", allowFinal = false, timeoutMs = 2_000) ||
            runtime.tap(108, 2294)
        runtime.sleep(900)
        if (runtime.currentPageHint() != PageHint.HOME) {
            runtime.launchXhs()
            runtime.sleep(1_500)
            SurfaceNavigator.tapBottomTab(runtime, "首页") || runtime.tap(108, 2294)
            runtime.sleep(900)
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
        return snapshot.nodes.any { n ->
            val t = n.text.orEmpty()
            val d = n.contentDescription.orEmpty()
            t == "添加标题" || d == "添加标题" || t == "发布笔记" || d == "发布笔记"
        }
    }

    companion object {
        private const val TITLE_SUMMARY_LIMIT = 64
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

        val selectors = buildList {
            command.stringParam("input_selector")?.let(::add)
            add("text=有话要说，快来评论")
            add("text=让大家听到你的声音")
            add("text=说点什么")
            command.stringParam("alt_input_selector")?.let(::add)
        }

        fun tryType(): Boolean = selectors.any { runtime.setText(it, text, timeoutMs = 8_000) }

        if (!tryType()) {
            val tapX = command.intParam("composer_tap_x", 393)
            val tapY = command.intParam("composer_tap_y", 494)
            if (!runtime.tap(tapX, tapY)) {
                return PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
            }
            runtime.sleep(1_200)
            if (!tryType()) {
                return if (knownCommentSurface || hint == PageHint.COMMENTS || hint == PageHint.UNKNOWN) {
                    PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
                } else {
                    PlaybookResult.failed(taskId, "WRONG_PAGE")
                }
            }
        }
        runtime.sleep(400)
        if (!runtime.click("text=发送", allowFinal = true, timeoutMs = 10_000)) {
            return PlaybookResult.failed(taskId, "FINAL_ACTION_BLOCKED")
        }
        runtime.sleep(1_000)
        return PlaybookResult.succeeded(taskId, effectCommitted = true)
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
        val pageBefore = runtime.currentPageHint()
        val composerReady = pageBefore == PageHint.INBOX || pageBefore == PageHint.UNKNOWN ||
            pageBefore == null
        if (titleHint != null) {
            val nav = SurfaceNavigator.goDmThread(runtime, titleHint)
            if (nav is NavResult.Failed) {
                return PlaybookResult.failed(taskId, nav.errorCode)
            }
        } else if (!composerReady) {
            return PlaybookResult.failed(taskId, "NAV_MISSING_HINT")
        } else if (pageBefore != PageHint.INBOX && pageBefore != PageHint.UNKNOWN && pageBefore != null) {
            return PlaybookResult.failed(taskId, "WRONG_PAGE")
        }
        val hint = runtime.currentPageHint()
        val inputSelector = command.stringParam("input_selector") ?: "text=发消息…"
        if (!runtime.setText(inputSelector, text, timeoutMs = 12_000) &&
            !runtime.setText("text=发消息", text, timeoutMs = 8_000)
        ) {
            return if (hint == PageHint.INBOX || hint == PageHint.UNKNOWN) {
                PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
            } else {
                PlaybookResult.failed(taskId, "WRONG_PAGE")
            }
        }
        runtime.sleep(400)
        if (!runtime.click("text=发送", allowFinal = true, timeoutMs = 10_000)) {
            return PlaybookResult.failed(taskId, "FINAL_ACTION_BLOCKED")
        }
        runtime.sleep(1_000)
        return PlaybookResult.succeeded(taskId, effectCommitted = true)
    }
}

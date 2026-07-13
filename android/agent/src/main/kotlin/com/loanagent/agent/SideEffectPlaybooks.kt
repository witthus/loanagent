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
            if (!runtime.click("contentDescription=发布", allowFinal = true, timeoutMs = 8_000)) {
                return PlaybookResult.failed(taskId, "PUBLISH_ENTRY_FAILED")
            }
            runtime.sleep(800)
            runtime.click("text=从相册选择", allowFinal = false, timeoutMs = 8_000)
            runtime.sleep(1_500)
            val tapX = command.intParam("album_tap_x", 177)
            val tapY = command.intParam("album_tap_y", 532)
            if (!runtime.tap(tapX, tapY)) {
                return PlaybookResult.failed(taskId, "MEDIA_MISSING")
            }
            runtime.sleep(800)
            var rounds = 0
            while (rounds < 3 && runtime.currentPageHint() != PageHint.EDITOR) {
                runtime.click("text=下一步", allowFinal = false, timeoutMs = 6_000)
                runtime.sleep(1_000)
                rounds += 1
            }
        }

        if (runtime.currentPageHint() != PageHint.EDITOR && !startInEditor) {
            // Editor hint may be UNKNOWN on some builds; continue if title field exists.
            if (!runtime.setText("text=添加标题", title, timeoutMs = 8_000) &&
                !runtime.setText("contentDescription=添加标题", title, timeoutMs = 8_000)
            ) {
                return PlaybookResult.failed(taskId, "EDITOR_NOT_READY")
            }
        } else {
            if (!runtime.setText("text=添加标题", title, timeoutMs = 12_000) &&
                !runtime.setText("contentDescription=添加标题", title, timeoutMs = 8_000)
            ) {
                return PlaybookResult.failed(taskId, "SET_TEXT_FAILED")
            }
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

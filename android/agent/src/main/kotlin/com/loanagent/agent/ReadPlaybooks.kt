package com.loanagent.agent

class ReadCommentsPlaybook : Playbook {
    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult {
        val taskId = command.taskId
        if (!runtime.accessibilityAlive()) {
            return PlaybookResult.failed(taskId, "A11Y_DOWN")
        }
        val nav = SurfaceNavigator.goNoteComments(
            runtime,
            titleSummary = command.stringParam("title_summary"),
            xhsHint = command.stringParam("xhs_hint"),
            locatorHint = command.stringParam("locator_hint")
                ?: command.stringParam("note_locator"),
        )
        if (nav is NavResult.Failed) {
            return PlaybookResult.failed(taskId, nav.errorCode)
        }
        val maxItems = command.intParam("max_items", 20)
        var hint = runtime.currentPageHint()
        if (hint == PageHint.NOTE_DETAIL) {
            runtime.click("contentDescription=评论", allowFinal = false, timeoutMs = 5_000) ||
                runtime.click("text=评论", allowFinal = false, timeoutMs = 5_000)
            runtime.sleep(1_200)
            hint = runtime.currentPageHint()
        }
        // ReadComments still needs extractable surface; allow UNKNOWN only after nav claimed note open.
        if (hint != PageHint.COMMENTS && hint != PageHint.NOTE_DETAIL && hint != PageHint.UNKNOWN) {
            return PlaybookResult.failed(taskId, "WRONG_PAGE")
        }
        val items = runtime.extractComments(maxItems)
        if (items.isEmpty()) {
            return PlaybookResult.failed(taskId, "EXTRACT_EMPTY")
        }
        val payload = linkedMapOf<String, Any?>(
            "kind" to "comments",
            "items" to items.map { comment ->
                linkedMapOf<String, Any?>(
                    "author_summary" to comment.authorSummary,
                    "body_summary" to comment.bodySummary,
                    "locator_hint" to comment.locatorHint,
                )
            },
        )
        command.stringParam("note_ref")?.let { payload["note_ref"] = it }
        command.stringParam("note_id")?.let { payload["note_id"] = it }
        return PlaybookResult.succeeded(taskId, resultPayload = payload)
    }
}

class InboxSyncPlaybook : Playbook {
    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult {
        val taskId = command.taskId
        if (!runtime.accessibilityAlive()) {
            return PlaybookResult.failed(taskId, "A11Y_DOWN")
        }
        val nav = SurfaceNavigator.goInbox(runtime)
        if (nav is NavResult.Failed) {
            return PlaybookResult.failed(taskId, nav.errorCode)
        }
        val maxItems = command.intParam("max_items", 20)
        if (runtime.currentPageHint() != PageHint.INBOX) {
            return PlaybookResult.failed(taskId, "WRONG_PAGE")
        }
        val threads = runtime.extractInboxThreads(maxItems)
        if (threads.isEmpty()) {
            return PlaybookResult.failed(taskId, "EXTRACT_EMPTY")
        }
        if (command.boolParam("open_first_thread")) {
            val locator = threads.first().locatorHint
            if (locator != null) {
                runtime.click(locator, allowFinal = false, timeoutMs = 5_000)
                runtime.sleep(1_000)
                val messages = runtime.extractDmMessages(maxItems)
                if (messages.isEmpty()) {
                    return PlaybookResult.failed(taskId, "EXTRACT_EMPTY")
                }
            }
        }
        val payload = mapOf(
            "kind" to "inbox",
            "threads" to threads.map { thread ->
                linkedMapOf<String, Any?>(
                    "title_summary" to thread.titleSummary,
                    "preview_summary" to thread.previewSummary,
                    "unread" to thread.unreadHint,
                    "locator_hint" to thread.locatorHint,
                )
            },
        )
        return PlaybookResult.succeeded(taskId, resultPayload = payload)
    }
}

class InboxOpenThreadPlaybook : Playbook {
    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult {
        val taskId = command.taskId
        if (!runtime.accessibilityAlive()) {
            return PlaybookResult.failed(taskId, "A11Y_DOWN")
        }
        val titleHint = command.stringParam("open_title_hint")
            ?: command.stringParam("thread_title")
        val maxItems = command.intParam("max_items", 20)
        // Already in a thread with messages: allow without re-nav.
        val alreadyOpen = runtime.extractDmMessages(maxItems).isNotEmpty() &&
            runtime.currentPageHint() != PageHint.HOME
        if (!alreadyOpen) {
            val nav = SurfaceNavigator.goDmThread(runtime, titleHint)
            if (nav is NavResult.Failed) {
                return PlaybookResult.failed(taskId, nav.errorCode)
            }
        }
        val messages = runtime.extractDmMessages(maxItems)
        if (messages.isEmpty()) {
            return PlaybookResult.failed(taskId, "EXTRACT_EMPTY")
        }
        val payload = mapOf(
            "kind" to "thread",
            "messages" to messages.map { message ->
                mapOf(
                    "sender_summary" to message.senderSummary,
                    "body_summary" to message.bodySummary,
                )
            },
        )
        return PlaybookResult.succeeded(taskId, resultPayload = payload)
    }
}

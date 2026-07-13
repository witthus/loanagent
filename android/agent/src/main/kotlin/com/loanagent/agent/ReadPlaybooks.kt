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
        // Prefer comments sheet; also accept surfaces that still show comment chrome.
        if (hint != PageHint.COMMENTS && !runtime.looksLikeCommentsSurface()) {
            return PlaybookResult.failed(taskId, "WRONG_PAGE")
        }
        val items = runtime.extractComments(maxItems)
        val payload = linkedMapOf<String, Any?>(
            "kind" to "comments",
            "items" to items.map { comment -> commentToMap(comment) },
        )
        command.stringParam("note_ref")?.let { payload["note_ref"] = it }
        command.stringParam("note_id")?.let { payload["note_id"] = it }
        return PlaybookResult.succeeded(taskId, resultPayload = payload)
    }

    private fun commentToMap(comment: ExtractedComment): Map<String, Any?> =
        linkedMapOf(
            "author_summary" to comment.authorSummary,
            "body_summary" to comment.bodySummary,
            "locator_hint" to comment.locatorHint,
            "posted_at_text" to comment.postedAtText,
            "reply_to_author" to comment.replyToAuthor,
            "replies" to comment.replies.map { reply ->
                linkedMapOf(
                    "author_summary" to reply.authorSummary,
                    "body_summary" to reply.bodySummary,
                    "locator_hint" to reply.locatorHint,
                    "posted_at_text" to reply.postedAtText,
                    "reply_to_author" to reply.replyToAuthor,
                )
            },
        )
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
        // Prefer hub surface chrome; page hint alone is brittle across XHS builds.
        if (!runtime.looksLikeInboxListSurface() && runtime.currentPageHint() != PageHint.INBOX) {
            return PlaybookResult.failed(taskId, "WRONG_PAGE")
        }
        val threads = runtime.extractInboxThreads(maxItems)
        val openThreads = command.boolParam("open_threads", default = true) ||
            command.boolParam("open_first_thread")
        val maxOpen = command.intParam("max_threads", 8).coerceIn(0, maxItems)
        val threadPayloads = threads.map { thread ->
            linkedMapOf<String, Any?>(
                "title_summary" to thread.titleSummary,
                "preview_summary" to thread.previewSummary,
                "unread" to thread.unreadHint,
                "locator_hint" to thread.locatorHint,
            )
        }.toMutableList()
        if (openThreads && threads.isNotEmpty()) {
            threads.take(maxOpen).forEachIndexed { index, thread ->
                val locator = thread.locatorHint
                val opened = if (locator != null) {
                    runtime.click(locator, allowFinal = false, timeoutMs = 5_000)
                } else {
                    runtime.clickTextContaining(thread.titleSummary, timeoutMs = 5_000)
                }
                if (opened) {
                    runtime.sleep(1_000)
                    if (runtime.looksLikeOpenDmThreadSurface()) {
                        val messages = runtime.extractDmMessages(maxItems)
                        threadPayloads[index]["messages"] = messages.map { message ->
                            linkedMapOf(
                                "sender_summary" to message.senderSummary,
                                "body_summary" to message.bodySummary,
                                "posted_at_text" to message.postedAtText,
                            )
                        }
                        if (messages.isNotEmpty()) {
                            val preview = messages.takeLast(3).joinToString(" ｜ ") {
                                val sender = it.senderSummary.trim()
                                val body = it.bodySummary.trim()
                                if (sender.isNotEmpty()) "$sender: $body" else body
                            }
                            threadPayloads[index]["preview_summary"] = preview.take(240)
                        }
                    }
                    runtime.globalBack()
                    runtime.sleep(700)
                }
            }
        }
        val payload = mapOf(
            "kind" to "inbox",
            "threads" to threadPayloads,
        )
        return PlaybookResult.succeeded(taskId, resultPayload = payload)
    }
}

class SyncNotesPlaybook : Playbook {
    override fun run(command: PlaybookCommand, runtime: PlaybookRuntime): PlaybookResult {
        val taskId = command.taskId
        if (!runtime.accessibilityAlive()) {
            return PlaybookResult.failed(taskId, "A11Y_DOWN")
        }
        val nav = SurfaceNavigator.goProfile(runtime)
        if (nav is NavResult.Failed) {
            return PlaybookResult.failed(taskId, nav.errorCode)
        }
        if (!runtime.looksLikeProfileSurface()) {
            return PlaybookResult.failed(taskId, "WRONG_PAGE")
        }
        val maxItems = command.intParam("max_items", 40)
        val collected = linkedMapOf<String, ExtractedProfileNote>()
        repeat(4) { round ->
            for (note in runtime.extractProfileNotes(maxItems)) {
                if (note.titleSummary !in collected) {
                    collected[note.titleSummary] = note
                }
            }
            if (collected.size >= maxItems) return@repeat
            if (round < 3) {
                runtime.swipe(540, 1_700, 540, 700, durationMs = 450)
                runtime.sleep(1_000)
            }
        }
        val items = collected.values.take(maxItems)
        return PlaybookResult.succeeded(
            taskId,
            resultPayload = mapOf(
                "kind" to "notes",
                "items" to items.map { note ->
                    linkedMapOf<String, Any?>(
                        "title_summary" to note.titleSummary,
                        "like_count" to note.likeCount,
                        "collect_count" to note.collectCount,
                        "read_count" to note.readCount,
                        "locator_hint" to note.locatorHint,
                    )
                },
            ),
        )
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
        // Already in an open DM thread: allow without re-nav.
        val alreadyOpen = runtime.looksLikeOpenDmThreadSurface() &&
            runtime.extractDmMessages(maxItems).isNotEmpty()
        if (!alreadyOpen) {
            val nav = SurfaceNavigator.goDmThread(runtime, titleHint)
            if (nav is NavResult.Failed) {
                return PlaybookResult.failed(taskId, nav.errorCode)
            }
        }
        if (!runtime.looksLikeOpenDmThreadSurface()) {
            return PlaybookResult.failed(taskId, "WRONG_PAGE")
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

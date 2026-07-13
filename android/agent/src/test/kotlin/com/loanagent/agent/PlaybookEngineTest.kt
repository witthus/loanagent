package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybookEngineTest {
    @Test
    fun unsupportedPlaybookFails() {
        val engine = PlaybookEngine(
            runtime = FakePlaybookRuntime(),
            registry = PlaybookRegistry(),
            ledger = MemoryEffectLedger(),
        )
        val result = engine.run(
            PlaybookCommand(taskId = "t1", playbook = "unknown_thing@1.0"),
        )!!
        assertFalse(result.success)
        assertEquals("UNSUPPORTED_PLAYBOOK", result.errorCode)
    }

    @Test
    fun ensureAppReadySucceedsOnHome() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(alive = true, foreground = true, hint = PageHint.HOME),
        )
        val result = engine.run(
            PlaybookCommand(taskId = "t2", playbook = "ensure_app_ready@1.0"),
        )!!
        assertTrue(result.success)
        assertNull(result.errorCode)
    }

    @Test
    fun readCommentsSucceedsWhenExtractNonEmpty() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(
                hint = PageHint.COMMENTS,
                comments = listOf(ExtractedComment("a", "b", null)),
            ),
        )
        val result = engine.run(PlaybookCommand(taskId = "rc1", playbook = "read_comments@1.0"))!!
        assertTrue(result.success)
        assertEquals("comments", result.resultPayload?.get("kind"))
        @Suppress("UNCHECKED_CAST")
        val items = result.resultPayload?.get("items") as List<Map<String, Any?>>
        assertEquals("a", items.single()["author_summary"])
        assertEquals("b", items.single()["body_summary"])
    }

    @Test
    fun inboxSyncFailsWhenEmpty() {
        val engine = engineWithDefaults(FakePlaybookRuntime(hint = PageHint.INBOX))
        val result = engine.run(PlaybookCommand(taskId = "in1", playbook = "inbox_sync@1.0"))!!
        assertEquals("EXTRACT_EMPTY", result.errorCode)
    }

    @Test
    fun publishNoteRequiresTitle() {
        val engine = engineWithDefaults(FakePlaybookRuntime(hint = PageHint.EDITOR))
        val result = engine.run(
            PlaybookCommand(
                taskId = "p1",
                playbook = "publish_note@1.0",
                params = mapOf("body" to "only body"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertEquals("MISSING_TITLE", result.errorCode)
    }

    @Test
    fun publishNoteCommitsWhenEditorPathWorks() {
        val runtime = FakePlaybookRuntime(hint = PageHint.EDITOR, clickOk = true, setTextOk = true)
        val engine = engineWithDefaults(runtime)
        val result = engine.run(
            PlaybookCommand(
                taskId = "p2",
                playbook = "publish_note@1.0",
                params = mapOf(
                    "title" to "标题",
                    "body" to "正文",
                    "start_in_editor" to true,
                ),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
        assertEquals("publish", result.resultPayload?.get("kind"))
        assertEquals("标题", result.resultPayload?.get("title_summary"))
    }

    @Test
    fun replyDmSucceeds() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(hint = PageHint.INBOX, clickOk = true, setTextOk = true),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "dm1",
                playbook = "reply_dm@1.0",
                params = mapOf("text" to "谢谢"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun replyCommentSucceedsOnCommentsPage() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(hint = PageHint.COMMENTS, clickOk = true, setTextOk = true),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "rc-reply",
                playbook = "reply_comment@1.0",
                params = mapOf("text" to "感谢首评"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun replyCommentUsesComposerTapFallback() {
        val runtime = object : FakePlaybookRuntime(
            hint = PageHint.NOTE_DETAIL,
            clickOk = false,
            setTextOk = false,
            tapOk = true,
        ) {
            private var typed = false
            override fun setText(selector: String, text: String, timeoutMs: Long): Boolean {
                if (!typed) return false
                return true
            }
            override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
                typed = true
                return true
            }
            override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean =
                selector.contains("发送")
        }
        val engine = engineWithDefaults(runtime)
        val result = engine.run(
            PlaybookCommand(
                taskId = "rc-tap",
                playbook = "reply_comment@1.0",
                params = mapOf("text" to "probe", "composer_tap_x" to 393, "composer_tap_y" to 494),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun replyCommentRejectsWrongPage() {
        val engine = engineWithDefaults(FakePlaybookRuntime(hint = PageHint.HOME, setTextOk = true))
        val result = engine.run(
            PlaybookCommand(
                taskId = "rc-wrong",
                playbook = "reply_comment@1.0",
                params = mapOf("text" to "x"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertEquals("NAV_MISSING_HINT", result.errorCode)
        assertFalse(result.effectCommitted)
    }

    @Test
    fun postCommentSucceedsOnCommentsPage() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(hint = PageHint.COMMENTS, clickOk = true, setTextOk = true),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "pc1",
                playbook = "post_comment@1.0",
                params = mapOf("text" to "首评来了"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun postCommentRejectsWrongPage() {
        val engine = engineWithDefaults(FakePlaybookRuntime(hint = PageHint.HOME, setTextOk = true))
        val result = engine.run(
            PlaybookCommand(
                taskId = "pc-wrong",
                playbook = "post_comment@1.0",
                params = mapOf("text" to "x"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertEquals("NAV_MISSING_HINT", result.errorCode)
        assertFalse(result.effectCommitted)
    }

    @Test
    fun postCommentRequiresText() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(hint = PageHint.COMMENTS, clickOk = true, setTextOk = true),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "pc-missing",
                playbook = "post_comment@1.0",
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertEquals("MISSING_TEXT", result.errorCode)
        assertFalse(result.effectCommitted)
    }

    @Test
    fun replyDmRejectsWrongPage() {
        val engine = engineWithDefaults(FakePlaybookRuntime(hint = PageHint.HOME, setTextOk = true))
        val result = engine.run(
            PlaybookCommand(
                taskId = "dm-wrong",
                playbook = "reply_dm@1.0",
                params = mapOf("text" to "x"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertEquals("NAV_MISSING_HINT", result.errorCode)
    }

    @Test
    fun replyDmAllowsUnknownChatSurfaceWhenComposerWorks() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(hint = PageHint.UNKNOWN, clickOk = true, setTextOk = true),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "dm-unknown",
                playbook = "reply_dm@1.0",
                params = mapOf("text" to "谢谢"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun inboxOpenThreadSucceedsWhenMessagesPresent() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(
                hint = PageHint.INBOX,
                messages = listOf(ExtractedDmMessage("peer", "hi")),
            ),
        )
        val result = engine.run(
            PlaybookCommand(taskId = "th1", playbook = "inbox_open_thread@1.0"),
        )!!
        assertTrue(result.success)
    }

    @Test
    fun readCommentsRejectsWrongPage() {
        val engine = engineWithDefaults(FakePlaybookRuntime(hint = PageHint.HOME))
        val result = engine.run(PlaybookCommand(taskId = "rc-wp", playbook = "read_comments@1.0"))!!
        assertEquals("NAV_MISSING_HINT", result.errorCode)
    }

    @Test
    fun sameTaskIdDoesNotReExecuteAfterAcknowledge() {
        val checks = AtomicInteger(0)
        val runtime = object : FakePlaybookRuntime(hint = PageHint.HOME) {
            override fun accessibilityAlive(): Boolean {
                checks.incrementAndGet()
                return true
            }
        }
        val engine = engineWithDefaults(runtime)
        val command = PlaybookCommand(taskId = "dup", playbook = "ensure_app_ready@1.0")
        val first = engine.run(command)
        assertTrue(first!!.success)
        engine.acknowledge(command, first)
        assertNull(engine.run(command))
        assertEquals(1, checks.get())
    }

    private fun engineWithDefaults(runtime: PlaybookRuntime): PlaybookEngine =
        PlaybookEngine(
            runtime = runtime,
            registry = DefaultPlaybookRegistry.create(),
            ledger = MemoryEffectLedger(),
        )

    open class FakePlaybookRuntime(
        private val alive: Boolean = true,
        private val foreground: Boolean = true,
        private val hint: PageHint? = PageHint.HOME,
        private val comments: List<ExtractedComment> = emptyList(),
        private val threads: List<ExtractedInboxThread> = emptyList(),
        private val messages: List<ExtractedDmMessage> = emptyList(),
        private val clickOk: Boolean = false,
        private val setTextOk: Boolean = false,
        private val tapOk: Boolean = true,
    ) : PlaybookRuntime {
        override fun accessibilityAlive(): Boolean = alive
        override fun launchXhs(): Boolean = true
        override fun waitForXhsForeground(timeoutMs: Long): Boolean = foreground
        override fun currentPageHint(): PageHint? = hint
        override fun currentLease(): TargetLease? =
            if (foreground) TargetLease("com.xingin.xhs", 1) else null

        override fun observe(): UiSnapshot? = null
        override fun extractComments(maxItems: Int) = comments.take(maxItems)
        override fun extractInboxThreads(maxItems: Int) = threads.take(maxItems)
        override fun extractDmMessages(maxItems: Int) = messages.take(maxItems)
        override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long) = clickOk
        override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean = clickOk
        override fun setText(selector: String, text: String, timeoutMs: Long) = setTextOk
        override fun tap(x: Int, y: Int, durationMs: Long) = tapOk
        override fun swipe(
            startX: Int,
            startY: Int,
            endX: Int,
            endY: Int,
            durationMs: Long,
        ): Boolean = true
        override fun globalBack(): Boolean = true
        override fun sleep(ms: Long) = Unit
    }
}

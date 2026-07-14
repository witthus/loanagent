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
            FakePlaybookRuntime(alive = true, foreground = true, startHint = PageHint.HOME),
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
                startHint = PageHint.HOME,
                comments = listOf(ExtractedComment("a", "b", null)),
                noteTitles = listOf("云测笔记"),
            ),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "rc1",
                playbook = "read_comments@1.0",
                params = mapOf("title_summary" to "云测笔记"),
            ),
        )!!
        assertTrue(result.success)
        assertEquals("comments", result.resultPayload?.get("kind"))
        @Suppress("UNCHECKED_CAST")
        val items = result.resultPayload?.get("items") as List<Map<String, Any?>>
        assertEquals("a", items.single()["author_summary"])
        assertEquals("b", items.single()["body_summary"])
    }

    @Test
    fun inboxSyncSucceedsWithEmptyThreadListForReconcile() {
        val engine = engineWithDefaults(FakePlaybookRuntime(startHint = PageHint.HOME))
        val result = engine.run(PlaybookCommand(taskId = "in1", playbook = "inbox_sync@1.0"))!!
        assertTrue(result.success)
        assertEquals("inbox", result.resultPayload?.get("kind"))
        @Suppress("UNCHECKED_CAST")
        val threads = result.resultPayload?.get("threads") as List<*>
        assertTrue(threads.isEmpty())
    }

    @Test
    fun publishNoteRequiresTitle() {
        val engine = engineWithDefaults(FakePlaybookRuntime(startHint = PageHint.EDITOR))
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
        val runtime = FakePlaybookRuntime(startHint = PageHint.EDITOR, clickOk = true, setTextOk = true)
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
    fun publishNoteFillsBodyAfterFocusingEditableField() {
        val runtime = object : FakePlaybookRuntime(startHint = PageHint.EDITOR, clickOk = true, tapOk = true) {
            private var bodyFocused = false
            private var titleValue = "添加标题"
            private var bodyValue = "添加正文"

            override fun observe(): UiSnapshot {
                val nodes = listOf(
                    UiNode(
                        text = titleValue,
                        editable = true,
                        clickable = true,
                        bounds = UiBounds(40, 400, 1000, 520),
                    ),
                    UiNode(
                        text = bodyValue,
                        editable = true,
                        clickable = true,
                        bounds = UiBounds(40, 560, 1000, 900),
                    ),
                    UiNode(text = "发布笔记", clickable = true),
                )
                return UiSnapshot(
                    packageName = "com.xingin.xhs",
                    className = "Frame",
                    pageHint = PageHint.EDITOR,
                    keyElements = emptyList(),
                    nodes = nodes,
                    truncated = false,
                )
            }

            override fun setText(selector: String, text: String, timeoutMs: Long): Boolean {
                if (selector.contains("添加标题") && text == "标题") {
                    titleValue = text
                    return true
                }
                if (selector.contains("添加正文") && text == "正文") {
                    if (!bodyFocused) return false
                    bodyValue = text
                    return true
                }
                if (selector.contains("className=android.widget.EditText") && text == "正文") {
                    if (!bodyFocused) return false
                    bodyValue = text
                    return true
                }
                return false
            }

            override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
                if (y in 560..900) bodyFocused = true
                return true
            }
        }
        val result = engineWithDefaults(runtime).run(
            PlaybookCommand(
                taskId = "p-body-focus",
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
        assertNull(result.errorCode)
    }

    @Test
    fun replyDmSucceeds() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(
                startHint = PageHint.HOME,
                clickOk = true,
                setTextOk = true,
                threads = listOf(ExtractedInboxThread("静生百慧茶叶馆", "hi", false, "text=静生百慧茶叶馆")),
            ),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "dm1",
                playbook = "reply_dm@1.0",
                params = mapOf("text" to "谢谢", "open_title_hint" to "静生百慧茶叶馆"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun replyCommentSucceedsOnCommentsPage() {
        val engine = engineWithDefaults(
            FakePlaybookRuntime(
                startHint = PageHint.HOME,
                clickOk = true,
                setTextOk = true,
                noteTitles = listOf("云测笔记"),
            ),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "rc-reply",
                playbook = "reply_comment@1.0",
                params = mapOf("text" to "感谢首评", "title_summary" to "云测笔记"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun replyCommentUsesComposerTapFallback() {
        val runtime = object : FakePlaybookRuntime(
            startHint = PageHint.HOME,
            clickOk = false,
            setTextOk = false,
            tapOk = true,
            noteTitles = listOf("云测笔记"),
        ) {
            private var typed = false
            override fun setText(selector: String, text: String, timeoutMs: Long): Boolean {
                if (!typed) return false
                return true
            }
            override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
                val nav = super.tap(x, y, durationMs)
                if (y < 2_000) typed = true
                return nav || true
            }
            override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean {
                if (selector.contains("发送")) return true
                return super.click(selector, allowFinal, timeoutMs)
            }
        }
        val engine = engineWithDefaults(runtime)
        val result = engine.run(
            PlaybookCommand(
                taskId = "rc-tap",
                playbook = "reply_comment@1.0",
                params = mapOf(
                    "text" to "probe",
                    "title_summary" to "云测笔记",
                    "composer_tap_x" to 393,
                    "composer_tap_y" to 494,
                ),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun replyCommentRejectsWrongPage() {
        val engine = engineWithDefaults(FakePlaybookRuntime(startHint = PageHint.HOME, setTextOk = true))
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
            FakePlaybookRuntime(
                startHint = PageHint.HOME,
                clickOk = true,
                setTextOk = true,
                noteTitles = listOf("云测笔记"),
            ),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "pc1",
                playbook = "post_comment@1.0",
                params = mapOf("text" to "首评来了", "title_summary" to "云测笔记"),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
        )!!
        assertTrue(result.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun postCommentRejectsWrongPage() {
        val engine = engineWithDefaults(FakePlaybookRuntime(startHint = PageHint.HOME, setTextOk = true))
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
            FakePlaybookRuntime(startHint = PageHint.COMMENTS, clickOk = true, setTextOk = true),
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
        val engine = engineWithDefaults(FakePlaybookRuntime(startHint = PageHint.HOME, setTextOk = true))
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
            FakePlaybookRuntime(
                startHint = PageHint.HOME,
                clickOk = true,
                setTextOk = true,
                threads = listOf(ExtractedInboxThread("静生百慧茶叶馆", "hi", false, null)),
            ),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "dm-unknown",
                playbook = "reply_dm@1.0",
                params = mapOf("text" to "谢谢", "open_title_hint" to "静生百慧茶叶馆"),
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
                startHint = PageHint.HOME,
                threads = listOf(ExtractedInboxThread("静生百慧茶叶馆", "hi", false, null)),
                messages = listOf(ExtractedDmMessage("peer", "hi")),
            ),
        )
        val result = engine.run(
            PlaybookCommand(
                taskId = "th1",
                playbook = "inbox_open_thread@1.0",
                params = mapOf("open_title_hint" to "静生百慧茶叶馆"),
            ),
        )!!
        assertTrue(result.success)
    }

    @Test
    fun readCommentsRejectsWrongPage() {
        val engine = engineWithDefaults(FakePlaybookRuntime(startHint = PageHint.HOME))
        val result = engine.run(PlaybookCommand(taskId = "rc-wp", playbook = "read_comments@1.0"))!!
        assertEquals("NAV_MISSING_HINT", result.errorCode)
    }

    @Test
    fun sameTaskIdDoesNotReExecuteAfterAcknowledge() {
        val checks = AtomicInteger(0)
        val runtime = object : FakePlaybookRuntime(startHint = PageHint.HOME) {
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
        startHint: PageHint? = PageHint.HOME,
        private val comments: List<ExtractedComment> = emptyList(),
        private val threads: List<ExtractedInboxThread> = emptyList(),
        private val messages: List<ExtractedDmMessage> = emptyList(),
        private val noteTitles: List<String> = emptyList(),
        private val clickOk: Boolean = false,
        private val setTextOk: Boolean = false,
        private val tapOk: Boolean = true,
    ) : PlaybookRuntime {
        private var hint: PageHint? = startHint
        private var onProfile = false
        private var onNotesList = false
        private var openedDm = false
        private val typedValues = mutableListOf<String>()

        override fun accessibilityAlive(): Boolean = alive
        override fun launchXhs(): Boolean = true
        override fun waitForXhsForeground(timeoutMs: Long): Boolean = foreground
        override fun currentPageHint(): PageHint? = hint
        override fun currentLease(): TargetLease? =
            if (foreground) TargetLease("com.xingin.xhs", 1) else null

        override fun observe(): UiSnapshot? {
            val nodes = mutableListOf(
                bottomTab("首页", 0),
                bottomTab("市集", 200),
                bottomTab("发布", 400),
                bottomTab("消息", 600),
                bottomTab("我", 800),
            )
            typedValues.forEach { value ->
                nodes += UiNode(text = value, editable = true)
            }
            when {
                openedDm -> {
                    nodes += UiNode(text = "发消息…", clickable = true)
                    nodes += UiNode(text = "当前在线")
                }
                hint == PageHint.INBOX -> {
                    nodes += UiNode(text = "消息")
                    nodes += UiNode(text = "赞和收藏", clickable = true)
                    nodes += UiNode(text = "陌生人消息", clickable = true)
                    nodes += UiNode(text = "系统通知", clickable = true)
                    threads.forEach { thread ->
                        nodes += UiNode(text = thread.titleSummary, clickable = true)
                    }
                }
                onProfile || onNotesList -> {
                    nodes += UiNode(text = "编辑资料", clickable = true)
                    nodes += UiNode(text = "粉丝")
                    nodes += UiNode(text = "关注")
                    nodes += UiNode(text = "笔记", clickable = true)
                    noteTitles.forEachIndexed { index, title ->
                        nodes += UiNode(
                            text = title,
                            contentDescription = "笔记,$title,来自逾期不候,2赞，41阅读",
                            clickable = true,
                            bounds = UiBounds(40, 500 + index * 120, 500, 600 + index * 120),
                        )
                    }
                }
                hint == PageHint.COMMENTS || hint == PageHint.NOTE_DETAIL -> {
                    nodes += UiNode(text = "留下你的想法吧", clickable = true)
                    nodes += UiNode(contentDescription = "评论", clickable = true)
                }
                else -> {
                    nodes += UiNode(contentDescription = "首页", clickable = true)
                    nodes += UiNode(contentDescription = "发现", clickable = true)
                }
            }
            return UiSnapshot(
                packageName = "com.xingin.xhs",
                className = "Frame",
                pageHint = hint ?: PageHint.HOME,
                keyElements = emptyList(),
                nodes = nodes,
                truncated = false,
            )
        }

        override fun extractComments(maxItems: Int) = comments.take(maxItems)
        override fun extractInboxThreads(maxItems: Int) = threads.take(maxItems)
        override fun extractDmMessages(maxItems: Int) =
            if (openedDm || messages.isNotEmpty()) messages.take(maxItems) else emptyList()
        override fun looksLikeInboxListSurface(): Boolean =
            hint == PageHint.INBOX && !openedDm
        override fun looksLikeOpenDmThreadSurface(): Boolean = openedDm
        override fun looksLikeCommentsSurface(): Boolean =
            hint == PageHint.COMMENTS || hint == PageHint.NOTE_DETAIL
        override fun looksLikeProfileSurface(): Boolean = onProfile || onNotesList

        override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean {
            when {
                selector.contains("消息") && !selector.contains("私信") -> {
                    hint = PageHint.INBOX
                    onProfile = false
                    onNotesList = false
                    openedDm = false
                    return true
                }
                selector.contains("首页") -> {
                    hint = PageHint.HOME
                    onProfile = false
                    onNotesList = false
                    openedDm = false
                    return true
                }
                selector.contains("我") -> {
                    enterProfile()
                    return true
                }
                selector.contains("笔记") && onProfile -> {
                    onNotesList = true
                    return true
                }
                selector.contains("评论") -> {
                    hint = PageHint.COMMENTS
                    return true
                }
                threads.any {
                    selector.contains(it.titleSummary) || it.locatorHint == selector
                } -> {
                    openedDm = true
                    hint = PageHint.UNKNOWN
                    return true
                }
                noteTitles.any { selector.contains(it) } -> {
                    hint = PageHint.NOTE_DETAIL
                    onNotesList = false
                    return true
                }
            }
            return clickOk
        }

        override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean {
            val thread = threads.firstOrNull {
                it.titleSummary.contains(fragment) || fragment.contains(it.titleSummary.take(4))
            }
            if (thread != null) {
                openedDm = true
                hint = PageHint.UNKNOWN
                return true
            }
            val note = noteTitles.firstOrNull { it.contains(fragment) || fragment.contains(it.take(4)) }
            if (note != null && (onProfile || onNotesList)) {
                hint = PageHint.NOTE_DETAIL
                onNotesList = false
                return true
            }
            return clickOk
        }

        override fun setText(selector: String, text: String, timeoutMs: Long): Boolean {
            if (!setTextOk) return false
            typedValues += text
            return true
        }
        override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
            if (y >= 2_000) {
                when {
                    x in 600..799 -> {
                        hint = PageHint.INBOX
                        onProfile = false
                        onNotesList = false
                        openedDm = false
                        return true
                    }
                    x >= 800 -> {
                        enterProfile()
                        return true
                    }
                    x <= 200 -> {
                        hint = PageHint.HOME
                        onProfile = false
                        onNotesList = false
                        openedDm = false
                        return true
                    }
                }
            }
            if ((onProfile || onNotesList) && noteTitles.isNotEmpty() && y in 400..900) {
                hint = PageHint.NOTE_DETAIL
                onNotesList = false
                return true
            }
            return tapOk
        }

        override fun swipe(
            startX: Int,
            startY: Int,
            endX: Int,
            endY: Int,
            durationMs: Long,
        ): Boolean = true

        override fun globalBack(): Boolean {
            if (openedDm) {
                openedDm = false
                hint = PageHint.INBOX
                return true
            }
            if (hint == PageHint.COMMENTS || hint == PageHint.NOTE_DETAIL) {
                enterProfile()
                return true
            }
            hint = PageHint.HOME
            onProfile = false
            onNotesList = false
            return true
        }

        override fun sleep(ms: Long) = Unit

        private fun enterProfile() {
            onProfile = true
            onNotesList = true
            openedDm = false
            hint = PageHint.UNKNOWN
        }

        private fun bottomTab(label: String, left: Int): UiNode =
            UiNode(
                text = label,
                contentDescription = label,
                clickable = true,
                bounds = UiBounds(left, 2_100, left + 180, 2_280),
            )
    }
}

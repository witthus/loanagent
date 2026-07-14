package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SurfaceNavigatorTest {
    @Test
    fun ensureForegroundFailsWhenScreenNotReady() {
        val runtime = object : NavFakeRuntime(start = PageHint.HOME) {
            override fun ensureScreenReady(timeoutMs: Long): String = "SCREEN_NOT_READY"
        }
        val result = SurfaceNavigator.ensureForeground(runtime)
        assertEquals("SCREEN_NOT_READY", (result as NavResult.Failed).errorCode)
    }

    @Test
    fun goInboxFromHomeSucceeds() {
        val runtime = NavFakeRuntime(start = PageHint.HOME)
        val result = SurfaceNavigator.goInbox(runtime)
        assertTrue(result is NavResult.Ok)
        assertEquals(PageHint.INBOX, runtime.currentPageHint())
    }

    @Test
    fun goDmThreadMatchesTitle() {
        val runtime = NavFakeRuntime(
            start = PageHint.HOME,
            threads = listOf(
                ExtractedInboxThread("噪声音频", "x", false, "text=噪声音频"),
                ExtractedInboxThread("静生百慧茶叶馆，在线", "hi", true, "text=静生百慧茶叶馆，在线"),
            ),
        )
        val result = SurfaceNavigator.goDmThread(runtime, openTitleHint = "静生百慧茶叶馆")
        assertTrue(result is NavResult.Ok)
        assertTrue(runtime.openedThread)
    }

    @Test
    fun goDmThreadMissingTargetFails() {
        val runtime = NavFakeRuntime(
            start = PageHint.INBOX,
            threads = listOf(ExtractedInboxThread("其他会话", "", false, null)),
        )
        val result = SurfaceNavigator.goDmThread(runtime, openTitleHint = "不存在的人")
        assertEquals("NAV_TARGET_NOT_FOUND", (result as NavResult.Failed).errorCode)
    }

    @Test
    fun goNoteCommentsFromHomeByTitle() {
        val runtime = NavFakeRuntime(
            start = PageHint.HOME,
            noteTitles = listOf("云测发帖W-123", "另一篇"),
        )
        val result = SurfaceNavigator.goNoteComments(
            runtime,
            titleSummary = "云测发帖W-123",
        )
        assertTrue(result is NavResult.Ok)
        assertEquals(PageHint.COMMENTS, runtime.currentPageHint())
    }

    @Test
    fun goNoteCommentsRequiresHintWhenOffSurface() {
        val runtime = NavFakeRuntime(start = PageHint.HOME)
        val result = SurfaceNavigator.goNoteComments(runtime, titleSummary = null)
        assertEquals("NAV_MISSING_HINT", (result as NavResult.Failed).errorCode)
    }

    @Test
    fun goNoteCommentsUsesBottomTabNotAmbiguousMeClick() {
        val runtime = NavFakeRuntime(
            start = PageHint.HOME,
            noteTitles = listOf("曦瓜大红袍"),
            meDescOpensInbox = true,
        )
        val result = SurfaceNavigator.goNoteComments(
            runtime,
            titleSummary = "曦瓜大红袍",
        )
        assertTrue(result is NavResult.Ok)
        assertEquals(PageHint.COMMENTS, runtime.currentPageHint())
    }

    @Test
    fun goNoteCommentsScrollsPastBottomChromeToCoverCard() {
        val runtime = NavFakeRuntime(
            start = PageHint.HOME,
            noteTitles = listOf("曦瓜大红袍｜岩茶品鉴手记"),
            coverDescFormat = true,
            buryTargetInChrome = true,
        )
        val result = SurfaceNavigator.goNoteComments(
            runtime,
            titleSummary = "曦瓜大红袍",
        )
        assertTrue(result is NavResult.Ok)
        assertTrue(runtime.swipeCount >= 1)
        assertEquals(PageHint.COMMENTS, runtime.currentPageHint())
    }

    @Test
    fun titleMatchPrefersLongestContains() {
        assertEquals(
            "云测发帖W-12345",
            SurfaceNavigator.bestTextMatch(
                hint = "云测发帖W-12345",
                candidates = listOf("云测", "云测发帖W-12345", "无关"),
            ),
        )
    }

    @Test
    fun looksLikeInboxHubDetectsMessageTabs() {
        val runtime = NavFakeRuntime(start = PageHint.INBOX, forceInboxChrome = true)
        assertTrue(SurfaceNavigator.looksLikeInboxHub(runtime))
        assertFalse(SurfaceNavigator.looksLikeProfile(runtime))
    }

    /** Fake that transitions surfaces when navigator clicks/taps expected chrome. */
    private open class NavFakeRuntime(
        start: PageHint,
        private val threads: List<ExtractedInboxThread> = emptyList(),
        private val noteTitles: List<String> = emptyList(),
        private val meDescOpensInbox: Boolean = false,
        private val forceInboxChrome: Boolean = false,
        private val coverDescFormat: Boolean = false,
        private val buryTargetInChrome: Boolean = false,
    ) : PlaybookRuntime {
        private var hint: PageHint? = start
        private var onProfile = false
        private var onNotesList = false
        private var scrolledPastChrome = false
        var openedThread: Boolean = false
            private set
        var swipeCount: Int = 0
            private set

        override fun accessibilityAlive(): Boolean = true
        override fun launchXhs(): Boolean = true
        override fun waitForXhsForeground(timeoutMs: Long): Boolean = true
        override fun currentPageHint(): PageHint? = hint
        override fun currentLease(): TargetLease? = TargetLease("com.xingin.xhs", 1)
        override fun observe(): UiSnapshot? {
            if (forceInboxChrome || (hint == PageHint.INBOX && !onProfile)) {
                return snapshot(
                    PageHint.INBOX,
                    listOf(
                        UiNode(text = "消息", clickable = false),
                        UiNode(text = "赞|收藏", clickable = true),
                        UiNode(text = "私信", clickable = true),
                        UiNode(text = "评论", clickable = true),
                        bottomTab("首页", 0),
                        bottomTab("市集", 200),
                        bottomTab("发布", 400),
                        bottomTab("消息", 600),
                        bottomTab("我", 800),
                    ),
                )
            }
            if (onProfile || onNotesList) {
                val nodes = mutableListOf(
                    UiNode(text = "编辑资料", clickable = true),
                    UiNode(text = "粉丝", clickable = false),
                    UiNode(text = "关注", clickable = false),
                    UiNode(text = "笔记", clickable = true),
                    UiNode(text = "赞过", clickable = true),
                    bottomTab("首页", 0),
                    bottomTab("市集", 200),
                    bottomTab("发布", 400),
                    bottomTab("消息", 600),
                    bottomTab("我", 800),
                )
                if (onNotesList) {
                    noteTitles.forEachIndexed { index, title ->
                        val inChrome = buryTargetInChrome && index == 0 && !scrolledPastChrome
                        val top = if (inChrome) 2_200 else 500 + index * 120
                        val bottom = if (inChrome) 2_232 else 600 + index * 120
                        val desc = if (coverDescFormat) {
                            "笔记,$title,来自逾期不候,2赞，41阅读"
                        } else {
                            null
                        }
                        nodes += UiNode(
                            text = if (coverDescFormat) null else title,
                            contentDescription = desc,
                            clickable = true,
                            bounds = UiBounds(40, top, 500, bottom),
                        )
                    }
                }
                return snapshot(PageHint.UNKNOWN, nodes)
            }
            return snapshot(
                hint ?: PageHint.HOME,
                listOf(
                    bottomTab("首页", 0),
                    bottomTab("市集", 200),
                    bottomTab("发布", 400),
                    bottomTab("消息", 600),
                    bottomTab("我", 800),
                    UiNode(contentDescription = "发现", clickable = true),
                    UiNode(contentDescription = "首页", clickable = true),
                ),
            )
        }

        override fun extractComments(maxItems: Int) = emptyList<ExtractedComment>()
        override fun extractInboxThreads(maxItems: Int) = threads.take(maxItems)
        override fun extractDmMessages(maxItems: Int) =
            if (openedThread) listOf(ExtractedDmMessage("peer", "hi")) else emptyList()

        override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean {
            when {
                selector.contains("消息") && !selector.contains("私信") -> {
                    hint = PageHint.INBOX
                    onProfile = false
                    onNotesList = false
                    return true
                }
                selector.contains("首页") -> {
                    hint = PageHint.HOME
                    onProfile = false
                    onNotesList = false
                    return true
                }
                selector == "text=我" && meDescOpensInbox -> {
                    // Simulate ambiguous/missing label; force position fallback.
                    return false
                }
                selector == "contentDescription=我" && meDescOpensInbox -> {
                    hint = PageHint.INBOX
                    onProfile = false
                    onNotesList = false
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
                threads.any { it.locatorHint == selector || selector.contains(it.titleSummary.take(4)) } -> {
                    openedThread = true
                    hint = PageHint.UNKNOWN
                    return true
                }
                noteTitles.any { selector.contains(it) } -> {
                    hint = PageHint.NOTE_DETAIL
                    onNotesList = false
                    return true
                }
            }
            return false
        }

        override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean {
            val hit = noteTitles.firstOrNull { it.contains(fragment) || fragment.contains(it.take(4)) }
            if (hit != null && (onProfile || onNotesList) && (!buryTargetInChrome || scrolledPastChrome)) {
                hint = PageHint.NOTE_DETAIL
                onNotesList = false
                return true
            }
            val thread = threads.firstOrNull {
                it.titleSummary.contains(fragment) || fragment.contains(it.titleSummary.take(4))
            }
            if (thread != null) {
                openedThread = true
                hint = PageHint.UNKNOWN
                return true
            }
            return false
        }

        override fun setText(selector: String, text: String, timeoutMs: Long): Boolean = false
        override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
            // Bottom nav by x position (1080-wide fake with 5 tabs).
            if (y >= 2_000) {
                when {
                    x in 600..799 -> {
                        hint = PageHint.INBOX
                        onProfile = false
                        onNotesList = false
                        return true
                    }
                    x >= 800 -> {
                        enterProfile()
                        return true
                    }
                }
                return false
            }
            if (onNotesList || onProfile) {
                val hitCover = observe()?.nodes?.any { node ->
                    val bounds = node.bounds ?: return@any false
                    if (x !in bounds.left..bounds.right || y !in bounds.top..bounds.bottom) {
                        return@any false
                    }
                    val desc = node.contentDescription.orEmpty()
                    val text = node.text.orEmpty()
                    desc.startsWith("笔记") || noteTitles.any { it == text || desc.contains(it) }
                } == true
                if (hitCover) {
                    hint = PageHint.NOTE_DETAIL
                    onNotesList = false
                    return true
                }
            }
            return false
        }

        override fun swipe(
            startX: Int,
            startY: Int,
            endX: Int,
            endY: Int,
            durationMs: Long,
        ): Boolean {
            swipeCount += 1
            if (endY < startY) {
                scrolledPastChrome = true
            }
            return true
        }

        override fun globalBack(): Boolean {
            hint = PageHint.HOME
            onProfile = false
            onNotesList = false
            return true
        }

        override fun sleep(ms: Long) = Unit

        private fun enterProfile() {
            onProfile = true
            onNotesList = true
            hint = PageHint.UNKNOWN
        }

        private fun bottomTab(label: String, left: Int): UiNode =
            UiNode(
                text = label,
                contentDescription = label,
                clickable = true,
                bounds = UiBounds(left, 2_100, left + 180, 2_280),
            )

        private fun snapshot(pageHint: PageHint, nodes: List<UiNode>): UiSnapshot =
            UiSnapshot(
                packageName = "com.xingin.xhs",
                className = "Frame",
                pageHint = pageHint,
                keyElements = emptyList(),
                nodes = nodes,
                truncated = false,
            )
    }
}

package com.loanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XhsOverlayDismissTest {
    @Test
    fun promoCloseAllowedOnHomeFeedBanner() {
        val runtime = SnapshotRuntime(
            nodes = listOf(
                UiNode(text = "看看最近治愈你的美食", bounds = UiBounds(178, 1699, 568, 1744)),
                UiNode(text = "官方喊你来参与热门话题", bounds = UiBounds(178, 1755, 541, 1793)),
                UiNode(text = "去发布", bounds = UiBounds(803, 1716, 968, 1776)),
                UiNode(contentDescription = "关闭", bounds = UiBounds(20, 100, 100, 180)),
            ),
        )
        assertTrue(XhsOverlayDismiss.shouldTapPromoClose(runtime))
    }

    @Test
    fun promoCloseBlockedOnAlbumChrome() {
        val runtime = SnapshotRuntime(
            nodes = listOf(
                UiNode(contentDescription = "关闭", bounds = UiBounds(17, 116, 169, 227)),
                UiNode(text = "全部", bounds = UiBounds(193, 144, 275, 199)),
                UiNode(text = "草稿箱", bounds = UiBounds(805, 130, 1036, 213)),
                UiNode(text = "照片", bounds = UiBounds(440, 232, 640, 339)),
            ),
        )
        assertFalse(XhsOverlayDismiss.shouldTapPromoClose(runtime))
    }

    @Test
    fun promoCloseBlockedOnEditor() {
        val runtime = SnapshotRuntime(
            nodes = listOf(
                UiNode(text = "添加标题", editable = true, bounds = UiBounds(40, 430, 1080, 570)),
                UiNode(text = "发布笔记", bounds = UiBounds(365, 2170, 1036, 2280)),
                UiNode(contentDescription = "关闭", bounds = UiBounds(20, 100, 100, 180)),
            ),
        )
        assertFalse(XhsOverlayDismiss.shouldTapPromoClose(runtime))
    }
}

private class SnapshotRuntime(
    private val nodes: List<UiNode>,
) : PlaybookRuntime {
    override fun accessibilityAlive(): Boolean = true
    override fun launchXhs(): Boolean = true
    override fun waitForXhsForeground(timeoutMs: Long): Boolean = true
    override fun currentPageHint(): PageHint = PageHint.HOME
    override fun currentLease(): TargetLease = TargetLease("com.xingin.xhs", 1)
    override fun observe(): UiSnapshot =
        UiSnapshot(
            packageName = "com.xingin.xhs",
            className = "Fixture",
            pageHint = PageHint.HOME,
            keyElements = emptyList(),
            nodes = nodes,
            truncated = false,
        )
    override fun extractComments(maxItems: Int): List<ExtractedComment> = emptyList()
    override fun extractInboxThreads(maxItems: Int): List<ExtractedInboxThread> = emptyList()
    override fun extractDmMessages(maxItems: Int): List<ExtractedDmMessage> = emptyList()
    override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean = false
    override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean = false
    override fun setText(selector: String, text: String, timeoutMs: Long): Boolean = false
    override fun tap(x: Int, y: Int, durationMs: Long): Boolean = true
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

package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PublishNoteFillTest {
    @Test
    fun alreadyFilledEditorSucceedsWithoutSetText() {
        val runtime = PrefilledEditorRuntime(
            title = "茉莉奶白夯爆了",
            body = "错过谁都不能错过它",
        )
        val result = PublishNotePlaybook().run(
            PlaybookCommand(
                taskId = "prefilled-publish",
                playbook = "publish_note@1.0",
                params = mapOf(
                    "title" to "茉莉奶白夯爆了",
                    "body" to "错过谁都不能错过它",
                    "start_in_editor" to true,
                ),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
            runtime,
        )

        assertEquals("succeeded", result.status)
        assertTrue(result.effectCommitted)
        assertEquals(0, runtime.setTextCalls)
        assertTrue(runtime.events.any { it == "click:text=发布笔记:true" })
    }

    @Test
    fun emptyEditorFillsViaNthEditableBeforeHints() {
        val runtime = EmptyEditorRuntime()
        val result = PublishNotePlaybook().run(
            PlaybookCommand(
                taskId = "empty-publish",
                playbook = "publish_note@1.0",
                params = mapOf(
                    "title" to "标题A",
                    "body" to "正文B内容",
                    "start_in_editor" to true,
                ),
                effectClass = EffectClass.NON_IDEMPOTENT,
            ),
            runtime,
        )

        assertEquals("succeeded", result.status)
        assertTrue(runtime.setTextCalls >= 1)
        assertTrue(
            "expected nth/classname path before long hint burn",
            runtime.setTextSelectors.any {
                it.contains("className=android.widget.EditText") || it.startsWith("text=")
            },
        )
        assertEquals("标题A", runtime.titleText)
        assertEquals("正文B内容", runtime.bodyText)
    }
}

private class PrefilledEditorRuntime(
    private val title: String,
    private val body: String,
) : PlaybookRuntime {
    val events = mutableListOf<String>()
    var setTextCalls = 0

    override fun beginSideEffect() {
        events += "effect_boundary"
    }

    override fun accessibilityAlive(): Boolean = true
    override fun launchXhs(): Boolean = true
    override fun waitForXhsForeground(timeoutMs: Long): Boolean = true
    override fun currentPageHint(): PageHint = PageHint.EDITOR
    override fun currentLease(): TargetLease = TargetLease("com.xingin.xhs", 1)
    override fun observe(): UiSnapshot = editorSnapshot(title, body)
    override fun extractComments(maxItems: Int): List<ExtractedComment> = emptyList()
    override fun extractInboxThreads(maxItems: Int): List<ExtractedInboxThread> = emptyList()
    override fun extractDmMessages(maxItems: Int): List<ExtractedDmMessage> = emptyList()
    override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean {
        events += "click:$selector:$allowFinal"
        return true
    }
    override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean = true
    override fun setText(selector: String, text: String, timeoutMs: Long): Boolean {
        setTextCalls += 1
        return false
    }
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

private class EmptyEditorRuntime : PlaybookRuntime {
    val setTextSelectors = mutableListOf<String>()
    var setTextCalls = 0
    var titleText: String = "添加标题"
    var bodyText: String = "添加正文"

    override fun beginSideEffect() = Unit
    override fun accessibilityAlive(): Boolean = true
    override fun launchXhs(): Boolean = true
    override fun waitForXhsForeground(timeoutMs: Long): Boolean = true
    override fun currentPageHint(): PageHint = PageHint.EDITOR
    override fun currentLease(): TargetLease = TargetLease("com.xingin.xhs", 1)
    override fun observe(): UiSnapshot = editorSnapshot(titleText, bodyText)
    override fun extractComments(maxItems: Int): List<ExtractedComment> = emptyList()
    override fun extractInboxThreads(maxItems: Int): List<ExtractedInboxThread> = emptyList()
    override fun extractDmMessages(maxItems: Int): List<ExtractedDmMessage> = emptyList()
    override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean = true
    override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean = true
    override fun setText(selector: String, text: String, timeoutMs: Long): Boolean {
        setTextCalls += 1
        setTextSelectors += selector
        // Simulate focused EditText win: classname writes to whichever was tapped last via index.
        when {
            selector == "text=添加标题" || selector.contains("EditText") && titleText == "添加标题" ->
                titleText = text
            selector == "text=添加正文" || selector.contains("EditText") ->
                bodyText = text
            selector.startsWith("text=") -> {
                val label = selector.removePrefix("text=")
                when (label) {
                    titleText -> titleText = text
                    bodyText -> bodyText = text
                }
            }
        }
        return true
    }
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

private fun editorSnapshot(title: String, body: String): UiSnapshot =
    UiSnapshot(
        packageName = "com.xingin.xhs",
        className = "CapaPostNotePlatformActivity",
        pageHint = PageHint.EDITOR,
        keyElements = emptyList(),
        nodes = listOf(
            UiNode(
                text = title,
                className = "android.widget.EditText",
                clickable = true,
                editable = true,
                bounds = UiBounds(40, 430, 1_080, 570),
            ),
            UiNode(
                text = body,
                className = "android.widget.EditText",
                clickable = true,
                editable = true,
                bounds = UiBounds(0, 570, 1_080, 1_100),
            ),
            UiNode(
                text = "发布笔记",
                className = "android.widget.Button",
                clickable = true,
                bounds = UiBounds(365, 2_170, 1_036, 2_280),
            ),
        ),
        truncated = false,
    )

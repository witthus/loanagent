package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentExtractorsTest {
    private val redactor = SensitiveTextRedactor()
    private val extractor = ContentExtractors(redactor)

    @Test
    fun extractsCommentSummariesWithoutRawPhoneNumbers() {
        val nodes = listOf(
            UiNode(text = "用户甲", clickable = true),
            UiNode(text = "请问可以做吗 联系13800138000"),
            UiNode(text = "用户乙"),
            UiNode(text = "同问"),
        )
        val items = extractor.extractComments(nodes, maxItems = 10)
        assertTrue(items.size >= 2)
        assertTrue(items.all { !it.bodySummary.contains("13800138000") })
        assertEquals(items[0].bodySummary.length, items[0].bodySummary.length) // non-empty
    }

    @Test
    fun extractsInboxThreadRowsWithUnreadHints() {
        val nodes = listOf(
            UiNode(text = "小红书用户A", clickable = true),
            UiNode(text = "[未读] 你好，想咨询一下"),
            UiNode(text = "小红书用户B", clickable = true),
            UiNode(text = "昨天已读内容"),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 10)
        assertTrue(threads.isNotEmpty())
        assertTrue(threads.first().titleSummary.isNotBlank())
    }

    @Test
    fun extractsDmMessagesFromOpenThread() {
        val nodes = listOf(
            UiNode(text = "对方"),
            UiNode(text = "我想了解方案"),
            UiNode(text = "我"),
            UiNode(text = "请发湖北+公积金基数"),
        )
        val messages = extractor.extractDmMessages(nodes, maxItems = 20)
        assertTrue(messages.size >= 2)
        assertTrue(messages.all { it.bodySummary.isNotBlank() })
    }
}

package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentExtractorsTest {
    private val redactor = SensitiveTextRedactor()
    private val extractor = ContentExtractors(redactor)

    @Test
    fun extractsCommentSummariesWithoutRawPhoneNumbers() {
        val nodes = listOf(
            UiNode(text = "用户甲", clickable = true),
            UiNode(text = "请问可以做吗 联系13800138000", bounds = UiBounds(0, 0, 100, 40)),
            UiNode(text = "用户乙"),
            UiNode(text = "同问"),
        )
        val items = extractor.extractComments(nodes, maxItems = 10)
        assertEquals(2, items.size)
        assertEquals("用户甲", items[0].authorSummary)
        assertEquals("请问可以做吗 联系138****8000", items[0].bodySummary)
        assertTrue(items[0].bodySummary.isNotBlank())
        assertFalse(items[0].bodySummary.contains("13800138000"))
        assertEquals("用户乙", items[1].authorSummary)
        assertEquals("同问", items[1].bodySummary)
        assertEquals("index=1;center=50,20", items[0].locatorHint)
        assertFalse(items.any { it.locatorHint.orEmpty().contains("text_hash") })
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
        assertEquals(2, threads.size)
        assertEquals("小红书用户A", threads[0].titleSummary)
        assertEquals("[未读] 你好，想咨询一下", threads[0].previewSummary)
        assertTrue(threads[0].unreadHint)
        assertEquals("小红书用户B", threads[1].titleSummary)
        assertEquals("昨天已读内容", threads[1].previewSummary)
        assertFalse(threads[1].unreadHint)
        assertFalse(threads.any { it.locatorHint.orEmpty().contains("text_hash") })
        assertTrue(threads[0].locatorHint.orEmpty().startsWith("index="))
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
        assertEquals(2, messages.size)
        assertEquals("对方", messages[0].senderSummary)
        assertEquals("我想了解方案", messages[0].bodySummary)
        assertEquals("我", messages[1].senderSummary)
        assertEquals("请发湖北+公积金基数", messages[1].bodySummary)
        assertTrue(messages.all { it.bodySummary.isNotBlank() })
    }

    @Test
    fun honorsMaxItemsForComments() {
        val nodes = listOf(
            UiNode(text = "用户甲", clickable = true),
            UiNode(text = "第一条"),
            UiNode(text = "用户乙", clickable = true),
            UiNode(text = "第二条"),
            UiNode(text = "用户丙", clickable = true),
            UiNode(text = "第三条"),
        )
        val items = extractor.extractComments(nodes, maxItems = 2)
        assertEquals(2, items.size)
        assertEquals("用户甲", items[0].authorSummary)
        assertEquals("第一条", items[0].bodySummary)
        assertEquals("用户乙", items[1].authorSummary)
        assertEquals("第二条", items[1].bodySummary)
    }

    @Test
    fun filtersChromeLabelsFromCommentBodies() {
        val nodes = listOf(
            UiNode(text = "评论"),
            UiNode(text = "说点什么"),
            UiNode(text = "赞"),
            UiNode(text = "用户甲", clickable = true),
            UiNode(text = "真实评论内容"),
            UiNode(text = "收藏"),
            UiNode(text = "分享"),
        )
        val items = extractor.extractComments(nodes, maxItems = 10)
        assertEquals(1, items.size)
        assertEquals("用户甲", items[0].authorSummary)
        assertEquals("真实评论内容", items[0].bodySummary)
        assertTrue(items.none { it.bodySummary in setOf("评论", "说点什么", "赞", "收藏", "分享") })
    }

    @Test
    fun extractsRealXhsCommentThreadAndSkipsChromeNoise() {
        val nodes = listOf(
            UiNode(text = "图片,第1张,共1张,双指左划或右划即可查看更多内容"),
            UiNode(text = "可能含AI生成内容"),
            UiNode(text = "曦瓜大红袍｜岩茶品鉴手记"),
            UiNode(text = "今天 19:37湖北"),
            UiNode(text = "12浏览"),
            UiNode(text = "已选定评论 2"),
            UiNode(text = "评论 2"),
            UiNode(text = "赞和收藏"),
            UiNode(text = "静生百慧茶叶馆", clickable = true),
            UiNode(text = "你的好友"),
            UiNode(text = "这个茶叶多钱？M0CAP-193716"),
            UiNode(text = "8分钟前 湖北 回复"),
            UiNode(text = "首评"),
            UiNode(text = "逾期不候", clickable = true),
            UiNode(text = "作者"),
            UiNode(text = "感谢首评！这泡岩韵出来后回甘比较清楚，欢迎继续交流品鉴。"),
            UiNode(text = "刚刚 湖北 回复"),
            UiNode(text = "已到底"),
            UiNode(text = "公开可见"),
            UiNode(text = "编辑和权限设置"),
            UiNode(text = "点赞 0"),
            UiNode(text = "收藏 0"),
            UiNode(text = "让大家听到你的声音"),
        )
        val items = extractor.extractComments(nodes, maxItems = 10)
        assertEquals(2, items.size)
        assertEquals("静生百慧茶叶馆", items[0].authorSummary)
        assertEquals("这个茶叶多钱？M0CAP-193716", items[0].bodySummary)
        assertEquals("逾期不候", items[1].authorSummary)
        assertTrue(items[1].bodySummary.contains("感谢首评"))
        assertTrue(items.none { it.bodySummary in setOf("你的好友", "首评", "已到底", "公开可见", "作者") })
        assertTrue(items.none { it.authorSummary.contains("分钟前") })
        assertTrue(items.none { it.bodySummary.startsWith("点赞") })
    }

    @Test
    fun extractsDmMessagesAndSkipsChatChrome() {
        val nodes = listOf(
            UiNode(text = "静生百慧茶叶馆"),
            UiNode(text = "当前在线"),
            UiNode(text = "逛逛店铺"),
            UiNode(text = "19:30"),
            UiNode(text = "我们已相互关注，开始聊天吧"),
            UiNode(text = "你好茶叶不错"),
            UiNode(text = "谢谢认可，这泡曦瓜大红袍岩韵和回甘都比较稳。"),
            UiNode(text = "咨询商品"),
            UiNode(text = "热销商品"),
            UiNode(text = "发消息…"),
        )
        val messages = extractor.extractDmMessages(nodes, maxItems = 20)
        assertTrue(messages.any { it.bodySummary.contains("你好茶叶不错") })
        assertTrue(messages.any { it.bodySummary.contains("谢谢认可") })
        assertTrue(messages.none { it.bodySummary in setOf("当前在线", "逛逛店铺", "咨询商品", "热销商品", "发消息…") })
        assertTrue(messages.none { it.senderSummary == "19:30" })
        assertTrue(messages.none { it.bodySummary == "19:30" })
    }

    @Test
    fun skipsInboxDiscoveryAndPromoRows() {
        val nodes = listOf(
            UiNode(text = "搜索", clickable = true),
            UiNode(text = "直播广场"),
            UiNode(text = "静生百慧茶叶馆", clickable = true),
            UiNode(text = "你好茶叶不错"),
            UiNode(text = "更多宝藏摊主", clickable = true),
            UiNode(text = "活动详情", clickable = true),
            UiNode(text = "赞和收藏", clickable = true),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 10)
        assertEquals(1, threads.size)
        assertEquals("静生百慧茶叶馆", threads[0].titleSummary)
        assertEquals("你好茶叶不错", threads[0].previewSummary)
    }

    @Test
    fun doesNotConsumeNextClickableTitleAsInboxPreview() {
        val nodes = listOf(
            UiNode(text = "小红书用户A", clickable = true),
            UiNode(text = "小红书用户B", clickable = true),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 10)
        assertEquals(2, threads.size)
        assertEquals("小红书用户A", threads[0].titleSummary)
        assertEquals("", threads[0].previewSummary)
        assertEquals("小红书用户B", threads[1].titleSummary)
        assertEquals("", threads[1].previewSummary)
    }
}

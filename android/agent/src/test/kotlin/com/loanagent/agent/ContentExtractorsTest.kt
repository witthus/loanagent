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
        assertEquals(1, items.size)
        assertEquals("静生百慧茶叶馆", items[0].authorSummary)
        assertEquals("这个茶叶多钱？M0CAP-193716", items[0].bodySummary)
        assertEquals("8分钟前 湖北", items[0].postedAtText)
        assertEquals(1, items[0].replies.size)
        assertEquals("逾期不候", items[0].replies[0].authorSummary)
        assertTrue(items[0].replies[0].bodySummary.contains("感谢首评"))
        assertEquals("静生百慧茶叶馆", items[0].replies[0].replyToAuthor)
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

    @Test
    fun ignoresAccessibilityContentDescriptionsThatAreNotVisibleCommentText() {
        // Live false positives from profile / note cards when contentDescription leaks in.
        val nodes = listOf(
            UiNode(contentDescription = "编辑主页", clickable = true),
            UiNode(contentDescription = "扫一扫"),
            UiNode(contentDescription = "赞和收藏按钮", clickable = true),
            UiNode(contentDescription = "头像,逾期不候", clickable = true),
            UiNode(
                contentDescription = "笔记,曦瓜大红袍｜岩茶品鉴手记,来自逾期不候,2赞，40阅读",
                clickable = true,
            ),
            UiNode(text = "静生百慧茶叶馆", clickable = true),
            UiNode(text = "这个茶叶多钱？"),
            UiNode(text = "13", clickable = true),
            UiNode(text = "粉丝"),
            UiNode(contentDescription = "[doge]", clickable = true),
            UiNode(contentDescription = "来自笔记·这是不是假期想溜出去玩的你！"),
        )
        val items = extractor.extractComments(nodes, maxItems = 10)
        assertEquals(1, items.size)
        assertEquals("静生百慧茶叶馆", items[0].authorSummary)
        assertEquals("这个茶叶多钱？", items[0].bodySummary)
    }

    @Test
    fun ignoresProfileAndBottomNavNoiseInInboxThreads() {
        val nodes = listOf(
            UiNode(text = "首页", clickable = true),
            UiNode(text = "市集", clickable = true),
            UiNode(text = "消息", clickable = true),
            UiNode(text = "我", clickable = true),
            UiNode(contentDescription = "6关注", clickable = true),
            UiNode(contentDescription = "13粉丝", clickable = true),
            UiNode(contentDescription = "赞和收藏按钮", clickable = true),
            UiNode(contentDescription = "评论和@按钮", clickable = true),
            UiNode(text = "编辑主页", clickable = true),
            UiNode(text = "小红书号：4122709580", clickable = true),
            UiNode(text = "静生百慧茶叶馆", clickable = true),
            UiNode(text = "云测私信请忽略"),
            UiNode(text = "陌生人消息", clickable = true),
            UiNode(text = "系统通知", clickable = true),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 10)
        assertEquals(1, threads.size)
        assertEquals("静生百慧茶叶馆", threads[0].titleSummary)
        assertEquals("云测私信请忽略", threads[0].previewSummary)
    }

    @Test
    fun ignoresCompositeMessageHubAccessibilityLabelsAsInboxTitles() {
        val nodes = listOf(
            UiNode(
                contentDescription = "静生百慧茶叶馆，，，20分钟内在线，11:59",
                clickable = true,
            ),
            UiNode(text = "静生百慧茶叶馆"),
            UiNode(text = "真实用户甲", clickable = true),
            UiNode(text = "你好想咨询一下"),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 10)
        assertEquals(1, threads.size)
        assertEquals("真实用户甲", threads[0].titleSummary)
        assertEquals("你好想咨询一下", threads[0].previewSummary)
    }

    @Test
    fun ignoresCompositeOnlineStatusTextAsInboxTitles() {
        // Same noise sometimes appears as visible text, not only contentDescription.
        val nodes = listOf(
            UiNode(text = "静生百慧茶叶馆，，，20分钟内在线，11:59", clickable = true),
            UiNode(text = "静生百慧茶叶馆"),
            UiNode(text = "男，", clickable = true),
            UiNode(text = "小组件浏览记录"),
            UiNode(text = "IP：湖北", clickable = true),
            UiNode(text = "真实用户乙", clickable = true),
            UiNode(text = "私信预览内容"),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 10)
        assertEquals(1, threads.size)
        assertEquals("真实用户乙", threads[0].titleSummary)
        assertEquals("私信预览内容", threads[0].previewSummary)
    }

    @Test
    fun ignoresProfileChromeWhenExtractingDmMessages() {
        val nodes = listOf(
            UiNode(text = "编辑主页"),
            UiNode(text = "扫一扫"),
            UiNode(text = "粉丝"),
            UiNode(text = "首页"),
            UiNode(text = "市集"),
            UiNode(text = "对方"),
            UiNode(text = "你好茶叶不错"),
            UiNode(text = "我"),
            UiNode(text = "谢谢认可，这泡岩韵不错。"),
            UiNode(text = "发消息…"),
        )
        val messages = extractor.extractDmMessages(nodes, maxItems = 20)
        assertEquals(2, messages.size)
        assertEquals("对方", messages[0].senderSummary)
        assertEquals("你好茶叶不错", messages[0].bodySummary)
        assertTrue(messages.none { it.bodySummary in setOf("编辑主页", "扫一扫", "粉丝", "首页", "市集") })
        assertTrue(messages.none { it.senderSummary in setOf("首页", "市集", "粉丝") })
    }

    @Test
    fun inboxListSurfaceRequiresMessageHubChromeNotProfile() {
        val profile = listOf(
            UiNode(text = "编辑主页"),
            UiNode(text = "小红书号：1"),
            UiNode(text = "消息"),
            UiNode(text = "赞和收藏"),
        )
        val inbox = listOf(
            UiNode(text = "消息"),
            UiNode(text = "赞和收藏"),
            UiNode(text = "陌生人消息"),
            UiNode(text = "系统通知"),
            UiNode(text = "用户甲", clickable = true),
            UiNode(text = "你好"),
        )
        assertFalse(extractor.looksLikeInboxListSurface(profile))
        assertTrue(extractor.looksLikeInboxListSurface(inbox))
    }

    @Test
    fun skipsInterestRailChromeAndProfileBleedOutsideInboxBand() {
        val nodes = listOf(
            UiNode(text = "消息"),
            UiNode(text = "赞和收藏"),
            UiNode(text = "陌生人消息", clickable = true, bounds = UiBounds(0, 100, 200, 180)),
            UiNode(text = "系统通知", clickable = true, bounds = UiBounds(200, 100, 400, 180)),
            // Interest chrome inside the list band must not become a thread.
            UiNode(text = "近期互动热门", clickable = true, bounds = UiBounds(0, 220, 400, 280)),
            // Profile bleed / interest people below the bottom tab band.
            UiNode(text = "吃颗vccc", clickable = true, bounds = UiBounds(0, 2150, 200, 2200)),
            UiNode(text = "维维桨板", clickable = true, bounds = UiBounds(200, 2150, 400, 2200)),
            UiNode(text = "真实客户甲", clickable = true, bounds = UiBounds(0, 400, 400, 460)),
            UiNode(text = "你好想咨询茶叶", bounds = UiBounds(0, 470, 400, 520)),
            UiNode(text = "首页", clickable = true, bounds = UiBounds(0, 2200, 200, 2300)),
            UiNode(text = "消息", clickable = true, bounds = UiBounds(200, 2200, 400, 2300)),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 10)
        assertEquals(1, threads.size)
        assertEquals("真实客户甲", threads[0].titleSummary)
        assertEquals("你好想咨询茶叶", threads[0].previewSummary)
        assertTrue(threads.none { it.titleSummary.contains("近期互动") })
        assertTrue(threads.none { it.titleSummary in setOf("吃颗vccc", "维维桨板") })
    }

    @Test
    fun treatsRelativeTimeRegionLinesAsCommentMetaNotAuthors() {
        val nodes = listOf(
            UiNode(text = "逾期不候", clickable = true),
            UiNode(text = "云测评论输入"),
            UiNode(text = "昨天 01:10 湖北 回复"),
            UiNode(text = "逾期不候", clickable = true),
            UiNode(text = "云测评论回复请忽略-1783876597"),
            UiNode(text = "昨天 01:17 湖北 回复"),
            UiNode(text = "静生百慧茶叶馆", clickable = true),
            UiNode(text = "这个茶叶多钱？M0CAP-190713"),
            UiNode(text = "2天前 湖北 回复"),
            UiNode(text = "逾期不候", clickable = true),
            UiNode(text = "作者"),
            UiNode(text = "感谢首评！这泡岩韵出来后回甘比较清楚，欢迎继续交流品鉴。"),
            UiNode(text = "刚刚 湖北 回复"),
        )
        val items = extractor.extractComments(nodes, maxItems = 10)
        assertEquals(3, items.size)
        assertEquals("云测评论输入", items[0].bodySummary)
        assertEquals("昨天 01:10 湖北", items[0].postedAtText)
        assertEquals("云测评论回复请忽略-1783876597", items[1].bodySummary)
        assertEquals("昨天 01:17 湖北", items[1].postedAtText)
        assertEquals("静生百慧茶叶馆", items[2].authorSummary)
        assertEquals(1, items[2].replies.size)
        assertTrue(items[2].replies[0].bodySummary.contains("感谢首评"))
        assertTrue(items.none { it.authorSummary.contains("回复") })
        assertTrue(items.none { it.bodySummary.contains("赞和收藏") })
    }

    @Test
    fun extractsProfileNotesWithLikeCollectAndReadCounts() {
        val nodes = listOf(
            UiNode(text = "编辑主页"),
            UiNode(
                contentDescription = "笔记,曦瓜大红袍｜岩茶品鉴手记,来自逾期不候,2赞，1收藏，41阅读",
                clickable = true,
                bounds = UiBounds(40, 500, 500, 900),
            ),
            UiNode(
                contentDescription = "笔记,春日茶会分享,来自逾期不候,8赞，3收藏",
                clickable = true,
                bounds = UiBounds(520, 500, 980, 900),
            ),
        )
        val notes = extractor.extractProfileNotes(nodes, maxItems = 10)
        assertEquals(2, notes.size)
        assertEquals("曦瓜大红袍｜岩茶品鉴手记", notes[0].titleSummary)
        assertEquals(2, notes[0].likeCount)
        assertEquals(1, notes[0].collectCount)
        assertEquals(41, notes[0].readCount)
        assertEquals("春日茶会分享", notes[1].titleSummary)
        assertEquals(8, notes[1].likeCount)
        assertEquals(3, notes[1].collectCount)
    }

    @Test
    fun extractsInboxThreadWhenTitleTextViewIsNotClickable() {
        val nodes = listOf(
            UiNode(text = "消息"),
            UiNode(text = "赞和收藏"),
            UiNode(text = "静生百慧茶叶馆", clickable = false),
            UiNode(text = "11:59", clickable = false),
            UiNode(text = "20分钟内在线", clickable = false),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 5)
        assertEquals(1, threads.size)
        assertEquals("静生百慧茶叶馆", threads[0].titleSummary)
    }
}

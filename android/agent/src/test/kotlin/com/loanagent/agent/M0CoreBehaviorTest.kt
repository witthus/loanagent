package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M0CoreBehaviorTest {
    @Test
    fun snapshotLimitsDepthNodeCountAndTextLength() {
        val root = RawUiNode(
            viewId = "root",
            text = "x".repeat(50),
            children = listOf(
                RawUiNode(viewId = "first", children = listOf(RawUiNode(viewId = "too-deep"))),
                RawUiNode(viewId = "over-limit"),
            ),
        )

        val snapshot = SnapshotBuilder(
            SnapshotLimits(maxDepth = 1, maxNodes = 2, maxTextLength = 8),
            SensitiveTextRedactor(),
        ).build("com.xingin.xhs", "HomeActivity", root)

        assertEquals(2, snapshot.nodes.size)
        assertEquals("xxxxxxxx", snapshot.nodes.first().text)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun selectorUsesStableAttributesBeforeClassAndClickable() {
        val generic = UiNode(className = "android.widget.Button", clickable = true)
        val exact = UiNode(
            viewId = "com.xingin.xhs:id/publish",
            text = "发布",
            className = "android.widget.Button",
            clickable = true,
        )

        val match = SelectorEngine().find(
            listOf(generic, exact),
            Selector(
                viewId = "com.xingin.xhs:id/publish",
                text = "发布",
                className = "android.widget.Button",
                clickable = true,
            ),
        )

        assertEquals(SelectorMatchStatus.UNIQUE, match.status)
        assertEquals(exact, match.node)
    }

    @Test
    fun pageClassifierCoversRequiredM0Hints() {
        val classifier = PageClassifier()
        val cases = mapOf(
            "首页 关注 发现" to PageHint.HOME,
            "搜索 取消 历史记录" to PageHint.SEARCH,
            "问一问 搜索结果页" to PageHint.SEARCH,
            "发布笔记 相册 拍摄" to PageHint.PUBLISH_ENTRY,
            "标题 正文 下一步" to PageHint.EDITOR,
            "赞 收藏 作者笔记" to PageHint.NOTE_DETAIL,
            "评论 说点什么" to PageHint.COMMENTS,
            "消息 私信 通知" to PageHint.INBOX,
            "业务升级维护中 暂不可用" to PageHint.BUSINESS_BLOCKED,
            "登录 手机号 验证码" to PageHint.LOGIN_REQUIRED,
            "完全陌生页面" to PageHint.UNKNOWN,
        )

        cases.forEach { (text, expected) ->
            assertEquals(expected, classifier.classify(listOf(UiNode(text = text))))
        }
    }

    @Test
    fun inboxTabWithLikeCollectCuesStillClassifiesAsInbox() {
        val nodes = listOf(
            UiNode(text = "消息"),
            UiNode(text = "赞|收藏"),
            UiNode(text = "评论"),
            UiNode(text = "登录|消息"),
            UiNode(contentDescription = "消息"),
        )

        assertEquals(PageHint.INBOX, PageClassifier().classify(nodes))
    }

    @Test
    fun noteDetailWithoutInboxCuesStillClassifiesAsNoteDetail() {
        val nodes = listOf(
            UiNode(text = "赞"),
            UiNode(text = "收藏"),
            UiNode(text = "作者笔记"),
            UiNode(text = "关注"),
        )

        assertEquals(PageHint.NOTE_DETAIL, PageClassifier().classify(nodes))
    }

    @Test
    fun loginCuesWithMessageTextStillClassifiesAsLoginRequired() {
        val nodes = listOf(
            UiNode(text = "登录"),
            UiNode(text = "手机号"),
            UiNode(text = "验证码"),
            UiNode(text = "消息"),
        )

        assertEquals(PageHint.LOGIN_REQUIRED, PageClassifier().classify(nodes))
    }

    @Test
    fun businessBlockedCuesWithNotificationTextStillClassifiesAsBusinessBlocked() {
        val nodes = listOf(
            UiNode(text = "业务升级"),
            UiNode(text = "暂不可用"),
            UiNode(text = "通知"),
        )

        assertEquals(PageHint.BUSINESS_BLOCKED, PageClassifier().classify(nodes))
    }

    @Test
    fun redactorNeverPersistsPasswordsOrSensitiveValues() {
        val redactor = SensitiveTextRedactor()

        assertEquals("[REDACTED_PASSWORD]", redactor.redact("secret", password = true))
        assertFalse(requireNotNull(redactor.redact("手机号 13800138000", password = false)).contains("13800138000"))
        assertFalse(requireNotNull(redactor.redact("验证码 123456", password = false)).contains("123456"))
        assertFalse(requireNotNull(redactor.redact("身份证 110101199001011234", password = false)).contains("110101199001011234"))
        assertFalse(requireNotNull(redactor.redact("邮箱 user@example.com", password = false)).contains("user@example.com"))
        assertFalse(
            requireNotNull(
                redactor.redact("Authorization: Bearer abc.def.ghi", password = false),
            ).contains("abc.def.ghi"),
        )
        assertFalse(
            requireNotNull(
                redactor.redact("session_token=super-secret-token", password = false),
            ).contains("super-secret-token"),
        )
    }

    @Test
    fun snapshotExportDoesNotPersistOrdinaryInputOrPrivateMessageBodies() {
        val snapshot = UiSnapshot(
            packageName = "com.xingin.xhs",
            className = "ChatActivity",
            pageHint = PageHint.INBOX,
            keyElements = listOf("消息", "这是完整私信正文不应持久化"),
            nodes = listOf(
                UiNode(
                    viewId = "message_input",
                    text = "普通输入框中的完整草稿不能落盘",
                    className = "android.widget.EditText",
                    editable = true,
                ),
                UiNode(
                    viewId = "message_body",
                    text = "这是完整私信正文不应持久化",
                    className = "android.widget.TextView",
                ),
            ),
            truncated = false,
        )

        val encoded = SnapshotJson.encode(snapshot)

        assertFalse(encoded.contains("普通输入框中的完整草稿不能落盘"))
        assertFalse(encoded.contains("这是完整私信正文不应持久化"))
        assertTrue(encoded.contains("\"text_summary\""))
        assertTrue(encoded.contains("\"sha256\""))
    }

    @Test
    fun ocrFallbackRequiresUserTriggerApi30AndInsufficientNodes() {
        val policy = OcrFallbackPolicy()

        assertEquals(
            OcrDecision.RUN,
            policy.decide(apiLevel = 30, userTriggered = true, usefulNodeCount = 0),
        )
        assertEquals(
            OcrDecision.SKIP_NOT_USER_TRIGGERED,
            policy.decide(apiLevel = 35, userTriggered = false, usefulNodeCount = 0),
        )
        assertEquals(
            OcrDecision.BLOCK_UNSUPPORTED_API,
            policy.decide(apiLevel = 29, userTriggered = true, usefulNodeCount = 0),
        )
        assertEquals(
            OcrDecision.SKIP_ACCESSIBILITY_SUFFICIENT,
            policy.decide(apiLevel = 35, userTriggered = true, usefulNodeCount = 3),
        )
    }

    @Test
    fun inputStrategyPrefersSetTextAndNeverSilentlySwitchesIme() {
        val strategy = InputStrategy()

        assertEquals(InputRoute.ACTION_SET_TEXT, strategy.choose(editable = true, setTextSupported = true, imeEnabled = true))
        assertEquals(InputRoute.MANUAL_IME, strategy.choose(editable = true, setTextSupported = false, imeEnabled = true))
        assertEquals(InputRoute.BLOCKED_ENABLE_IME_MANUALLY, strategy.choose(editable = true, setTextSupported = false, imeEnabled = false))
        assertEquals(InputRoute.BLOCKED_NOT_EDITABLE, strategy.choose(editable = false, setTextSupported = true, imeEnabled = true))
    }

    @Test
    fun selectorReturnsNotFoundAndAmbiguousWithoutChoosingFirst() {
        val engine = SelectorEngine()

        assertEquals(
            SelectorMatchStatus.NOT_FOUND,
            engine.find(
                listOf(UiNode(text = "首页")),
                Selector(contentDescription = "发布"),
            ).status,
        )
        val duplicate = engine.find(
            listOf(
                UiNode(viewId = "publish", text = "第一个"),
                UiNode(viewId = "publish", text = "第二个"),
            ),
            Selector(viewId = "publish"),
        )

        assertEquals(SelectorMatchStatus.AMBIGUOUS, duplicate.status)
        assertEquals(null, duplicate.node)
    }

    @Test
    fun selectorIsIndeterminateWhenTraversalWasTruncatedAfterOneMatch() {
        val result = SelectorEngine().find(
            nodes = listOf(UiNode(viewId = "publish")),
            selector = Selector(viewId = "publish"),
            sourceTruncated = true,
        )

        assertEquals(SelectorMatchStatus.INDETERMINATE, result.status)
        assertEquals(null, result.node)
    }
}

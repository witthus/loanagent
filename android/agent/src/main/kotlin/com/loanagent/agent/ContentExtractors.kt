package com.loanagent.agent

data class ExtractedComment(
    val authorSummary: String,
    val bodySummary: String,
    val locatorHint: String?,
)

data class ExtractedInboxThread(
    val titleSummary: String,
    val previewSummary: String,
    val unreadHint: Boolean,
    val locatorHint: String?,
)

data class ExtractedDmMessage(
    val senderSummary: String,
    val bodySummary: String,
)

class ContentExtractors(
    private val redactor: SensitiveTextRedactor = SensitiveTextRedactor(),
) {
    fun extractComments(nodes: List<UiNode>, maxItems: Int): List<ExtractedComment> {
        val entries = usefulEntries(nodes).filterNot { isCommentChrome(it.raw) }
        val results = mutableListOf<ExtractedComment>()
        var index = 0
        while (index < entries.size && results.size < maxItems.coerceAtLeast(0)) {
            val author = entries[index]
            if (!looksLikeCommentAuthor(author)) {
                index += 1
                continue
            }
            var bodyIndex = index + 1
            while (bodyIndex < entries.size && isCommentBadge(entries[bodyIndex].raw)) {
                bodyIndex += 1
            }
            val body = entries.getOrNull(bodyIndex)
            if (body != null && looksLikeCommentBody(body.raw)) {
                results += ExtractedComment(
                    authorSummary = summarize(author.raw, AUTHOR_LIMIT).ifBlank { UNKNOWN_AUTHOR },
                    bodySummary = summarize(body.raw, COMMENT_BODY_LIMIT),
                    locatorHint = locatorHint(body),
                )
                index = bodyIndex + 1
                continue
            }
            index += 1
        }
        return results
    }

    fun extractInboxThreads(nodes: List<UiNode>, maxItems: Int): List<ExtractedInboxThread> {
        val entries = usefulEntries(nodes)
        val results = mutableListOf<ExtractedInboxThread>()
        var index = 0
        while (index < entries.size && results.size < maxItems.coerceAtLeast(0)) {
            val title = entries[index]
            if (isInboxTitle(title) && !isInboxChromeTitle(title.raw)) {
                val titleSummary = summarize(title.raw, TITLE_LIMIT)
                if (titleSummary.isNotBlank()) {
                    val candidate = entries.getOrNull(index + 1)
                    val preview = candidate?.takeUnless {
                        isInboxTitle(it) || isInboxChromeTitle(it.raw) || isInboxChromePreview(it.raw)
                    }
                    val previewText = preview?.raw.orEmpty()
                    results += ExtractedInboxThread(
                        titleSummary = titleSummary,
                        previewSummary = summarize(previewText, PREVIEW_LIMIT),
                        unreadHint = hasUnreadHint(title.raw) || hasUnreadHint(previewText),
                        locatorHint = locatorHint(title),
                    )
                    index += if (preview == null) 1 else 2
                    continue
                }
            }
            index += 1
        }
        return results.take(maxItems.coerceAtLeast(0))
    }

    fun extractDmMessages(nodes: List<UiNode>, maxItems: Int): List<ExtractedDmMessage> {
        val entries = usefulEntries(nodes).filterNot { isDmChrome(it.raw) }
        val results = mutableListOf<ExtractedDmMessage>()
        var index = 0
        while (index < entries.size && results.size < maxItems.coerceAtLeast(0)) {
            val sender = entries[index]
            val body = entries.getOrNull(index + 1)
            if (body != null && looksLikeShortLabel(sender.raw) && looksLikeDmBody(body.raw)) {
                results += ExtractedDmMessage(
                    senderSummary = summarize(sender.raw, AUTHOR_LIMIT).ifBlank { "participant" },
                    bodySummary = summarize(body.raw, DM_BODY_LIMIT),
                )
                index += 2
                continue
            }
            if (looksLikeDmBody(sender.raw)) {
                results += ExtractedDmMessage(
                    senderSummary = "participant",
                    bodySummary = summarize(sender.raw, DM_BODY_LIMIT),
                )
            }
            index += 1
        }
        return results.take(maxItems.coerceAtLeast(0))
    }

    private fun usefulEntries(nodes: List<UiNode>): List<TextEntry> =
        nodes.mapIndexedNotNull { index, node ->
            val raw = listOfNotNull(node.text, node.contentDescription)
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: return@mapIndexedNotNull null
            if (raw in CHROME_TEXT || raw.length > RAW_TEXT_LIMIT) return@mapIndexedNotNull null
            TextEntry(index, node, raw)
        }

    private fun summarize(value: String, limit: Int): String =
        redactor.redact(value.trim(), password = false)
            ?.replace(WHITESPACE, " ")
            ?.take(limit)
            .orEmpty()

    private fun locatorHint(entry: TextEntry): String {
        val bounds = entry.node.bounds
        val boundsHint = if (bounds?.isUsable == true) {
            ";center=${bounds.centerX},${bounds.centerY}"
        } else {
            ""
        }
        return "index=${entry.index}$boundsHint"
    }

    private fun isInboxTitle(entry: TextEntry): Boolean =
        entry.node.clickable

    private fun looksLikeCommentAuthor(entry: TextEntry): Boolean {
        val value = entry.raw
        if (isCommentChrome(value) || isCommentBadge(value) || isClockOrRelativeTime(value)) return false
        if (value.length > AUTHOR_LIMIT) return false
        if (hasSentencePunctuation(value)) return false
        return entry.node.clickable || looksLikeShortLabel(value)
    }

    private fun looksLikeCommentBody(value: String): Boolean {
        if (isCommentChrome(value) || isCommentBadge(value) || isClockOrRelativeTime(value)) return false
        return value.length >= 2
    }

    private fun looksLikeDmBody(value: String): Boolean {
        if (isDmChrome(value) || isClockOrRelativeTime(value)) return false
        return value.length >= 2
    }

    private fun looksLikeShortLabel(value: String): Boolean =
        value.length <= AUTHOR_LIMIT && !hasSentencePunctuation(value) && !hasUnreadHint(value)

    private fun hasSentencePunctuation(value: String): Boolean =
        value.any { it in SENTENCE_PUNCTUATION }

    private fun hasUnreadHint(value: String): Boolean = value.contains("未读", ignoreCase = true)

    private fun isCommentChrome(value: String): Boolean =
        value in CHROME_TEXT ||
            value in COMMENT_CHROME_EXACT ||
            COMMENT_CHROME_PREFIX.any(value::startsWith) ||
            COMMENT_CHROME_CONTAINS.any(value::contains) ||
            COMMENT_COUNT_LIKE.matches(value) ||
            GALLERY_HINT.containsMatchIn(value)

    private fun isCommentBadge(value: String): Boolean =
        value in COMMENT_BADGES

    private fun isInboxChromeTitle(value: String): Boolean =
        value in INBOX_CHROME_TITLES || INBOX_CHROME_TITLE_PREFIX.any(value::startsWith)

    private fun isInboxChromePreview(value: String): Boolean =
        value in INBOX_CHROME_TITLES || isClockOrRelativeTime(value)

    private fun isDmChrome(value: String): Boolean =
        value in DM_CHROME_EXACT ||
            value in CHROME_TEXT ||
            isClockOrRelativeTime(value) ||
            DM_CHROME_CONTAINS.any(value::contains)

    private fun isClockOrRelativeTime(value: String): Boolean =
        CLOCK_LIKE.matches(value) || RELATIVE_TIME.containsMatchIn(value)

    private data class TextEntry(
        val index: Int,
        val node: UiNode,
        val raw: String,
    )

    private companion object {
        const val AUTHOR_LIMIT = 32
        const val TITLE_LIMIT = 64
        const val PREVIEW_LIMIT = 96
        const val COMMENT_BODY_LIMIT = 120
        const val DM_BODY_LIMIT = 160
        const val RAW_TEXT_LIMIT = 256
        const val UNKNOWN_AUTHOR = "unknown"

        val WHITESPACE = Regex("\\s+")
        val SENTENCE_PUNCTUATION = setOf('，', '。', ',', '.', '?', '？', '!', '！', '+', '｜', '|', '：', ':')
        val COMMENT_COUNT_LIKE = Regex("""^(评论|点赞|收藏|浏览)\s*\d+$|^已选定评论\s*\d+$|^\d+浏览$""")
        val CLOCK_LIKE = Regex("""^\d{1,2}:\d{2}$""")
        val RELATIVE_TIME = Regex("""(\d+\s*分钟前|\d+\s*小时前|刚刚|今天\s*\d{1,2}:\d{2}).*""")
        val GALLERY_HINT = Regex("""图片,第\d+张|双指左划|共\d+张""")

        val CHROME_TEXT = setOf(
            "评论",
            "说点什么",
            "赞",
            "收藏",
            "分享",
            "关注",
            "发送",
            "相册",
            "更多",
            "消息",
        )

        val COMMENT_CHROME_EXACT = setOf(
            "赞和收藏",
            "已到底",
            "公开可见",
            "编辑和权限设置",
            "让大家听到你的声音",
            "可能含AI生成内容",
            "薯条推广",
            "合作码",
            "删除",
            "编辑",
            "关闭",
            "权限设置",
        )

        val COMMENT_CHROME_PREFIX = listOf("评论 ", "点赞 ", "收藏 ", "已选定评论")
        val COMMENT_CHROME_CONTAINS = listOf("双指左划", "共1张", "共1条评论")
        val COMMENT_BADGES = setOf("你的好友", "首评", "作者", "置顶")

        val INBOX_CHROME_TITLES = setOf(
            "搜索",
            "直播广场",
            "更多宝藏摊主",
            "活动详情",
            "赞和收藏",
            "粉丝",
            "系统通知",
            "陌生人消息",
            "草稿箱",
        )
        val INBOX_CHROME_TITLE_PREFIX = listOf("活动")

        val DM_CHROME_EXACT = setOf(
            "当前在线",
            "逛逛店铺",
            "咨询商品",
            "热销商品",
            "发消息…",
            "发消息",
            "按住说话",
        )
        val DM_CHROME_CONTAINS = listOf("相互关注", "开始聊天")
    }
}

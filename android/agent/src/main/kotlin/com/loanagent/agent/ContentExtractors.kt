package com.loanagent.agent

data class ExtractedComment(
    val authorSummary: String,
    val bodySummary: String,
    val locatorHint: String?,
    val postedAtText: String? = null,
    val replyToAuthor: String? = null,
    val replies: List<ExtractedComment> = emptyList(),
    val leftHint: Int? = null,
    val preferAsReply: Boolean = false,
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
    val postedAtText: String? = null,
)

data class ExtractedProfileNote(
    val titleSummary: String,
    val likeCount: Int? = null,
    val collectCount: Int? = null,
    val readCount: Int? = null,
    val locatorHint: String? = null,
)

class ContentExtractors(
    private val redactor: SensitiveTextRedactor = SensitiveTextRedactor(),
) {
    fun extractComments(nodes: List<UiNode>, maxItems: Int): List<ExtractedComment> {
        val entries = usefulEntries(nodes).filterNot { isCommentChrome(it.raw) }
        val flat = mutableListOf<ExtractedComment>()
        var index = 0
        while (index < entries.size && flat.size < maxItems.coerceAtLeast(0) * 3) {
            val author = entries[index]
            if (!looksLikeCommentAuthor(author)) {
                index += 1
                continue
            }
            var bodyIndex = index + 1
            var preferAsReply = false
            while (bodyIndex < entries.size && isCommentBadge(entries[bodyIndex].raw)) {
                if (entries[bodyIndex].raw == "作者") preferAsReply = true
                bodyIndex += 1
            }
            val body = entries.getOrNull(bodyIndex)
            if (body == null || !looksLikeCommentBody(body.raw) || body.raw == author.raw) {
                index += 1
                continue
            }
            var cursor = bodyIndex + 1
            var postedAt: String? = null
            while (cursor < entries.size && isCommentBadge(entries[cursor].raw)) {
                cursor += 1
            }
            if (cursor < entries.size && isCommentMetaLine(entries[cursor].raw)) {
                postedAt = summarizeCommentMeta(entries[cursor].raw)
                cursor += 1
            }
            flat += ExtractedComment(
                authorSummary = summarize(author.raw, AUTHOR_LIMIT).ifBlank { UNKNOWN_AUTHOR },
                bodySummary = summarize(body.raw, COMMENT_BODY_LIMIT),
                locatorHint = locatorHint(body),
                postedAtText = postedAt,
                leftHint = author.node.bounds?.left ?: body.node.bounds?.left,
                preferAsReply = preferAsReply,
            )
            index = cursor
        }
        return foldCommentTree(flat, maxItems)
    }

    private fun foldCommentTree(flat: List<ExtractedComment>, maxRoots: Int): List<ExtractedComment> {
        if (flat.isEmpty()) return emptyList()
        val minLeft = flat.mapNotNull { it.leftHint }.minOrNull()
        val roots = mutableListOf<ExtractedComment>()
        var currentRoot: ExtractedComment? = null
        val replyBuffer = mutableListOf<ExtractedComment>()

        fun flushRoot() {
            val root = currentRoot ?: return
            roots += root.copy(replies = replyBuffer.toList())
            replyBuffer.clear()
            currentRoot = null
        }

        for (item in flat) {
            // Author badge marks note-owner comments AND true replies. Only nest the first
            // owner reply under a non-owner root; further owner comments become new roots.
            val isReply = item.preferAsReply &&
                currentRoot != null &&
                !currentRoot.preferAsReply &&
                replyBuffer.isEmpty()
            if (currentRoot == null || !isReply) {
                flushRoot()
                if (roots.size >= maxRoots.coerceAtLeast(0)) break
                currentRoot = item.copy(replies = emptyList())
            } else {
                replyBuffer += item.copy(
                    replyToAuthor = item.replyToAuthor ?: currentRoot?.authorSummary,
                    replies = emptyList(),
                    preferAsReply = false,
                )
            }
        }
        flushRoot()
        return roots.take(maxRoots.coerceAtLeast(0))
    }

    private fun summarizeCommentMeta(raw: String): String {
        val cleaned = raw.replace(Regex("""\s*回复\s*$"""), "").trim()
        return summarize(cleaned, 48)
    }

    fun extractInboxThreads(nodes: List<UiNode>, maxItems: Int): List<ExtractedInboxThread> {
        val entries = inboxListEntries(nodes)
        val results = mutableListOf<ExtractedInboxThread>()
        var index = 0
        while (index < entries.size && results.size < maxItems.coerceAtLeast(0)) {
            val title = entries[index]
            if (isInboxTitle(title, entries, index) && !isInboxChromeTitle(title.raw)) {
                val titleSummary = summarize(title.raw, TITLE_LIMIT)
                if (titleSummary.isNotBlank()) {
                    val candidate = entries.getOrNull(index + 1)
                    val preview = candidate?.takeUnless {
                        isInboxTitle(it, entries, index + 1) ||
                            isInboxChromeTitle(it.raw) ||
                            isInboxChromePreview(it.raw)
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
        val entries = usefulEntries(nodes).filterNot {
            isDmChrome(it.raw) ||
                (
                    (isInboxChromeTitle(it.raw) || PROFILE_OR_NAV_TITLE.contains(it.raw)) &&
                        it.raw !in DM_ALLOWED_SELF_LABELS
                    )
        }
        val results = mutableListOf<ExtractedDmMessage>()
        var index = 0
        while (index < entries.size && results.size < maxItems.coerceAtLeast(0)) {
            val sender = entries[index]
            val body = entries.getOrNull(index + 1)
            if (
                body != null &&
                looksLikeShortLabel(sender.raw) &&
                !isDmSenderChrome(sender.raw) &&
                looksLikeDmBody(body.raw)
            ) {
                results += ExtractedDmMessage(
                    senderSummary = summarize(sender.raw, AUTHOR_LIMIT).ifBlank { "participant" },
                    bodySummary = summarize(body.raw, DM_BODY_LIMIT),
                )
                index += 2
                continue
            }
            if (looksLikeDmBody(sender.raw) && !looksLikeShortLabel(sender.raw)) {
                results += ExtractedDmMessage(
                    senderSummary = "participant",
                    bodySummary = summarize(sender.raw, DM_BODY_LIMIT),
                )
            }
            index += 1
        }
        return results.take(maxItems.coerceAtLeast(0))
    }

    /** Message hub list (not profile / home), used as a second gate after PageHint.INBOX. */
    fun looksLikeInboxListSurface(nodes: List<UiNode>): Boolean {
        val searchable = nodes.joinToString("\n") {
            listOfNotNull(it.text, it.contentDescription).joinToString(" ")
        }
        val hubTiles = searchable.contains("新增关注") ||
            searchable.contains("评论和@") ||
            searchable.contains("陌生人消息") ||
            searchable.contains("系统通知")
        // Profile bleed via ViewPager is common; hub tiles still mean message tab.
        if (!hubTiles && PROFILE_SURFACE_MARKERS.any(searchable::contains)) return false
        if (hubTiles) return searchable.contains("消息")
        if (searchable.contains("发消息") && searchable.contains("当前在线")) return true
        if (!searchable.contains("消息")) return false
        val likeCollect = searchable.contains("赞和收藏") || searchable.contains("赞|收藏")
        return likeCollect &&
            !searchable.contains("编辑主页") &&
            !searchable.contains("小红书号")
    }

    fun looksLikeOpenDmThreadSurface(nodes: List<UiNode>): Boolean {
        val searchable = nodes.joinToString("\n") {
            listOfNotNull(it.text, it.contentDescription).joinToString(" ")
        }
        if (PROFILE_SURFACE_MARKERS.any(searchable::contains)) return false
        return searchable.contains("发消息") ||
            searchable.contains("当前在线") ||
            searchable.contains("按住说话")
    }

    /** Comment sheet chrome — not profile / note-only detail. */
    fun looksLikeCommentsSurface(nodes: List<UiNode>): Boolean {
        val searchable = nodes.joinToString("\n") {
            listOfNotNull(it.text, it.contentDescription).joinToString(" ")
        }
        if (PROFILE_SURFACE_MARKERS.any(searchable::contains)) return false
        return searchable.contains("说点什么") ||
            searchable.contains("让大家听到你的声音") ||
            searchable.contains("已选定评论") ||
            (searchable.contains("评论") && searchable.contains("回复"))
    }

    /**
     * Profile note grid cards. Live content-desc looks like:
     * `笔记,曦瓜大红袍｜岩茶品鉴手记,来自逾期不候,2赞，41阅读`
     */
    fun extractProfileNotes(nodes: List<UiNode>, maxItems: Int): List<ExtractedProfileNote> {
        val results = linkedMapOf<String, ExtractedProfileNote>()
        for (node in nodes) {
            if (results.size >= maxItems.coerceAtLeast(0)) break
            val label = listOfNotNull(node.contentDescription, node.text)
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: continue
            val parsed = parseProfileNoteCard(label) ?: continue
            val bounds = node.bounds
            if (bounds != null && bounds.centerY > 2100) continue
            if (bounds != null && bounds.top < 200) continue
            val key = parsed.titleSummary
            if (key.isBlank() || key in results) continue
            results[key] = parsed.copy(
                locatorHint = bounds?.let { "center=${it.centerX},${it.centerY}" },
            )
        }
        return results.values.toList().take(maxItems.coerceAtLeast(0))
    }

    private fun parseProfileNoteCard(label: String): ExtractedProfileNote? {
        val trimmed = label.trim()
        if (!(trimmed.startsWith("笔记,") || trimmed.startsWith("笔记，"))) return null
        val parts = trimmed.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val title = parts[1]
        if (title.length < 2 || title in PROFILE_OR_NAV_TITLE) return null
        val statsBlob = parts.drop(2).joinToString(",")
        return ExtractedProfileNote(
            titleSummary = summarize(title, NOTE_TITLE_LIMIT),
            likeCount = STATS_LIKE.find(statsBlob)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            collectCount = STATS_COLLECT.find(statsBlob)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            readCount = STATS_READ.find(statsBlob)?.groupValues?.getOrNull(1)?.toIntOrNull(),
        )
    }

    private fun usefulEntries(nodes: List<UiNode>): List<TextEntry> =
        nodes.mapIndexedNotNull { index, node ->
            val raw = extractableText(node) ?: return@mapIndexedNotNull null
            if (raw in CHROME_TEXT || raw.length > RAW_TEXT_LIMIT) return@mapIndexedNotNull null
            TextEntry(index, node, raw)
        }

    /**
     * Prefer conversation rows between message-hub tiles and the bottom tab bar.
     * XHS IndexActivity ViewPager often bleeds profile / note nodes into the same tree.
     */
    private fun inboxListEntries(nodes: List<UiNode>): List<TextEntry> {
        val entries = usefulEntries(nodes)
        val maxBottom = nodes.mapNotNull { it.bounds?.bottom }.maxOrNull() ?: return entries
        val chromeTop = maxBottom - 220
        val hubBottom = nodes.mapNotNull { node ->
            val label = listOfNotNull(node.text, node.contentDescription).joinToString("|")
            if (INBOX_HUB_TILE_MARKERS.any(label::contains)) node.bounds?.bottom else null
        }.maxOrNull()
        if (hubBottom == null || hubBottom <= 0) return entries
        return entries.filter { entry ->
            val bounds = entry.node.bounds ?: return@filter true
            bounds.centerY in (hubBottom + 1) until chromeTop
        }
    }

    /**
     * Prefer visible [UiNode.text]. Accessibility contentDescription is only used when it looks
     * like a short human label — composite card labels / button descriptions caused live false
     * positives ("赞和收藏按钮", "笔记,…,来自…").
     */
    private fun extractableText(node: UiNode): String? {
        val text = node.text?.trim().orEmpty()
        if (text.isNotBlank()) return text
        val desc = node.contentDescription?.trim().orEmpty()
        if (desc.isBlank()) return null
        if (isAccessibilityChromeDescription(desc)) return null
        return desc
    }

    private fun isAccessibilityChromeDescription(value: String): Boolean {
        if (value.contains("按钮")) return true
        if (value.startsWith("头像") || value.startsWith("笔记")) return true
        if (value.startsWith("来自笔记")) return true
        if (value.count { it == ',' || it == '，' } >= 1) return true
        if (ACCESSIBILITY_EMOJI_ONLY.matches(value)) return true
        return false
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

    private fun isInboxTitle(entry: TextEntry, entries: List<TextEntry>, index: Int): Boolean {
        if (!looksLikeConversationTitle(entry.raw)) return false
        if (entry.node.clickable) return true
        // XHS list rows often leave the title TextView non-clickable; only accept those when the
        // following line looks like time / online status / message preview — not another name.
        if (!looksLikeShortLabel(entry.raw) || entry.raw.length > AUTHOR_LIMIT) return false
        val next = entries.getOrNull(index + 1) ?: return false
        if (isClockOrRelativeTime(next.raw) || ONLINE_STATUS_BLOB.containsMatchIn(next.raw)) {
            return true
        }
        if (GENDER_ONLY.matches(next.raw)) return false
        if (PROFILE_OR_NAV_TITLE.contains(next.raw)) return false
        if (next.raw.startsWith("小红书号") || next.raw.startsWith("IP：") || next.raw.startsWith("IP:")) {
            return false
        }
        if (looksLikeConversationTitle(next.raw) && looksLikeShortLabel(next.raw)) {
            return false
        }
        return next.raw.length >= 2 &&
            !isInboxChromeTitle(next.raw) &&
            !isInboxChromePreview(next.raw)
    }

    private fun looksLikeConversationTitle(value: String): Boolean {
        if (isInboxChromeTitle(value)) return false
        if (INBOX_INTEREST_MARKERS.any(value::contains)) return false
        if (DIGITS_ONLY.matches(value)) return false
        if (isClockOrRelativeTime(value)) return false
        if (value.length > TITLE_LIMIT) return false
        if (value.contains("按钮")) return false
        if (PROFILE_OR_NAV_TITLE.any { value == it || value.startsWith(it) }) return false
        if (FOLLOW_FANS_COUNT.matches(value)) return false
        if (value.startsWith("小红书号") || value.startsWith("IP：") || value.startsWith("IP:")) return false
        if (value.startsWith("笔记,")) return false
        // Composite list-row a11y / status blobs: "店名，，，20分钟内在线，11:59"
        if (value.count { it == '，' || it == ',' } >= 2) return false
        if (ONLINE_STATUS_BLOB.containsMatchIn(value)) return false
        if (GENDER_ONLY.matches(value)) return false
        if (ACCESSIBILITY_EMOJI_ONLY.matches(value)) return false
        if (value.startsWith("来自笔记")) return false
        return true
    }

    private fun looksLikeCommentAuthor(entry: TextEntry): Boolean {
        val value = entry.raw
        if (isCommentChrome(value) || isCommentBadge(value) || isCommentMetaLine(value)) return false
        if (DIGITS_ONLY.matches(value)) return false
        if (value.length > AUTHOR_LIMIT) return false
        if (hasSentencePunctuation(value)) return false
        if (value.startsWith("来自笔记")) return false
        if (PROFILE_OR_NAV_TITLE.contains(value)) return false
        if (value.contains("赞和收藏")) return false
        return entry.node.clickable || looksLikeShortLabel(value)
    }

    private fun looksLikeCommentBody(value: String): Boolean {
        if (isCommentChrome(value) || isCommentBadge(value) || isCommentMetaLine(value)) return false
        if (PROFILE_OR_NAV_TITLE.contains(value)) return false
        if (value.startsWith("来自笔记")) return false
        if (FOLLOW_FANS_COUNT.matches(value)) return false
        if (value.startsWith("小红书号")) return false
        if (value.contains("赞和收藏")) return false
        if (COMMENT_COUNT_LIKE.matches(value)) return false
        // Note titles often leak into the comments sheet as a11y labels.
        if ('｜' in value || '|' in value) return false
        return value.length >= 2
    }

    private fun looksLikeDmBody(value: String): Boolean {
        if (isDmChrome(value) || isClockOrRelativeTime(value)) return false
        if (PROFILE_OR_NAV_TITLE.contains(value)) return false
        if (isInboxChromeTitle(value)) return false
        if (FOLLOW_FANS_COUNT.matches(value)) return false
        if (value.startsWith("小红书号") || value.startsWith("IP：") || value.startsWith("IP:")) return false
        if (ONLINE_STATUS_BLOB.containsMatchIn(value)) return false
        return value.length >= 2
    }

    private fun isDmSenderChrome(value: String): Boolean {
        if (value in DM_ALLOWED_SELF_LABELS) return false
        return PROFILE_OR_NAV_TITLE.contains(value) ||
            isInboxChromeTitle(value) ||
            DIGITS_ONLY.matches(value) ||
            FOLLOW_FANS_COUNT.matches(value)
    }

    private fun looksLikeShortLabel(value: String): Boolean =
        value.length <= AUTHOR_LIMIT &&
            !hasSentencePunctuation(value) &&
            !hasUnreadHint(value) &&
            !DIGITS_ONLY.matches(value)

    private fun hasSentencePunctuation(value: String): Boolean =
        value.any { it in SENTENCE_PUNCTUATION }

    private fun hasUnreadHint(value: String): Boolean = value.contains("未读", ignoreCase = true)

    private fun isCommentChrome(value: String): Boolean =
        value in CHROME_TEXT ||
            value in COMMENT_CHROME_EXACT ||
            COMMENT_CHROME_PREFIX.any(value::startsWith) ||
            COMMENT_CHROME_CONTAINS.any(value::contains) ||
            COMMENT_COUNT_LIKE.matches(value) ||
            GALLERY_HINT.containsMatchIn(value) ||
            PROFILE_OR_NAV_TITLE.contains(value)

    private fun isCommentBadge(value: String): Boolean =
        value in COMMENT_BADGES

    /** Relative time / region /「回复」meta under a comment, e.g. `8分钟前 湖北 回复`. */
    private fun isCommentMetaLine(value: String): Boolean =
        isClockOrRelativeTime(value) || COMMENT_META_LINE.matches(value)

    private fun isInboxChromeTitle(value: String): Boolean =
        value in INBOX_CHROME_TITLES ||
            INBOX_CHROME_TITLE_PREFIX.any(value::startsWith) ||
            INBOX_INTEREST_MARKERS.any(value::contains) ||
            PROFILE_OR_NAV_TITLE.contains(value) ||
            FOLLOW_FANS_COUNT.matches(value)

    private fun isInboxChromePreview(value: String): Boolean =
        value in INBOX_CHROME_TITLES ||
            isClockOrRelativeTime(value) ||
            PROFILE_OR_NAV_TITLE.contains(value) ||
            FOLLOW_FANS_COUNT.matches(value) ||
            value.startsWith("来自笔记")

    private fun isDmChrome(value: String): Boolean =
        value in DM_CHROME_EXACT ||
            value in CHROME_TEXT ||
            isClockOrRelativeTime(value) ||
            DM_CHROME_CONTAINS.any(value::contains)

    private fun isClockOrRelativeTime(value: String): Boolean =
        CLOCK_LIKE.matches(value) || RELATIVE_TIME.matches(value)

    private data class TextEntry(
        val index: Int,
        val node: UiNode,
        val raw: String,
    )

    private companion object {
        const val AUTHOR_LIMIT = 32
        const val TITLE_LIMIT = 64
        const val NOTE_TITLE_LIMIT = 120
        const val PREVIEW_LIMIT = 96
        const val COMMENT_BODY_LIMIT = 120
        const val DM_BODY_LIMIT = 160
        const val RAW_TEXT_LIMIT = 256
        const val UNKNOWN_AUTHOR = "unknown"
        const val REPLY_LEFT_DELTA = 36

        val WHITESPACE = Regex("\\s+")
        val SENTENCE_PUNCTUATION = setOf('，', '。', ',', '.', '?', '？', '!', '！', '+', '｜', '|', '：', ':')
        val COMMENT_COUNT_LIKE = Regex("""^(评论|点赞|收藏|浏览)\s*\d+$|^已选定评论\s*\d+$|^\d+浏览$""")
        val CLOCK_LIKE = Regex("""^\d{1,2}:\d{2}$""")
        val RELATIVE_TIME = Regex(
            """^(\d+\s*分钟前|\d+\s*小时前|刚刚|昨天|前天|今天(\s*\d{1,2}:\d{2})?|周[一二三四五六日]|星期[一二三四五六日天])(\s*\d{1,2}:\d{2})?$""",
        )
        val COMMENT_META_LINE = Regex(
            """^(?:(?:\d+\s*(?:分钟|小时|天)前)|刚刚|昨天|前天|今天|\d{1,2}-\d{1,2})(?:[\s\d:：\u4e00-\u9fff]{0,20})?(?:\s*回复)?$""",
        )
        val GALLERY_HINT = Regex("""图片,第\d+张|双指左划|共\d+张""")
        val DIGITS_ONLY = Regex("""^\d+$""")
        val FOLLOW_FANS_COUNT = Regex("""^\d+\s*(关注|粉丝|获赞与收藏)?$|^\d+粉丝$|^\d+关注$|^公开\s*\d+$""")
        val ACCESSIBILITY_EMOJI_ONLY = Regex("""^\[[a-zA-Z0-9_]+]$""")
        val ONLINE_STATUS_BLOB = Regex("""分钟内在线|当前在线|昨天在线|前天在线|\d+天前在线|\d{1,2}:\d{2}$""")
        val STATS_LIKE = Regex("""(\d+)\s*赞""")
        val STATS_COLLECT = Regex("""(\d+)\s*收藏""")
        val STATS_READ = Regex("""(\d+)\s*阅读""")
        val GENDER_ONLY = Regex("""^[男女]，?$""")
        val PROFILE_SURFACE_MARKERS = listOf(
            "编辑主页",
            "小红书号",
            "获赞与收藏",
            "点击这里，填写简介",
        )
        val INBOX_HUB_TILE_MARKERS = listOf(
            "赞和收藏",
            "赞|收藏",
            "新增关注",
            "评论和@",
            "陌生人消息",
            "系统通知",
        )

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

        val PROFILE_OR_NAV_TITLE = setOf(
            "首页",
            "市集",
            "我",
            "发布",
            "编辑主页",
            "扫一扫",
            "笔记",
            "赞过",
            "群聊",
            "菜单",
            "搜索",
            "粉丝",
            "获赞与收藏",
            "浏览记录",
            "看过的笔记",
            "钱包",
            "查看详情",
            "不显示",
            "删除",
            "标为未读",
            "点击这里，填写简介",
            "小组件浏览记录",
            "小组件群聊",
            "小组件钱包",
        )
        // Chat bubbles often label self as "我"; keep for DM sender pairing.
        val DM_ALLOWED_SELF_LABELS = setOf("我", "对方")

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
            "设为公开",
        )

        val COMMENT_CHROME_PREFIX = listOf("评论 ", "点赞 ", "收藏 ", "已选定评论", "全部 ")
        val COMMENT_CHROME_CONTAINS = listOf("双指左划", "共1张", "共1条评论", "设为公开")
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
            "消息",
            "新增关注",
            "评论和@",
            "赞和收藏按钮",
            "评论和@按钮",
            "新增关注按钮",
            // Profile "people you may like" / interest rails bleed via ViewPager.
            "近期互动热门",
            "你可能感兴趣",
            "可能感兴趣的人",
            "为你推荐",
            "发现好友",
            "通讯录好友",
        )
        val INBOX_CHROME_TITLE_PREFIX = listOf(
            "活动",
            "消息，",
            "消息,",
            "近期互动",
            "你可能感兴趣",
            "可能感兴趣",
            "感兴趣的人",
            "为你推荐",
        )
        val INBOX_INTEREST_MARKERS = listOf(
            "感兴趣",
            "近期互动",
            "为你推荐",
            "发现好友",
        )

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

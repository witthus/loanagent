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
        val entries = usefulEntries(nodes)
        val results = mutableListOf<ExtractedComment>()
        var index = 0
        while (index < entries.size && results.size < maxItems.coerceAtLeast(0)) {
            val author = entries[index]
            val body = entries.getOrNull(index + 1)
            if (body != null && (author.node.clickable || looksLikeShortLabel(author.raw))) {
                val bodySummary = summarize(body.raw, COMMENT_BODY_LIMIT)
                if (bodySummary.isNotBlank()) {
                    results += ExtractedComment(
                        authorSummary = summarize(author.raw, AUTHOR_LIMIT).ifBlank { UNKNOWN_AUTHOR },
                        bodySummary = bodySummary,
                        locatorHint = locatorHint(body),
                    )
                    index += 2
                    continue
                }
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
            if (isInboxTitle(title)) {
                val titleSummary = summarize(title.raw, TITLE_LIMIT)
                if (titleSummary.isNotBlank()) {
                    val candidate = entries.getOrNull(index + 1)
                    // Do not consume another clickable/title row as this thread's preview.
                    val preview = candidate?.takeUnless { isInboxTitle(it) }
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
        val entries = usefulEntries(nodes)
        val results = mutableListOf<ExtractedDmMessage>()
        var index = 0
        while (index < entries.size && results.size < maxItems.coerceAtLeast(0)) {
            val sender = entries[index]
            val body = entries.getOrNull(index + 1)
            if (body != null && looksLikeShortLabel(sender.raw)) {
                val bodySummary = summarize(body.raw, DM_BODY_LIMIT)
                if (bodySummary.isNotBlank()) {
                    results += ExtractedDmMessage(
                        senderSummary = summarize(sender.raw, AUTHOR_LIMIT).ifBlank { "participant" },
                        bodySummary = bodySummary,
                    )
                    index += 2
                    continue
                }
            }
            val bodySummary = summarize(sender.raw, DM_BODY_LIMIT)
            if (bodySummary.isNotBlank()) {
                results += ExtractedDmMessage(
                    senderSummary = "participant",
                    bodySummary = bodySummary,
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

    private fun looksLikeShortLabel(value: String): Boolean =
        value.length <= AUTHOR_LIMIT && !hasSentencePunctuation(value) && !hasUnreadHint(value)

    private fun hasSentencePunctuation(value: String): Boolean =
        value.any { it in setOf('，', '。', ',', '.', '?', '？', '!', '！', '+') }

    private fun hasUnreadHint(value: String): Boolean = value.contains("未读", ignoreCase = true)

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
    }
}

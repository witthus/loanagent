package com.loanagent.agent

/**
 * Playbook-facing automation surface. E0 used ensure-only methods; E1+ adds observe/act/extract.
 */
interface PlaybookRuntime {
    fun accessibilityAlive(): Boolean
    fun launchXhs(): Boolean
    fun waitForXhsForeground(timeoutMs: Long): Boolean
    fun currentPageHint(): PageHint?
    fun currentLease(): TargetLease?
    fun observe(): UiSnapshot?
    fun extractComments(maxItems: Int = 20): List<ExtractedComment>
    fun extractInboxThreads(maxItems: Int = 20): List<ExtractedInboxThread>
    fun extractDmMessages(maxItems: Int = 20): List<ExtractedDmMessage>
    fun click(selector: String, allowFinal: Boolean = false, timeoutMs: Long = 10_000): Boolean
    /** Click first node whose text or contentDescription contains [fragment] (navigation aid). */
    fun clickTextContaining(fragment: String, timeoutMs: Long = 8_000): Boolean
    fun setText(selector: String, text: String, timeoutMs: Long = 12_000): Boolean
    fun tap(x: Int, y: Int, durationMs: Long = 50): Boolean
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 400): Boolean
    fun globalBack(): Boolean
    fun sleep(ms: Long)
}

object FinalActionPolicy {
    private val exactTerms = setOf(
        "发布",
        "发布笔记",
        "确认发布",
        "发送",
        "发送评论",
        "发送私信",
        "发表",
        "提交",
    )
    private val idTerms = listOf("publish", "send", "submit")

    fun blocks(selector: Selector): Boolean =
        selector.text?.trim() in exactTerms ||
            selector.contentDescription?.trim() in exactTerms ||
            selector.viewId?.lowercase()?.let { id ->
                idTerms.any(id::contains)
            } == true
}

fun PlaybookCommand.stringParam(key: String): String? =
    params[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

fun PlaybookCommand.intParam(key: String, default: Int): Int =
    when (val value = params[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }

fun PlaybookCommand.boolParam(key: String, default: Boolean = false): Boolean =
    when (val value = params[key]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> default
    }

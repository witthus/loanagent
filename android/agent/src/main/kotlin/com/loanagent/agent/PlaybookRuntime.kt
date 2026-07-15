package com.loanagent.agent

/**
 * Playbook-facing automation surface. E0 used ensure-only methods; E1+ adds observe/act/extract.
 */
interface PlaybookRuntime {
    /**
     * Marks the point immediately before a non-idempotent external effect can occur.
     * Authorization to click a final control is not itself proof that the effect boundary was crossed.
     */
    fun beginSideEffect() = Unit
    fun accessibilityAlive(): Boolean
    fun launchXhs(): Boolean
    fun waitForXhsForeground(timeoutMs: Long): Boolean
    /**
     * Wake the display and dismiss a non-secure (none / swipe) keyguard.
     * @return null on success, otherwise an error code such as [ScreenReadyPolicy.ERROR_SECURE_OR_FAILED].
     */
    fun ensureScreenReady(timeoutMs: Long = 15_000L): String? = null
    fun currentPageHint(): PageHint?
    fun currentLease(): TargetLease?
    fun observe(): UiSnapshot?
    fun extractComments(maxItems: Int = 20): List<ExtractedComment>
    fun extractInboxThreads(maxItems: Int = 20): List<ExtractedInboxThread>
    fun extractDmMessages(maxItems: Int = 20): List<ExtractedDmMessage>
    fun extractProfileNotes(maxItems: Int = 30): List<ExtractedProfileNote> = emptyList()
    fun looksLikeInboxListSurface(): Boolean = false
    fun looksLikeOpenDmThreadSurface(): Boolean = false
    fun looksLikeCommentsSurface(): Boolean = false
    fun looksLikeProfileSurface(): Boolean = false
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

package com.loanagent.agent

sealed class NavResult {
    data object Ok : NavResult()
    data class Failed(val errorCode: String) : NavResult()
}

/**
 * Shared UI navigation into XHS comment / inbox surfaces before playbook business steps.
 *
 * Profile note: do NOT rely on `contentDescription=我` alone — findAccessibilityNodeInfosByText
 * can resolve the wrong node and land on the 消息 hub (classified as INBOX). Prefer bottom-tab
 * position + profile chrome verification.
 */
object SurfaceNavigator {
    private const val MIN_MATCH_LEN = 4
    private const val BACK_LIMIT = 4

    fun ensureForeground(runtime: PlaybookRuntime): NavResult {
        if (!runtime.accessibilityAlive()) return NavResult.Failed("A11Y_DOWN")
        if (!runtime.waitForXhsForeground(8_000)) {
            runtime.launchXhs()
            if (!runtime.waitForXhsForeground(12_000)) {
                return NavResult.Failed("XHS_NOT_FOREGROUND")
            }
        }
        return NavResult.Ok
    }

    fun goInbox(runtime: PlaybookRuntime): NavResult {
        ensureForeground(runtime).let { if (it is NavResult.Failed) return it }
        if (runtime.currentPageHint() == PageHint.INBOX) return NavResult.Ok
        if (clickMessageTab(runtime)) {
            runtime.sleep(1_000)
            if (runtime.currentPageHint() == PageHint.INBOX) return NavResult.Ok
        }
        resetTowardHome(runtime)
        if (clickMessageTab(runtime)) {
            runtime.sleep(1_200)
            if (runtime.currentPageHint() == PageHint.INBOX) return NavResult.Ok
        }
        return NavResult.Failed("NAV_TIMEOUT")
    }

    fun goDmThread(runtime: PlaybookRuntime, openTitleHint: String?): NavResult {
        val hint = openTitleHint?.trim()?.takeIf { it.isNotEmpty() }
            ?: return NavResult.Failed("NAV_MISSING_HINT")
        goInbox(runtime).let { if (it is NavResult.Failed) return it }
        val threads = runtime.extractInboxThreads(30)
        val matched = threads.firstOrNull { thread ->
            titlesMatch(hint, thread.titleSummary) || titlesMatch(hint, thread.previewSummary)
        } ?: return NavResult.Failed("NAV_TARGET_NOT_FOUND")
        val locator = matched.locatorHint
        val opened = when {
            locator != null && runtime.click(locator, allowFinal = false, timeoutMs = 6_000) -> true
            runtime.clickTextContaining(matched.titleSummary, timeoutMs = 6_000) -> true
            runtime.clickTextContaining(hint, timeoutMs = 6_000) -> true
            runtime.click("text=${matched.titleSummary}", allowFinal = false, timeoutMs = 4_000) -> true
            else -> tapNodeContaining(runtime, hint) ||
                tapNodeContaining(runtime, matched.titleSummary)
        }
        if (!opened) return NavResult.Failed("NAV_TARGET_NOT_FOUND")
        runtime.sleep(1_000)
        return NavResult.Ok
    }

    fun goNoteComments(
        runtime: PlaybookRuntime,
        titleSummary: String?,
        xhsHint: String? = null,
        locatorHint: String? = null,
    ): NavResult {
        ensureForeground(runtime).let { if (it is NavResult.Failed) return it }
        val page = runtime.currentPageHint()
        if (page == PageHint.COMMENTS) return NavResult.Ok
        if (page == PageHint.NOTE_DETAIL) {
            openCommentsChrome(runtime)
            return NavResult.Ok
        }

        val title = titleSummary?.trim()?.takeIf { it.isNotEmpty() }
        val alt = xhsHint?.trim()?.takeIf { it.isNotEmpty() }
        val locator = locatorHint?.trim()?.takeIf { it.isNotEmpty() }
        if (title == null && alt == null && locator == null) {
            return NavResult.Failed("NAV_MISSING_HINT")
        }

        if (!openProfile(runtime)) {
            return NavResult.Failed("NAV_TIMEOUT")
        }

        // Notes tab when present (grid may already be visible).
        runtime.click("text=笔记", allowFinal = false, timeoutMs = 4_000) ||
            runtime.click("contentDescription=笔记", allowFinal = false, timeoutMs = 3_000) ||
            runtime.click("contentDescription=已选定笔记", allowFinal = false, timeoutMs = 3_000)
        runtime.sleep(1_000)

        // Profile grids often keep the target cover below the fold (or overlapping the
        // bottom tab bar). Never tap matches that sit in the chrome zone — scroll first.
        var openedNote = false
        for (attempt in 0 until 6) {
            openedNote = openNoteByHints(runtime, title, alt, locator)
            if (openedNote) break
            swipeUp(runtime)
            runtime.sleep(750)
        }
        if (!openedNote) return NavResult.Failed("NAV_TARGET_NOT_FOUND")
        runtime.sleep(1_200)
        openCommentsChrome(runtime)
        val after = runtime.currentPageHint()
        return if (
            after == PageHint.COMMENTS ||
            after == PageHint.NOTE_DETAIL ||
            after == PageHint.UNKNOWN ||
            after == null
        ) {
            NavResult.Ok
        } else {
            NavResult.Failed("NAV_TIMEOUT")
        }
    }

    fun bestTextMatch(hint: String, candidates: List<String>): String? {
        val normalizedHint = normalize(hint)
        if (normalizedHint.isEmpty()) return null
        return candidates
            .map { it to normalize(it) }
            .filter { (_, norm) -> titlesMatch(normalizedHint, norm) }
            .maxByOrNull { (_, norm) ->
                overlapScore(normalizedHint, norm)
            }
            ?.first
    }

    internal fun titlesMatch(a: String?, b: String?): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length <= right.length) right else left
        if (shorter.length < MIN_MATCH_LEN) return false
        return longer.contains(shorter)
    }

    internal fun looksLikeInboxHub(runtime: PlaybookRuntime): Boolean {
        val blob = surfaceBlob(runtime)
        return (blob.contains("赞|收藏") || blob.contains("赞和收藏")) &&
            (blob.contains("私信") || blob.contains("评论")) &&
            blob.contains("消息")
    }

    internal fun looksLikeProfile(runtime: PlaybookRuntime): Boolean {
        if (looksLikeInboxHub(runtime)) return false
        val blob = surfaceBlob(runtime)
        return blob.contains("编辑资料") ||
            (blob.contains("粉丝") && blob.contains("关注")) ||
            (blob.contains("笔记") && (blob.contains("赞过") || blob.contains("收藏")))
    }

    private fun surfaceBlob(runtime: PlaybookRuntime): String {
        val snapshot = runtime.observe() ?: return ""
        return snapshot.nodes.joinToString("\n") {
            listOfNotNull(it.text, it.contentDescription).joinToString("|")
        }
    }

    private fun openProfile(runtime: PlaybookRuntime): Boolean {
        resetTowardHome(runtime)
        runtime.sleep(600)
        // Never use click("text=我") / click("contentDescription=我"): findByText is ambiguous and
        // on this XHS build routinely lands on the 消息 hub. Tap the bottom-tab by label/bounds.
        if (tapBottomTabLabeled(runtime, "我") || runtime.tap(972, 2294)) {
            runtime.sleep(1_500)
            if (looksLikeProfile(runtime)) return true
            if (!looksLikeInboxHub(runtime)) return true
        }
        // Second attempt after forcing home chrome.
        resetTowardHome(runtime)
        runtime.sleep(500)
        if (tapBottomTabLabeled(runtime, "我") ||
            tapBottomNavFromRight(runtime, 0) ||
            runtime.tap(972, 2294)
        ) {
            runtime.sleep(1_500)
            return looksLikeProfile(runtime) || !looksLikeInboxHub(runtime)
        }
        return false
    }

    private fun tapBottomTabLabeled(runtime: PlaybookRuntime, label: String): Boolean {
        val snapshot = runtime.observe() ?: return false
        val maxBottom = snapshot.nodes.mapNotNull { it.bounds?.bottom }.maxOrNull() ?: return false
        val threshold = maxBottom - 220
        val node = snapshot.nodes.firstOrNull { node ->
            val bounds = node.bounds ?: return@firstOrNull false
            if (!node.clickable || bounds.top < threshold) return@firstOrNull false
            val desc = node.contentDescription.orEmpty()
            val text = node.text.orEmpty()
            desc == label ||
                desc.startsWith("$label，") ||
                desc.startsWith("$label,") ||
                text == label
        } ?: return false
        val bounds = node.bounds ?: return false
        return runtime.tap(bounds.centerX, bounds.centerY)
    }

    private fun openNoteByHints(
        runtime: PlaybookRuntime,
        title: String?,
        alt: String?,
        locator: String?,
    ): Boolean {
        if (locator != null && runtime.click(locator, allowFinal = false, timeoutMs = 5_000)) {
            return true
        }
        // Prefer XHS profile cover cards: content-desc like
        // "笔记,曦瓜大红袍｜岩茶品鉴手记,来自…,2赞，41阅读"
        if (tapSafeNoteCard(runtime, title, alt)) return true
        if (title != null) {
            // Truncated cover titles: try a stable prefix via safe node tap only.
            val prefix = title.take(8)
            if (prefix.length >= MIN_MATCH_LEN && tapSafeNoteCard(runtime, prefix, null)) {
                return true
            }
            if (tapNodeContaining(runtime, title, requireAboveChrome = true)) return true
            if (runtime.click("text=$title", allowFinal = false, timeoutMs = 2_000)) return true
        }
        if (alt != null) {
            if (tapSafeNoteCard(runtime, alt, null)) return true
            if (tapNodeContaining(runtime, alt, requireAboveChrome = true)) return true
        }
        return false
    }

    /**
     * Tap a profile/grid note card whose label matches, but only if its center is above
     * the bottom tab chrome. Matching below that zone often hits 消息/我 instead.
     */
    private fun tapSafeNoteCard(
        runtime: PlaybookRuntime,
        title: String?,
        alt: String?,
    ): Boolean {
        val hints = listOfNotNull(title, alt).map { normalize(it) }.filter { it.isNotEmpty() }
        if (hints.isEmpty()) return false
        val snapshot = runtime.observe() ?: return false
        val maxBottom = snapshot.nodes.mapNotNull { it.bounds?.bottom }.maxOrNull() ?: return false
        val chromeTop = maxBottom - 220
        val matches = snapshot.nodes.mapNotNull { node ->
            val bounds = node.bounds ?: return@mapNotNull null
            if (bounds.centerY >= chromeTop) return@mapNotNull null
            if (bounds.top < 350) return@mapNotNull null
            val desc = node.contentDescription.orEmpty()
            val text = node.text.orEmpty()
            val label = when {
                desc.startsWith("笔记,") || desc.startsWith("笔记，") -> desc
                text.isNotBlank() && titlesMatchAny(hints, text) -> text
                desc.isNotBlank() && titlesMatchAny(hints, desc) -> desc
                else -> return@mapNotNull null
            }
            if (!titlesMatchAny(hints, label)) return@mapNotNull null
            Triple(label, bounds, node.clickable)
        }
        val best = matches.maxByOrNull { (label, _, _) ->
            hints.maxOf { hint -> overlapScore(hint, normalize(label)) }
        } ?: return false
        return runtime.tap(best.second.centerX, best.second.centerY)
    }

    private fun titlesMatchAny(hints: List<String>, label: String): Boolean {
        val normalized = normalize(label)
        return hints.any { titlesMatch(it, normalized) }
    }

    private fun tapBottomNavFromRight(runtime: PlaybookRuntime, indexFromRight: Int): Boolean {
        val snapshot = runtime.observe() ?: return false
        val maxBottom = snapshot.nodes.mapNotNull { it.bounds?.bottom }.maxOrNull() ?: return false
        val threshold = maxBottom - 280
        val tabs = snapshot.nodes.asSequence()
            .mapNotNull { node ->
                val bounds = node.bounds ?: return@mapNotNull null
                if (!node.clickable) return@mapNotNull null
                if (bounds.top < threshold) return@mapNotNull null
                val width = bounds.right - bounds.left
                if (width !in 60..480) return@mapNotNull null
                bounds to node
            }
            .sortedBy { it.first.left }
            .toList()
        if (tabs.size < 4) return false
        val index = tabs.size - 1 - indexFromRight
        if (index !in tabs.indices) return false
        val bounds = tabs[index].first
        return runtime.tap(bounds.centerX, bounds.centerY)
    }

    private fun swipeUp(runtime: PlaybookRuntime) {
        runtime.swipe(540, 1_700, 540, 700, durationMs = 450)
    }

    private fun overlapScore(a: String, b: String): Int =
        if (a.length <= b.length) a.length else b.length

    private fun normalize(value: String?): String =
        value?.replace("\\s+".toRegex(), "")?.trim().orEmpty()

    private fun clickMessageTab(runtime: PlaybookRuntime): Boolean =
        tapBottomTabLabeled(runtime, "消息") ||
            runtime.click("text=消息", allowFinal = false, timeoutMs = 5_000) ||
            runtime.click("contentDescription=消息", allowFinal = false, timeoutMs = 3_000) ||
            runtime.clickTextContaining("消息", timeoutMs = 3_000)

    private fun resetTowardHome(runtime: PlaybookRuntime) {
        var backs = 0
        while (backs < BACK_LIMIT &&
            runtime.currentPageHint() != PageHint.HOME &&
            runtime.currentPageHint() != PageHint.INBOX
        ) {
            runtime.globalBack()
            runtime.sleep(400)
            backs += 1
        }
        runtime.click("contentDescription=首页", allowFinal = false, timeoutMs = 3_000) ||
            runtime.click("text=首页", allowFinal = false, timeoutMs = 3_000)
        runtime.sleep(600)
    }

    private fun openCommentsChrome(runtime: PlaybookRuntime) {
        runtime.click("contentDescription=评论", allowFinal = false, timeoutMs = 4_000) ||
            runtime.click("text=评论", allowFinal = false, timeoutMs = 4_000)
        runtime.sleep(1_000)
    }

    private fun tapNodeContaining(
        runtime: PlaybookRuntime,
        hint: String,
        requireAboveChrome: Boolean = false,
    ): Boolean {
        val snapshot = runtime.observe() ?: return false
        val chromeTop = if (requireAboveChrome) {
            val maxBottom = snapshot.nodes.mapNotNull { it.bounds?.bottom }.maxOrNull()
                ?: return false
            maxBottom - 220
        } else {
            Int.MAX_VALUE
        }
        val candidates = snapshot.nodes.mapNotNull { node ->
            val bounds = node.bounds
            if (requireAboveChrome) {
                if (bounds == null || bounds.centerY >= chromeTop) return@mapNotNull null
            }
            val label = node.text?.takeIf(String::isNotBlank)
                ?: node.contentDescription?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            label to node
        }
        val matchText = bestTextMatch(hint, candidates.map { it.first }) ?: return false
        val node = candidates.firstOrNull { it.first == matchText }?.second ?: return false
        val bounds = node.bounds ?: return false
        return runtime.tap(bounds.centerX, bounds.centerY)
    }
}

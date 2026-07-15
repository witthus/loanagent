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
    private val CENTER_IN_LOCATOR = Regex("""center=(\d+),(\d+)""")

    /**
     * Wake / unlock (swipe or none) then bring XHS to the foreground.
     * Never assume the phone is already lit or on XHS.
     */
    fun ensureForeground(runtime: PlaybookRuntime): NavResult {
        if (!runtime.accessibilityAlive()) return NavResult.Failed("A11Y_DOWN")
        runtime.ensureScreenReady(15_000)?.let { return NavResult.Failed(it) }
        if (runtime.waitForXhsForeground(600)) return NavResult.Ok
        repeat(3) {
            runtime.launchXhs()
            if (runtime.waitForXhsForeground(10_000)) return NavResult.Ok
            runtime.sleep(500)
        }
        return NavResult.Failed("XHS_NOT_FOREGROUND")
    }

    /**
     * Always navigate home → 消息 from any screen. Never trust that the device is
     * already on the inbox list (open DM / wrong tab are common failure modes).
     */
    fun goInbox(runtime: PlaybookRuntime): NavResult {
        ensureForeground(runtime).let { if (it is NavResult.Failed) return it }
        val startHint = runtime.currentPageHint()
        // From note/comment sheets, backs alone are unreliable — jump to Index home first.
        if (startHint == PageHint.NOTE_DETAIL || startHint == PageHint.COMMENTS) {
            runtime.launchXhs()
            if (!runtime.waitForXhsForeground(12_000)) {
                return NavResult.Failed("XHS_NOT_FOREGROUND")
            }
            if (!waitForMainTabs(runtime)) {
                resetTowardHome(runtime)
            }
        } else {
            resetTowardHome(runtime)
            runtime.sleep(600)
        }
        // Leave any open DM before treating the message tab as the list hub.
        leaveOpenDmIfNeeded(runtime)
        if (hasMainBottomTabs(runtime) && clickMessageTab(runtime)) {
            runtime.sleep(1_400)
            leaveOpenDmIfNeeded(runtime)
            if (onInboxListNotOpenChat(runtime)) return NavResult.Ok
        }
        runtime.launchXhs()
        if (!runtime.waitForXhsForeground(12_000)) {
            return NavResult.Failed("XHS_NOT_FOREGROUND")
        }
        waitForMainTabs(runtime)
        leaveOpenDmIfNeeded(runtime)
        if (hasMainBottomTabs(runtime) && clickMessageTab(runtime)) {
            runtime.sleep(1_800)
            leaveOpenDmIfNeeded(runtime)
            if (onInboxListNotOpenChat(runtime)) return NavResult.Ok
        }
        if (hasMainBottomTabs(runtime)) {
            runtime.tap(756, 2294)
            runtime.sleep(1_500)
            leaveOpenDmIfNeeded(runtime)
            if (onInboxListNotOpenChat(runtime)) return NavResult.Ok
        }
        return NavResult.Failed("NAV_TIMEOUT")
    }

    private fun waitForMainTabs(runtime: PlaybookRuntime, timeoutMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hint = runtime.currentPageHint()
            if (
                hint != PageHint.NOTE_DETAIL &&
                hint != PageHint.COMMENTS &&
                hasMainBottomTabs(runtime)
            ) {
                runtime.sleep(400)
                return true
            }
            runtime.sleep(350)
        }
        return hasMainBottomTabs(runtime) &&
            runtime.currentPageHint() != PageHint.NOTE_DETAIL &&
            runtime.currentPageHint() != PageHint.COMMENTS
    }

    // Accept hub chrome even when PageHint flickers (profile bleed / short 赞收藏 terms).
    private fun onInboxSurface(runtime: PlaybookRuntime): Boolean =
        runtime.currentPageHint() == PageHint.INBOX ||
            runtime.looksLikeInboxListSurface() ||
            looksLikeInboxHub(runtime)

    private fun onInboxListNotOpenChat(runtime: PlaybookRuntime): Boolean =
        onInboxSurface(runtime) && !runtime.looksLikeOpenDmThreadSurface()

    /** Back out of an open DM until the message list / main tabs reappear. */
    private fun leaveOpenDmIfNeeded(runtime: PlaybookRuntime) {
        var backs = 0
        while (backs < 4 && runtime.looksLikeOpenDmThreadSurface()) {
            runtime.globalBack()
            runtime.sleep(400)
            backs += 1
        }
    }

    /** Always home → 我 → profile; never reuse an already-visible profile page. */
    fun goProfile(runtime: PlaybookRuntime): NavResult {
        ensureForeground(runtime).let { if (it is NavResult.Failed) return it }
        if (openProfile(runtime)) {
            ensureNotesTab(runtime)
            return NavResult.Ok
        }
        return NavResult.Failed("NAV_TIMEOUT")
    }

    private fun ensureNotesTab(runtime: PlaybookRuntime) {
        // Profile may open on 收藏/赞过; prefer 笔记 grid for sync.
        runtime.click("text=笔记", allowFinal = false, timeoutMs = 3_000) ||
            runtime.click("contentDescription=笔记", allowFinal = false, timeoutMs = 2_000) ||
            runtime.click("contentDescription=已选定笔记", allowFinal = false, timeoutMs = 2_000)
        runtime.sleep(800)
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
        val opened = openThreadByLocatorOrTitle(runtime, matched.titleSummary, hint, locator)
        if (!opened) return NavResult.Failed("NAV_TARGET_NOT_FOUND")
        runtime.sleep(1_000)
        return NavResult.Ok
    }

    private fun openThreadByLocatorOrTitle(
        runtime: PlaybookRuntime,
        titleSummary: String,
        hint: String,
        locator: String?,
    ): Boolean {
        // Prefer center= taps; "index=N;center=x,y" is not a StrictSelector.
        val center = locator?.let { CENTER_IN_LOCATOR.find(it) }
        if (center != null) {
            val x = center.groupValues[1].toIntOrNull()
            val y = center.groupValues[2].toIntOrNull()
            if (x != null && y != null && runtime.tap(x, y)) {
                return true
            }
        }
        if (locator != null &&
            !locator.contains("center=") &&
            runtime.click(locator, allowFinal = false, timeoutMs = 4_000)
        ) {
            return true
        }
        return runtime.clickTextContaining(titleSummary, timeoutMs = 6_000) ||
            runtime.clickTextContaining(hint, timeoutMs = 6_000) ||
            runtime.click("text=$titleSummary", allowFinal = false, timeoutMs = 4_000) ||
            tapNodeContaining(runtime, hint) ||
            tapNodeContaining(runtime, titleSummary)
    }

    /**
     * Always navigate home → 我 → note → comments. Never reuse an already-open
     * note/comments sheet from a previous command.
     */
    fun goNoteComments(
        runtime: PlaybookRuntime,
        titleSummary: String?,
        xhsHint: String? = null,
        locatorHint: String? = null,
    ): NavResult {
        ensureForeground(runtime).let { if (it is NavResult.Failed) return it }

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
        for (attempt in 0 until 12) {
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
        if (!blob.contains("消息")) return false
        val hubChrome = blob.contains("赞和收藏") ||
            blob.contains("赞|收藏") ||
            blob.contains("评论和@") ||
            blob.contains("新增关注")
        val secondary = blob.contains("私信") ||
            blob.contains("评论") ||
            blob.contains("系统通知") ||
            blob.contains("陌生人消息")
        return hubChrome && secondary
    }

    internal fun looksLikeProfile(runtime: PlaybookRuntime): Boolean {
        if (looksLikeInboxHub(runtime)) {
            // ViewPager can bleed hub chrome; still accept clear profile identity.
            val blob = surfaceBlob(runtime)
            return blob.contains("编辑主页") ||
                blob.contains("小红书号") ||
                blob.contains("获赞与收藏")
        }
        val blob = surfaceBlob(runtime)
        return blob.contains("编辑主页") ||
            blob.contains("编辑资料") ||
            blob.contains("小红书号") ||
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

    /** Public bottom-tab tap for playbooks (publish / inbox / profile). */
    fun tapBottomTab(runtime: PlaybookRuntime, label: String): Boolean =
        tapBottomTabLabeled(runtime, label)

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
            tapBottomNavFromRight(runtime, 1) ||
            runtime.tap(756, 2294) ||
            runtime.click("text=消息", allowFinal = false, timeoutMs = 5_000) ||
            runtime.click("contentDescription=消息", allowFinal = false, timeoutMs = 3_000) ||
            runtime.clickTextContaining("消息", timeoutMs = 3_000)

    private fun resetTowardHome(runtime: PlaybookRuntime) {
        // Note detail / comment / open-DM sheets hide the real bottom tabs. Absolute taps
        // at tab coordinates hit note action chrome instead — back out first.
        // ViewPager can still bleed 首页/消息 nodes into the note tree, so never trust
        // tab chrome while page_hint is NOTE_DETAIL or COMMENTS.
        var backs = 0
        while (backs < 10) {
            val hint = runtime.currentPageHint()
            if (hint == PageHint.HOME) break
            if (
                hint == PageHint.NOTE_DETAIL ||
                hint == PageHint.COMMENTS ||
                runtime.looksLikeOpenDmThreadSurface() ||
                !hasMainBottomTabs(runtime)
            ) {
                runtime.globalBack()
                runtime.sleep(450)
                backs += 1
                continue
            }
            // Leave inbox / other tabs so every command starts from a known home root.
            if (hint == PageHint.INBOX) {
                if (tapBottomTabLabeled(runtime, "首页") || runtime.tap(108, 2294)) {
                    runtime.sleep(500)
                }
                break
            }
            break
        }
        if (
            !hasMainBottomTabs(runtime) ||
            runtime.currentPageHint() == PageHint.NOTE_DETAIL ||
            runtime.currentPageHint() == PageHint.COMMENTS
        ) {
            runtime.launchXhs()
            runtime.waitForXhsForeground(12_000)
            runtime.sleep(900)
        }
        if (runtime.currentPageHint() != PageHint.HOME && runtime.currentPageHint() != PageHint.INBOX) {
            runtime.click("contentDescription=首页", allowFinal = false, timeoutMs = 3_000) ||
                runtime.click("text=首页", allowFinal = false, timeoutMs = 3_000) ||
                (hasMainBottomTabs(runtime) && runtime.tap(108, 2294))
            runtime.sleep(600)
        }
    }

    private fun hasMainBottomTabs(runtime: PlaybookRuntime): Boolean {
        val hint = runtime.currentPageHint()
        if (hint == PageHint.NOTE_DETAIL || hint == PageHint.COMMENTS) return false
        val blob = surfaceBlob(runtime)
        return blob.contains("首页") && (blob.contains("消息") || blob.contains("市集"))
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

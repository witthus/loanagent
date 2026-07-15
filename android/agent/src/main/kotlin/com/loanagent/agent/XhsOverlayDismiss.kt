package com.loanagent.agent

/**
 * Dismiss XHS / system transient overlays that block publish navigation.
 * Avoids tapping album chrome "关闭" (content-desc on CapaAlbumActivity).
 */
object XhsOverlayDismiss {
    /** Safe affirmative dismissals observed on device — never album back. */
    private val SAFE_DISMISS_LABELS = listOf(
        "我知道了",
        "知道了",
        "以后再说",
        "跳过",
        "不再提示",
        "允许",
        "始终允许",
        "仅使用期间允许",
        "同意",
    )

    fun dismiss(runtime: PlaybookRuntime, rounds: Int = 2) {
        repeat(rounds) {
            var hit = false
            for (label in SAFE_DISMISS_LABELS) {
                if (tapExact(runtime, label) ||
                    runtime.click("text=$label", allowFinal = false, timeoutMs = 600) ||
                    runtime.click("contentDescription=$label", allowFinal = false, timeoutMs = 600)
                ) {
                    hit = true
                    runtime.sleep(250)
                    break
                }
            }
            if (!hit && shouldTapPromoClose(runtime)) {
                tapExact(runtime, "关闭") ||
                    runtime.click("contentDescription=关闭", allowFinal = false, timeoutMs = 600)
                runtime.sleep(250)
            } else if (!hit) {
                return
            }
        }
    }

    /**
     * Only tap 关闭 when the tree looks like a home/feed promo, not album/editor chrome.
     */
    internal fun shouldTapPromoClose(runtime: PlaybookRuntime): Boolean {
        val snapshot = runtime.observe() ?: return false
        val labels = snapshot.nodes.map { node ->
            listOfNotNull(node.text, node.contentDescription).joinToString(" ")
        }
        val joined = labels.joinToString("\n")
        if (joined.contains("从相册选择") || joined.contains("添加标题") || joined.contains("发布笔记")) {
            return false
        }
        if (labels.any { it == "照片" || it == "实况图" || it == "全屏图" || it == "草稿箱" }) {
            return false
        }
        return labels.any { label ->
            label.contains("官方") ||
                label.contains("热门话题") ||
                label.contains("去发布") ||
                label.contains("看看最近")
        }
    }

    private fun tapExact(runtime: PlaybookRuntime, label: String): Boolean {
        val snapshot = runtime.observe() ?: return false
        val node = snapshot.nodes.firstOrNull { n ->
            n.text == label || n.contentDescription == label
        } ?: return false
        val bounds = node.bounds ?: return false
        return runtime.tap(bounds.centerX, bounds.centerY)
    }
}

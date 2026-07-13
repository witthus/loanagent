package com.loanagent.agent

enum class PageHint {
    HOME,
    SEARCH,
    PUBLISH_ENTRY,
    EDITOR,
    NOTE_DETAIL,
    COMMENTS,
    INBOX,
    BUSINESS_BLOCKED,
    LOGIN_REQUIRED,
    UNKNOWN,
}

data class PageRule(
    val hint: PageHint,
    val anyTerms: Set<String>,
    val minimumMatches: Int,
)

object M0PageRules {
    const val VERSION = "m0-2026-07-12b"

    val ordered = listOf(
        PageRule(PageHint.BUSINESS_BLOCKED, setOf("业务升级", "维护中", "暂不可用"), 1),
        PageRule(PageHint.LOGIN_REQUIRED, setOf("登录", "手机号", "验证码"), 2),
        // Prefer explicit editor chrome — bare 标题/正文/下一步 match filter screens too early.
        PageRule(PageHint.EDITOR, setOf("添加标题", "添加正文", "发布笔记"), 2),
        PageRule(PageHint.PUBLISH_ENTRY, setOf("发布笔记", "相册", "拍摄"), 2),
        PageRule(PageHint.COMMENTS, setOf("评论", "说点什么", "让大家听到你的声音"), 2),
        PageRule(PageHint.INBOX, setOf("消息", "私信", "通知", "发消息", "当前在线"), 2),
        PageRule(PageHint.NOTE_DETAIL, setOf("赞", "收藏", "作者笔记"), 2),
        // Strong search-only cues. Do not use 全部/用户/商品 alone — they appear on HOME too.
        PageRule(PageHint.SEARCH, setOf("取消", "历史记录", "猜你想搜", "搜索历史", "问一问"), 1),
        PageRule(PageHint.HOME, setOf("首页", "关注", "发现"), 2),
    )
}

class PageClassifier(
    private val rules: List<PageRule> = M0PageRules.ordered,
) {
    fun classify(nodes: List<UiNode>): PageHint {
        val searchable = nodes.joinToString("\n") {
            listOfNotNull(it.text, it.contentDescription, it.viewId).joinToString(" ")
        }
        // Message hub chrome ("赞和收藏" / "赞|收藏" / "评论和@" / "新增关注") contains short
        // substrings that otherwise match NOTE_DETAIL (赞+收藏) or HOME (关注). Prefer hub early.
        // ViewPager may also bleed profile markers into the tree — hub tiles still win.
        if (
            looksLikeInboxHubSurface(searchable) &&
            !looksLikeCommentComposer(searchable)
        ) {
            return PageHint.INBOX
        }
        val matched = rules.firstOrNull { rule ->
            rule.anyTerms.count { term -> containsTerm(searchable, term) } >= rule.minimumMatches
        }?.hint
        if (matched != null && matched in HIGH_PRIORITY_HINTS) {
            return matched
        }
        // Profile "我" shares bottom-tab "消息" and often hub chrome; do not treat as inbox.
        if (looksLikeProfileSurface(searchable)) {
            return matched ?: PageHint.UNKNOWN
        }
        val hasStrongInboxCue = searchable.contains("私信") ||
            (searchable.contains("消息") && searchable.contains("通知")) ||
            looksLikeInboxHubSurface(searchable) ||
            (searchable.contains("发消息") && !looksLikeCommentComposer(searchable)) ||
            (searchable.contains("当前在线") && searchable.contains("发消息")) ||
            (searchable.contains("陌生人消息") && searchable.contains("系统通知"))
        if (hasStrongInboxCue && !looksLikeCommentComposer(searchable)) {
            return PageHint.INBOX
        }
        // Feed cards expose 赞/收藏 and would otherwise win NOTE_DETAIL over HOME.
        if (
            matched == PageHint.NOTE_DETAIL &&
            containsTerm(searchable, "首页") &&
            containsTerm(searchable, "发现") &&
            !searchable.contains("作者笔记")
        ) {
            return PageHint.HOME
        }
        return matched ?: PageHint.UNKNOWN
    }

    private fun looksLikeProfileSurface(searchable: String): Boolean {
        val profileMarkers = listOf("编辑主页", "小红书号", "获赞与收藏", "点击这里，填写简介")
        return profileMarkers.any(searchable::contains)
    }

    private fun looksLikeCommentComposer(searchable: String): Boolean =
        searchable.contains("说点什么") || searchable.contains("让大家听到你的声音")

    private fun looksLikeInboxHubSurface(searchable: String): Boolean {
        if (!searchable.contains("消息")) return false
        // Message-tab tiles. Prefer these over bare 赞和收藏 — profile ViewPager pages
        // often bleed 编辑主页/小红书号 into the same a11y tree.
        if (
            searchable.contains("新增关注") ||
            searchable.contains("评论和@") ||
            searchable.contains("陌生人消息") ||
            searchable.contains("系统通知")
        ) {
            return true
        }
        val likeCollect = searchable.contains("赞和收藏") || searchable.contains("赞|收藏")
        return likeCollect && !looksLikeProfileSurface(searchable)
    }

    /**
     * Short rule terms like 赞/收藏/关注 must not fire only as substrings of hub compounds
     * such as 「赞和收藏」 or 「新增关注」.
     */
    private fun containsTerm(searchable: String, term: String): Boolean {
        if (!searchable.contains(term)) return false
        if (term !in SHORT_AMBIGUOUS_TERMS) return true
        var stripped = searchable
        for (compound in INBOX_HUB_COMPOUNDS) {
            stripped = stripped.replace(compound, " ")
        }
        return stripped.contains(term)
    }

    private companion object {
        val HIGH_PRIORITY_HINTS = setOf(
            PageHint.BUSINESS_BLOCKED,
            PageHint.LOGIN_REQUIRED,
            PageHint.EDITOR,
            PageHint.PUBLISH_ENTRY,
            PageHint.COMMENTS,
        )
        val INBOX_HUB_MARKERS = listOf(
            "赞和收藏",
            "赞|收藏",
            "评论和@",
            "新增关注",
            "陌生人消息",
            "系统通知",
        )
        val INBOX_HUB_COMPOUNDS = listOf(
            "赞和收藏按钮",
            "赞和收藏",
            "赞|收藏",
            "评论和@按钮",
            "评论和@",
            "新增关注按钮",
            "新增关注",
        )
        val SHORT_AMBIGUOUS_TERMS = setOf("赞", "收藏", "关注", "评论")
    }
}

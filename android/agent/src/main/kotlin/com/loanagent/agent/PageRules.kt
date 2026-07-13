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
        PageRule(PageHint.EDITOR, setOf("标题", "正文", "下一步"), 2),
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
        val matched = rules.firstOrNull { rule ->
            rule.anyTerms.count(searchable::contains) >= rule.minimumMatches
        }?.hint
        if (matched != null && matched in HIGH_PRIORITY_HINTS) {
            return matched
        }
        val hasStrongInboxCue = searchable.contains("私信") ||
            searchable.contains("登录|消息") ||
            (searchable.contains("消息") && searchable.contains("通知")) ||
            (searchable.contains("消息") && searchable.contains("赞和收藏")) ||
            (searchable.contains("发消息") && !searchable.contains("说点什么")) ||
            (searchable.contains("当前在线") && searchable.contains("发消息"))
        if (hasStrongInboxCue && !searchable.contains("说点什么") && !searchable.contains("让大家听到你的声音")) {
            return PageHint.INBOX
        }
        // Feed cards expose 赞/收藏 and would otherwise win NOTE_DETAIL over HOME.
        if (
            matched == PageHint.NOTE_DETAIL &&
            searchable.contains("首页") &&
            searchable.contains("发现") &&
            !searchable.contains("作者笔记")
        ) {
            return PageHint.HOME
        }
        return matched ?: PageHint.UNKNOWN
    }

    private companion object {
        val HIGH_PRIORITY_HINTS = setOf(
            PageHint.BUSINESS_BLOCKED,
            PageHint.LOGIN_REQUIRED,
            PageHint.EDITOR,
            PageHint.PUBLISH_ENTRY,
            PageHint.COMMENTS,
        )
    }
}

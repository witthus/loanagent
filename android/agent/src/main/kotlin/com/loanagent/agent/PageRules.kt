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
    const val VERSION = "m0-2026-07-12"

    val ordered = listOf(
        PageRule(PageHint.BUSINESS_BLOCKED, setOf("业务升级", "维护中", "暂不可用"), 1),
        PageRule(PageHint.LOGIN_REQUIRED, setOf("登录", "手机号", "验证码"), 2),
        PageRule(PageHint.EDITOR, setOf("标题", "正文", "下一步"), 2),
        PageRule(PageHint.PUBLISH_ENTRY, setOf("发布笔记", "相册", "拍摄"), 2),
        PageRule(PageHint.COMMENTS, setOf("评论", "说点什么"), 2),
        PageRule(PageHint.INBOX, setOf("消息", "私信", "通知"), 2),
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
        val hasInboxCue = listOf("私信", "通知", "登录|消息").any(searchable::contains) ||
            (searchable.contains("消息") && !searchable.contains("说点什么"))
        val hasCommentComposer = searchable.contains("说点什么")
        if (hasInboxCue && !hasCommentComposer) {
            return PageHint.INBOX
        }
        return rules.firstOrNull { rule ->
            rule.anyTerms.count(searchable::contains) >= rule.minimumMatches
        }?.hint ?: PageHint.UNKNOWN
    }
}

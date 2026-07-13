package com.loanagent.agent

data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
    val isUsable: Boolean get() = right > left && bottom > top
}

/** Resolve the label used for selector matching on editable fields (hint when text is empty). */
object AccessibleText {
    fun resolve(text: CharSequence?, hint: CharSequence?): String? {
        val visible = text?.toString()?.trim().orEmpty()
        if (visible.isNotEmpty()) return visible
        val hintValue = hint?.toString()?.trim().orEmpty()
        return hintValue.takeIf { it.isNotEmpty() }
    }

    fun matchesSelector(selectorText: String?, text: CharSequence?, hint: CharSequence?): Boolean =
        selectorText == null || resolve(text, hint) == selectorText
}

data class UiNode(
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val bounds: UiBounds? = null,
)

data class RawUiNode(
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val bounds: UiBounds? = null,
    val children: List<RawUiNode> = emptyList(),
)

data class UiSnapshot(
    val packageName: String,
    val className: String,
    val pageHint: PageHint,
    val keyElements: List<String>,
    val nodes: List<UiNode>,
    val truncated: Boolean,
)

data class SnapshotLimits(
    val maxDepth: Int = 20,
    val maxNodes: Int = 500,
    val maxTextLength: Int = 256,
)

class SensitiveTextRedactor {
    fun redact(value: String?, password: Boolean): String? {
        if (value == null) return null
        if (password) return "[REDACTED_PASSWORD]"
        var result = value
        result = PHONE.replace(result) { "${it.value.take(3)}****${it.value.takeLast(4)}" }
        result = ID_CARD.replace(result, "[REDACTED_ID]")
        result = EMAIL.replace(result, "[REDACTED_EMAIL]")
        result = BEARER.replace(result) { "${it.groupValues[1]} [REDACTED_TOKEN]" }
        result = TOKEN_CONTEXT.replace(result) { "${it.groupValues[1]}=[REDACTED_TOKEN]" }
        result = OTP_CONTEXT.replace(result) { match ->
            match.value.replace(DIGITS, "******")
        }
        return result
    }

    private companion object {
        val PHONE = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
        val ID_CARD = Regex("(?<!\\d)\\d{17}[\\dXx](?!\\d)")
        val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val BEARER = Regex("(?i)(Bearer)\\s+[A-Za-z0-9._~+/-]+=*")
        val TOKEN_CONTEXT = Regex(
            "(?i)\\b(session(?:_token)?|access_token|refresh_token|auth_token|token)\\b\\s*[:=]\\s*([^\\s,;]+)",
        )
        val OTP_CONTEXT = Regex("(验证码|校验码|动态码)\\s*[:：]?\\s*\\d{4,8}")
        val DIGITS = Regex("\\d{4,8}")
    }
}

class SnapshotBuilder(
    private val limits: SnapshotLimits = SnapshotLimits(),
    private val redactor: SensitiveTextRedactor = SensitiveTextRedactor(),
    private val classifier: PageClassifier = PageClassifier(),
) {
    fun build(
        packageName: String,
        className: String,
        root: RawUiNode,
        sourceTruncated: Boolean = false,
    ): UiSnapshot {
        val nodes = mutableListOf<UiNode>()
        var truncated = sourceTruncated

        fun visit(raw: RawUiNode, depth: Int) {
            if (depth > limits.maxDepth || nodes.size >= limits.maxNodes) {
                truncated = true
                return
            }
            nodes += UiNode(
                viewId = raw.viewId?.take(limits.maxTextLength),
                text = redactor.redact(raw.text, raw.password)?.take(limits.maxTextLength),
                contentDescription = redactor.redact(raw.contentDescription, raw.password)
                    ?.take(limits.maxTextLength),
                className = raw.className?.take(limits.maxTextLength),
                clickable = raw.clickable,
                editable = raw.editable,
                password = raw.password,
                bounds = raw.bounds,
            )
            raw.children.forEach { child -> visit(child, depth + 1) }
        }

        visit(root, 0)
        val hint = classifier.classify(nodes)
        val keys = nodes.asSequence()
            .flatMap { sequenceOf(it.text, it.contentDescription, it.viewId) }
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
            .toList()
        return UiSnapshot(packageName, className, hint, keys, nodes, truncated)
    }
}

data class Selector(
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
) {
    val hasStableIdentity: Boolean
        get() = viewId != null || text != null || contentDescription != null || className != null
}

enum class SelectorMatchStatus {
    UNIQUE,
    NOT_FOUND,
    AMBIGUOUS,
    INDETERMINATE,
}

data class SelectorMatch(
    val status: SelectorMatchStatus,
    val node: UiNode? = null,
)

class SelectorEngine {
    fun find(
        nodes: List<UiNode>,
        selector: Selector,
        sourceTruncated: Boolean = false,
    ): SelectorMatch {
        val matches = nodes.asSequence()
            .filter { node -> matches(node, selector) }
            .take(2)
            .toList()
        return when {
            matches.size > 1 -> SelectorMatch(SelectorMatchStatus.AMBIGUOUS)
            sourceTruncated -> SelectorMatch(SelectorMatchStatus.INDETERMINATE)
            matches.isEmpty() -> SelectorMatch(SelectorMatchStatus.NOT_FOUND)
            else -> SelectorMatch(SelectorMatchStatus.UNIQUE, matches.single())
        }
    }

    fun hasAnyMatch(nodes: List<UiNode>, selector: Selector): Boolean =
        nodes.any { node -> matches(node, selector) }

    private fun matches(node: UiNode, selector: Selector): Boolean =
        (selector.viewId == null || node.viewId == selector.viewId) &&
            (selector.text == null || node.text == selector.text) &&
            (selector.contentDescription == null ||
                node.contentDescription == selector.contentDescription) &&
            (selector.className == null || node.className == selector.className) &&
            (selector.clickable == null || node.clickable == selector.clickable)
}

enum class OcrDecision {
    RUN,
    SKIP_NOT_USER_TRIGGERED,
    SKIP_ACCESSIBILITY_SUFFICIENT,
    BLOCK_UNSUPPORTED_API,
}

class OcrFallbackPolicy {
    fun decide(apiLevel: Int, userTriggered: Boolean, usefulNodeCount: Int): OcrDecision = when {
        !userTriggered -> OcrDecision.SKIP_NOT_USER_TRIGGERED
        apiLevel < 30 -> OcrDecision.BLOCK_UNSUPPORTED_API
        usefulNodeCount > 0 -> OcrDecision.SKIP_ACCESSIBILITY_SUFFICIENT
        else -> OcrDecision.RUN
    }
}

enum class InputRoute {
    ACTION_SET_TEXT,
    MANUAL_IME,
    CLIPBOARD,
    BLOCKED_ENABLE_IME_MANUALLY,
    BLOCKED_NOT_EDITABLE,
}

class InputStrategy {
    fun choose(
        editable: Boolean,
        setTextSupported: Boolean,
        imeEnabled: Boolean,
        clipboardAllowed: Boolean = true,
    ): InputRoute = when {
        !editable -> InputRoute.BLOCKED_NOT_EDITABLE
        setTextSupported -> InputRoute.ACTION_SET_TEXT
        imeEnabled -> InputRoute.MANUAL_IME
        clipboardAllowed -> InputRoute.CLIPBOARD
        else -> InputRoute.BLOCKED_ENABLE_IME_MANUALLY
    }
}

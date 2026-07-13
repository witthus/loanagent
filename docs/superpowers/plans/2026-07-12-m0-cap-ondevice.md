# M0-Cap On-Device Capabilities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the logged-in Redmi test device, make the Android Agent reliably complete three M0-Cap capabilities—publish a note, extract comment-thread content, and extract inbox/DM content—with matrix evidence, before any cloud feature work.

**Architecture:** Keep using AccessibilityService + debug broadcast bridge (no MQTT yet). Add pure-Kotlin extractors and page-rule fixes under `android/agent`, expose them via new debug commands that write redacted JSON to `files/m0-debug-result.json`, extend input with clipboard fallback, and add an explicit final-publish opt-in so `FinalActionPolicy` still blocks accidental「发布」clicks. Verify with Docker Gradle unit tests and `ops/m0/run-redmi-matrix.sh` on device.

**Tech Stack:** Kotlin, Android AccessibilityService, JUnit (Docker `android-builder`), bash matrix runner, existing `SnapshotBuilder` / `SensitiveTextRedactor` / `M0DebugBridge`.

**Spec:** `docs/superpowers/specs/2026-07-12-xhs-role-funnel-agent-design.md` §2.2 M0-Cap only.  
**Out of scope:** Control Plane, MQTT, Web console, multi-account orchestrator, MDM.

---

## File map

| File | Responsibility |
| --- | --- |
| `android/agent/src/main/kotlin/com/loanagent/agent/PageRules.kt` | Fix INBOX vs NOTE_DETAIL classification; optional activity hint |
| `android/agent/src/main/kotlin/com/loanagent/agent/ContentExtractors.kt` | **Create** — extract comments / inbox threads / DM messages (redacted summaries) |
| `android/agent/src/main/kotlin/com/loanagent/agent/UiModels.kt` | Add `CLIPBOARD` to `InputRoute` / `InputStrategy` |
| `android/agent/src/main/kotlin/com/loanagent/agent/AsyncExecution.kt` | Wire clipboard fallback after SET_TEXT failure when allowed |
| `android/agent/src/main/kotlin/com/loanagent/agent/M0AccessibilityService.kt` | Clipboard paste helper if needed |
| `android/agent/src/debug/kotlin/com/loanagent/agent/M0DebugCommandReceiver.kt` | New commands + `allow_final_action` opt-in |
| `android/agent/src/test/kotlin/com/loanagent/agent/M0CoreBehaviorTest.kt` | Classifier + extractor unit tests |
| `android/agent/src/test/kotlin/com/loanagent/agent/ContentExtractorsTest.kt` | **Create** — extractor tests |
| `android/agent/src/testDebug/kotlin/com/loanagent/agent/M0DebugBridgeTest.kt` | Bridge command / final-action tests |
| `ops/m0/run-redmi-matrix.sh` | Helpers for extract / publish-cap commands |
| `ops/m0/xhs-flow-matrix.md` | Record PASS/FAIL evidence |
| `ops/m0/m0-cap-checklist.md` | **Create** — operator steps for real-device run |

---

### Task 1: Fix INBOX vs NOTE_DETAIL page classification

**Files:**
- Modify: `android/agent/src/main/kotlin/com/loanagent/agent/PageRules.kt`
- Modify: `android/agent/src/test/kotlin/com/loanagent/agent/M0CoreBehaviorTest.kt`
- Modify: `android/agent/src/main/kotlin/com/loanagent/agent/UiModels.kt` (only if `SnapshotBuilder` must pass activity into classifier — preferred)

- [ ] **Step 1: Write failing tests for message-tab misclassification**

Add to `M0CoreBehaviorTest.kt`:

```kotlin
@Test
fun inboxTabWithLikeCollectCuesStillClassifiesAsInbox() {
    val nodes = listOf(
        UiNode(text = "消息"),
        UiNode(text = "赞|收藏"),
        UiNode(text = "评论"),
        UiNode(text = "登录|消息"),
        UiNode(contentDescription = "消息"),
    )
    assertEquals(PageHint.INBOX, PageClassifier().classify(nodes))
}

@Test
fun noteDetailWithoutInboxCuesStillClassifiesAsNoteDetail() {
    val nodes = listOf(
        UiNode(text = "赞"),
        UiNode(text = "收藏"),
        UiNode(text = "作者笔记"),
        UiNode(text = "关注"),
    )
    assertEquals(PageHint.NOTE_DETAIL, PageClassifier().classify(nodes))
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew :agent:testDebugUnitTest --tests com.loanagent.agent.M0CoreBehaviorTest.inboxTabWithLikeCollectCuesStillClassifiesAsInbox \
  --project-cache-dir /home/gradle/work/project-cache --no-daemon
```

Expected: FAIL (`NOTE_DETAIL` or wrong hint).

- [ ] **Step 3: Implement classifier fix**

In `PageRules.kt`, change classification to prefer INBOX when inbox nav/chat cues dominate. Minimal approach:

```kotlin
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
```

Bump `M0PageRules.VERSION` to `m0-2026-07-12`.

Update existing `pageClassifierCoversRequiredM0Hints` if any fixture string now expects INBOX differently — keep HOME/SEARCH/etc. assertions green.

If `SnapshotBuilder` already has activity `className`, optionally short-circuit to INBOX when `className` contains `im.` / `Message` / `Chat` (add a focused unit test).

- [ ] **Step 4: Re-run classifier tests — expect PASS**

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew :agent:testDebugUnitTest --tests com.loanagent.agent.M0CoreBehaviorTest --project-cache-dir /home/gradle/work/project-cache --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/agent/src/main/kotlin/com/loanagent/agent/PageRules.kt \
  android/agent/src/test/kotlin/com/loanagent/agent/M0CoreBehaviorTest.kt \
  android/agent/src/main/kotlin/com/loanagent/agent/UiModels.kt
git commit -m "$(cat <<'EOF'
fix(agent): prefer INBOX over NOTE_DETAIL on message tab

EOF
)"
```

---

### Task 2: Comment and inbox content extractors (pure Kotlin)

**Files:**
- Create: `android/agent/src/main/kotlin/com/loanagent/agent/ContentExtractors.kt`
- Create: `android/agent/src/test/kotlin/com/loanagent/agent/ContentExtractorsTest.kt`

- [ ] **Step 1: Write failing extractor tests**

```kotlin
package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentExtractorsTest {
    private val redactor = SensitiveTextRedactor()
    private val extractor = ContentExtractors(redactor)

    @Test
    fun extractsCommentSummariesWithoutRawPhoneNumbers() {
        val nodes = listOf(
            UiNode(text = "用户甲", clickable = true),
            UiNode(text = "请问可以做吗 联系13800138000"),
            UiNode(text = "用户乙"),
            UiNode(text = "同问"),
        )
        val items = extractor.extractComments(nodes, maxItems = 10)
        assertTrue(items.size >= 2)
        assertTrue(items.all { !it.bodySummary.contains("13800138000") })
        assertEquals(items[0].bodySummary.length, items[0].bodySummary.length) // non-empty
    }

    @Test
    fun extractsInboxThreadRowsWithUnreadHints() {
        val nodes = listOf(
            UiNode(text = "小红书用户A", clickable = true),
            UiNode(text = "[未读] 你好，想咨询一下"),
            UiNode(text = "小红书用户B", clickable = true),
            UiNode(text = "昨天已读内容"),
        )
        val threads = extractor.extractInboxThreads(nodes, maxItems = 10)
        assertTrue(threads.isNotEmpty())
        assertTrue(threads.first().titleSummary.isNotBlank())
    }

    @Test
    fun extractsDmMessagesFromOpenThread() {
        val nodes = listOf(
            UiNode(text = "对方"),
            UiNode(text = "我想了解方案"),
            UiNode(text = "我"),
            UiNode(text = "请发湖北+公积金基数"),
        )
        val messages = extractor.extractDmMessages(nodes, maxItems = 20)
        assertTrue(messages.size >= 2)
        assertTrue(messages.all { it.bodySummary.isNotBlank() })
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL (class missing)**

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew :agent:testDebugUnitTest --tests com.loanagent.agent.ContentExtractorsTest --project-cache-dir /home/gradle/work/project-cache --no-daemon
```

Expected: compilation FAIL / class not found.

- [ ] **Step 3: Implement extractors**

Create `ContentExtractors.kt`:

```kotlin
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
        val texts = nodes.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
        val skip = setOf("评论", "说点什么", "赞", "收藏", "分享", "关注", "发送")
        val bodies = texts.filter { it !in skip && it.length >= 2 }
        return bodies.take(maxItems).mapIndexed { index, body ->
            ExtractedComment(
                authorSummary = redactor.redact(texts.getOrNull(index - 1) ?: "unknown", false) ?: "unknown",
                bodySummary = redactor.redact(body, false)?.take(120) ?: "",
                locatorHint = "text_hash:${body.hashCode()}",
            )
        }.filter { it.bodySummary.isNotBlank() }
    }

    fun extractInboxThreads(nodes: List<UiNode>, maxItems: Int): List<ExtractedInboxThread> {
        val clickableLabels = nodes.filter { it.clickable }
            .mapNotNull { n ->
                val label = listOfNotNull(n.text, n.contentDescription).firstOrNull { !it.isNullOrBlank() }
                label?.trim()
            }
            .filter { it.length >= 2 }
            .distinct()
        return clickableLabels.take(maxItems).map { title ->
            val unread = title.contains("未读") || title.contains("[未读]")
            ExtractedInboxThread(
                titleSummary = redactor.redact(title, false)?.take(64) ?: "",
                previewSummary = "",
                unreadHint = unread,
                locatorHint = "text=${title.take(32)}",
            )
        }.filter { it.titleSummary.isNotBlank() }
    }

    fun extractDmMessages(nodes: List<UiNode>, maxItems: Int): List<ExtractedDmMessage> {
        val texts = nodes.mapNotNull { it.text?.trim()?.takeIf { t -> t.length >= 1 } }
        val skip = setOf("发送", "相册", "更多", "消息")
        return texts.filter { it !in skip }.take(maxItems).map { body ->
            ExtractedDmMessage(
                senderSummary = "participant",
                bodySummary = redactor.redact(body, false)?.take(160) ?: "",
            )
        }.filter { it.bodySummary.isNotBlank() }
    }
}
```

Tune heuristics until Task 2 unit tests pass; keep summaries short and always redacted.

- [ ] **Step 4: Re-run extractor tests — expect PASS**

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew :agent:testDebugUnitTest --tests com.loanagent.agent.ContentExtractorsTest --project-cache-dir /home/gradle/work/project-cache --no-daemon
```

- [ ] **Step 5: Commit**

```bash
git add android/agent/src/main/kotlin/com/loanagent/agent/ContentExtractors.kt \
  android/agent/src/test/kotlin/com/loanagent/agent/ContentExtractorsTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): add redacted comment and inbox extractors

EOF
)"
```

---

### Task 3: Debug commands for EXTRACT_COMMENTS / EXTRACT_INBOX / EXTRACT_THREAD

**Files:**
- Modify: `android/agent/src/debug/kotlin/com/loanagent/agent/M0DebugCommandReceiver.kt`
- Modify: `android/agent/src/testDebug/kotlin/com/loanagent/agent/M0DebugBridgeTest.kt`
- Modify: `ops/m0/run-redmi-matrix.sh`

- [ ] **Step 1: Write failing bridge tests**

In `M0DebugBridgeTest.kt`, add cases that send `EXTRACT_COMMENTS` / `EXTRACT_INBOX` / `EXTRACT_THREAD` against a fake controller returning a snapshot with known nodes, and assert JSON contains `"items"` and `"status":"SUCCESS"` (and no raw phone `13800138000`).

- [ ] **Step 2: Run debug unit tests — expect FAIL**

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew :agent:testDebugUnitTest --tests com.loanagent.agent.M0DebugBridgeTest --project-cache-dir /home/gradle/work/project-cache --no-daemon
```

- [ ] **Step 3: Implement commands in `M0DebugBridge`**

Add enum values:

```kotlin
EXTRACT_COMMENTS,
EXTRACT_INBOX,
EXTRACT_THREAD,
```

On execute: require a11y controller → `observe`/current snapshot nodes → run `ContentExtractors` → write JSON like:

```json
{
  "schema_version": "m0-debug-1",
  "command": "EXTRACT_COMMENTS",
  "status": "SUCCESS",
  "page_hint": "COMMENTS",
  "count": 3,
  "items": [ { "author_summary": "...", "body_summary": "...", "locator_hint": "..." } ]
}
```

Do not include full raw node trees.

Extend `run-redmi-matrix.sh`:

```bash
extract-comments) bridge EXTRACT_COMMENTS ;;
extract-inbox) bridge EXTRACT_INBOX ;;
extract-thread) bridge EXTRACT_THREAD ;;
```

- [ ] **Step 4: Re-run bridge tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add android/agent/src/debug/kotlin/com/loanagent/agent/M0DebugCommandReceiver.kt \
  android/agent/src/testDebug/kotlin/com/loanagent/agent/M0DebugBridgeTest.kt \
  ops/m0/run-redmi-matrix.sh
git commit -m "$(cat <<'EOF'
feat(agent): expose extract debug commands for comments and inbox

EOF
)"
```

---

### Task 4: Clipboard input fallback for XHS editors

**Files:**
- Modify: `android/agent/src/main/kotlin/com/loanagent/agent/UiModels.kt`
- Modify: `android/agent/src/main/kotlin/com/loanagent/agent/AsyncExecution.kt`
- Modify: `android/agent/src/main/kotlin/com/loanagent/agent/M0AccessibilityService.kt` (clipboard + paste if required)
- Modify: `android/agent/src/test/kotlin/com/loanagent/agent/M0CoreBehaviorTest.kt`
- Modify: `android/agent/src/test/kotlin/com/loanagent/agent/M0ExecutionIntegrationTest.kt` as needed

- [ ] **Step 1: Failing test for clipboard route**

```kotlin
@Test
fun inputStrategyFallsBackToClipboardWhenSetTextUnsupportedAndImeDisabled() {
    val strategy = InputStrategy()
    assertEquals(
        InputRoute.CLIPBOARD,
        strategy.choose(
            editable = true,
            setTextSupported = false,
            imeEnabled = false,
            clipboardAllowed = true,
        ),
    )
}
```

- [ ] **Step 2: Run — expect FAIL (signature / enum)**

- [ ] **Step 3: Implement**

```kotlin
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
```

In `AsyncExecution` SET_TEXT path: if node action fails or route is CLIPBOARD, copy text via `ClipboardManager`, focus node, paste (`ACTION_PASTE` if available) or long-press paste; return `ActionResult` with `message` containing `input_route=CLIPBOARD` (or SET_TEXT/IME). Never log full text in diagnostics beyond length.

Update existing `InputStrategy` tests to pass the new parameter.

- [ ] **Step 4: Unit + integration tests PASS**

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew :agent:testDebugUnitTest --tests com.loanagent.agent.M0CoreBehaviorTest --tests com.loanagent.agent.M0ExecutionIntegrationTest --project-cache-dir /home/gradle/work/project-cache --no-daemon
```

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(agent): add clipboard fallback input route for editors

EOF
)"
```

---

### Task 5: Explicit final-publish opt-in for M0-Cap

**Files:**
- Modify: `android/agent/src/debug/kotlin/com/loanagent/agent/M0DebugCommandReceiver.kt`
- Modify: `android/agent/src/testDebug/kotlin/com/loanagent/agent/M0DebugBridgeTest.kt`
- Modify: `ops/m0/run-redmi-matrix.sh`

**Context:** `FinalActionPolicy` currently blocks selectors like `text=发布`. M0-Cap needs exactly one real publish, without weakening default safety.

- [ ] **Step 1: Failing tests**

1. Without flag: `text=发布` still → `FINAL_ACTION_UNSUPPORTED`.
2. With `--ez allow_final_action true` **and** `--ez confirmed true`: click allowed through to controller (mock returns SUCCESS).

- [ ] **Step 2: Run — expect FAIL on case 2**

- [ ] **Step 3: Implement**

```kotlin
const val EXTRA_ALLOW_FINAL_ACTION = "allow_final_action"

// in executeAction:
val allowFinal = intent.getBooleanExtra(EXTRA_ALLOW_FINAL_ACTION, false)
val confirmed = intent.getBooleanExtra(EXTRA_CONFIRMED, false)
if (command == DebugCommand.CLICK && FinalActionPolicy.blocks(selector)) {
    if (!(allowFinal && confirmed)) {
        finish("FINAL_ACTION_UNSUPPORTED", null)
        return CompletedRequestHandle
    }
}
```

Matrix helper:

```bash
click-final) bridge CLICK --es selector "$ARG2" --ez confirmed true --ez allow_final_action true --ei timeout_ms 8000 ;;
```

Document that only M0-Cap publish uses `click-final`.

- [ ] **Step 4: Bridge tests PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(agent): allow explicit final publish click for M0-Cap only

EOF
)"
```

---

### Task 6: Stage APK + device dry-run scripts for the three capabilities

**Files:**
- Create: `ops/m0/m0-cap-checklist.md`
- Modify: `ops/m0/run-redmi-matrix.sh` (wake helpers if missing)
- Modify: `ops/m0/xhs-flow-matrix.md` (result rows after run)

- [ ] **Step 1: Write checklist doc with exact commands**

`ops/m0/m0-cap-checklist.md` must include:

1. `bash ops/m0/stage-apks.sh` then install `agent-m0-debug.apk`
2. Ensure Bound accessibility
3. Push one test image to device DCIM/Download
4. **read_comments:** open a note → `extract-comments` → expect `count>=1`
5. **read_inbox:** `click 'text=消息'` → observe `page_hint=INBOX` → `extract-inbox` → open thread → `extract-thread`
6. **publish_note:** enter publish flow → select image → SET_TEXT title/body (or clipboard) → `click-final 'text=发布'` once → record success
7. Update matrix PASS/FAIL

- [ ] **Step 2: Stage and install via Docker ADB**

```bash
bash ops/m0/stage-apks.sh
export REDMI_ADB_ENDPOINT=192.168.10.13:42255 REDMI_SERIAL=192.168.10.13:42255
# push + adb install -r as documented in redmi-note-12-turbo-test.md
```

- [ ] **Step 3: Execute checklist on device; paste outcomes into matrix**

Update `ops/m0/xhs-flow-matrix.md` rows:

- 搜索/编辑框单次输入 → update if clipboard/SET_TEXT now works on editor
- 笔记详情 / 评论区 / 消息页 / 发帖相关 → PASS with date `2026-07-12+` evidence notes

- [ ] **Step 4: Commit docs + script only after real-device evidence exists**

```bash
git add ops/m0/m0-cap-checklist.md ops/m0/xhs-flow-matrix.md ops/m0/run-redmi-matrix.sh
git commit -m "$(cat <<'EOF'
docs(m0): record M0-Cap device evidence for publish and inbox reads

EOF
)"
```

If a capability fails on device, **do not** mark PASS; open a follow-up task in the matrix notes and stop cloud work.

---

### Task 7: Full agent unit regression before declaring M0-Cap gate

**Files:** none new

- [ ] **Step 1: Run full agent unit tests in Docker**

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew :agent:testDebugUnitTest :agent:testReleaseUnitTest \
  --project-cache-dir /home/gradle/work/project-cache --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm matrix shows three M0-Cap rows PASS**

- [ ] **Step 3: Commit any remaining test fixes**

- [ ] **Step 4: Stop** — do not start M1-Cloud until human confirms gate.

---

## Spec coverage check

| Spec requirement | Task |
| --- | --- |
| Fix message tab `page_hint` | Task 1 |
| `read_comments` structured extract | Tasks 2–3, 6 |
| `read_inbox` list + thread extract | Tasks 2–3, 6 |
| `publish_note` with recorded input route | Tasks 4–6 |
| Real publish once on logged-in device | Tasks 5–6 |
| Evidence in matrix | Task 6 |
| No cloud until PASS | Task 7 + plan out-of-scope |
| DO/MDM deferred | Out of scope (no tasks) |

## Placeholder / consistency review

- No TBD steps; commands and types named consistently (`ContentExtractors`, `InputRoute.CLIPBOARD`, `EXTRA_ALLOW_FINAL_ACTION`).
- Final publish remains blocked unless **both** `allow_final_action` and `confirmed` are true.

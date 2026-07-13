# 端上自动表面导航设计

**日期：** 2026-07-13  
**状态：** 已实施；真机：`inbox_sync` / `reply_dm` 自首页自动导航 **succeeded**；`read_comments` 经「我→笔记」路径仍可能 `NAV_TIMEOUT`（资料页选择器待加固）  
**范围：** Agent Playbook 在执行前自动导航到评论区 / 私信列表或指定会话；Control Plane 下发任务时补齐定位 params  
**上位：** `2026-07-13-ondevice-playbook-engine-design.md`；`2026-07-13-ops-publish-comments-inbox-design.md`

---

## 1. 目标

云端下发下列任务时，无论设备当前在哪个 App / 界面，Agent 均先导航到正确表面再执行业务：

| Playbook | 目标表面 |
| --- | --- |
| `read_comments` | 指定笔记的评论区（或笔记详情可抽评论） |
| `reply_comment` | 同上（再回复） |
| `inbox_sync` | 消息列表 `INBOX` |
| `inbox_open_thread` | 指定会话（可抽消息） |
| `reply_dm` | 指定会话输入框 |

**非目标：** 深链 Intent、模糊「最像」多候选、人工确认、`post_comment` / `publish_note` 导航（本波不改）。

---

## 2. 决策摘要

| 项 | 选择 |
| --- | --- |
| 范围 | B：回复 + 只读（上表） |
| 笔记定位 | A：云端 `title_summary` / `xhs_hint` / `locator_hint`，UI「我 → 笔记」匹配 |
| 失败策略 | A：硬失败，明确错误码，不执行副作用回复 |
| 架构 | 共享 `SurfaceNavigator`，各 Playbook 开头调用 |

---

## 3. 架构

```text
Task params (CP)
  title_summary / open_title_hint / locator_hint / xhs_hint
        ↓
Playbook.run
        ↓
SurfaceNavigator.ensureForeground
  → resetTowardHome (optional backs + 首页)
  → goInbox | goNoteComments(hints)
        ↓
业务：extract / setText / send
```

新增 `android/agent/src/main/.../SurfaceNavigator.kt`（纯 Kotlin，依赖 `PlaybookRuntime`）。

`NavResult`：`Ok` | `Failed(errorCode)`。

错误码：

| Code | 含义 |
| --- | --- |
| `A11Y_DOWN` | 无障碍未绑定 |
| `XHS_NOT_FOREGROUND` | 无法拉起小红书 |
| `NAV_MISSING_HINT` | 需要定位线索但 params 缺失，且当前不在目标表面 |
| `NAV_TIMEOUT` | 步骤超时仍未到目标 hint |
| `NAV_TARGET_NOT_FOUND` | 列表中匹配不到标题/会话 |

已在目标表面且业务可继续时：**跳过**完整导航（短路径）。

---

## 4. 导航路径

### 4.1 公共：确保前台

1. `accessibilityAlive` 否则 `A11Y_DOWN`  
2. `waitForXhsForeground`；失败则 `launchXhs` 再等；仍失败 `XHS_NOT_FOREGROUND`

### 4.2 `goInbox`

1. 若已是 `INBOX` → Ok  
2. 点击 `contentDescription=消息` 或 `text=消息`  
3. 失败则 `globalBack` 最多 4 次，再点 `contentDescription=首页` / `text=首页`，再点消息  
4. 仍非 `INBOX` → `NAV_TIMEOUT`

### 4.3 `goDmThread(openTitleHint)`

1. `goInbox`  
2. `extractInboxThreads`；按 `titleContains(hint)` 选最优（最长公共前缀 / 包含关系）  
3. 有 `locator_hint` 则 click；否则对匹配节点 `observe` + `tap` 中心  
4. 找不到 → `NAV_TARGET_NOT_FOUND`  
5. 成功标准：能 `extractDmMessages` 非空，或 hint 为 `UNKNOWN`/`INBOX` 且后续由业务探测 composer（会话页常 UNKNOWN）

### 4.4 `goNoteComments(titleSummary?, xhsHint?, locatorHint?)`

1. 若已是 `COMMENTS` 或 `NOTE_DETAIL` → 必要时点「评论」打开评论层 → Ok  
2. 无线索且不在目标面 → `NAV_MISSING_HINT`  
3. 路径：点 `contentDescription=我` / `text=我` → 点「笔记」相关入口（`text=笔记` / 含「笔记」的 tab）→ 在可见节点中按 `title_summary` **包含匹配**（`observe` + `tap`）  
4. 进入 `NOTE_DETAIL` 后点评论  
5. 目标 hint ∈ `{COMMENTS, NOTE_DETAIL}` 否则 `NAV_TARGET_NOT_FOUND` / `NAV_TIMEOUT`

标题匹配规则：规范化空白后，`nodeText.contains(hint)` 或 `hint.contains(nodeText)`（nodeText 长度 ≥ 4），取最长匹配；零匹配则失败（禁止随便点第一条）。

---

## 5. Playbook 变更

| Playbook | 变更 |
| --- | --- |
| `inbox_sync` | 开头 `goInbox`（替代仅点消息） |
| `inbox_open_thread` | `goDmThread(open_title_hint)`；缺 hint 且抽不到消息 → `NAV_MISSING_HINT` |
| `reply_dm` | 有 `open_title_hint`/`thread_title` 则先 `goDmThread`；成功后再 setText/发送 |
| `read_comments` | 非评论面则 `goNoteComments`；成功后再 extract |
| `reply_comment` | 同上，再回复 |

`WRONG_PAGE` 保留为「导航声称成功但业务探测失败」的兜底；导航阶段优先用 `NAV_*`。

---

## 6. Control Plane

下发时补齐线索（已有则保留）：

- `NotesService.sync_comments` / `reply_comment`：params 增加 `title_summary`（来自 `published_notes`）  
- `InboxService.reply_thread`：已有 `open_title_hint`  
- `inbox_open_thread` 任务创建处（若有）：传入 `open_title_hint`

无 schema 版本变更（params JSON 扩展）。

---

## 7. 测试

- 单元：`SurfaceNavigatorTest` + 更新 `PlaybookEngineTest`  
  - 从 `HOME` + 有 hint → 评论/私信成功路径（Fake 按 click/observe 切换 hint）  
  - 缺 hint → `NAV_MISSING_HINT`  
  - 列表无匹配 → `NAV_TARGET_NOT_FOUND`  
- 真机：首页下发 `inbox_sync`、带 `open_title_hint` 的 `reply_dm`、带 `title_summary` 的 `read_comments`（需账号下确有该笔记）

---

## 8. 风险

- 「我 → 笔记」选择器随 XHS 版本漂移 → 错误码可观测，后续可调 selector 表  
- 标题截断导致匹配失败 → 运营侧保证 `title_summary` 与端上可见前缀一致；发布入库已截断 64 字  
- 会话列表噪声行（赞和收藏等）→ 匹配必须基于 hint，禁止 open_first 作为 reply 默认

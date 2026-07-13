# 云端运营后台设计：发帖 · 评论 · 私信闭环

**日期：** 2026-07-13  
**状态：** W1–W4 已落地（2026-07-13）；云端发帖/私信入库真机冒烟通过；评论页依赖端上停留在评论面（否则 `WRONG_PAGE`，可经 events `result_payload` 入库）  
**范围：** Ops Web + Control Plane API + Agent 回执扩展，使运营可在云端：  
1）下发小红书笔记；2）查看评论与私信；3）回复指定评论与指定私信。  
**非目标：** 多机扩容、TLS、release 通道、自动托评链 UI（M2b 另页）、内容自动生成。  
**上位：** `2026-07-12-xhs-cloud-management-design.md`；端上 Playbook 引擎 / MediaBridge 已具备。

---

## 1. 现状与缺口

| 能力 | 现状 | 缺口 |
| --- | --- | --- |
| 云端发帖 | API：`POST /media`、`/content`、`/publish/immediate`、排期；真机已通 | Ops UI 仅列表，**无上传/立即发布表单** |
| 评论展示 | 端上 `read_comments` 可抽取；events **只回** `status/error_code` | **评论未入库、无 UI**；无法点选某条回复 |
| 私信展示 | `inbox_*` 表 + ingest/sync API；Ops 简表 | sync 成功**不自动写入**会话正文；需靠手动 ingest；无会话详情 |
| 回复评论 | 端上 `reply_comment`；需人在评论页 | 云端无「指定评论」模型与入口；缺 `target` 定位参数约定 |
| 回复私信 | `POST /inbox/reply` + 端上 `reply_dm`；页上真机已通 | UI 简陋；缺线程详情内一键回复；设备须在目标会话页（或后续增强导航） |

**核心阻塞：** Agent → CP 的 events **不携带** `result_payload`（抽取摘要）。没有这条通道，云端无法「显示所有评论和私信内容」。

---

## 2. 目标体验（运营视角）

```text
登录 Ops
  ├─ 发帖台：选账号 → 上传图 → 填标题正文 →「立即发布」→ 看任务状态
  ├─ 评论台：选账号/笔记 →「同步评论」→ 列表展示 → 点某条「回复」→ 填文案发送
  └─ 私信台：选账号 →「同步私信」→ 会话列表 → 进线程看消息 →「回复」→ 合规校验后下发
```

验收（单机即可，多机前硬门槛）：

1. 浏览器完成一次真实 `publish_note` 且 `effect_committed=true`。  
2. 浏览器可见 ≥1 条评论摘要与 ≥1 个私信会话/消息摘要（脱敏）。  
3. 浏览器对**指定**评论、**指定**私信线程各成功下发一次回复任务并回执 succeeded（或明确 `WRONG_PAGE`/`SET_TEXT_FAILED` 且可指引补导航）。

---

## 3. 方案对比与推荐

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **A. 扩展 events 带 result_payload（推荐）** | 端上抽取结果随 events 上报；CP 入库；Ops 读库 | 与现网 poll/events 一致；一次任务闭环 | 需改 Agent reporter + CP schema |
| B. 独立 sync API 由 Ops 拉端 | 云端主动问设备 | UI 简单 | 设备无入站 HTTP；违背现通道 |
| C. 仅 MQTT 新主题 | 另开 telemetry | 解耦 | 现网 MQTT 常不可达 |

**推荐 A。** 只读 Playbook（`read_comments` / `inbox_sync` / `inbox_open_thread`）成功时，events 增加可选 `result_payload`；CP `mark_from_event` 解析后写入 `note_comments` / `inbox_*`。

---

## 4. 信息架构与页面

保留现有服务端渲染（Jinja），按工作流补齐表单；不引入重 SPA。

| 导航 | 路径 | 能力 |
| --- | --- | --- |
| 总览 | `/ops/` | 在线设备、今日任务、告警摘要（可延后） |
| 发帖台 | `/ops/publish` | **新建**：账号选择、多图上传、标题/正文、敏感词提示、「立即发布」；任务状态条 |
| 素材库 | `/ops/content` | 已有列表；链到发帖台 |
| 评论台 | `/ops/comments` | **新建**：账号 + 可选 `note_ref`；「同步评论」；评论表；行内「回复」 |
| 私信台 | `/ops/inbox` | **增强**：同步按钮、线程列表、线程详情消息、行内回复、Lead 标记 |
| 任务中心 | `/ops/tasks` | 已有；展示关联 `playbook` / `error_code` |
| 设备账号 | `/ops/devices` `/ops/accounts` | 已有 |

---

## 5. 数据模型（增量）

### 5.1 已有（复用）

- `content_assets` / `media_objects` / `schedule_items`  
- `tasks`  
- `inbox_threads` / `inbox_messages` / `leads`

### 5.2 新增：笔记与评论

```text
published_notes
  note_id PK
  account_id FK
  publish_task_id UNIQUE          -- 对应 publish_note 任务
  content_id FK NULL
  title_summary                   -- 脱敏短摘要
  xhs_hint TEXT NULL              -- 端上可提供的定位 hint（文案/索引），非必须
  last_synced_at
  created_at, updated_at

note_comments
  comment_id PK
  note_id FK
  account_id FK                   -- 冗余便于按账号查
  author_summary
  body_summary
  locator_hint TEXT NULL          -- 端上抽取的定位（如 index/center），供 reply 使用
  source_task_id                  -- 来自哪次 read_comments
  created_at
  UNIQUE(note_id, author_summary, body_summary)  -- 弱去重
```

`reply_comment` 任务 params：

```json
{
  "text": "感谢关注…",
  "note_id": "…",
  "comment_id": "…",
  "input_selector": "text=有话要说，快来评论",
  "locator_hint": "…",
  "composer_tap_x": 393,
  "composer_tap_y": 494
}
```

端上优先用现有 composer 路径；`locator_hint` 供后续点「回复」按钮增强（本期可只传 `text` + 要求设备已在评论面，与现 Playbook 一致）。

### 5.3 私信

复用 `inbox_threads` / `inbox_messages`。ingest 由 **events 自动触发**，不再依赖人工 POST（保留 ingest API 作排障）。

`reply_dm` params：

```json
{
  "text": "…",
  "thread_id": "…",
  "open_title_hint": "静生百慧茶叶馆"
}
```

本期：若设备已在目标 `ChatActivity`，直接发送；`open_title_hint` 留给下一迭代自动点进会话（可选本期做 best-effort click）。

### 5.4 events 扩展

```json
{
  "task_id": "…",
  "status": "succeeded",
  "error_code": null,
  "result_payload": {
    "kind": "comments" | "inbox" | "thread" | "publish",
    "note_ref": "optional",
    "items": [ { "author_summary", "body_summary", "locator_hint?" } ],
    "threads": [ { "title_summary", "preview_summary", "unread", "messages?" } ]
  }
}
```

规则：

- 仅摘要 + 长度限制（单字段 ≤ 256，items ≤ 50）；禁止原文手机号/微信号明文（端上已有脱敏则再扫一层）。  
- `kind=publish` 时可带 `title_summary` 写入 `published_notes`。  
- 失败任务可不带 payload。

---

## 6. API 增量

鉴权：Ops Bearer / Session（浏览器）；设备 events：`X-Device-Token` 或 Ops（现网）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/devices/{id}/events` | **扩展** body 可选 `result_payload` |
| GET | `/api/v1/notes` | 按 account 列表已发笔记 |
| POST | `/api/v1/notes/{note_id}/sync-comments` | 创建 `read_comments@1.0`（params 可含 note hint） |
| GET | `/api/v1/notes/{note_id}/comments` | 评论列表 |
| POST | `/api/v1/comments/{comment_id}/reply` | 合规扫描 → `reply_comment@1.0` |
| POST | `/api/v1/inbox/sync` | 已有 |
| GET | `/api/v1/inbox/threads/{id}/messages` | **新增** 消息列表 |
| POST | `/api/v1/inbox/threads/{id}/reply` | 等同增强版 reply（强制 thread_id） |
| POST | `/api/v1/publish/immediate` | 已有；Web 表单调用 |

发帖台 Web 流程：`upload media` → `create content` → `immediate`（现 API，补 HTML form + multipart）。

---

## 7. Agent 改动（最小）

1. `PlaybookResult` 已有/扩展 `resultPayload: Map?`；只读 Playbook 填入脱敏 items。  
2. `TaskEventSink.report(..., resultPayload: JSONObject?)`。  
3. `TaskEventReporter` POST 写入 `result_payload`。  
4. `publish_note` 成功时可带 `kind=publish` + title 摘要。  
5. **不改** main 无网络边界：仍由 debug Bridge 上报。

页面闸门（与现一致，写进产品文案）：

- `reply_comment`：设备宜在目标笔记评论面；否则 `WRONG_PAGE` / `SET_TEXT_FAILED`。  
- `reply_dm`：宜在目标会话；云端文案提示「请保持该会话打开」或后续做自动导航。

首期产品策略：**同步类任务可后台跑；回复类任务要求手机停在正确页面**（运维 SOP），避免首期做复杂导航状态机。多机前可接受；UI 在失败时展示 `error_code` + 一键「重试」。

---

## 8. Ops UI 交互细节

### 8.1 发帖台 `/ops/publish`

1. 下拉 `PUBLISHER_*` 且 online+a11y 的账号。  
2. `<input type=file multiple accept=image/*>` → 逐个 `POST /media`。  
3. 标题、正文；提交前本地提示敏感词（服务端仍最终校验）。  
4. 「立即发布」→ 展示返回 `task_id`，轮询 `/api/v1/tasks?account_id=` 或页面刷新。  

### 8.2 评论台 `/ops/comments`

1. 选账号 → 列出 `published_notes`（无笔记时提示先发帖或手工登记）。  
2. 「同步评论」→ `sync-comments` → 成功后刷新 `note_comments`。  
3. 每行：作者摘要、正文摘要、「回复」展开 textarea + 提交。  

### 8.3 私信台 `/ops/inbox`

1. 「同步私信」→ `inbox/sync`；成功后由 events 自动 ingest。  
2. 点击线程 → 详情页消息列表。  
3. 底部回复框；提交走合规；拒绝明文微信号/手机号（已有）。  

---

## 9. 安全与合规

- 展示字段仅 summary；日志不打正文。  
- 回复 API 统一 `ComplianceGuard`（敏感词 + 联系方式）。  
- 副作用任务禁止自动用同 `task_id` 重派；UI 不提供「一键重做已提交」。  
- 仍为 debug 通道与明文 HTTP（已知债）；本设计不解决 TLS。

---

## 10. 实施分期

| 阶段 | 交付 | 验收 |
| --- | --- | --- |
| **W1 回执通道** | events `result_payload`；CP 入库 comments/inbox；单测 | 手动 dispatch `read_comments`/`inbox_sync` 后 DB 有行 |
| **W2 发帖台 UI** | `/ops/publish` 完整表单 | 浏览器发帖 succeeded |
| **W3 评论台** | sync + 列表 + 指定回复 | 浏览器同步可见；指定回复任务回执 |
| **W4 私信台增强** | 自动 ingest、详情、线程回复 | 浏览器可见消息并回复 succeeded |

依赖：单机 a11y Bound；`PUBLIC_BASE_URL` 手机可达；`MEDIA_ROOT` 可写。

---

## 11. 与多机测试的关系

本设计完成前**不开始多机**。完成后多机只需：每机独立 account↔device、重复发帖台选账号即可；评论/私信按 `account_id` 隔离。

---

## 12. 自我审查

- 无 TBD：回复时设备须在正确页作为首期 SOP 已写明。  
- 不引入云端任意脚本。  
- 复用现有 Playbook，不新造端上解释器。  
- 明确 events 扩展为评论/私信上云的唯一推荐路径。  

---

## 13. 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-07-13 | 初稿：发帖台 + 评论台 + 私信台；events result_payload；W1–W4 分期 |

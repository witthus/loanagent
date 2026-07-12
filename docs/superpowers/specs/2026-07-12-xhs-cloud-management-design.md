# 小红书矩阵 · 云端管理系统设计（M1-Cloud + M2-Ops）

**日期：** 2026-07-12  
**状态：** M1 进行中；实现计划见 `docs/superpowers/plans/2026-07-12-m1-cloud-control-plane.md`  
**运营依据：** `docs/superpowers/specs/structure.md`  
**上位技术依据：** `docs/superpowers/specs/2026-07-12-xhs-role-funnel-agent-design.md`  
**实现基座：** 扩展现有 FastAPI `control-plane` + Compose（Postgres / MQTT），不新建微服务拆分  

**范围：** 云端 Control Plane、Ops Web、按角色的调度与漏斗闭环（发帖 / 托评链 / 私信台 / 线索）。  
**不含：** 端上 Accessibility 实现细节（已由 M0-Cap 与角色漏斗稿覆盖）、文案/封面生成、Device Owner/MDM、LLM 对话下发。

---

## 0. 已确认产品决策

| 项 | 选择 |
| --- | --- |
| 设计深度 | M1-Cloud + M2-Ops 一次设计齐，可分阶段实现 |
| 架构 | **方案 1**：扩展现有 Control Plane 单体 |
| Web 登录 | 共用运营入口；权限位预留，首期不做控制台多角色 |
| 私信/线索 | 只同步；**回复与意向标记全人工** |
| 发帖触发 | **半自动**：Web「执行排期」或「立即发布」；成功后 **自动** 托评链 |
| 舰队 | 约 8 账号 / 8 机：`PUBLISHER_MAIN`×1 + `PUBLISHER_MATRIX`×2 + `ENGAGER`×5 |
| 前置 | M0-Cap 三项已在真机 CLEARED；可开始云端实现 |

---

## 1. 目标与成功标准

将 `structure.md` 的日节奏落成可操作的云端系统：运营上传脱敏素材 → 半自动发帖 → 自动诱导评论链 → 私信同步与人工合规回复 → 线索标记交接。

### 1.1 M1-Cloud 验收

1. 至少 1 台设备心跳 online，账号已绑定 `role`。  
2. MQTT 空任务 / 只读 `ensure_app_ready` 往返成功。  
3. Ops Web 可登录，可见设备/账号一览与任务列表。  

### 1.2 M2-Ops 验收

1. 对 `PUBLISHER_*` 完成「上传素材 → 立即发布或执行排期 → `publish_note` effect_committed」。  
2. 发帖成功后自动产生 ≥1 条 `ENGAGER` `post_comment` 与对应 `PUBLISHER` `reply_comment`（可配置条数与延迟）。  
3. `inbox_sync` 轮询可见会话；Web 人工下发 `reply_dm`（模板或名片图）成功。  
4. API **拒绝** 明文微信号/手机号作为常规回复 params。  
5. `ENGAGER` 账号创建 `publish_note` 被 API/编排器拒绝。  
6. 连续 3 个运营日无「已 effect_committed 的副作用任务被自动重派」。

---

## 2. 角色功能地图（对齐 structure.md）

### 2.1 小红书账号角色（系统强制）

| 角色代码 | 数量 | 人设用途 | 云端允许的副作用 | 禁止 |
| --- | --- | --- | --- | --- |
| `PUBLISHER_MAIN` | 1 | 专家号：深度干货/案例 | 发帖、回评论、回私信、置顶维护任务 | 不当 ENGAGER 灌诱导评 |
| `PUBLISHER_MATRIX` | 2 | 案例/视角号 | 发帖、回评论、回私信 | 同左 |
| `ENGAGER` | 5 | 素人白号 | **仅**对指定笔记 `post_comment`；可选私信（默认关） | **任何** `publish_note*`；不可作排期发帖目标 |

扩容优先加 `ENGAGER`；发帖号保持少量高质量。

### 2.2 日节奏映射

| 时段（structure） | 人工 | 云端 |
| --- | --- | --- |
| 09:00–10:30 | 上传脱敏图文/封面 | Content 入库、敏感词校验、绑定 `PUBLISHER_*`、写入排期 |
| 11:30–12:30 / 21:00–22:30 | 点「执行排期」或「立即发布」 | `publish_note` 错峰下发 |
| 发帖成功 ≈10 min | — | Engagement：`post_comment`(ENGAGER) → `reply_comment`(PUBLISHER) |
| 全天 | 私信台回复、打意向 | `inbox_sync`；仅人工触发 `reply_dm` |
| 17:30–22:30 | 线索跟进、剩余排期再执行 | 矩阵剩余发帖 + Lead 看板 |

### 2.3 合规强制（产品规则）

- 私信/评论 params **禁止**下发未处理的微信号、电话号码纯文本。  
- 允许：已审核**名片图片**素材 ID、谐音/「留 V」**话术模板** ID。  
- 正文敏感词表默认参考 structure（如「债务重组」「停息挂账」「网贷催收」等）；命中则拒绝创建任务并提示改写。  
- 系统不生成文案；只执行已入库素材。

---

## 3. 系统边界

```text
┌──────────────────────────────────────────────────────────┐
│ Ops Web（共用登录）                                        │
│ 设备账号 · 素材排期 · 任务 · 私信台 · 线索 · 告警           │
└────────────────────────────┬─────────────────────────────┘
                             │ HTTPS API
┌────────────────────────────▼─────────────────────────────┐
│ Control Plane（FastAPI 单体扩展）                          │
│ Device · Account · Content · Schedule · Engagement       │
│ Task · Inbox/Lead · Audit/Alert · Object Storage 签名    │
└───────────────┬───────────────────────────┬──────────────┘
                │ MQTT commands/events        │ 签名 URL
┌───────────────▼───────────────┐   ┌─────────▼────────────┐
│ On-device Agent（每机）         │   │ Media 对象存储        │
└───────────────┬───────────────┘   └──────────────────────┘
                │ Accessibility
┌───────────────▼───────────────┐
│ 小红书 App + 独立 SIM           │
└───────────────────────────────┘
```

**首期不做：** DO/MDM、LLM 网关、私信自动回复、自动意向打标、内容生成、实时投屏、一号多机、明文微信号群发。

**交付 SOP（每机人工一次）：** 侧载 Agent → 开无障碍 → 保活 checklist → 登录唯一小红书号 → 云端绑定 device↔account↔role → 验收心跳。

---

## 4. Control Plane 模块

| 模块 | 职责 |
| --- | --- |
| Device Registry | `device_id`、Agent 版本、心跳、蜂窝/Wi‑Fi、`a11y_bound`、online/offline |
| Account Registry | `account_id`、`role`、日配额、状态、`device_id` 1:1 |
| Content Library | 标题/正文/图片、定位标签、敏感词结果、名片图与话术模板 |
| Schedule Board | 排期条目（账号+素材+时间窗+状态）；**不自动到点开火** |
| Engagement Orchestrator | 发帖成功后延迟托评链；失败停链告警 |
| Task Service | 创建、角色门禁、MQTT 下发、回执状态机、禁止副作用自动重派 |
| Inbox & Lead Desk | 会话/消息摘要缓存、人工回复下发、线索状态 |
| Audit / Alert | 运营操作审计、停机类告警 |
| Auth（薄） | 共用运营账号（session/token）；`permission` 字段预留扩展 |

模块以 Python 包边界拆分，同进程部署；Postgres 为系统真源，MQTT 为设备通道。

---

## 5. 数据模型（逻辑）

### 5.1 实体与关系

```text
Device 1——1 Account(role, quotas, status)
Account 1——* ScheduleItem ——1 ContentAsset 1——* MediaObject
ScheduleItem | ManualPublish  →  Task(publish_note)
Task(publish).effect_committed → EngagementChain
  EngagementChain → Task(post_comment)* → Task(reply_comment)
Account 1——* InboxThread 1——* InboxMessage
InboxThread ——0..1 Lead
OpsReplyAction → Task(reply_dm)
OpsUser（共用）→ AuditLog
```

### 5.2 关键字段约定

**Account**

| 字段 | 说明 |
| --- | --- |
| `role` | `PUBLISHER_MAIN` \| `PUBLISHER_MATRIX` \| `ENGAGER` |
| `device_id` | 非空唯一；解绑需显式操作 |
| `network_policy` | 默认 `cellular_only` |
| `daily_publish_quota` | 仅 PUBLISHER；ENGAGER 为 0 |
| `inbox_sync_enabled` | PUBLISHER 默认 true；ENGAGER 默认 false |
| `status` | `active` \| `paused` \| `blocked` \| `needs_login` |

**ContentAsset**

| 字段 | 说明 |
| --- | --- |
| `title` / `body` | 运营上传 |
| `media_ids[]` | 封面与配图 |
| `geo_tags` | 可选同城标签文案（执行侧 params） |
| `sensitivity_status` | `passed` \| `rejected` \| `needs_review` |
| `allowed_roles` | 通常仅 `PUBLISHER_*` |

**ScheduleItem**

| 字段 | 说明 |
| --- | --- |
| `account_id` | 必须为 PUBLISHER_* |
| `content_id` | |
| `window_start` / `window_end` | 建议发布时间窗（供运营选择，非自动触发器） |
| `status` | `draft` \| `ready` \| `dispatched` \| `done` \| `failed` \| `cancelled` |

**EngagementChain**

| 字段 | 说明 |
| --- | --- |
| `publish_task_id` / `note_ref` | 发帖回执 |
| `publisher_account_id` | |
| `delay_sec` | 默认 600 |
| `engager_plan[]` | `{account_id, template_id}`，话术去重 |
| `reply_template_id` | 主号/矩阵回复话术 |
| `status` | `pending` \| `running` \| `completed` \| `stopped` |

**InboxThread / Lead**

| 字段 | 说明 |
| --- | --- |
| Thread：`account_id`, `peer_key`, `unread`, `last_preview`, `synced_at` | 脱敏摘要 |
| Lead：`intent` | `unknown` \| `high_intent` \| `not_fit` |
| Lead：`handoff` | `none` \| `waiting_wechat` \| `handed_off` |
| 仅人工更新 intent/handoff | |

**Task**  
继承 `schemas/task.schema.json`。创建时必须解析到唯一 `device_id`；`params` 可含 `role_context`, `note_ref`, `template_id`, `media_asset_ids`, `schedule_item_id`, `chain_id`。

---

## 6. MQTT / Task 流

### 6.1 通道

沿用 `schemas/mqtt-contract.json`：

- `devices/{deviceId}/commands` / `devices/{deviceId}/events`
- QoS 1；去重键 `task_id`
- 非幂等副作用：`effect_committed` 或不确定后 **禁止自动重派**

### 6.2 半自动发帖 + 托评链

```text
运营：素材 sensitivity=passed → ScheduleItem(ready) 或「立即发布」
  → POST /schedules/{id}/dispatch 或 POST /publish/immediate
  → TaskService：
       assert account.role in PUBLISHER_*
       assert device online && a11y healthy && network_policy ok
       assert quota remaining
  → MQTT publish_note
  → events: effect_committed(+ note_ref, input_route)
  → Orchestrator 创建 EngagementChain(pending)
  → 到 delay 后为每个 engager 建 post_comment（错峰数秒）
  → 各 post_comment 成功 → 建 reply_comment(publisher)
  → BUSINESS_BLOCKED / EFFECT_UNKNOWN / A11Y_* → chain=stopped + Alert
```

### 6.3 私信（人工）

```text
Cron/worker：对 inbox_sync_enabled 账号派发 inbox_sync（只读）
  → 可选 inbox_open_thread（未读优先）
  → upsert Thread/Message 摘要
运营 Web：打开会话 → 选 template_id 或 card_media_id → POST reply
  → 合规扫描 params → Task reply_dm
  → 禁止自动回复与自动打标
```

### 6.4 Playbook 与角色门禁

| Playbook | 角色 |
| --- | --- |
| `publish_note` | 仅 `PUBLISHER_*` |
| `post_comment` | 主：`ENGAGER`；编排链内使用 |
| `reply_comment` | `PUBLISHER_*` |
| `inbox_sync` / `inbox_open_thread` | 默认 `PUBLISHER_*` |
| `reply_dm` | 有私信职责的账号（默认 PUBLISHER_*） |
| `ensure_app_ready` / `dismiss_interruptions` | 全部 |
| `read_comments` | 观测/验收；不作为日节奏主路径 |

门禁在 **API 与 TaskService 双处**执行，防止误配。

---

## 7. Ops Web 页面

共用运营登录。导航按日工作流排序：

| # | 页面 | 能力 |
| --- | --- | --- |
| 1 | 总览 | 在线设备数、今日已发/配额、告警条数、待处理线索 |
| 2 | 账号与设备 | 角色、绑定、心跳、蜂窝/Wi‑Fi、暂停/恢复、配额 |
| 3 | 素材库 | 上传图文、敏感词结果、名片图与话术模板 CRUD |
| 4 | 排期板 | 按账号/时间窗列出 ScheduleItem；「执行选中排期」「立即发布」 |
| 5 | 任务中心 | Task 列表、状态、失败码、链 ID、禁止一键重派已提交副作用 |
| 6 | 互动链 | 链状态、子任务、手动停止链 |
| 7 | 私信台 | 按账号会话列表 → 详情 → 选模板/名片图回复 |
| 8 | 线索看板 | intent/handoff 筛选与标记 |
| 9 | 告警 | 无障碍掉绑、Wi‑Fi 违规、登录失效、风控停机 |

首期可用服务端渲染或轻 SPA；不追求设计系统完整度。

---

## 8. API 清单（最小）

前缀建议 `/api/v1`。鉴权：运营 Bearer/Session。设备侧保持 MQTT，不经由下列浏览器 API 下发操控。

### 8.1 注册与健康

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 已有 |
| GET/PATCH | `/devices`, `/devices/{id}` | 列表/更新备注与期望 Agent 版本 |
| GET/POST/PATCH | `/accounts`, `/accounts/{id}` | 含 role、绑定 device、配额、inbox 开关 |
| POST | `/accounts/{id}/pause` \| `/resume` | 移出/恢复调度 |

### 8.2 素材与排期

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/contents` | 创建；同步敏感词扫描 |
| POST | `/contents/{id}/media` | 上传或拿预签名 PUT |
| GET | `/contents` | 筛选 sensitivity/role |
| POST | `/templates` | 话术或名片图模板 |
| POST | `/schedules` | 创建排期 |
| POST | `/schedules/dispatch` | body: `schedule_ids[]`；半自动开火 |
| POST | `/publish/immediate` | `account_id` + `content_id` |

### 8.3 任务与编排

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/tasks` | 过滤 account/device/playbook/status/chain |
| GET | `/tasks/{id}` | 详情与事件 |
| GET | `/engagement-chains` | 链列表 |
| POST | `/engagement-chains/{id}/stop` | 人工停链 |
| POST | `/tasks/{id}/reconcile` | **仅**人工确认后的处置（关闭或标记已核），不暗含自动重做副作用 |

### 8.4 私信与线索

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/inbox/threads` | 按 account、未读 |
| GET | `/inbox/threads/{id}` | 消息摘要 |
| POST | `/inbox/threads/{id}/reply` | `template_id` 或 `card_media_id`；合规校验 |
| GET/PATCH | `/leads` | intent/handoff |

### 8.5 合规校验（内部共用）

`ComplianceGuard.scan_text(text) -> allow | reject(reasons)`  
用于 Content 入库、`reply` API、模板保存。拒绝原因返回给 Web，不静默吞掉。

---

## 9. 错误处理、告警与调度门闸

| 信号 | 系统行为 | 人工 |
| --- | --- | --- |
| 心跳超时 | device=offline；跳过新副作用任务 | 查电量/SIM/进程 |
| `a11y` 未 Bound | 暂停该机副作用；Alert | 关开无障碍 / 重装 |
| `wifi_connected` 且 policy=cellular_only | Alert；暂停副作用（排障白名单除外） | 改回蜂窝 |
| `LOGIN_REQUIRED` / 业务风控页 | account=needs_login 或 paused；停链 | 登录/降温 |
| `EFFECT_UNKNOWN` / `reconcile_required` | 不自动重派 | 核对笔记/评论是否已存在 |
| 敏感词/明文微信号 | API 4xx | 改文案或改用名片图 |
| ENGAGER 发帖 | API 403 | 改绑定角色 |

Kill Switch：全局或按账号 `paused`，立即停止新派发（进行中任务靠端上取消策略，首期允许跑完当前步后停）。

---

## 10. 安全

- 传输：HTTPS + MQTT TLS。  
- 设备身份：每机密钥；commands 签名/校验按既有合同演进。  
- 数据：私信/评论摘要脱敏后短期保留（建议 ≤30 天可配）；原图诊断默认不上云。  
- 运营账号：强密码/Token；首期单租户；权限位预留 `ops.admin` / `ops.desk` / `ops.readonly`。  
- 禁止：云端下发任意代码或原始坐标脚本作为常规手段。  
- 合规声明：企业自有设备私有分发；**不保证**符合小红书用户协议；无对抗平台风控设计。

---

## 11. 实现分期

| 阶段 | 交付 | 退出条件 |
| --- | --- | --- |
| **M1-Cloud** | Postgres 表：Device/Account；心跳接入；MQTT 任务往返；Web 登录+设备账号页+任务只读；Auth 薄层 | 1 机 online + 空/只读任务成功 |
| **M2a 发帖** | Content+Media+敏感词；Schedule；dispatch/immediate；`publish_note` 闭环 | 真机发帖 effect_committed ≥1（业务号） |
| **M2b 托评链** | EngagementChain；ENGAGER/PUBLISHER 评论任务；链停与告警 | 配置条数诱导评+回复自动跑通 |
| **M2c 私信台** | inbox_sync 消费；Thread UI；人工 reply_dm；Lead 标记；明文拒绝单测 | 同步可见 + 人工回复成功 + 拒绝用例 PASS |

端上 Agent 需已具备对应 Playbook；云端只编排与鉴权。若某 Playbook 端上未就绪，该分期阻塞并回落到端上补齐，不在云端假装成功。

---

## 12. 测试策略

| 层 | 内容 |
| --- | --- |
| 单元 | 角色门禁、敏感词/微信号拒绝、链状态机、配额、Schedule dispatch 前置条件 |
| API | 契约测试：ENGAGER 发帖 403；reply 含微信号 400；dispatch 生成 Task |
| 集成 | Compose 内 MQTT mock 或真实 broker；Task 状态迁移 |
| 真机 | M2 退出条件各至少 1 次端到端；证据写入 `ops/m0/` 或后续 `ops/m2/` 矩阵 |

---

## 13. 与既有文档关系

| 文档 | 关系 |
| --- | --- |
| `structure.md` | 运营角色与日节奏真源；本设计将其产品化为系统功能 |
| `2026-07-12-xhs-role-funnel-agent-design.md` | 上位架构与 Playbook 清单；**本文件是其第 7 节的可实施细化** |
| `2026-07-10-xhs-ondevice-agent-design.md` | 端上细节参考；DO/MDM 门禁已被角色漏斗稿与本稿排除 |
| `schemas/task.schema.json` / `mqtt-contract.json` | 任务与通道合同，实现时扩展不得破坏既有必填 |

冲突时：角色漏斗稿的门禁/顺序优先；**云端模块字段与 API 以本文为准**。

---

## 14. 非目标（重申）

- 控制台多角色权限落地（仅预留）  
- 排期到点全自动开火（无人工「执行排期」）  
- 私信自动回复 / 自动意向分类  
- 内容与封面生成、选题引擎  
- Device Owner / MDM、对话 Agent、实时投屏  

---

## 15. 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-07-12 | 初稿：M1+M2 云端管理系统；方案 1 扩展 CP；半自动发帖+自动托评；私信人工；Web 共用登录 |

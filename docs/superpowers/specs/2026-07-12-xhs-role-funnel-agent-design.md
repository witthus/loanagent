# 小红书矩阵 · 角色漏斗 Agent 技术方案（首期修订）

**日期：** 2026-07-12  
**状态：** 设计定稿（待实现；云端建设以 M0-Cap 为硬前置）  
**运营依据：** `docs/superpowers/specs/structure.md`  
**与旧稿关系：** 本文件修订并优先于 `2026-07-10-xhs-ondevice-agent-design.md` 中与首期门禁、Device Owner/MDM、里程碑冲突的条款。端上无障碍架构、Task 状态机、失败码与安全原则仍继承旧稿，除非本节明确改写。云端模块的可实施细化见 [`2026-07-12-xhs-cloud-management-design.md`](./2026-07-12-xhs-cloud-management-design.md)。

**范围：** 设备控制、角色调度、发帖/评论互动/私信与线索交接的技术路径。  
**不含：** 文案/封面创意生成、法务结论、验证码破解、Root/改机。

---

## 1. 背景与目标

将小红书打造成可持续运转的「同城专业案例 → 评论区钩子 → 私信对接 → 私域微信」漏斗。客群强调隐私与专业度；系统负责**按角色自动执行**运营规定动作，不负责生产营销文案。

### 1.1 已确认产品决策

| 项 | 选择 |
| --- | --- |
| 首期舰队 | 约 **8 个账号 / 8 台物理机**：3 个发帖号 + 5 个互动号 |
| 账号模型 | **一机一卡一号**；禁止多号共用同一 Wi‑Fi |
| 内容来源 | 运营预先上传标题/正文/图片到云端；到点由 Agent 执行 |
| 私信 | 定时同步会话列表与未读；云端可点进会话查看最新消息并回复 |
| 升级与纳管 | **Device Owner / MDM 首期不做**；人工侧载安装最新 APK |
| 云端 UI | API + 简易 Web 控制台 |
| 建设顺序 | **先端上能力门禁，再部署云端** |

### 1.2 成功标准（首期技术）

1. **M0-Cap（云端前硬门禁）：** 在已登录小红书的测试真机上，Agent 能完成发帖、读取评论区内容、读取私信内容，并留下矩阵证据。
2. **M1-Cloud：** 云端 Control Plane + MQTT + 简易 Web 上线；单机心跳与空任务跑通。
3. **M2-Ops：** 角色感知调度可支撑「3 发帖 + 5 互动」日节奏：定时发帖、发帖后诱导评论链、私信同步与合规回复、线索标记。
4. 遇验证码、登录失效、风控提示时自动停机告警，不循环硬点；副作用不确定时进入 `reconcile_required`。

---

## 2. 门禁与实施顺序

### 2.1 废除的硬门禁

- ~~两机不同型号全部矩阵通过才允许建云端~~
- ~~Android Enterprise / Device Owner 为生产基线才允许建云端~~

上述能力改为 **M3+ 可选增强**（多地无人值守升级、策略合规），不阻塞首期业务。

### 2.2 M0-Cap（当前唯一硬前置）

在 Redmi Note 12 Turbo（或当前测试机）+ 真实小红书稳定版、**已登录业务账号**上验证：

| 能力 | Playbook / 动作 | 最小验收 |
| --- | --- | --- |
| 发帖 | `publish_note` | 图文：选图 → 输入标题/正文 → 发布成功 1 次（测试机允许真实发布）；输入路径记录所用路由（`ACTION_SET_TEXT` / IME / 剪贴板） |
| 评论区读取 | `read_comments` | 进入笔记详情 → 评论区 → 结构化抽出 ≥1 条评论摘要（脱敏） |
| 私信读取 | `read_inbox` | 消息 Tab → 会话列表（含未读摘要）→ 点进会话 → 最近 N 条消息摘要；修正消息 Tab 误判为 `NOTE_DETAIL` |

证据写入 `ops/m0/xhs-flow-matrix.md`。三项未全 PASS 前，**不开始云端功能开发与生产部署**（基础设施探活除外）。

### 2.3 顺序总览

```text
M0-Cap（端上三项）
  → M1-Cloud（CP + MQTT + Web + 心跳）
  → M2-Ops（角色调度：发帖 / 互动链 / 私信台 / 线索）
  → M3+（多地扩容、可选 MDM、对话 Agent）
```

---

## 3. 账号角色与运营映射（`structure.md`）

### 3.1 角色定义

| 角色代码 | 数量（一期） | 人设用途 | 允许的副作用操作 |
| --- | --- | --- | --- |
| `PUBLISHER_MAIN` | 1 | 专家号：深度干货/案例 | 发帖、回评论、回私信、置顶维护 |
| `PUBLISHER_MATRIX` | 2 | 案例/视角号 | 发帖、回评论、回私信 |
| `ENGAGER` | 5 | 素人白号 | **不发帖**；对指定笔记发诱导评论；按策略可回私信 |

合计约 8 号；扩容时优先加 `ENGAGER`，发帖号保持少量高质量。

### 3.2 网络与设备铁律

- 每台设备独立物理 SIM，日常蜂窝上网。
- 健康检查上报 `wifi_connected`；策略为 `cellular_only` 的账号在检测到业务 Wi‑Fi 时告警并暂停副作用任务（排障白名单除外）。
- 注册与实名由运营线下完成；系统只记录账号状态，不代替平台认证。

### 3.3 每日节奏 → 系统任务

| 时段（参考 `structure.md`） | 人工 | 系统 |
| --- | --- | --- |
| 09:00–10:30 | 上传当日图文素材（脱敏文案、封面） | 素材入库、敏感词校验、绑定目标账号 |
| 11:30–12:30 | — | `PUBLISHER_*` 执行 `publish_note`（错峰） |
| 发帖成功后 ≈10 分钟 | — | `ENGAGER` 执行 `post_comment`（诱导剧本）；对应 `PUBLISHER` 执行 `reply_comment` |
| 13:00–14:00 / 全天 | 审核高意向 | `inbox_sync`；Web 会话台 |
| 17:30–22:30 | 线索跟进 | 剩余矩阵发帖；`reply_dm`（名片图或「留 V」，禁止明文微信号文本） |
| 21:00–22:30 | — | 主号晚间档可选第二窗口发帖 |

### 3.4 合规操作约束（产品规则，系统强制）

- 私信/评论 **禁止** 下发未加密的微信号、电话号码纯文本作为常规 params。
- 允许：下发已审核的**名片图片素材**、谐音/引导「留 V」话术模板 ID。
- 正文敏感词：可配置词表拦截（默认参考 `structure.md`：如「债务重组」「停息挂账」等）；命中则拒绝创建任务并提示运营改写。
- 内容生成不在系统内；系统只执行已入库素材。

---

## 4. 系统架构（首期）

```text
┌─────────────────────────────────────────────────────────┐
│ Cloud Control Plane + 简易 Web                            │
│ · Device / Account Registry（含 role）                    │
│ · Content Library + 敏感词校验                            │
│ · Scheduler + Engagement Orchestrator                   │
│ · Task Service + Inbox/Lead Desk                        │
│ · Audit / Alert                                         │
└────────────────────────────┬────────────────────────────┘
                             │ MQTT（任务/回执/心跳）
┌────────────────────────────▼────────────────────────────┐
│ On-device Agent（每机一 App）                              │
│ · Cloud Link · Task Runner（单机串行）                    │
│ · Observer · Action Executor · Playbook Engine          │
│ · Media Bridge · Input Bridge · Health Reporter         │
└────────────────────────────┬────────────────────────────┘
                             │ Accessibility
┌────────────────────────────▼────────────────────────────┐
│ 小红书 App + 独立 SIM                                     │
└─────────────────────────────────────────────────────────┘
```

**明确不做（首期）：** Device Owner / MDM / DPC 纳管链路；USB Device Farm 生产依赖；一号多机；云端下发任意代码。

**交付 SOP（每机一次人工）：** 侧载安装 Agent → 用户开启无障碍（及可选 IME）→ 厂商保活 checklist → 登录唯一小红书号 → 云端绑定 `device_id`↔`account_id`↔`role` → 验收心跳与只读空任务。

---

## 5. 端上 Agent（继承并收窄）

### 5.1 模块

与旧稿一致：Foreground Service、AccessibilityService、Observer、Action Executor、Playbook Engine、Device State Guard、Media Bridge、Input Bridge、Local Store（Task 去重 + `effect_committed`）。

差异：

- 健康信号中的 `managed` **不再作为执行前置**；改为可选字段。前置改为：`a11y_enabled` + `screen_ready` + `keyguard` 策略 + 目标包 lease。
- 无障碍掉绑（HyperOS Enabled 但 Bound 空）→ `A11Y_DISABLED`，暂停副作用任务，推送告警；依赖人工关开服务，不假设 `settings put` 静默重绑。

### 5.2 M0-Cap 阶段通道

云端未上线前，使用 debug 广播 / 本地诊断 JSON 验收三项能力；验收通过后再接通 MQTT。

### 5.3 输入

优先 `ACTION_SET_TEXT`；失败则已验证的 IME 或剪贴板。逐字符手势不作常规路径。发帖验收必须记录实际路由。

---

## 6. Task 与 Playbook

### 6.1 Task

继承现有 `schemas/task.schema.json` 字段与状态机（`queued` → … → `effect_committed` / `reconcile_required`）。  
扩展约定（实现时进 schema 或 account 侧关联，不破坏既有必填）：

- 解析目标时必须落到唯一 `device_id`（由 `account_id` 1:1 映射）。
- `params` 可含 `role_context`、`note_ref`、`template_id`、`media_asset_ids`。
- `ENGAGER` 账号拒绝 `playbook=publish_note*`。

### 6.2 首期 Playbook 清单

| Playbook | effect_class | 主要角色 | 说明 |
| --- | --- | --- | --- |
| `ensure_app_ready` | idempotent | 全部 | 前台就绪、清打断 |
| `publish_note` | non_idempotent | PUBLISHER_* | 图文发布 |
| `read_comments` | readonly | 任意（验收/观测） | 抽取评论列表摘要 |
| `post_comment` | non_idempotent | ENGAGER（主）、也可 PUBLISHER | 对目标笔记发评 |
| `reply_comment` | non_idempotent | PUBLISHER_* | 回复指定评论 |
| `inbox_sync` | readonly | PUBLISHER_*（主）、ENGAGER 可选 | 会话列表 + 未读 |
| `inbox_open_thread` | readonly | 有私信职责的账号 | 会话最近 N 条 |
| `reply_dm` | non_idempotent | 有私信职责的账号 | 模板/名片图回复 |
| `dismiss_interruptions` | idempotent | 全部 | 共享步骤 |

控制流内置 Agent（版本化）；云端只传 params 与签名 selector 配置包。

### 6.3 互动链（Engagement Orchestrator）

发帖 Task `effect_committed` 后，编排器创建子操作（同一 `operation_id` 前缀）：

1. 延迟 T（默认 10 分钟，可配置）后，为 1–N 个 `ENGAGER` 创建 `post_comment`（不同话术模板，防同文）。
2. ENGAGER 成功后，为对应 `PUBLISHER` 创建 `reply_comment`（引导私信/看置顶的合规话术）。
3. 任一步 `BUSINESS_BLOCKED` / `EFFECT_UNKNOWN` → 停止该链自动续跑，告警人工。

---

## 7. Cloud Control Plane（过 M0-Cap 后）

### 7.1 模块

| 模块 | 职责 |
| --- | --- |
| Device Registry | 设备、Agent 版本、心跳、蜂窝/Wi‑Fi 状态、保活健康 |
| Account Registry | 账号、`role`、配额、状态、`device_id` 1:1 |
| Content Library | 素材、封面、正文、定位标签、敏感词结果、可用账号 |
| Scheduler | 角色日历、错峰、日配额 |
| Engagement Orchestrator | 发帖后评论链 |
| Task Service | 队列与状态机 |
| Inbox & Lead Desk | 会话列表/详情、回复下发、意向标记、`lead_handoff` |
| Audit / Alert | 审计与告警 |
| 简易 Web | 素材上传与排期、任务态、会话台、线索看板 |

### 7.2 简易 Web 最小页面

1. 账号/设备一览（角色、在线、配额剩余）  
2. 素材库上传与敏感词提示  
3. 排期日历（按角色）  
4. 任务列表与失败码  
5. 私信：会话列表 → 详情 → 回复（选模板/名片图）  
6. 线索：意向标记与交接状态  

### 7.3 对话 Agent

首期 **不做**。调度与 Web 人工操作为任务来源；LLM 网关留在 M3+。

### 7.4 素材下发

短时效签名 URL → Agent 蜂窝下载 → hash 校验 → MediaStore 独立 album → Playbook 选择。诊断图默认不上云；失败时可脱敏压缩上传。

---

## 8. 可靠性与安全（摘要）

- MQTT QoS 1 + `task_id` 去重；副作用一次生效后禁止自动重派。
- 心跳超时标记 offline；无障碍关闭 / 登录失效 / 风控 → 移出调度。
- TLS；设备密钥；UI/私信摘要脱敏短期保留。
- Kill Switch；禁止云端任意代码。
- 合规：企业自有设备 + 私有分发；不保证符合小红书用户协议；无对抗风控设计。

---

## 9. 里程碑与验收

| 阶段 | 内容 | 进入下一阶段条件 |
| --- | --- | --- |
| **M0-Cap** | 端上发帖 / 读评论 / 读私信真机验收 | 矩阵三项 PASS |
| **M1-Cloud** | 部署 CP、MQTT、Web、心跳、账号角色模型 | 1 机在线 + 空任务 |
| **M2-Ops** | 素材排期、`publish_note`、互动链、`inbox_*`、`reply_dm`、线索台 | 连续 3 天按角色日节奏无重复副作用 |
| **M3+** | 扩至满编 8 机、多地、可选 MDM、对话 Agent | 书面扩大范围 |

**M0-Cap 量化：** 发帖真实成功 ≥1；`read_comments` / `read_inbox` 各至少 1 次结构化成功回执；关键失败可归入既有 `error_code`。

**M2 量化：** 日发布按配额执行；每篇发帖链至少触发配置条数的诱导评任务；私信同步延迟可配置（建议 ≤15 分钟一轮）；明文微信号下发被 API 拒绝。

---

## 10. 非目标（首期）

- 内容/封面自动生成与选题策略引擎  
- Device Owner / MDM 强制纳管  
- 两机 Go 作为云端前置  
- iOS、Root、改机、验证码破解  
- 实时投屏中控  
- 一号多机热迁移  
- 在私信中自动群发微信号纯文本  

---

## 11. 风险与人工边界

| 风险 | 系统 | 人工 |
| --- | --- | --- |
| 无障碍掉绑 | 告警并暂停任务 | 关开无障碍 / 重装 APK |
| 同 Wi‑Fi 连坐 | 检测告警、暂停 | 改回蜂窝、隔离设备 |
| UI 改版 | selector 配置灰度 | 暂停受影响 Playbook 并修规则 |
| 发帖/评论效果不明 | `reconcile_required` | 核对是否已发，禁止盲重试 |
| 敏感词/导流封号 | 词表与模板强制 | 改文案、换名片图、降频 |
| SIM/欠费 | 离线告警 | 补费换卡 |

---

## 12. 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-07-12 | 首期修订：DO/MDM 后置；M0-Cap 端上三项硬前置；对齐 `structure.md` 的 8 号角色漏斗；云端按角色调度与私信台设计；建设顺序云端后置 |

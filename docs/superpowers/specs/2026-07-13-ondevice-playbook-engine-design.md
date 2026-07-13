# 端上 Playbook 引擎设计（双轨过渡）

**日期：** 2026-07-13  
**状态：** 已批准；E0–E3 已实现（单元测试通过；真机云下发：`inbox_sync`/`publish_note` succeeded，只读/回复在非目标页返回明确 `error_code`）  
**范围：** Android Agent 端上 Task Runner / Playbook 引擎；不改 Control Plane 编排语义  
**上位：** `2026-07-12-xhs-role-funnel-agent-design.md` §5–6；`2026-07-12-agent-cloud-bridge-design.md`  
**决策：** 边界方案 **C**（引擎在 `main`，云通道仍仅 debug）

---

## 1. 背景与目标

M0-Cap 三项（发帖 / 读评论 / 读私信）已在真机用 **ADB debug 广播 + shell** 验收通过，但尚未收成可被云端 Task 调度的 Playbook。Cloud bridge 目前只支持 `ensure_app_ready`。

**目标：** 在 Agent 内建立统一的 Playbook 引擎，使云端下发的 Task 信封（HTTP poll / MQTT）能：

1. 按 `playbook` 分发到版本化实现  
2. 单机串行执行、按 `task_id` 去重  
3. 正确处理 `effect_class` 与本地 `effect_committed`  
4. 产出结构化结果，经现有 events 通道回执  

**非目标（本设计）：** 素材库 / 排期 / 互动链编排（属 M2-Ops CP）；release 联网；TLS / 设备证书；云端下发任意脚本。

---

## 2. 边界（方案 C）

```text
android/agent/src/main/
  PlaybookEngine + Task models + Playbook 实现
  （无 INTERNET；不依赖 CloudBridgeConfig）

android/agent/src/debug/
  CloudBridgeCoordinator / HTTP poll / MQTT
  → 解析信封后调用 PlaybookEngine.run(command)
  （保留写死 token / 明文 HTTP，仅 debug）
```

| 层 | 职责 | 联网 |
| --- | --- | --- |
| `main` Engine | 解析 Task 命令、去重、串行、执行 Playbook、本地副作用记账 | 否 |
| `debug` Bridge | 心跳、拉命令、回执 events | 是（现网） |
| `release` | 不含 Bridge；Engine 可被本地诊断/未来通道调用 | 否（维持现状） |

---

## 3. 引擎实现方案对比

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **1. 原语编排（推荐）** | Playbook 调用与 M0 相同的 observe/click/setText/wait/extract 原语（经 `PlaybookRuntime` 门面） | 复用已验真机路径；单测可 Fake runtime | Playbook 代码仍是命令式步骤 |
| **2. 显式状态机** | 每 Playbook 独立状态枚举 + 转移表 | 形式化、易画图 | 过重；M0 脚本已是线性步骤，迁移成本高 |
| **3. 云端下发步骤表** | params 含 step[]，端上通用解释器 | 改流程不用发版 | 违反「禁止云端任意代码/脚本」；选择器漂移难控 |

**推荐方案 1。** 与现有 `M0AccessibilityService` / debug 广播能力对齐；把 shell 脚本中的稳定步骤搬进 Kotlin Playbook，不引入新解释器。

---

## 4. 核心模型

### 4.1 入站命令（与 CP 信封对齐）

引擎只依赖字段子集（未知字段忽略）：

```text
task_id, operation_id?, account_id?, playbook, params, effect_class?, timeout_sec?
```

`playbook` 形如 `name@major.minor`（与 `task.schema.json` 一致）。

### 4.2 出站结果

```text
PlaybookResult {
  task_id
  success: Boolean
  status: "succeeded" | "failed"   // 回执给现网 CP events hook
  error_code: String?              // 短码
  effect_committed: Boolean        // 副作用是否已发生
  result_payload: Map?             // 只读抽取摘要等（不上云明文敏感字段；可本地诊断）
}
```

说明：现网 CP `POST .../events` 仅消费 `task_id/status/error_code`。`effect_committed` 以端上本地账本为准；CP 侧对 readonly 成功时自行置 `effect_committed=true`。后续正式 MQTT events 再扩展字段，本设计不阻塞。

### 4.3 effect_class 规则

| effect_class | 去重后再次收到同 `task_id` | 失败重试（新 task_id） |
| --- | --- | --- |
| `readonly` | 忽略（不执行） | 允许 |
| `idempotent` | 忽略 | 允许 |
| `non_idempotent` | 若本地已 `effect_committed` → 忽略并可选回报 succeeded/已提交；未提交则可执行一次 | **禁止自动用同语义重派**（云端责任）；端上若未提交则新 task_id 可跑 |

端上本地持久化：`task_id → { effect_committed, playbook, updated_at }`（SharedPreferences 或 app-private JSON，debug/release 皆可）。

### 4.4 串行与超时

- 单设备 **同一时刻至多一个** Playbook 在跑（队列或拒绝 `BUSY`）。  
- 使用 Task 的 `timeout_sec`（缺省按 playbook，如 ensure=20s、publish=120s）。  
- 超时：`failed` / `TIMEOUT`；若已判定副作用完成则仍记 `effect_committed=true` 并走 `reconcile` 语义由云端后续处理（端上先回报 `failed`+已提交标志写入本地）。

---

## 5. 组件与文件映射

| 组件 | Source set | 职责 |
| --- | --- | --- |
| `PlaybookCommand` / `PlaybookResult` | main | 不可变数据类 |
| `PlaybookRuntime` | main | 门面：a11y、lease、observe、click、setText、wait、extract、launchXhs、allowFinal |
| `AccessibilityPlaybookRuntime` | main | 适配 `M0AccessibilityService` |
| `Playbook` | main | `fun run(cmd, runtime): PlaybookResult` |
| `PlaybookRegistry` | main | `name` → 工厂；未知 → `UNSUPPORTED_PLAYBOOK` |
| `TaskDeduper` / `EffectLedger` | main | task_id 去重 + effect_committed 持久化 |
| `PlaybookEngine` | main | 串行调度：dedupe → registry → run → ledger |
| `EnsureAppReadyPlaybook` | main（从 debug 上移） | 现有逻辑 |
| `PublishNotePlaybook` 等 | main | 分期实现（见 §7） |
| `TaskCommandDispatcher` | debug | 变薄：解析 JSON → `engine.run` → events |
| Debug Bridge | debug | 不变：投递与回执 |

Debug 广播（`M0DebugCommandReceiver`）**保留**作矩阵/人工诊断；不强制经 Engine。Playbook 与广播共享底层 Action API，避免两套点击逻辑。

---

## 6. PlaybookRuntime 最小能力

```text
accessibilityAlive(): Boolean
currentLease(): TargetLease?
launchXhs(): Boolean
waitForForeground(pkg, timeoutMs): Boolean
observe(lease): UiSnapshot?
pageHint(lease): PageHint?
click(lease, selector, allowFinal): ActionResult
setText(lease, selector, text): ActionResult
wait(lease, condition, timeoutMs): WaitResult
extractComments|Inbox|Thread(lease): ExtractionResult
globalBack(lease): Boolean
```

副作用 Playbook（`publish_note` / `reply_*`）必须显式 `allowFinal=true` 才会点「发布/发送」；默认与现网 FinalActionPolicy 一致。

---

## 7. 实现分期（引擎落地后）

| 阶段 | Playbook | 验收 |
| --- | --- | --- |
| **E0** | Engine 骨架 + 上移 `ensure_app_ready` + Bridge 接线 | 真机再跑一次云端 `ensure_app_ready` → succeeded |
| **E1** | `read_comments@1.0`、`inbox_sync@1.0`（含 optional open thread） | 云下发只读任务，回执带明确失败码或成功；矩阵对照 M0-Cap |
| **E2** | `publish_note@1.0` | params：标题/正文/选图策略（首期：相册第 N 格或已推送测试图）；真机 effect_committed≥1 |
| **E3** | `reply_comment` / `reply_dm` | 对齐已有手工证据路径 |

媒体：E2 首期 **不实现签名 URL 下载**；沿用 M0-Cap「图已在相册」或 debug 推图。签名 URL → MediaStore 留 M2a 与引擎并行的 Media Bridge 小节。

---

## 8. 与 Cloud Bridge / CP 的衔接

```text
CP POST /tasks
  → MQTT（尽力）+ DB status=accepted
  → Agent HTTP GET .../commands
  → PlaybookEngine.run
  → POST .../events { task_id, status, error_code }
  → CP mark readonly/non-readonly per existing hooks
```

Dispatcher 对未知 playbook：`failed` / `UNSUPPORTED_PLAYBOOK`（不崩溃）。  
Bridge 不再内嵌 Playbook 业务分支（除注册表装配）。

---

## 9. 测试策略

| 层 | 内容 |
| --- | --- |
| 单元 | Deduper、EffectLedger、Registry、EnsureAppReady / 只读 Playbook 在 FakeRuntime 上的分支 |
| Debug 单元 | Dispatcher 调 Engine、去重不二次执行 |
| 真机 | E0 云闭环；E1/E2 各至少 1 次；证据追加 `ops/m0/xhs-flow-matrix.md` |

Docker：`./gradlew :agent:testDebugUnitTest :agent:test`（经 android-builder）。

---

## 10. 错误码（约定）

沿用并扩展短码：`A11Y_DOWN`、`XHS_NOT_FOREGROUND`、`LOGIN_REQUIRED`、`BUSINESS_BLOCKED`、`TIMEOUT`、`BUSY`、`UNSUPPORTED_PLAYBOOK`、`LEASE_LOST`、`FINAL_ACTION_BLOCKED`、`MEDIA_MISSING`、`OBSERVE_FAILED`、`EXTRACT_EMPTY`。

---

## 11. 自我审查

- 无 TBD；媒体下载明确推迟到 Media Bridge。  
- 与「禁止云端任意脚本」一致（方案 1）。  
- release 不联网；debug 嵌入 token 的权衡继承 bridge 设计。  
- 与现网 events 字段兼容；不强制先改 CP schema。  

---

## 12. 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-07-13 | 初稿：方案 C + 原语编排引擎；E0–E3 分期 |

# 小红书矩阵 · 多物理机云控技术方案（端上 Agent）

**日期：** 2026-07-10  
**状态：** 已定稿（待实现）  
**范围：** 仅设备控制与任务编排技术路径；不含内容生产、选题与素材创意。

---

## 1. 背景与目标

通过 Agent 控制多台 **Android 物理真机**，完成小红书日常操作：笔记编辑与发布、评论、回复等。设备将分布在多地以获取当地流量，因此生产路径不能依赖「每地一台 Linux + USB Hub」。

### 1.1 已确认约束

| 项 | 选择 |
|----|------|
| 系统 | 全 Android |
| 规模 | 首期约 5–20 台，可扩展 |
| 部署 | 多地分散；现场无需工控机 |
| 网络 | 每台独立 SIM / 蜂窝流量 |
| 操控 | 手机端 Agent + 系统无障碍（Accessibility）模拟触控 |
| 上层 | 日常调度 + 对话式 Agent 均可下发任务 |
| 内容 | 本设计不考虑文案/素材生成 |

### 1.2 成功标准（技术）

- 异地手机仅需充电 + SIM，即可稳定接收云端任务并回执。
- 发帖 / 收件箱同步 / 回复 / 评论等 Playbook 可重复执行，失败可分类诊断。
- 遇验证码、登录失效、风控提示时自动停机并告警，不循环硬点。
- 调度与对话 Agent 共用同一套 Task 模型与执行通道。

---

## 2. 核心决策

### 2.1 生产主路径：纯端上 Agent（方案 A）

```
云端 Control Plane
  → MQTT / WebSocket（经手机蜂窝）
  → 手机 Agent App
  → AccessibilityService（读控件树 + 点击/滑动/输入）
  → 小红书 App（由其自身完成对平台的网络请求）
```

**明确不做：**

- 调用小红书未公开私有 HTTP API / 协议群控作为主路径。
- 以 USB + 电脑 ADB/uiautomator2 作为生产依赖。
- Root、改机、验证码破解等对抗手段。

### 2.2 「无障碍注入」含义

Android 无障碍服务在用户授权后，可：

1. **读**：获取当前界面控件树（文案、viewId、是否可点击、bounds 等）。
2. **写**：对目标节点执行点击、长按、滑动、输入、返回等，效果等价于真人触屏操作。

小红书仍走官方客户端逻辑；Agent 不替代其业务 API，只替代「手指」。

观测原则：**UI Tree First**；截图仅用于失败诊断，不作主决策路径。

### 2.3 曾评估后放弃的生产方案

| 方案 | 结论 |
|------|------|
| 单机 USB Device Farm + uiautomator2 | 适合实验室打磨，多地硬件成本高，不作生产主路径 |
| 中心调度 + 多地 USB Node | 每地仍需主机与 Hub，不符合降本目标 |
| 现成云真机 / STF | 难满足独立蜂窝与矩阵业务编排，不作底座 |

---

## 3. 系统架构

### 3.1 逻辑分层

```
┌─────────────────────────────────────────────┐
│ Cloud Control Plane                         │
│ · Device / Account Registry                 │
│ · Scheduler（配额、错峰、日历）                │
│ · Task Service（队列、状态机、重试）           │
│ · Agent Gateway（自然语言 → Task）           │
│ · Audit / Alert                             │
└──────────────────┬──────────────────────────┘
                   │ 鉴权长连接 + 任务/回执
┌──────────────────▼──────────────────────────┐
│ On-device Agent App（每台物理机）              │
│ · Cloud Link / Heartbeat                    │
│ · Task Runner（单机串行队列）                 │
│ · Observer（Accessibility 精简摘要）         │
│ · Action Executor（tap / type / swipe …）   │
│ · Playbook Engine                           │
│ · Health Reporter / Media Bridge            │
└──────────────────┬──────────────────────────┘
                   │ 无障碍 API
┌──────────────────▼──────────────────────────┐
│ 小红书 App + 独立 SIM                         │
└─────────────────────────────────────────────┘
```

### 3.2 关键边界

- Control Plane **只下发业务意图**（Playbook 名 + 参数），不下发原始坐标脚本作为常规手段。
- 手机 Agent 是 **唯一** 操作小红书 UI 的组件。
- 一设备同一时刻只执行一个 Task（单 Runner 队列）；并行靠多机，不靠单机多任务抢屏。
- **一设备一账号** 硬绑定；禁止一号多机自动迁移。

---

## 4. 现场硬件（极简）

| 组件 | 要求 |
|------|------|
| Android 手机 | 尽量统一或少型号；开启所需权限 |
| SIM | 一机一卡，日常使用蜂窝（关闭 Wi‑Fi，除非排障） |
| 电源与支架 | 长期插电、散热、避免叠放 |
| 现场电脑 / USB Hub | **不需要** |

交付 SOP（每台一次人工）：安装 Agent → 开启无障碍 → 电池优化白名单 / 厂商自启清单 → 登录小红书 → 绑定码关联云端账号 → 验收心跳与空任务。

---

## 5. 手机端 Agent 设计

### 5.1 进程与权限

- **Foreground Service**：前台通知保活，降低被系统杀死概率。
- **AccessibilityService**：读树与注入操作的唯一通道。
- 其他：忽略电池优化、媒体读写（发帖素材）、可选通知监听（辅助消息类任务）。
- 不依赖 Root / Shizuku 作为生产前提。

### 5.2 内部模块

| 模块 | 职责 |
|------|------|
| Cloud Link | 连接、重连、收任务、上报进度/结果、配置拉取 |
| Task Runner | 本地串行队列；超时与取消 |
| Observer | 将 AccessibilityNodeInfo 裁剪为 page_hint + key_elements |
| Action Executor | 原子动作：tap(selector)、type、swipe、press(back)、wait |
| Playbook Engine | 版本化业务剧本 |
| Interruption Guard | 关闭权限框、更新提示、活动弹窗等打断 |
| Health Reporter | 电量、温度、无障碍开关、服务存活、前台包名、网络 |
| Media Bridge | 下载素材 → 写入相册 → 供发布页选择 |
| Local Store | 任务缓存、轻量状态；支持回执补传 |

### 5.3 观测与动作约定

**Observer 输出（示意）：**

```text
observe() -> {
  page_hint,
  hierarchy_summary,   # 裁剪后，禁止默认回传整棵巨型树
  key_elements[],
  app_state            # 前台包名等
}
```

**原子动作：** 业务层只组合原子动作；禁止以固定坐标为主路径（坐标仅作极端兜底并打日志）。

**等待：** 以 `wait_for_element` / 条件等待为主，禁止用固定 sleep 作为主逻辑。

**截图：** 默认关闭；连续 UI 失败或 `BUSINESS_BLOCKED` 时可选上传一张压缩诊断图。

### 5.4 保活与健康

按厂商维护 checklist（小米 / 华为 / OPPO / vivo 等）：自启、后台锁定、禁止强制停止误操作等。

健康信号至少包括：`accessibility_enabled`、`battery_opt_ignored`、`service_alive`、`last_heartbeat_age`。任一项失败 → 云端告警 → **转人工**，不做隐藏保活黑科技。

### 5.5 与云端协议（最小集）

**上行：** `heartbeat`、`task_progress`、`task_result`、`health_alert`  
**下行：** `assign_task`、`cancel_task`、`config_update`、`pause` / `resume`

**断网策略（首期）：** 不拉取新任务；已在执行的任务可做完后待联网补回执。

**鉴权：** 设备级密钥 / 证书 + 旋转 Token；任务 payload 签名防伪造。

---

## 6. 任务模型与 Playbook

### 6.1 Task

```text
Task {
  task_id
  device_id | account_id   # 解析到唯一设备
  playbook                 # 如 publish_note@1.0
  params                   # 业务参数（内容由上游注入，本层不生成）
  priority                 # 对话临时指令可高于日常调度
  schedule_at
  timeout_sec
  retry_policy
  source                   # scheduler | agent | manual
}
```

结果：`success | failed | cancelled` + `result_payload` + `error_code` + 可选诊断图引用。

### 6.2 状态机

```text
queued → running → succeeded
                 → failed → (retry?) → queued | dead
                 → cancelled
```

### 6.3 失败码

| error_code | 含义 | 处理 |
|------------|------|------|
| `DEVICE_OFFLINE` | 心跳丢失 / Agent 不可达 | 暂停调度，待恢复 |
| `UI_NOT_FOUND` | 控件找不到 | 有限重试；持续则标记 Playbook/App 版本漂移 |
| `APP_CRASH` | 小红书闪退 | 重启 App 后有限重试 |
| `BUSINESS_BLOCKED` | 验证码、登录失效、风控提示 | **禁止自动重试**，告警人工 |
| `TIMEOUT` | 步骤或整任务超时 | 按策略重试或失败 |
| `PRECONDITION` | 素材缺失、无障碍关闭等 | 不重试，修前置条件 |
| `A11Y_DISABLED` | 无障碍被关 | 告警人工重新开启 |

### 6.4 首期 Playbook

1. `ensure_app_ready` — 前台就绪、清打断弹窗  
2. `publish_note` — 发布图文/视频笔记  
3. `inbox_sync` — 同步待回复互动，产出结构化待办  
4. `reply_comment` — 回复指定评论  
5. `post_comment` — 对目标笔记发表评论  

首期 Playbook **内置在 Agent App**（带版本号）；云端只传 params。云端 DSL 热更新列为二期。

弹窗处理抽成共享步骤 `dismiss_interruptions`，供各 Playbook 复用。

### 6.5 调度与对话 Agent 共用队列

```text
Scheduler ──┐
            ├──► Task Service ──► 目标设备 Agent
Agent NL  ──┘
```

- 默认 **不打断** 正在 `running` 的任务；高优先级只插入同设备队列前方。
- Agent 只映射到已注册 Playbook，禁止临时发明未注册操作序列。
- Agent 最小工具集：`list_devices` / `list_accounts`、`create_tasks`、`get_task` / `cancel_task`、`get_inbox_todos`、`set_account_status`。

---

## 7. Cloud Control Plane

### 7.1 模块

- Device Registry：`device_id`、`agent_version`、`a11y_ok`、`last_heartbeat`、可选 `region`
- Account Registry：与 `device_id` 1:1；`daily_quotas`；`status`（active / frozen / login_required / risk_blocked）
- Scheduler：日历模板、配额、错峰（避免多机整点齐发）
- Task Service：持久化队列与状态机
- Agent Gateway：LLM + tool calling
- Audit / Alert：操作审计与健康/风控告警

### 7.2 素材

任务携带云存储 URL → Agent 经蜂窝下载 → 写入相册并 media scan → Playbook 在发布页按规则选中。注意流量与包体大小配额。

### 7.3 账号状态

`risk_blocked` / `login_required` 的账号自动移出调度，直到人工解除。

---

## 8. 可靠性、观测与安全

### 8.1 可靠性

- 云端任务持久化（Postgres）；心跳超时（建议 2–3 分钟）标记 `offline`。
- 端上 Runner 与云端双重超时。
- 进程/手机重启后：未完成任务由云端判定重派或失败（策略可配置；发帖类需防重复，依赖结果可观测性）。

### 8.2 观测

- 设备：在线、电量、温度、无障碍、队列深度、最近错误码  
- 任务：逐步 `page_hint` + 结果  
- 告警：批量掉线、验证码、登录失效、无障碍关闭  

### 8.3 安全

- 全链路 TLS；设备身份与任务签名  
- 日志不落密码；登录态保留在小红书 App 内  
- LLM API Key 仅存 Control Plane  
- 全量审计对话 Agent 与人工强制操作  

### 8.4 合规声明

使用无障碍自动操作第三方 App 可能违反小红书用户协议及当地法规，存在封号与合规风险。本方案 **不包含** 绕过验证码、风控或设备指纹伪造的设计；实施方需自行评估并承担风险。

---

## 9. 技术选型

| 层 | 选型 |
|----|------|
| 手机 Agent | Kotlin；Foreground Service + AccessibilityService |
| 云端 API | Python FastAPI（或同等）；Postgres；Redis（队列/锁，可按规模引入） |
| 长连接 | MQTT（如 EMQX）优先，或 WebSocket |
| 调度 | DB due 扫描或延迟队列 |
| 对话 Agent | LLM + function calling → Task Service |
| Playbook 首期 | 端上 Kotlin 实现；params 用 JSON Schema 约束 |

建议仓库逻辑结构：

```text
agent-android/       # 手机端 App
control-plane/       # 云端服务
schemas/             # Task / Device / Event 契约
playbooks/           # 剧本说明与版本 changelog（实现可在端上）
ops/                 # 厂商保活 SOP、设备交付清单
```

---

## 10. 实施路线

| 里程碑 | 内容 |
|--------|------|
| M1 | 云端骨架 + 端上心跳/收任务/回执；空 Playbook 或仅拉起 App |
| M2 | Observer + 原子动作 + `ensure_app_ready` |
| M3 | `publish_note`、`inbox_sync`、`reply_comment`、`post_comment` |
| M4 | 账号绑定、配额调度、错峰、告警 |
| M5 | 对话 Agent |
| M6 | 厂商保活 SOP 固化、诊断图上传、多地寄送试点 |

**验收：** 异地无工控机条件下心跳稳定；核心 Playbook 可重复跑通；`BUSINESS_BLOCKED` 会停而非死循环；调度与对话任务同队列。

---

## 11. 非目标（首期不做）

- 内容生成与运营策略  
- iOS  
- USB Device Farm 生产依赖  
- Root / 改机 / 验证码自动破解  
- 实时投屏中控 UI（非控制链路必需）  
- Playbook 云端 DSL 热更新（二期）  
- 一号多机热迁移  

---

## 12. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-10 | 初稿：由 USB Farm 方案修订为纯端上 Agent + 无障碍注入；UI Tree First；调度 + 对话 Agent 共用 Task 模型 |

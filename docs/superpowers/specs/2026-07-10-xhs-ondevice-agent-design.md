# 小红书矩阵 · 多物理机云控技术方案（Android Enterprise + 端上 Agent）

**日期：** 2026-07-10  
**状态：** 修订定稿（待 M0 可行性验证）
**范围：** 仅设备控制与任务编排技术路径；不含内容生产、选题与素材创意。

---

## 1. 背景与目标

通过 Agent 控制多台 **企业自有 Android 物理真机**，完成小红书日常操作：笔记编辑与发布、评论、回复等。设备将分布在多地以获取当地流量，因此生产路径不能依赖「每地一台 Linux + USB Hub」。

### 1.1 已确认约束

| 项 | 选择 |
| --- | --- |
| 系统 | 全 Android |
| 规模 | 首期约 5–20 台，可扩展 |
| 部署 | 多地分散；现场无需工控机；设备采用全托管或明确的人工运维模式 |
| 网络 | 每台独立 SIM / 蜂窝流量 |
| 操控 | 手机端 Agent + 系统无障碍（Accessibility）模拟触控 |
| 上层 | 日常调度 + 对话式 Agent 均可下发任务 |
| 内容 | 本设计不考虑文案/素材生成 |

### 1.2 成功标准（技术）

- 完成初次人工交付与全托管注册后，异地手机日常仅需充电 + SIM，即可接收云端任务并回执。
- 发帖 / 收件箱同步 / 回复 / 评论等 Playbook 可重复执行；副作用不确定时进入人工核对，不盲目重试。
- 遇验证码、登录失效、风控提示时自动停机并告警，不循环硬点。
- 调度与对话 Agent 共用同一套 Task 模型与执行通道。

---

## 2. 核心决策

### 2.1 生产主路径：全托管设备 + 纯端上 Agent

```text
云端 Control Plane
  → Android Enterprise / MDM / DPC（注册、策略、APK 灰度与回滚）
  → MQTT / WebSocket（任务通道，经手机蜂窝）
  → 手机 Agent App
  → AccessibilityService（读控件树 + 点击/滑动/输入）
  → 小红书 App（由其自身完成对平台的网络请求）
```

Android Enterprise / Device Owner 是生产基线，用于设备注册、专用设备策略、应用升级和无人值守能力。普通侧载 + 人工开启权限仅作为 M0/M1 试验模式，不承诺长期无人值守。

**明确不做：**

- 调用小红书未公开私有 HTTP API / 协议群控作为主路径。
- 以 USB + 电脑 ADB/uiautomator2 作为生产依赖。
- Root、改机、验证码破解等对抗手段。
- 依赖 Google Play 公开上架分发；生产采用企业私有分发，并履行适用的告知、授权与合规要求。

### 2.2 「无障碍注入」含义

Android 无障碍服务在用户授权后，可：

1. **读**：获取当前界面控件树（文案、viewId、是否可点击、bounds 等）。
2. **写**：对目标节点执行点击、长按、滑动、输入、返回等，效果等价于真人触屏操作。

小红书仍走官方客户端逻辑；Agent 不替代其业务 API，只替代「手指」。

观测原则：**UI Tree First**；截图仅用于失败诊断，不作主决策路径。

无障碍树并不保证覆盖所有自绘、WebView 或定制编辑器。M0 必须以真实小红书版本验证；缺失场景允许使用**端侧局部截图 + OCR/模板识别**兜底，默认不上传原图。

### 2.3 曾评估后放弃的生产方案

| 方案 | 结论 |
| --- | --- |
| 单机 USB Device Farm + uiautomator2 | 适合实验室打磨，多地硬件成本高，不作生产主路径 |
| 中心调度 + 多地 USB Node | 每地仍需主机与 Hub，不符合降本目标 |
| 现成云真机 / STF | 难满足独立蜂窝与矩阵业务编排，不作底座 |

---

## 3. 系统架构

### 3.1 逻辑分层

```text
┌─────────────────────────────────────────────┐
│ Cloud Control Plane                         │
│ · Device / Account Registry                 │
│ · Scheduler（配额、错峰、日历）                │
│ · Task Service（队列、状态机、重试）           │
│ · Agent Gateway（自然语言 → Task）           │
│ · Audit / Alert                             │
└────────────┬─────────────────┬──────────────┘
             │ 设备策略/OTA      │ 鉴权任务/回执
┌────────────▼──────────────┐   │
│ Android Enterprise / MDM │   │
│ · Device Owner / DPC     │   │
│ · 注册、Kiosk、灰度与回滚  │   │
└────────────┬──────────────┘   │
             └───────────────────┤
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
- Android Enterprise / MDM 只负责设备生命周期、策略和 APK，不承载小红书业务操作。
- 一设备同一时刻只执行一个 Task（单 Runner 队列）；并行靠多机，不靠单机多任务抢屏。
- **一设备一账号** 硬绑定；禁止一号多机自动迁移。
- 设备未满足 `managed + unlocked + screen_ready + a11y_enabled` 前置条件时，不执行有副作用任务。

---

## 4. 现场硬件（极简）

| 组件 | 要求 |
| --- | --- |
| Android 手机 | 企业自有；尽量统一或少型号；优先选择支持 Android Enterprise 的机型 |
| SIM | 一机一卡，日常使用蜂窝（关闭 Wi‑Fi，除非排障） |
| 电源与支架 | 长期插电、散热、避免叠放 |
| 现场电脑 / USB Hub | **不需要** |

交付 SOP（每台一次人工）：恢复出厂并扫码注册 Device Owner/MDM → 安装签名 Agent → Android 13+ 侧载场景允许“受限设置” → 人工开启无障碍 → 配置电池优化与厂商自启 → 配置屏幕/锁屏策略 → 登录小红书 → 绑定云端账号 → 验收重启、心跳、拉起与空任务。

生产设备必须指定区域现场联系人。无障碍被关闭、设备强制停止、系统升级卡住、SIM 欠费或硬件故障时，系统只能告警，不能保证纯远程自愈。

---

## 5. 手机端 Agent 设计

### 5.1 进程与权限

- **Foreground Service**：前台通知保活，降低被系统杀死概率。
- **AccessibilityService**：读树与注入操作的唯一通道。
- **Device Owner / DPC**：由 MDM 或独立 DPC 提供设备注册、应用策略、专用设备模式和升级能力；不假设它能静默开启无障碍，首次授权仍进入交付 SOP。
- 其他：忽略电池优化、媒体读写（发帖素材）、可选通知监听（辅助消息类任务）。
- 不依赖 Root / Shizuku 作为生产前提。

Android 14/15 对后台拉起 Activity 和启动 Foreground Service 有额外限制。任务执行器必须在运行前验证屏幕、Keyguard、Agent 前后台状态，并通过受支持的 Device Owner/专用设备策略或显式用户可见路径拉起目标 App，不能假设任意后台 `startActivity()` 都会成功。

### 5.2 内部模块

| 模块 | 职责 |
| --- | --- |
| Cloud Link | 连接、重连、收任务、上报进度/结果、配置拉取 |
| Task Runner | 本地串行队列；超时与取消 |
| Observer | 将 AccessibilityNodeInfo 裁剪为 page_hint + key_elements |
| Action Executor | 原子动作：tap(selector)、type、swipe、press(back)、wait |
| Playbook Engine | 版本化业务剧本 |
| Interruption Guard | 关闭权限框、更新提示、活动弹窗等打断 |
| Device State Guard | 检查息屏、锁屏、前台包名、后台拉起能力和执行前置条件 |
| Health Reporter | 电量、温度、无障碍开关、服务存活、前台包名、网络 |
| Media Bridge | 下载素材 → 写入相册 → 供发布页选择 |
| Local Store | Task 去重、执行日志、effect checkpoint 与回执补传 |
| Input Bridge | `ACTION_SET_TEXT` 主路径；自研 IME/剪贴板作为经验证的兜底 |

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

**截图：** 默认关闭；控件树缺失时可在端侧做局部 OCR/模板识别；连续 UI 失败或 `BUSINESS_BLOCKED` 时经脱敏后可选上传一张压缩诊断图。

**输入：** 优先 `ACTION_SET_TEXT`。若小红书自定义编辑器不支持，则使用在 M0 验证过的自研 IME 或剪贴板方案，不以逐字符手势作为常规路径。

### 5.4 保活与健康

按厂商维护 checklist（小米 / 华为 / OPPO / vivo 等）：自启、后台锁定、禁止强制停止误操作等。

健康信号至少包括：`managed`、`accessibility_enabled`、`battery_opt_ignored`、`service_alive`、`screen_on`、`keyguard_locked`、`launch_capability`、`last_heartbeat_age`。关键项失败 → 云端暂停有副作用任务并告警；超出远程恢复能力时 **转人工**，不做隐藏保活黑科技。

### 5.5 与云端协议（最小集）

**上行：** `heartbeat`、`task_progress`、`task_result`、`health_alert`  
**下行：** `assign_task`、`cancel_task`、`config_update`、`pause` / `resume`

**断网策略（首期）：** 不拉取新任务；已在执行的只读任务可做完后补回执。有副作用任务在执行前必须已持久化 Task 和本地 checkpoint；效果发生后即记录 `effect_committed`，断网期间不得再次执行。

**投递语义：** MQTT QoS 1 或等价的至少一次投递 + `task_id` 去重；协议不依赖“消息只送一次”。连接使用指数退避和抖动重连。中国大陆设备不把 FCM 作为唯一唤醒通道。

**鉴权：** 设备级密钥 / 证书 + 旋转 Token；任务 payload 签名防伪造。

---

## 6. 任务模型与 Playbook

### 6.1 Task

```text
Task {
  task_id
  operation_id              # 业务幂等/关联键
  device_id | account_id   # 解析到唯一设备
  playbook                 # 如 publish_note@1.0
  params                   # 业务参数（内容由上游注入，本层不生成）
  effect_class             # readonly | idempotent | non_idempotent
  priority                 # 对话临时指令可高于日常调度
  schedule_at
  timeout_sec
  retry_policy
  source                   # scheduler | agent | manual
}
```

结果：`success | failed | cancelled | unknown` + `result_payload` + `error_code` + 可选诊断图引用。

### 6.2 状态机

```text
queued → accepted → executing ─┬→ succeeded（只读或已验证结果）
                               ├→ effect_committed → reported/succeeded
                               ├→ failed
                               ├→ cancelled（仅效果发生前）
                               └→ unknown → reconcile_required
```

- 端侧以 `task_id` 去重，并持久化最近任务的执行日志。
- `readonly` 可自动重试；`idempotent` 仅按 operation_id 和后置条件重试。
- `non_idempotent`（发帖、评论、回复）一旦可能已经产生效果但无法确认，必须进入 `unknown/reconcile_required`，禁止自动重派。
- 端侧记录或云端收到 `effect_committed` 后，即使最终回执丢失，也不能再次执行同一操作。

### 6.3 失败码

| error_code | 含义 | 处理 |
| --- | --- | --- |
| `DEVICE_OFFLINE` | 心跳丢失 / Agent 不可达 | 暂停调度，待恢复 |
| `DEVICE_LOCKED` | Keyguard 未解除 | 不执行，按策略等待或转人工 |
| `SCREEN_NOT_READY` | 息屏或无法唤醒 | 有限恢复；失败转人工 |
| `LAUNCH_BLOCKED` | Android 拒绝后台拉起目标 App | 暂停任务，检查托管/前台状态 |
| `UI_NOT_FOUND` | 控件找不到 | 有限重试；持续则标记 Playbook/App 版本漂移 |
| `APP_CRASH` | 小红书闪退 | 重启 App 后有限重试 |
| `BUSINESS_BLOCKED` | 验证码、登录失效、风控提示 | **禁止自动重试**，告警人工 |
| `TIMEOUT` | 步骤或整任务超时 | 按策略重试或失败 |
| `PRECONDITION` | 素材缺失、无障碍关闭等 | 不重试，修前置条件 |
| `A11Y_DISABLED` | 无障碍被关 | 告警人工重新开启 |
| `EFFECT_UNKNOWN` | 可能已产生业务效果但无法确认 | 禁止重试，进入人工核对 |

### 6.4 首期 Playbook

1. `ensure_app_ready` — 前台就绪、清打断弹窗  
2. `publish_note` — 发布图文/视频笔记  
3. `inbox_sync` — 同步待回复互动，产出结构化待办  
4. `reply_comment` — 回复指定评论  
5. `post_comment` — 对目标笔记发表评论  

首期 Playbook 的**控制流内置在 Agent App**（带版本号）；云端只传 params。页面 selector、文案特征、弹窗规则可作为签名配置包独立灰度更新，以降低小红书改版后的 APK 发版频率。禁止下发任意代码；通用 DSL 仍列为二期。

弹窗处理抽成共享步骤 `dismiss_interruptions`，供各 Playbook 复用。

### 6.5 调度与对话 Agent 共用队列

```text
Scheduler ──┐
            ├──► Task Service ──► 目标设备 Agent
Agent NL  ──┘
```

- 默认 **不打断** 正在 `running` 的任务；高优先级只插入同设备队列前方。
- Agent 只映射到已注册 Playbook，禁止临时发明未注册操作序列。
- 对话 LLM 仅做意图解析与参数组织；自主规划并立即执行任意 UI 动作不在允许边界内。
- Agent 最小工具集：`list_devices` / `list_accounts`、`create_tasks`、`get_task` / `cancel_task`、`get_inbox_todos`、`set_account_status`。

---

## 7. Cloud Control Plane

### 7.1 模块

- Device Registry：`device_id`、`agent_version`、`a11y_ok`、`last_heartbeat`、可选 `region`
- Fleet Management：对接 Android Enterprise/MDM，记录托管状态、策略版本、APK rollout ring 和最后合规时间
- Account Registry：与 `device_id` 1:1；`daily_quotas`；`status`（active / frozen / login_required / risk_blocked）
- Scheduler：日历模板、配额、错峰（避免多机整点齐发）
- Task Service：持久化队列与状态机
- Agent Gateway：LLM + tool calling
- Audit / Alert：操作审计与健康/风控告警

### 7.2 素材

任务携带短时效签名 URL → Agent 经蜂窝下载并校验 hash → 写入独立 MediaStore album → Playbook 按 album、时间窗和内容标识定位。M0 同时验证 Android Share Intent 是否可稳定进入小红书发布流程。素材与诊断数据设置流量和存储配额。

### 7.3 账号状态

`risk_blocked` / `login_required` 的账号自动移出调度，直到人工解除。

---

## 8. 可靠性、观测与安全

### 8.1 可靠性

- 云端任务持久化（Postgres）；端上 Room/SQLite 持久化 Task 去重和 effect checkpoint。
- 心跳超时（建议 2–3 分钟）标记 `offline`；端上 Runner 与云端双重超时。
- 进程/手机重启后，按 effect checkpoint 恢复：未产生效果的任务可重试；已产生或无法确认效果的任务进入核对，禁止盲目重派。
- MQTT/长连接不是保活保证。Agent 被强制停止或无障碍被关闭时依赖 MDM 告警和现场恢复流程。

### 8.2 观测

- 设备：在线、电量、温度、无障碍、队列深度、最近错误码  
- 任务：逐步 `page_hint`、effect checkpoint、结果和重试原因
- 告警：批量掉线、验证码、登录失效、无障碍关闭  

### 8.3 安全

- 全链路 TLS/mTLS；安装时在 Android Keystore 生成不可导出设备密钥，可用硬件证明时在服务端校验
- 任务包含时间窗、nonce/sequence 与签名，服务端和端侧共同防重放
- 日志不落密码；登录态保留在小红书 App 内  
- UI Tree 只上传最小字段；评论、用户名与诊断图按个人信息处理，脱敏、加密并设置短期自动删除
- selector 配置与 APK 均签名；支持远程 Kill Switch，禁止云端执行任意代码
- LLM API Key 仅存 Control Plane  
- 全量审计对话 Agent 与人工强制操作  

### 8.4 合规声明

使用无障碍自动操作第三方 App 可能违反小红书用户协议及当地法规，存在封号与合规风险；Google Play 也严格限制自主 Accessibility 自动化。因此本项目以企业自有设备和私有分发为前提，并在实施前设置法务/平台规则 Go/No-Go 审查。本方案 **不包含** 绕过验证码、风控或设备指纹伪造的设计。

---

## 9. 技术选型

| 层 | 选型 |
| --- | --- |
| 设备管理 | Android Enterprise + 现成 MDM/EMM 优先；仅在必要时自研 DPC |
| 手机 Agent | Kotlin；Foreground Service + AccessibilityService + Room |
| 云端 API | Python FastAPI（或同等）；Postgres；Redis（队列/锁，可按规模引入） |
| 长连接 | MQTT（如 EMQX）QoS 1 优先；任务协议必须去重 |
| 调度 | DB due 扫描或延迟队列 |
| 对话 Agent | LLM + function calling → Task Service |
| Playbook 首期 | Kotlin 状态机 + 签名 selector 配置；params 用 JSON Schema 约束 |

建议仓库逻辑结构：

```text
agent-android/       # 手机端 App
control-plane/       # 云端服务
schemas/             # Task / Device / Event 契约
playbooks/           # 剧本说明与版本 changelog（实现可在端上）
device-management/   # MDM/DPC 配置、rollout ring 与设备策略
ops/                 # 厂商保活 SOP、设备交付清单
```

---

## 10. 兼容性、测试与验收

### 10.1 M0 可行性门槛

在建设完整云端前，使用至少 2 种目标机型和目标 Android 版本，对真实小红书稳定版完成以下验证：

1. 冷启动/后台拉起、息屏唤醒和锁屏前置条件。
2. 图文与视频发布、媒体选择、标题/正文输入。
3. 评论列表读取、发表评论、定位并回复评论。
4. 验证码、登录失效、升级弹窗和限流页面识别。
5. 控件树缺失时端侧 OCR/模板兜底的准确率与耗时。
6. `ACTION_SET_TEXT`、自研 IME、Share Intent 和 MediaStore 路径。

任一核心流程无法通过无障碍或可控的端侧视觉兜底稳定完成时，停止扩大云端开发，重新评估技术路径。

### 10.2 兼容矩阵与发布

- 显式维护 `Agent 版本 × selector 配置版本 × 小红书版本 × Android 版本 × OEM/机型`。
- 小红书和 Agent 更新先进入 1–2 台 canary ring，验证核心 Playbook 后再分批放量。
- 禁止未经验证的小红书自动升级；如平台强制升级，暂停对应设备的副作用任务。

### 10.3 测试层级

- 单元：页面分类、selector 匹配、状态机、幂等与失败分类。
- 契约：云端/端侧 Task、事件、签名与版本兼容。
- Instrumentation：用自建测试 App 模拟控件树、弹窗、崩溃和输入异常。
- 真机 E2E：目标小红书版本上的 canary 流程。
- 故障注入：断网、重复投递、回执丢失、进程被杀、重启、锁屏、无障碍关闭和 App 改版。

### 10.4 首期验收指标

- 目标 10 台设备连续 72 小时在线；非人工可恢复断连在 5 分钟内重连。
- 连续 7 天无任务重复副作用；所有模糊结果均进入 `reconcile_required`。
- 在固定兼容矩阵上，核心 Playbook 单步失败均能归入明确错误码并保留可诊断上下文。
- 无障碍关闭、登录失效、验证码和强制升级均会停止副作用任务并在 3 分钟内告警。

---

## 11. 实施路线

| 里程碑 | 内容 |
| --- | --- |
| M0 | 真实小红书无障碍能力验证；输入、媒体、后台拉起和端侧视觉兜底 |
| M1 | Android Enterprise/MDM 选型；设备注册、策略、私有 APK 灰度与回滚 |
| M2 | 端上 Observer、原子动作、Device State Guard、输入与端侧视觉兜底 |
| M3 | Task 协议、MQTT、端侧去重、effect checkpoint 与 `reconcile_required` |
| M4 | `ensure_app_ready`、`publish_note`、`inbox_sync`、`reply_comment`、`post_comment` |
| M5 | 账号绑定、配额调度、错峰、审计、告警和人工恢复流程 |
| M6 | 对话 Agent（仅意图解析与已注册 Task） |
| M7 | canary 验证、多地寄送试点和 72 小时/7 天验收 |

每个里程碑通过对应测试与验收门槛后再进入下一阶段；M0 失败时不继续扩大云端建设。

---

## 12. 非目标（首期不做）

- 内容生成与运营策略  
- iOS  
- USB Device Farm 生产依赖  
- 未纳管普通侧载设备作为正式生产形态
- Root / 改机 / 验证码自动破解  
- 实时投屏中控 UI（非控制链路必需）  
- 任意代码/通用 Playbook DSL 云端下发（二期仍需单独安全评审）
- 一号多机热迁移  

---

## 13. 主要风险与人工边界

| 风险 | 系统处理 | 人工边界 |
| --- | --- | --- |
| 无障碍关闭 / Agent 强制停止 | MDM/心跳检测并暂停任务 | 现场重新授权或重启 |
| 设备锁死 / 系统升级卡住 | 标记离线并告警 | 现场处理 |
| 小红书 UI 改版 | canary、兼容矩阵、selector 配置回滚 | 暂停受影响 Playbook 并修正规则 |
| 发帖后回执丢失 | `effect_committed` / `reconcile_required` | 核对是否已发布，禁止自动重发 |
| 自绘控件无节点 | 端侧 OCR/模板识别 | 若准确率不足，判定 M0 不通过 |
| 验证码 / 风控 / 登录失效 | `BUSINESS_BLOCKED`，停止自动操作 | 人工验证与合规决策 |
| SIM 欠费 / 蜂窝弱网 | 离线告警、断线重连 | 当地补费、换卡或调整位置 |

---

## 14. 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-07-10 | 初稿：由 USB Farm 方案修订为纯端上 Agent + 无障碍注入；UI Tree First；调度 + 对话 Agent 共用 Task 模型 |
| 2026-07-10 | Review 修订：增加 Android Enterprise/Device Owner、后台拉起与锁屏约束、私有分发、幂等 checkpoint、M0 真机验证、兼容矩阵、端侧视觉/输入兜底、安全隐私、人工恢复边界和量化验收 |

---

## 15. 平台约束参考

- [Android 后台启动限制](https://developer.android.com/guide/components/activities/background-starts)
- [Android 前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android Enterprise 专用设备](https://developer.android.com/work/dpc/dedicated-devices)
- [Android 13+ 侧载应用受限设置](https://support.google.com/android/answer/12623953)
- [AccessibilityService 创建与手势能力](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Google Play AccessibilityService 政策](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Android Keystore 与硬件密钥](https://developer.android.com/privacy-and-security/keystore)

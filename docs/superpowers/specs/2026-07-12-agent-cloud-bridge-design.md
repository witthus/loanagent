# Debug Agent ↔ 云端桥接设计（方案 A）

**日期：** 2026-07-12  
**状态：** 已批准（对话确认）  
**范围：** `android/agent` **debug** 构建仅；固定连现网 Control Plane  
**上位：** `docs/superpowers/specs/2026-07-12-xhs-cloud-management-design.md` M1 往返验收  

---

## 1. 目标

在 Redmi Note 12 Turbo（`device_id=redmi-note-12`）上安装 debug Agent，完成：

1. 周期心跳 → 云端设备 `online`  
2. MQTT 订阅 `devices/{deviceId}/commands`  
3. 执行只读 playbook `ensure_app_ready@1.0`  
4. HTTP 回执 `POST /api/v1/devices/{id}/events` → 任务 `succeeded` / `failed`  

Ops Web 可观察设备与任务状态。

## 2. 固定配置（debug 写死）

| 项 | 值 |
| --- | --- |
| Control Plane base | `http://119.45.36.208` |
| MQTT | `tcp://119.45.36.208:11883`（尽力；真机 Wi‑Fi 常只能出站 80/22，故以 HTTP poll 为主） |
| Command poll | `GET /api/v1/devices/{id}/commands` + `X-Device-Token`（返回 `accepted` 任务信封） |
| `device_id` | `redmi-note-12` |
| `DEVICE_TOKEN` | 与服务器 `/opt/loanagent/.env` 中 `DEVICE_TOKEN` 一致 |
| `OPS` events 鉴权 | 本版事件走现有 **Ops Bearer** hook：`POST .../events` 需 `Authorization: Bearer OPS_TOKEN`；debug 包内写死与服务器相同的 `OPS_TOKEN`（仅 debug，不进 release） |
| `agent_version` | `{versionName}-debug`（如 `0.1.0-debug`） |

> 说明：当前 Control Plane 的 `/events` 路由使用 `require_ops`，不是 device token。M1 debug 为打通闭环，在 debug 配置中同时嵌入 `DEVICE_TOKEN`（心跳）与 `OPS_TOKEN`（回执）。后续正式设备通道应改为 MQTT events 或 device 凭证，移除 debug 内嵌 OPS_TOKEN。

## 3. 组件

| 组件 | Source set | 职责 |
| --- | --- | --- |
| `CloudBridgeConfig` | debug | 上述常量 |
| `HeartbeatClient` | debug | `POST /api/v1/devices/{id}/heartbeat` + `X-Device-Token`；约 30s |
| `MqttCommandClient` | debug | Paho 连接 broker，订阅 commands，QoS 1 |
| `CloudBridgeService` | debug | FGS：启停心跳与 MQTT；无障碍连上时启动 |
| `TaskCommandDispatcher` | debug | 解析 MQTT JSON；`task_id` 去重；按 playbook 分发 |
| `EnsureAppReadyPlaybook` | debug | 见 §4 |
| `TaskEventReporter` | debug | `POST /api/v1/devices/{id}/events` |

**依赖（debug 或主模块可选）：** Eclipse Paho MQTT Android/Java client；OkHttp 或 `HttpURLConnection`（优先已有/轻量，避免无谓扩大依赖——若 lockfile 允许则 OkHttp）。

**权限：** debug `AndroidManifest` 增加 `INTERNET`（及可选 `ACCESS_NETWORK_STATE`）。main 继续 `tools:node="remove"` 网络权限，release 保持离线。

**生命周期：** `M0AccessibilityService.onServiceConnected`（debug）启动 `CloudBridgeService`；`onDestroy` 停止。可与现有 `M0DebugKeepAliveService` 并存或合并通知渠道，避免双 FGS 冲突——优先 **扩展 KeepAlive 或单一 FGS** 同时跑心跳+MQTT。

## 4. `ensure_app_ready@1.0`

成功条件（全部满足）：

1. `M0AccessibilityService.instance != null`  
2. 尝试 `getLaunchIntentForPackage("com.xingin.xhs")` 拉起小红书  
3. 在超时内取得 `currentLease()?.packageName == "com.xingin.xhs"`  
4. `observe` 得到的 `page_hint` **不是** `LOGIN_REQUIRED` 或 `BUSINESS_BLOCKED`  

失败：`status=failed`，`error_code` 使用简短码（如 `A11Y_DOWN` / `XHS_NOT_FOREGROUND` / `LOGIN_REQUIRED` / `BUSINESS_BLOCKED` / `TIMEOUT`）。  
本版不强制自动点掉全部弹窗；能观察即过，弹窗导致无法 lease 则失败。

其它 playbook：返回 `failed` / `UNSUPPORTED_PLAYBOOK`（不崩溃）。

## 5. 任务流

```
Ops/API POST /api/v1/tasks
  → CP MQTT publish devices/redmi-note-12/commands
  → Agent MqttCommandClient onMessage
  → TaskCommandDispatcher (dedupe task_id)
  → EnsureAppReadyPlaybook
  → TaskEventReporter POST .../events { task_id, status }
  → CP 更新 task succeeded|failed
```

MQTT 信封字段对齐 CP 下发（含 `task_id`, `playbook`, `account_id`, …）。未知字段忽略。

## 6. 非目标

- TLS / 命令签名  
- MQTT `events` 主题上报  
- `publish_note` 及其它副作用 playbook  
- release 联网  
- 可配置 URL UI  
- 改变 main 清单网络策略  

## 7. 真机验证清单

1. Docker 构建 `assembleDebug`，无线 ADB 安装  
2. 用户手动开启无障碍  
3. Ops Devices：`redmi-note-12` 的 `last_seen_at` 刷新  
4. `POST /api/v1/tasks` `ensure_app_ready@1.0`（账号 `phone-publisher-1`）  
5. 任务变为 `succeeded`（XHS 已登录可进前台）或带明确 `error_code` 的 `failed`  
6. 重复同一 `task_id` 不二次执行  

## 8. 测试（工程）

- 单元：topic、dispatcher 去重、playbook 在 Fake controller 上的分支  
- 不要求本设计阶段上 Robolectric 全量 MQTT；真机为验收主路径  

---

## 自我审查

- 无 TBD；OPS_TOKEN 嵌入 debug 的权衡已写明。  
- 与 CP 现网 `/events` = `require_ops` 一致，避免实现后 401。  
- 范围仅 debug + 单一 playbook，与「固定当前服务器」决策一致。  

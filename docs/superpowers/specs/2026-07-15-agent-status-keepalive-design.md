# Agent 状态页：熄屏保活 + 云端身份 + 连接健康

Date: 2026-07-15  
Status: approved for implementation

## Goal

把 Debug Agent 的 `AgentStatusActivity` 从「诊断工具箱」收成现场可用的**设备健康首页**，并配套无线 ADB 排查脚本。运维站在手机旁应能立刻回答：这是哪台设备、连没连上云端、能不能熄屏唤醒、无障碍/IME 是否就绪。

## Non-goals

- 不把 MQTT 不通标成总连接失败（HTTP 心跳/拉令为主通道）
- 不强制修改系统锁屏；有密码仅提示，不尝试破解
- 不在 App 内伪造「厂商自启动已开启」（HyperOS 无可靠 API）
- 不向 Release 变体迁入云桥密钥或 MQTT
- 不做复杂 Dashboard / 图表 / 多 Tab

## App UI (top → bottom)

1. **身份卡**
   - 云端 `display_name`（无则显示「未命名」）
   - `device_id`（可复制）
   - 绑定账号：`display_name` / `account_id` / 角色 / `active|paused`（未绑定则明示）
   - 版本、型号摘要、地区 `geo_label`（有则显示）
2. **连接卡**
   - 云桥 FGS：运行中 / 未运行（可一键启动）
   - 最近心跳：成功时间或失败摘要；据此判定「服务器可达」
   - 控制面 host（`CONTROL_PLANE_BASE_URL`）
   - HTTP 拉令：最近成功/失败（次要一行）
   - MQTT：通/不通（标注「非主通道」）
   - 当前网络：Wi‑Fi / 蜂窝（来自本地探测）
3. **熄屏保活检查卡**（失败项红字 +「去设置」）
   - 无障碍已绑定
   - Loanagent IME 已启用且为当前输入法
   - 已忽略电池优化
   - 锁屏无密码（`!isKeyguardSecure`）
   - Debug 云桥 FGS 在跑
   - 全过：绿色「熄屏保活检查通过」
   - 灰色说明：厂商自启动/极端省电请用运维脚本核对
4. **执行就绪一行**：屏幕亮/灭、锁屏类型、小红书是否安装
5. **高级诊断**（默认折叠）：现有 click / setText / swipe / OCR 等测试控件

`onResume` 与约 2–3s 定时刷新；「刷新」按钮强制重算。

## Control-plane

`POST /api/v1/devices/{id}/heartbeat` 已返回设备 `asdict(record)`（含 `display_name`、`geo_label` 等）。

**增量**：响应增加可选字段：

```json
"bound_account": {
  "account_id": "...",
  "display_name": "...",
  "role": "PUBLISHER_MAIN",
  "status": "active"
} | null
```

查找：按 `device_id` 查 `accounts`；无绑定则为 `null`。不改变心跳鉴权与超时语义。

## Android bridge status

Debug 侧维护进程内 `CloudBridgeStatus` 快照（线程安全）：

- 最近心跳成功/失败时刻、错误摘要、解析到的 `display_name` / `bound_account` / `geo_label`
- FGS / coordinator 是否已 start
- 最近 HTTP poll / MQTT 连接结果（能拿到则更新）

`HeartbeatClient.send` 改为解析 2xx JSON body（失败仍记失败）。  
Main 源通过现有反射/门面读取快照；Release 显示「本构建无云桥」。

## Keep-alive checker

`KeepAliveHealthChecker`（main，可单测）：输入能力接口（电池优化、锁屏安全、无障碍、IME、FGS、XHS 安装），输出 `List<KeepAliveIssue>`（code、message、settingsAction）。

## Ops script

`ops/m0/keep-alive-screen-check.sh`：

- 入参：`SERIAL`（默认 `192.168.10.17:40663`）、可选云端探测
- Docker `android-builder` + adb：连通、包、无障碍、IME、FGS、`dumpsys power`/`deviceidle`、锁屏相关
- 可选：熄屏后等待并查云端 `online`（需 `OPS_TOKEN` + `device_id`）
- 输出 PASS/FAIL checklist

## Testing

- CP：心跳返回 `bound_account` 绑定/未绑定用例
- Android：`KeepAliveHealthChecker` 策略单测；`CloudBridgeStatus` / 心跳 JSON 解析单测（testDebug）
- `AgentStatusActivity`：至少验证告警区/折叠区存在（边界测试可轻量）
- 脚本：`bash -n`；Compose 内对假 serial 的用法写在脚本头注释

## Auth risk (unchanged)

共享 `DEVICE_TOKEN`、Debug 硬编码凭据仍为已知风险；本改动不扩大到 Release。

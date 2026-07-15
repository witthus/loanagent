# 运维台：删除未绑定离线设备

Date: 2026-07-16  
Status: approved for implementation

## Goal

运维人员可在设备页「待绑定设备」中，硬删除确认废弃的测试机 / 错包残留记录，避免列表长期堆积。

## Decisions

| 项 | 选择 |
|----|------|
| 语义 | 硬删除（从 `devices` 表移除） |
| 资格 | 仅**未绑定**且**离线** |
| 关联数据 | 删除该 `device_id` 下的 `tasks`；`device_agent_upgrades` 依赖现有 `ON DELETE CASCADE` |
| API 形态 | `DELETE /api/v1/devices/{device_id}`（ops token） |
| UI | 待绑定表行内「删除」；仅离线行可点；`window.confirm` |

## Non-goals

- 软废弃 / 恢复
- 批量一键清空
- 删除已绑定设备（须先解绑）
- 删除在线未绑定设备（须先停 Agent 等其离线）
- 改 enrollment_tokens（无 FK，不阻塞删除）

## Control-plane

### Endpoint

`DELETE /api/v1/devices/{device_id}`  
Auth: `require_ops`  
Success: `200` `{ "ok": true, "device_id": "..." }`

### Repository rules (single transaction)

1. `mark_stale_offline()`（与 list 一致，避免假在线挡删除）
2. 设备不存在 → `DeviceNotFoundError` → **404** `DEVICE_NOT_FOUND`
3. 任一 `accounts.device_id` 指向该设备 → `DeviceBoundError` → **409** `DEVICE_BOUND`
4. `devices.online = TRUE` → `DeviceStillOnlineError` → **409** `DEVICE_STILL_ONLINE`
5. `DELETE FROM tasks WHERE device_id = %s`
6. `DELETE FROM devices WHERE device_id = %s`

### Error body

与账号 API 一致：`detail: { "code", "message" }`。

## Ops-web

在「待绑定设备」表增加操作列：

- 离线：红色「删除」按钮 → confirm「删除设备「{name}」（{device_id}）？将移除该设备及关联任务，不可恢复。」→ `DELETE` → 刷新列表
- 在线：不展示删除（或禁用 + 提示先离线）；创建/绑定流程不变

## Tests

- 离线未绑定可删；设备与 tasks 消失
- 在线未绑定 → 409 `DEVICE_STILL_ONLINE`
- 已绑定 → 409 `DEVICE_BOUND`
- 不存在 → 404
- 无 ops token → 401/403（与现有一致）

## Success criteria

运维可在 UI 清掉离线待绑定垃圾设备；在线或已绑定设备无法误删。

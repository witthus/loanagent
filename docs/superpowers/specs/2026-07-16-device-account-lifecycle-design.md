# 设备 / 账号生命周期与僵尸升级清理

Date: 2026-07-16  
Status: approved — implementing

## Goal

运维可处理：僵尸 pending 升级、永久离线未绑定设备、账号换绑；避免列表长期脏数据。

## Decisions

| 场景 | 方案 |
|------|------|
| 僵尸 pending（Agent 已升到目标版，或推错 id） | Ops `DELETE …/upgrade` 取消；心跳若版本已覆盖目标则自动 `succeeded` |
| 未绑定离线设备 | 既有删除；删除时 CASCADE upgrades（已有 FK） |
| 账号换机 | `POST /api/v1/accounts/{id}/rebind`：原子解绑旧设备意图 + 绑新在线设备 |
| 已绑定永久离线 | 须先解绑再删（不变） |
| UI | 升级页「取消」；设备/账号「换绑」；删除提示更明确 |

## Non-goals

- 自动物理销毁手机
- 删除已绑定在线设备
- 合并 Agent `dev-…` 与 DPC enrolled id（另案）

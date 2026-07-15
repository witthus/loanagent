# Agent 状态页与熄屏保活 — Implementation Plan

> **For agentic workers:** 按任务顺序执行；每任务先测后改；全部完成前不要停。

**Spec:** `docs/superpowers/specs/2026-07-15-agent-status-keepalive-design.md`

## Task 1: CP heartbeat `bound_account`

**Files:** `control-plane/src/loanagent/main.py`, `accounts.py` (lookup helper), `tests/test_devices_accounts.py`

- Add `find_by_device_id` (or reuse list filter) on AccountRepository
- Heartbeat response: `{**asdict(record), "bound_account": ...}`
- Tests: bound + unbound

## Task 2: CloudBridgeStatus + heartbeat parse

**Files:** debug `CloudBridgeStatus.kt`, `CloudHttpClients.kt`, `CloudBridgeCoordinator.kt`, `M0DebugKeepAliveService.kt`; testDebug tests

## Task 3: KeepAliveHealthChecker

**Files:** main `KeepAliveHealthChecker.kt`, `KeepAliveHealthCheckerTest.kt`

## Task 4: AgentStatusActivity refactor

**Files:** `AgentStatusActivity.kt` (+ optional small UI helpers)

## Task 5: Ops script

**Files:** `ops/m0/keep-alive-screen-check.sh`

## Task 6: Verify in Docker

- `pytest` devices/accounts + related
- Android unit tests for new classes
- `bash -n` script

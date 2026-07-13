# Agent Cloud Bridge (Debug) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Debug Agent on `redmi-note-12` heartbeats to `119.45.36.208`, receives MQTT `ensure_app_ready@1.0`, reports task result via HTTP events.

**Architecture:** Debug-only `CloudBridgeService` (extend keep-alive FGS): OkHttp/HttpURLConnection heartbeat + Paho MQTT subscribe; dispatcher runs `EnsureAppReadyPlaybook` against `M0AccessibilityService`; events POST with Ops Bearer (matches CP).

**Tech Stack:** Kotlin, Paho MQTT, HttpURLConnection (no new OkHttp if lockfile painful), Docker Gradle, wireless ADB.

**Spec:** `docs/superpowers/specs/2026-07-12-agent-cloud-bridge-design.md`

---

## File map

| File | Responsibility |
| --- | --- |
| `android/agent/src/debug/.../CloudBridgeConfig.kt` | Fixed URLs/tokens/device_id |
| `android/agent/src/debug/.../HeartbeatClient.kt` | POST heartbeat |
| `android/agent/src/debug/.../TaskEventReporter.kt` | POST events |
| `android/agent/src/debug/.../MqttCommandClient.kt` | Subscribe commands |
| `android/agent/src/debug/.../EnsureAppReadyPlaybook.kt` | Readiness checks |
| `android/agent/src/debug/.../TaskCommandDispatcher.kt` | Dedupe + dispatch |
| `android/agent/src/debug/.../CloudBridgeCoordinator.kt` | Wire heartbeat+mqtt+dispatch loops |
| Modify `M0DebugKeepAliveService.kt` | Start coordinator when FGS starts |
| Modify debug `AndroidManifest.xml` | INTERNET (+ NETWORK_STATE) |
| Modify `build.gradle.kts` + lockfile | Paho dependency |
| `android/agent/src/testDebug/...` | Unit tests for dispatcher/playbook |

Tokens (server `.env`):  
`DEVICE_TOKEN=cb571ab15f2f873f0fbbb533b16a70a5`  
`OPS_TOKEN=0e8fb81845ddbdcf39f214e5864329a2`

---

### Task 1: Config + HTTP clients + tests

- [ ] CloudBridgeConfig constants
- [ ] HeartbeatClient + TaskEventReporter (HttpURLConnection)
- [ ] Unit tests with mock/fake HTTP or pure URL building
- [ ] Debug INTERNET permission

### Task 2: EnsureAppReady + Dispatcher

- [ ] EnsureAppReadyPlaybook with injectable controller facade
- [ ] TaskCommandDispatcher dedupe by task_id
- [ ] Unit tests with Fake controller

### Task 3: MQTT + KeepAlive wiring

- [ ] Add Paho to gradle (update lockfile via Docker)
- [ ] MqttCommandClient
- [ ] CloudBridgeCoordinator started from M0DebugKeepAliveService
- [ ] Accessibility already starts KeepAlive in debug

### Task 4: Build, install, device verify

- [ ] `assembleDebug` in Docker
- [ ] Reconnect wireless ADB; install
- [ ] Enable a11y; verify heartbeat on Ops
- [ ] Dispatch ensure_app_ready; verify succeeded/failed

---

## Docker commands

```bash
docker compose -f infra/compose.yaml --profile android run --rm android-builder \
  ./gradlew :agent:testDebugUnitTest :agent:assembleDebug --no-daemon ...
```

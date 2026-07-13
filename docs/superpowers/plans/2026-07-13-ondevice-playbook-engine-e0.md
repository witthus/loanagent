# On-device Playbook Engine (E0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land PlaybookEngine in `main`, move `ensure_app_ready` onto it, and wire debug CloudBridge so the existing cloud round-trip still succeeds.

**Architecture:** Dual-track (design C): engine + ledger + registry live in `main` with no network deps; debug `TaskCommandDispatcher` parses JSON and calls `PlaybookEngine.run`. E0 keeps `PlaybookRuntime` minimal (ensure-only surface); expand in E1+.

**Tech Stack:** Kotlin, Robolectric unit tests, Docker `android-builder` Gradle.

**Spec:** `docs/superpowers/specs/2026-07-13-ondevice-playbook-engine-design.md`

---

## File map (E0)

| File | Responsibility |
| --- | --- |
| `android/agent/src/main/kotlin/.../PlaybookModels.kt` | `PlaybookCommand`, `PlaybookResult`, `EffectClass` |
| `android/agent/src/main/kotlin/.../PlaybookRuntime.kt` | Minimal runtime interface (ensure surface) |
| `android/agent/src/main/kotlin/.../EffectLedger.kt` | In-memory + SharedPreferences ledger |
| `android/agent/src/main/kotlin/.../PlaybookRegistry.kt` | playbook base name → Playbook |
| `android/agent/src/main/kotlin/.../PlaybookEngine.kt` | Serial run, dedupe, busy, dispatch |
| `android/agent/src/main/kotlin/.../EnsureAppReadyPlaybook.kt` | Moved from debug |
| `android/agent/src/test/kotlin/.../PlaybookEngineTest.kt` | Engine/dedupe/ensure unit tests |
| Modify `TaskCommandDispatcher.kt` | Delegate to engine |
| Modify `CloudBridgeCoordinator.kt` | Build engine + AccessibilityPlaybookRuntime |
| Modify `CloudBridgeLogicTest.kt` | Use engine-backed dispatcher |
| Delete debug `EnsureAppReadyPlaybook.kt` after move | Avoid duplicate |

---

### Task 1: Models + Engine + Ensure (TDD)

- [ ] Failing tests for: unsupported playbook, ensure success/fail, same `task_id` not re-executed, busy returns `BUSY`
- [ ] Implement models, in-memory ledger, registry, engine, ensure playbook in `main`
- [ ] Tests pass via Docker Gradle

### Task 2: Bridge wiring

- [ ] `AccessibilityPlaybookRuntime` (can live in debug or main adapting `M0AccessibilityService`)
- [ ] Dispatcher: parse JSON → `PlaybookCommand` → `engine.run` → report events
- [ ] Update debug unit tests
- [ ] `assembleDebug` + optional device `ensure_app_ready` smoke

### Task 3: Spec status

- [ ] Mark design doc status approved / E0 in progress
- [ ] Do not commit unless user asks

---

## Docker commands

```bash
docker compose -f infra/compose.yaml --profile android run --rm android-builder \
  ./gradlew :agent:test :agent:testDebugUnitTest :agent:assembleDebug \
  --no-daemon --project-cache-dir /home/gradle/work/project-cache \
  --dependency-verification=strict
```

# Publish Note Stabilize — Design

## Problem

`publish_note` is slow (~2–3+ min on failure paths) and flaky. Latest `.17` failure (`SET_TEXT_FAILED`) left the editor already showing the correct title/body — fill retries timed out without accepting “already correct”.

## Pipeline (current)

```
CP create task
  → device MQTT/HTTP accept
  → MediaBridge download + MediaStore insert   (network; variable)
  → ensureForeground + resetToHomeFeed        (~3–6s sleeps)
  → open publish tab + 从相册选择             (~3–7s)
  → pick grid cell + wizard 下一步×N          (~3–12s)
  → fillPublishField(title) then (body)       (worst: 30–90s hint retries)
  → click 发布笔记
```

Dominant pain: **fill** (hint timeouts + focus loops before nth EditText) and **fixed sleeps** in nav/album.

## Changes (this round)

1. **Already-correct short-circuit** — if `fieldLooksFilled`, succeed without `setText`.
2. **Prefer nth EditText first** — tap index 0/1, short className/label setText; hints only as quick fallback.
3. **`PublishFill` logcat** — each attempt: strategy, selector, ms, ok — Wi‑Fi ADB tuning.
4. **Tighten obvious waits** — drop duplicate album sleep; shorten post-fill sleeps; keep sheet/wizard polls.

## Non-goals

- MQTT 11883 connectivity on `.17` (ops follow-up).
- Redesigning MediaBridge / album geometry.
- Changing CP quota/network gates.

## Success

- Autofill/draft matching params → publish without multi-second hint burn.
- Empty editor → fill via nth EditText in one short path.
- `adb logcat -s PublishFill:I` shows winning strategy for further timeout cuts.

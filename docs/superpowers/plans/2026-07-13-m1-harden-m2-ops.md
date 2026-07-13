# M1 Harden → M2a/b/c Implementation Plan

> **For agentic workers:** Execute sequentially. Prefer Docker for all tests. Do not ask the user for permission on test/debug commands. Do not git commit unless the user explicitly asks.

**Goal:** Close M1-Cloud operational gaps, then ship M2a (content/media/schedule + Media Bridge), then on-device `post_comment` + M2b engagement chain, then M2c inbox/lead desk.

**Status (2026-07-13 overnight):** Phase A–D **implemented with unit/API tests green**. Live device E2E for M2a/b/c and server redeploy of CP **not** fully re-smoked in this pass — verify on return.

**Architecture:** Extend FastAPI control-plane + Postgres; keep debug Cloud Bridge as device channel (HTTP poll primary). Media via local MEDIA_ROOT + HMAC signed URLs (no boto3); Agent Media Bridge downloads into MediaStore before `publish_note`. Engagement is a CP state machine creating Tasks; inbox desk is ingest + human `reply_dm` dispatch.

---

## Phase checklist

### Phase A — M1 Harden — DONE
- [x] A1 Stale online sweeper (90s)
- [x] A2 GET/PATCH devices + Ops columns
- [x] A3 Dispatch gate a11y_bound
- [x] A4 Poll marks executing
- [x] A5 Events auth device OR ops
- [x] A6 `.env.example` tokens + `ops/m1/harden-notes.md`

### Phase B — M2a — DONE (API/unit)
- [x] B1 Schema v12 content/media/schedule
- [x] B2 Sensitive word scanner
- [x] B3 Content/Media APIs + signed download
- [x] B4 Schedule + immediate publish → publish_note
- [x] B5 Agent Media Bridge (debug)
- [x] B6 Ops `/ops/content` list
- [ ] B7 Live device E2E upload→publish effect_committed (pending return)

### Phase C — M2b — DONE (API/unit)
- [x] C1 Agent `post_comment` playbook
- [x] C2–C4 EngagementChain + alerts + APIs
- [ ] C5 Live device/cloud smoke (pending return)

### Phase D — M2c — DONE (API/unit)
- [x] D1–D4 Inbox/leads/sync/reply + contact reject
- [x] Ops `/ops/inbox`
- [ ] D5 Live smoke + matrix notes (pending return)

---

## Still open after overnight
1. Redeploy control-plane to `119.45.36.208` with new schema migrations
2. Live M2a publish with signed media URL on Redmi
3. Live engagement chain tick with real ENGAGER account
4. Live inbox sync → ingest → reply_dm
5. Migrate Agent event reporter to DEVICE_TOKEN (optional)
6. TLS / remove embedded OPS_TOKEN
7. Git commit/PR when user asks

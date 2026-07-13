# Overnight progress — 2026-07-13

Plan: `docs/superpowers/plans/2026-07-13-m1-harden-m2-ops.md`

## Done (code + unit/API tests)

### M1 Harden
- Stale heartbeat → offline (90s)
- `GET/PATCH /api/v1/devices/{id}`; Ops devices show a11y/wifi/cellular
- Task create requires `a11y_bound` (`409 A11Y_DOWN`)
- Command poll: `accepted` → `executing`
- Events: `X-Device-Token` **or** ops Bearer
- Notes: `ops/m1/harden-notes.md`; `.env.example` tokens

### M2a
- Schema v12: media / content / schedules
- Sensitivity gate; media HMAC download (local `MEDIA_ROOT`, no boto3)
- Immediate publish + schedule dispatch → `publish_note@1.0`
- Agent debug `MediaBridge` before engine
- Ops `/ops/content`

### M2b
- Agent `post_comment@1.0`
- Schema v13: engagement_chains + alerts
- Hook on publish success; advance → post_comment → reply_comment
- Ops APIs: chains / advance / alerts

### M2c
- Schema v14: inbox_threads / messages / leads
- ingest / sync / reply (contact reject) / leads
- Ops `/ops/inbox`

## Verified locally
- `:agent:testDebugUnitTest` BUILD SUCCESSFUL
- CP: m2a + m2b + m2c + tasks + devices + ops + roles + schemas + health **PASS**
- (android manifest path tests may fail in tools container if bind mounts incomplete — unrelated)

## Still for you on return
1. Live E2E: media upload → immediate publish on Redmi (reinstall Agent for MediaBridge/`post_comment`)
2. Live engagement chain with ENGAGER account + advance
3. Live inbox sync → ingest → reply_dm
4. Persist `MEDIA_ROOT` volume in `compose.server.yaml` if media should survive container recreate (currently env set; confirm bind mount)
5. Git commit/PR when you want
6. TLS / remove embedded OPS_TOKEN (still open)

## Live E2E (2026-07-13 morning)

| Step | Result |
| --- | --- |
| ensure_app_ready | succeeded |
| M2a media→content→immediate publish | **succeeded** `8b9f87c2-…` effect_committed=true（含 media_urls） |
| M2b engagement | chain created then **stopped** `NO_ENGAGER_AVAILABLE`（同机 1:1 无法再绑 ENGAGER） |
| M2c inbox_sync | succeeded |
| M2c CONTACT_FORBIDDEN | 400 as expected |
| M2c reply_dm | 页上成功路径另记；非私信面 SET_TEXT_FAILED 属预期 |

MEDIA_ROOT on server: `/tmp/loanagent-media`（可写；容器重建会丢文件，后续可再改持久卷权限）

## Ops Social W1–W4 (2026-07-13 midday)

Spec: `docs/superpowers/specs/2026-07-13-ops-publish-comments-inbox-design.md`

| Wave | Result |
| --- | --- |
| W1 events `result_payload` | Agent + CP ingest；`GET /api/v1/tasks/{id}` 补齐 |
| W2 `/ops/publish` | 真机 **succeeded** `b6f761eb-…` effect_committed=true；`published_notes` 入库 |
| W3 `/ops/comments` | UI+API 可用；端上非评论面 `WRONG_PAGE`；events 注入评论后 UI 可见并可下发 `reply_comment` |
| W4 inbox | `inbox_sync` 真机入库 13 threads；线程详情+回复表单；`reply_dm` 非会话面 `SET_TEXT_FAILED`/`WRONG_PAGE` 属预期 |

Agent APK staged: `ops/m0/generated/agent-m0-debug.apk` sha `f2ec8cc2…`；已装 `192.168.10.13:42367`  
Local tests: `test_ops_social` + task GET；`:agent:testDebugUnitTest` / assembleDebug OK  
CP deployed `119.45.36.208` schema v15


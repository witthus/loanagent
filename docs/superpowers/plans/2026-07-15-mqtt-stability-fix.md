# MQTT Stability Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status (2026-07-15 23:14 CST):** Task 1 **P0 完成**；Task 3 **P1 客户端 keepalive 已改并单测通过**。对照最新工作区：升级/HTTPS 改动未触及 MQTT 客户端，方案无需改方向。剩余 **Task 4 真机验收**；Task 2 仍可选。

**Goal:** Restore reachable, stay-alive MQTT command delivery from EMQX to debug Android agents on `119.45.36.208:11883`, without treating MQTT as the primary channel.

**Architecture:** Two independent failure layers. (1) Tencent Cloud security group blocked inbound TCP `11883` before packets reached the VM — **fixed in console (Task 1 done)**. (2) Android `MqttCommandClient` declares MQTT keepalive=30s but only PINGREQs after 45s idle, so the broker kicks the session at keepalive×1.5 — **still open (Task 3)**. Keep HTTP poll as primary.

**Tech Stack:** Tencent Cloud security group (console), EMQX 5.8.6 (Compose), Kotlin hand-rolled MQTT 3.1.1 client, Paho on control-plane (unchanged).

## Global Constraints

- Do **not** make MQTT the primary device channel; HTTP heartbeat/poll/events remain primary.
- Prefer opening only **`11883/tcp`** publicly; do not rely on `1883` for devices (ISP blocks are common).
- No MQTT auth/TLS in this pass (existing debt); do not widen exposure beyond what devices need.
- All Android/CP code changes via existing Docker Gradle / compose test flows (no host SDK installs).
- Do not change task dispatch semantics (`accepted` + HTTP poll fallback + `task_id` dedupe).

## Root cause (confirmed on server after P0)

| Layer | Cause | Symptom | Status |
| --- | --- | --- | --- |
| **P0 网络** | 腾讯云安全组未放行入站 `TCP 11883`（Docker 已映射，EMQX 本机正常） | 外网 SYN 超时；设备根本连不上 broker | **已修复并复验** |
| **P1 客户端** | `CONNECT keepalive=30` 且 `soTimeout=45_000` 才发 PINGREQ；broker 在 `keepalive×1.5=45s` 踢线 | 偶发连上后约 45s 断开，3s 重连，状态页 MQTT 闪断 | **未修（下一优先）** |
| **P2 卫生** | 公网仍映射 `1883`/`18083`；clean session + 旋转 clientId | 暴露面偏大；断线无离线队列（HTTP poll 兜底） | 可选 |

**不是** EMQX 宕机、CP `MQTT_URL` 错误或 Docker 端口未映射：服务端本机 CONNACK、CP→`mqtt://emqx:1883` publish 在 P0 前后均正常。

## Evidence — before P0 (2026-07-15 ~21:35)

| Check | Result |
| --- | --- |
| `loanagent-emqx-1` | healthy, listener `0.0.0.0:1883` running |
| Host `127.0.0.1:1883` / `:11883` MQTT CONNECT | CONNACK success ~0ms |
| CP `MqttCommandBus` → `mqtt://emqx:1883` | `PUBLISH_OK` ~0.14s |
| check-host.net TCP `:80` | reachable |
| check-host.net TCP `:1883` / `:11883` | **Connection timed out** |
| Server `tcpdump eth0 port 11883` during Clash-path Mac connect | **0 packets**（本机 Clash TUN 会伪造 TCP） |
| Local reproduce `keepalive=30` + `soTimeout=45` | session ends at **45.1s** |

## Evidence — after P0 SG open (2026-07-15 22:37 CST, server login)

| Check | Result |
| --- | --- |
| check-host.net TCP `:11883` (CY/FI/IR/NL/US) | **all OK** (`time` ~0.21–0.34s) |
| External MQTT CONNECT → CONNACK | `20020000` in **~0.05s** |
| Server `tcpdump eth0 port 11883` | **real SYN/ACK + MQTT payload** (e.g. `117.154.66.22` ↔ `10.206.0.10:11883`) |
| iptables NAT `dpt:11883` | counters advancing (was 0 before) |
| Host local `127.0.0.1:11883` | still CONNACK `20020000` |
| EMQX `listeners` `shutdown_count` | `{keepalive_timeout,1},{tcp_closed,9}` ← **生产侧确认 P1** |
| EMQX `clients list` | still empty at snapshot（无常驻设备会话 / 或断线后 clean session） |

Root cause ranking (updated):

1. ~~**P0 — Cloud SG**~~ → **DONE**
2. **P1 — Client keepalive race** → **remaining cause of “不稳定/闪断”**
3. **P2 — Hygiene** → optional
---

## File map

| Path | Role |
| --- | --- |
| Tencent 安全组 (console, not in repo) | Allow inbound `11883/tcp` |
| `infra/compose.server.yaml` | Optional: stop publishing `1883`/`18083` publicly |
| `android/agent/src/debug/kotlin/.../MqttCommandClient.kt` | Keepalive / PING timing |
| `android/agent/src/testDebug/kotlin/.../` (new or existing) | Unit/integration test for ping interval < keepalive×1.5 |
| `ops/m0/` or `ops/m1/` smoke note | Post-fix verification commands |

---

### Task 1: Open cloud security group for device MQTT

**Status:** ✅ **Completed 2026-07-15** (operator opened inbound TCP 11883; verified on server).

**Files:**
- Modify: Tencent Cloud console → CVM `ins-owka7zvy` (`119.45.36.208`) → bound 安全组 inbound rules
- Optional doc touch: `ops/m1/` smoke note only if we already document ports there

**Interfaces:**
- Consumes: evidence that host EMQX already listens via Docker `0.0.0.0:11883->1883`
- Produces: Internet SYN/ACK to `119.45.36.208:11883` succeeds from third-party probes and phones

- [x] **Step 1: Confirm current SG inbound**

In Tencent console, record existing inbound allows (expect `22`, `80`, maybe others; expect **no** `11883`/`1883` before fix).

- [x] **Step 2: Add inbound rule**

| Protocol | Port | Source | Note |
| --- | --- | --- | --- |
| TCP | 11883 | `0.0.0.0/0` (or office/device egress CIDRs if known) | Device MQTT remap |

Do **not** open `18083` (dashboard) in this pass. Prefer **not** opening `1883` either (devices use 11883).

- [x] **Step 3: Verify from outside (not via Mac Clash TUN alone)**

Post-fix (2026-07-15 22:37):

- check-host.net `:11883` → 5/5 nodes OK
- External CONNACK `20020000` ~50ms
- `tcpdump eth0` shows real handshake + 26B CONNECT / 4B CONNACK
- Localhost CONNACK still OK

```bash
# Re-run anytime:
REQ=$(curl -sS 'https://check-host.net/check-tcp?host=119.45.36.208:11883&max_nodes=5' -H 'Accept: application/json')
RID=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["request_id"])' "$REQ")
sleep 8
curl -sS "https://check-host.net/check-result/$RID" -H 'Accept: application/json'
```

- [x] **Step 4: Stop — no repo commit for SG-only change**

Console-only; no git commit required for Task 1.

---

### Task 2: (Optional hardening) Stop advertising unused public MQTT/dashboard ports

**Files:**
- Modify: `infra/compose.server.yaml` (emqx `ports:`)

**Interfaces:**
- Consumes: Task 1 leaves `11883` open
- Produces: Host no longer listens on public `1883` / `18083`

Current:

```yaml
ports:
  - "1883:1883"
  - "11883:1883"
  - "18083:18083"
```

Target:

```yaml
ports:
  # Devices use 11883 (1883 often blocked by residential ISPs).
  - "11883:1883"
  # Keep broker loopback-only for CP via Docker network (mqtt://emqx:1883).
  # Do not publish 1883/18083 on 0.0.0.0 unless explicitly needed.
```

- [ ] **Step 1: Edit compose ports as above**

- [ ] **Step 2: Redeploy emqx only on server**

```bash
ssh -i ~/.ssh/id_ed25519_witt -o IdentitiesOnly=yes ubuntu@119.45.36.208 \
  'cd /opt/loanagent && sudo docker compose --env-file .env -f infra/compose.server.yaml up -d emqx && sudo docker compose --env-file .env -f infra/compose.server.yaml ps emqx'
```

Expected: `11883` published; `1883`/`18083` absent from `docker port loanagent-emqx-1`.

- [ ] **Step 3: Confirm CP publish still works**

```bash
ssh -i ~/.ssh/id_ed25519_witt -o IdentitiesOnly=yes ubuntu@119.45.36.208 \
  'sudo docker exec -e PYTHONPATH=/app/src loanagent-control-plane-1 python -c "from loanagent.mqtt_bus import MqttCommandBus as B; import os,time; B(os.environ[\"MQTT_URL\"]).publish(\"devices/diag/commands\",{\"task_id\":\"t\",\"schema_version\":1,\"playbook\":\"noop\"}); print(\"OK\")"'
```

Expected: `OK`.

- [ ] **Step 4: Commit (when executing)**

```bash
git add infra/compose.server.yaml
git commit -m "$(cat <<'EOF'
fix(infra): publish only MQTT 11883 on server EMQX

Stop exposing unused 1883/18083 on 0.0.0.0; devices already target 11883
and CP reaches EMQX on the Docker network.
EOF
)"
```

---

### Task 3: Fix Android MQTT keepalive / PING race

**Status:** ✅ **Implemented 2026-07-15** (unit tests green). Device soak = Task 4.

**Files:**
- Modify: `android/agent/src/debug/kotlin/com/loanagent/agent/MqttCommandClient.kt`
- Test: `android/agent/src/testDebug/kotlin/com/loanagent/agent/MqttKeepAliveTimingTest.kt`

**Interfaces:**
- Consumes: MQTT 3.1.1 rule — broker drops client if no packet within `keepalive × 1.5`
- Produces: client sends PINGREQ while idle **before** that deadline

Bug was:

```kotlin
sock.soTimeout = 45_000
// ...
data.writeShort(30) // keep alive
// on SocketTimeoutException -> PINGREQ
```

With keepalive=30, broker timeout = 45s; client first ping at 45s → race → disconnect.

Chosen fix:

| Constant | New value | Why |
| --- | --- | --- |
| CONNECT keepalive | `60` seconds | More headroom on flaky mobile networks |
| `soTimeout` (idle → PINGREQ) | `mqttSocketTimeoutMs(60)` → `20_000` ms | Ping at 20s ≪ 60×1.5=90s |
| reconnect sleep | keep `3_000` for this pass | Separate follow-up (backoff) |

- [x] **Step 1: Write failing test for ping-before-broker-timeout**
- [x] **Step 2: Run test — RED confirmed** (`KEEP_ALIVE_SEC` / timeout mismatch)
- [x] **Step 3: Implement** (`KEEP_ALIVE_SEC=60`, `mqttSocketTimeoutMs`, wire `soTimeout` + CONNECT)
- [x] **Step 4: Re-run unit tests** — `MqttKeepAliveTimingTest` green
- [x] **Step 5: Manual soak against local/prod EMQX** (Task 4)
- [ ] **Step 6: Commit** (only when operator requests a commit)
---

### Task 4: End-to-end verification on device + server

**Files:** none required (ops commands only)

- [x] **Step 1: Install/rebuild debug APK after Task 3** (Docker Gradle + adb install as usual) — Note 12 Turbo `dev-1f59cb3868f3d76a`, agent 0.1.5 / code 6, keepalive=60.

- [x] **Step 2: On device status page**

Expect MQTT line can flip to `已连接（非主通道）` and stay connected for ≥2 minutes without flapping.

Verified 2026-07-16 on `la-dev-1f59cb3868f3d76a-*`: 7 samples / ~2 min, `connected_at` stable, `keepalive=60` (screen stay-on). Screen-off may still drop the socket on HyperOS (separate from keepalive race).

- [x] **Step 3: On server**

```bash
ssh -i ~/.ssh/id_ed25519_witt -o IdentitiesOnly=yes ubuntu@119.45.36.208 \
  'sudo docker exec loanagent-emqx-1 /opt/emqx/bin/emqx ctl clients list'
```

Expected: at least one `la-<deviceId>-…` client while bridge FGS is running. Observed `la-dev-1f59cb3868f3d76a-…` with `keepalive=60`.

- [x] **Step 4: Dispatch a readonly task**

Create `ensure_app_ready@1.0` via ops; confirm device accepts via MQTT **or** HTTP poll (either OK). MQTT path is success if EMQX shows the subscribe and task starts before the 5s poll would.

Screen-off `ensure_app_ready` succeeded after HyperOS appops `10021/10020/10008 allow` + ScreenWake early-exit fix (`01af3ce2-…`).

- [x] **Step 5: Confirm HTTP still primary**

With MQTT deliberately stopped (force-stop bridge briefly), HTTP poll must still pick up `accepted` tasks.

Verified by host `iptables DROP` on `:11883`/`:1883` + EMQX kick (no package force-stop): task `588719be-…` → `succeeded` while device had no MQTT client; MQTT reconnected after rules removed.

---

### Task 5: Explicit non-goals / follow-ups (do not do in this change set)

- MQTT username/password, ACL, mTLS
- Persistent session (`clean session=0`) + durable clientId
- Exponential reconnect backoff + jitter (spec asked; defer)
- CP connection pooling / outbox worker
- Migrating events uplink to MQTT
- Fixing EMQX dashboard on `18083` (not required for command path)

---

## Execution order (after approval)

1. ~~**Task 1** first — without SG open, device work is wasted.~~ **DONE**
2. Task 2 optional (can ship with remaining work).
3. **Task 3 next** — prevents “connected but flapping” / clears future `keepalive_timeout` on EMQX.
4. **Task 4** gate before calling done.

## Next action

~~Implement **Task 3**~~ **DONE (unit)**. Remaining: **Task 4** device E2E (install debug APK, confirm `emqx ctl clients list` holds ≥2 minutes without new `keepalive_timeout`). Task 2 (shrink public EMQX ports) still optional.

## Self-review

- Spec coverage: public reachability + client keepalive race both have tasks; HTTP-primary preserved.
- No placeholders in steps; commands and constants concrete.
- Mac Clash TUN documented so future probes use check-host / phone / server tcpdump, not raw Mac sockets through `utun8`.

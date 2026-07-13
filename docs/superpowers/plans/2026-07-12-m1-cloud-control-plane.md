# M1-Cloud Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver M1-Cloud from `2026-07-12-xhs-cloud-management-design.md`: Device/Account registry with roles, heartbeat → online, MQTT readonly task round-trip, thin Ops auth, and a minimal Web console showing devices/accounts/tasks.

**Architecture:** Extend the existing FastAPI `control-plane` monolith and Postgres migrations pattern used by `EnrollmentRepository`. Add domain packages under `control-plane/src/loanagent/` (devices, accounts, tasks, auth, mqtt). Serve a small Jinja2 Ops UI from the same app. MQTT uses the Compose `emqx` broker already wired as `MQTT_URL`.

**Tech Stack:** Python 3.12, FastAPI, psycopg3, pytest, httpx, Jinja2, aiomqtt (or paho-mqtt), Docker Compose (`infra/compose.yaml`).

**Spec:** `docs/superpowers/specs/2026-07-12-xhs-cloud-management-design.md` §1.1 / §11 M1-Cloud only.  
**Out of scope for this plan:** Content library, schedules, engagement chains, inbox/lead desk, media signing (M2a–M2c — separate plans).

**Docker test command (all tasks):**

```bash
docker compose -f infra/compose.yaml --profile tools run --rm control-plane-tools \
  uv run --frozen pytest tests/<file> -q
```

---

## File map

| File | Responsibility |
| --- | --- |
| `control-plane/src/loanagent/roles.py` | Account role enum + playbook allow checks |
| `control-plane/src/loanagent/db.py` | Shared migrate runner / advisory lock helper |
| `control-plane/src/loanagent/devices.py` | Device model + repository + heartbeat |
| `control-plane/src/loanagent/accounts.py` | Account model + repository + bind/pause |
| `control-plane/src/loanagent/tasks.py` | Task persistence + status transitions |
| `control-plane/src/loanagent/mqtt_bus.py` | Publish command / optional test subscriber helper |
| `control-plane/src/loanagent/auth.py` | Shared ops token / dependency |
| `control-plane/src/loanagent/main.py` | Wire routes + lifespan |
| `control-plane/src/loanagent/web/` | Jinja templates + HTML routes |
| `control-plane/tests/test_roles.py` | Role gate unit tests |
| `control-plane/tests/test_devices_accounts.py` | Registry API tests |
| `control-plane/tests/test_tasks_mqtt.py` | Task create + MQTT publish contract tests |
| `control-plane/tests/test_ops_web.py` | Login + page smoke |
| `control-plane/pyproject.toml` | Add jinja2 + mqtt client deps |
| `infra/compose.yaml` | Pass `OPS_TOKEN` env if needed |

---

### Task 1: Role enum and publish gate

**Files:**
- Create: `control-plane/src/loanagent/roles.py`
- Create: `control-plane/tests/test_roles.py`

- [ ] **Step 1: Write failing tests**

```python
# control-plane/tests/test_roles.py
from loanagent.roles import AccountRole, playbook_allowed_for_role

def test_engager_cannot_publish_note() -> None:
    assert playbook_allowed_for_role(AccountRole.ENGAGER, "publish_note@1.0") is False

def test_publisher_main_can_publish_note() -> None:
    assert playbook_allowed_for_role(AccountRole.PUBLISHER_MAIN, "publish_note@1.0") is True

def test_engager_can_post_comment() -> None:
    assert playbook_allowed_for_role(AccountRole.ENGAGER, "post_comment@1.0") is True
```

- [ ] **Step 2: Run test — expect FAIL (import/module missing)**

```bash
docker compose -f infra/compose.yaml --profile tools run --rm control-plane-tools \
  uv run --frozen pytest tests/test_roles.py -q
```

Expected: FAIL / collection error.

- [ ] **Step 3: Implement**

```python
# control-plane/src/loanagent/roles.py
from __future__ import annotations

from enum import StrEnum

class AccountRole(StrEnum):
    PUBLISHER_MAIN = "PUBLISHER_MAIN"
    PUBLISHER_MATRIX = "PUBLISHER_MATRIX"
    ENGAGER = "ENGAGER"

_PUBLISHERS = {AccountRole.PUBLISHER_MAIN, AccountRole.PUBLISHER_MATRIX}

# playbook name before @version
_ALLOW: dict[AccountRole, frozenset[str]] = {
    AccountRole.PUBLISHER_MAIN: frozenset({
        "ensure_app_ready", "publish_note", "read_comments", "post_comment",
        "reply_comment", "inbox_sync", "inbox_open_thread", "reply_dm",
        "dismiss_interruptions",
    }),
    AccountRole.PUBLISHER_MATRIX: frozenset({
        "ensure_app_ready", "publish_note", "read_comments", "post_comment",
        "reply_comment", "inbox_sync", "inbox_open_thread", "reply_dm",
        "dismiss_interruptions",
    }),
    AccountRole.ENGAGER: frozenset({
        "ensure_app_ready", "read_comments", "post_comment",
        "inbox_sync", "inbox_open_thread", "reply_dm", "dismiss_interruptions",
    }),
}

def playbook_base(playbook: str) -> str:
    return playbook.split("@", 1)[0]

def playbook_allowed_for_role(role: AccountRole, playbook: str) -> bool:
    return playbook_base(playbook) in _ALLOW[role]

def is_publisher(role: AccountRole) -> bool:
    return role in _PUBLISHERS
```

- [ ] **Step 4: Run tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/loanagent/roles.py control-plane/tests/test_roles.py
git commit -m "feat(cp): add account role playbook gates for M1"
```

---

### Task 2: Devices + Accounts schema and repositories

**Files:**
- Create: `control-plane/src/loanagent/db.py`
- Create: `control-plane/src/loanagent/devices.py`
- Create: `control-plane/src/loanagent/accounts.py`
- Create: `control-plane/tests/test_devices_accounts.py`
- Modify: `control-plane/src/loanagent/main.py` (wire migrate in lifespan after enrollment migrate, or call unified migrate)

Follow `EnrollmentRepository.migrate()` style: `loanagent_schema_migrations` versions **10+** for fleet tables (leave 1–2 for enrollment).

- [ ] **Step 1: Write failing repository/API tests** using FastAPI `TestClient` + real Postgres from `DATABASE_URL` (Compose tools service already has it). Pattern from `tests/test_enrollment.py`.

Cover:
- create device, heartbeat sets `online` + `last_seen_at`
- create account with role, bind to device (1:1)
- second account cannot bind same device
- ENGAGER `daily_publish_quota` defaults to 0; PUBLISHER_MAIN defaults to 1

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement tables**

```sql
-- migration version 10
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  agent_version TEXT,
  manufacturer TEXT,
  model TEXT,
  online BOOLEAN NOT NULL DEFAULT FALSE,
  last_seen_at TIMESTAMPTZ,
  wifi_connected BOOLEAN,
  a11y_bound BOOLEAN,
  cellular_ok BOOLEAN,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS accounts (
  account_id TEXT PRIMARY KEY,
  role TEXT NOT NULL CHECK (role IN ('PUBLISHER_MAIN','PUBLISHER_MATRIX','ENGAGER')),
  device_id TEXT UNIQUE REFERENCES devices(device_id),
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','paused','blocked','needs_login')),
  network_policy TEXT NOT NULL DEFAULT 'cellular_only',
  daily_publish_quota INT NOT NULL DEFAULT 0,
  inbox_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  display_name TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Default quotas in application layer on insert: publishers → 1 (or 2 for matrix), engager → 0; `inbox_sync_enabled` true for publishers.

- [ ] **Step 4: REST endpoints**

| Method | Path |
| --- | --- |
| POST | `/api/v1/devices/{device_id}/heartbeat` |
| GET | `/api/v1/devices` |
| POST | `/api/v1/accounts` |
| GET | `/api/v1/accounts` |
| PATCH | `/api/v1/accounts/{account_id}` |
| POST | `/api/v1/accounts/{account_id}/pause` |
| POST | `/api/v1/accounts/{account_id}/resume` |

Heartbeat body (JSON): `agent_version`, `wifi_connected`, `a11y_bound`, `cellular_ok` (booleans optional). Upserts device row.

Protect mutating ops routes with Ops auth (Task 4) — for Task 2 tests, either inject dependency override or set `OPS_TOKEN` in fixture.

- [ ] **Step 5: Tests PASS + commit**

```bash
git commit -m "feat(cp): device and account registry with roles"
```

---

### Task 3: Task store + readonly dispatch stub

**Files:**
- Create: `control-plane/src/loanagent/tasks.py`
- Create: `control-plane/src/loanagent/mqtt_bus.py`
- Create: `control-plane/tests/test_tasks_mqtt.py`
- Modify: `control-plane/pyproject.toml` (add `aiomqtt` or `paho-mqtt`)
- Run `uv lock` inside control-plane container after dep change

- [ ] **Step 1: Failing tests**

1. Creating `ensure_app_ready@1.0` for a bound PUBLISHER account inserts task `queued` then `dispatched`.  
2. Creating `publish_note@1.0` for ENGAGER raises 403.  
3. MQTT publish called with topic `devices/{deviceId}/commands` and payload containing `task_id` (mock bus in unit test).  
4. Idempotent: duplicate `task_id` insert rejected or no-op.

- [ ] **Step 2: Schema migration version 11**

```sql
CREATE TABLE IF NOT EXISTS tasks (
  task_id TEXT PRIMARY KEY,
  operation_id TEXT NOT NULL,
  device_id TEXT NOT NULL REFERENCES devices(device_id),
  account_id TEXT NOT NULL REFERENCES accounts(account_id),
  playbook TEXT NOT NULL,
  params JSONB NOT NULL DEFAULT '{}',
  effect_class TEXT NOT NULL,
  effect_committed BOOLEAN NOT NULL DEFAULT FALSE,
  status TEXT NOT NULL,
  reconcile_required BOOLEAN NOT NULL DEFAULT FALSE,
  priority INT NOT NULL DEFAULT 100,
  timeout_sec INT NOT NULL DEFAULT 120,
  source TEXT NOT NULL DEFAULT 'ops_web',
  error_code TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

- [ ] **Step 3: Implement `TaskService.create_and_dispatch`**

- Resolve `account_id` → `device_id` + `role`  
- `playbook_allowed_for_role`  
- If account `paused`/`blocked`/`needs_login` or device `online` is false → 409  
- Persist task; publish MQTT command envelope (minimal: `{schema_version, task_id, playbook, params, effect_class, ...}` aligned with task.schema required fields)  
- Update status `dispatched`

For M1, **receiving** device events can be a separate worker stub: `POST /api/v1/devices/{id}/events` test hook that marks task `succeeded` / `effect_committed` for readonly playbooks — enough for console demo without Android MQTT client yet. Document that production path is MQTT `events` topic subscriber (Task 3b optional if time).

- [ ] **Step 4: Endpoint `POST /api/v1/tasks`** body: `account_id`, `playbook`, `params`, `operation_id` optional (generate UUID).  
`GET /api/v1/tasks` list with filters.

- [ ] **Step 5: Tests PASS + commit**

```bash
git commit -m "feat(cp): task create with role gates and MQTT dispatch"
```

---

### Task 4: Thin Ops auth

**Files:**
- Create: `control-plane/src/loanagent/auth.py`
- Modify: `control-plane/src/loanagent/main.py`
- Modify: `infra/compose.yaml` (`OPS_TOKEN: ${OPS_TOKEN:-dev-only-ops-token}`)
- Modify: tests to pass `Authorization: Bearer …`

- [ ] **Step 1: Failing test** — GET `/api/v1/accounts` without header → 401; with correct bearer → 200.

- [ ] **Step 2: Implement**

```python
# auth.py
import os
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

_security = HTTPBearer(auto_error=False)

def require_ops(creds: HTTPAuthorizationCredentials | None = Depends(_security)) -> str:
    expected = os.environ.get("OPS_TOKEN", "")
    if not expected or creds is None or creds.credentials != expected:
        raise HTTPException(status_code=401, detail="unauthorized")
    return "ops"
```

Apply to `/api/v1/*` except `/health` and device heartbeat (device heartbeat may use device credential later; for M1 allow heartbeat with shared `DEVICE_HEARTBEAT_TOKEN` or same OPS_TOKEN documented as temporary).

**M1 decision (explicit):** Heartbeat uses header `X-Device-Token` equal to env `DEVICE_TOKEN` (default `dev-only-device-token`). Ops APIs use `OPS_TOKEN`.

- [ ] **Step 3: PASS + commit**

```bash
git commit -m "feat(cp): ops and device token auth for M1 APIs"
```

---

### Task 5: Minimal Ops Web

**Files:**
- Create: `control-plane/src/loanagent/web/templates/base.html`
- Create: `control-plane/src/loanagent/web/templates/login.html`
- Create: `control-plane/src/loanagent/web/templates/dashboard.html`
- Create: `control-plane/src/loanagent/web/templates/devices.html`
- Create: `control-plane/src/loanagent/web/templates/accounts.html`
- Create: `control-plane/src/loanagent/web/templates/tasks.html`
- Create: `control-plane/src/loanagent/web/routes.py`
- Create: `control-plane/tests/test_ops_web.py`
- Modify: `pyproject.toml` add `jinja2`
- Modify: `main.py` mount routes

- [ ] **Step 1: Failing smoke tests**

- GET `/ops/login` → 200  
- POST `/ops/login` with wrong token → 401 page  
- POST `/ops/login` with `OPS_TOKEN` → set cookie → redirect `/ops/`  
- GET `/ops/devices` with cookie → 200 and contains a seeded device_id  

- [ ] **Step 2: Implement cookie session** — signed cookie or raw token cookie `ops_session=OPS_TOKEN` over HTTP localhost only (document insecurity for prod). Pages call repositories server-side (no browser bearer).

- [ ] **Step 3: Pages show tables for devices, accounts (role/status), tasks (status/playbook). Link nav between them.

- [ ] **Step 4: PASS + commit**

```bash
git commit -m "feat(cp): minimal ops web for devices accounts tasks"
```

---

### Task 6: M1 acceptance script + docs

**Files:**
- Create: `ops/m1/smoke-m1-cloud.sh`
- Create: `ops/m1/README.md`
- Modify: `control-plane/README.md` — M1 endpoints + tokens  
- Modify: `docs/superpowers/specs/2026-07-12-xhs-cloud-management-design.md` status line → M1 in progress / note plan path

- [ ] **Step 1: Script** (Docker-only) that:

1. Waits for `control-plane` health  
2. Heartbeats a fixture `device_id=m1-smoke-device`  
3. Creates `PUBLISHER_MAIN` account bound to it  
4. Creates `ensure_app_ready@1.0` task  
5. Asserts task row `dispatched` or later  
6. Curl `/ops/login` flow optional  

- [ ] **Step 2: Run smoke against compose stack**

```bash
docker compose -f infra/compose.yaml up -d postgres emqx minio control-plane
bash ops/m1/smoke-m1-cloud.sh
```

Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git commit -m "docs(ops): add M1-Cloud smoke script and CP README"
```

---

## Spec coverage (M1 only)

| Spec item | Task |
| --- | --- |
| Device registry + heartbeat | T2 |
| Account + role 1:1 device | T2 |
| Role gate ENGAGER≠publish | T1, T3 |
| Task service + MQTT commands | T3 |
| Ops auth thin | T4 |
| Web devices/accounts/tasks | T5 |
| M1 exit: 1 device online + readonly task | T6 |
| Content/Schedule/Engagement/Inbox | **Deferred to M2 plans** |

## Placeholder scan

No TBD steps; MQTT event subscriber full production worker deferred with explicit test-hook alternative in Task 3.

---

## After this plan

1. Implement M1 via subagent-driven or inline execution.  
2. Write `docs/superpowers/plans/2026-07-12-m2a-publish-content.md` (and M2b/M2c) only after M1 smoke passes.

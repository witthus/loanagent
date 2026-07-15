# Control Plane

Minimal FastAPI control-plane package with health, M0 one-time Device Owner
enrollment, and M1 fleet/task dispatch endpoints. Enrollment tokens are hashed,
initialized at application startup, and atomically bound to a device in Postgres;
retries by that device are idempotent and other devices conflict. Postgres schema
changes run as transactional, advisory-locked, versioned migrations and can
upgrade the legacy unversioned enrollment table.

## Local Defaults

`infra/compose.yaml` exposes the API at `http://localhost:8000` and injects
development-only tokens:

- Ops API: `Authorization: Bearer dev-only-ops-token`
- Device heartbeat: `X-Device-Token: dev-only-device-token`

Override them with `OPS_TOKEN`, `DEVICE_TOKEN`, and `CONTROL_PLANE_URL` when
running smoke checks outside the default Compose environment.

## M1 Endpoints

- `GET /health` returns control-plane readiness after startup migrations.
- `POST /api/v1/devices/{device_id}/heartbeat` upserts the device and marks it
  online. Body fields are optional: `agent_version`, `wifi_connected`,
  `a11y_bound`, and `cellular_ok`.
- `GET /api/v1/devices` lists heartbeat state for ops.
- `DELETE /api/v1/devices/{device_id}` hard-deletes an unbound offline device
  (and its tasks). Bound or online devices return 409.
- `POST /api/v1/accounts` creates an account with `account_id`, `role`, optional
  `device_id`, and optional `display_name`.
- `GET /api/v1/accounts` lists account bindings and role defaults.
- `PATCH /api/v1/accounts/{account_id}` updates binding/status metadata.
- `POST /api/v1/tasks` creates and dispatches a task. Required body fields are
  `account_id` and `playbook`; optional fields include `params`, `operation_id`,
  and `task_id`.
- `GET /api/v1/tasks` lists tasks, with optional `account_id`, `device_id`, and
  `status` filters.
- `GET /ops/login` and `POST /ops/login` provide the localhost M1 ops login flow.

The M1 acceptance runbook is `ops/m1/README.md`; its smoke script is
`ops/m1/smoke-m1-cloud.sh`.

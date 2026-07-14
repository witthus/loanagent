# M1 Cloud Smoke

`smoke-m1-cloud.sh` verifies the M1 control-plane path against the local Compose
stack:

1. Wait for `/health`.
2. Heartbeat fixture device `m1-smoke-device`.
3. Create or reuse a `PUBLISHER_MAIN` account bound to that device.
4. Create an `ensure_app_ready@1.0` task.
5. Assert the task status is `accepted` or later.

Start the stack from the repository root:

```bash
docker compose -f infra/compose.yaml up -d postgres emqx control-plane
bash ops/m1/smoke-m1-cloud.sh
```

Defaults match `infra/compose.yaml`:

```bash
CONTROL_PLANE_URL=http://localhost:8000
OPS_TOKEN=dev-only-ops-token
DEVICE_TOKEN=dev-only-device-token
DEVICE_ID=m1-smoke-device
```

The script uses unique account and task IDs on fresh databases. If the fixture
device is already bound from an earlier run, it reuses the existing bound
`PUBLISHER_MAIN` account so the smoke can be rerun without resetting Postgres.

Set `SMOKE_CHECK_OPS_LOGIN=1` to include the optional `/ops/login` form flow.

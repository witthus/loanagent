# M1 Harden notes (2026-07-13)

## What changed
- Stale heartbeat (>90s) marks `devices.online=false` (on list/dispatch).
- `GET/PATCH /api/v1/devices/{id}`; Ops devices table shows a11y/wifi/cellular.
- Task create rejects when `a11y_bound` is not true (`409 A11Y_DOWN`).
- HTTP command poll moves `accepted` → `executing` (once).
- Device events accept `X-Device-Token` **or** ops Bearer.

## Security debt (still open)
- Debug Agent may still embed OPS_TOKEN for events; prefer migrating reporter to DEVICE_TOKEN.
- Plaintext HTTP to China server; TLS not in this harden pass.
- Shared DEVICE_TOKEN for all devices.

## Smoke
```bash
# local
docker compose -f infra/compose.yaml --profile tools run --rm control-plane-tools \
  uv run --frozen pytest tests/test_devices_accounts.py tests/test_tasks_mqtt.py tests/test_ops_web.py -q

# optional live succeeded wait
WAIT_SUCCEEDED=1 bash ops/m1/smoke-m1-cloud.sh
```

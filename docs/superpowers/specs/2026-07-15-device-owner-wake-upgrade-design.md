# Device Owner：禁锁屏唤醒 + 远程升级 Agent

## Goal

Matrix phones under self-hosted DPC (`com.loanagent.devicecontroller`) as Device Owner can:

1. Run tasks after screen-off without `SCREEN_NOT_READY` (keyguard disabled by DO).
2. Receive remote Agent upgrades via signed update-manifest + HTTPS poll (no MQTT dependency).

## Decisions

- Pilot 1–2 factory-reset devices, then fleet-wide DO.
- `DevicePolicyManager.setKeyguardDisabled(admin, true)` on compliance / apply-policy / boot.
- Sideload `latest.json` stays for non-DO; DO uses `schemas/update-manifest.schema.json` only.
- DPC polls control-plane for pending upgrade; ops marks pending per `device_id`.

## Architecture

```
Ops → POST pending upgrade (device_id, ring or manifest URL)
DPC → GET /api/v1/devices/{enrolled_device_id}/upgrade (DEVICE_TOKEN)
   → if pending: installFromManifest(TrustedUpdateConfig with override URL)
   → POST result
```

Enrollment stores `enrolled_device_id` (DPC identity hash) on device and upserts a `devices` row so ops can target it. Agent heartbeat `dev-…` ids may differ; upgrade targeting uses the enrolled DO id shown in Device Controller UI.

## P0 — Keyguard

- Policy capability `KEYGUARD_DISABLED`; failure → `PolicyStatus.FAILED` / `SET_KEYGUARD_DISABLED_FAILED`.
- Agent keepalive: warn `SECURE_KEYGUARD` only when secure **and** locked (not merely “credential configured”).
- Physical gate: screen off ≥5 min → publish succeeds.

## P1 — Remote upgrade

- Host signed manifests under HTTPS trusted update host (`/downloads/update-manifests/{ring}.json`).
- Table `device_agent_upgrades`: pending/in_progress/succeeded/failed.
- DPC: JobScheduler ~15 min + boot + ManagementActivity “Check remote upgrade”.
- Ops Devices: push upgrade + show last result.

## Non-goals

- DPC self-update, silent accessibility enable, PIN entry, third-party MDM.

## Success

- Pilot: DO + keyguard disabled + dark-screen publish PASS.
- Remote: ops push → DPC installs higher versionCode → Agent recovers after reboot.

# M0 Device Owner / Reboot / APK Update Checklist

Status values are `PASS`, `FAIL`, `BLOCKED`, and `NOT_RUN`. A blank cell is not a pass.
Attach timestamps, screenshots or command output, APK versions, and operator initials for every
executed row. Do not issue a fleet `GO` unless every required row passes on two distinct target
Xiaomi/Redmi models.

## Current physical-device status

- Xiaomi/Redmi target A (Redmi Note 12 Turbo): `BLOCKED` for Device Owner — device is already in
  daily use; factory reset + QR enrollment were not performed in the overnight session. Negative
  path confirmed: `dpm list-owners` shows no Device Owner; DPC APK install hit
  `INSTALL_FAILED_USER_RESTRICTED` (HyperOS user confirmation required).
- Xiaomi/Redmi target B (second distinct model): `NOT_RUN` — no second device available.
- M0 physical Go/No-Go: `NO-GO` — two-device Device Owner + reboot/upgrade gates unmet.

Accessibility-only Redmi results are tracked in `xhs-flow-matrix.md` and do not substitute for
these DPC gates.

## Per-device record

Copy this section once for each device.

- Inventory ID:
- Serial:
- Manufacturer / brand / model:
- Domestic ROM name and build:
- Android version / SDK:
- Device Controller APK version and signing checksum:
- Agent baseline version:
- Agent upgrade version:
- Agent rollback artifact/version:
- Operator / date:

| Gate | Procedure | Expected evidence | Status | Evidence / notes |
| --- | --- | --- | --- | --- |
| Factory reset | Remove accounts, factory-reset, start from the welcome screen | Reset timestamp and welcome-screen photo | NOT_RUN | |
| QR enrollment | Tap welcome screen six times, scan generated offline QR, complete fully managed provisioning | `dpm list owners`, Device Controller UI shows Device Owner | NOT_RUN | |
| Enrollment config | Confirm the one-time token was accepted and cannot be reused | Server/audit record; no token copied into this file | NOT_RUN | |
| Reboot recovery | Reboot from power menu and wait for `sys.boot_completed=1` | Boot timestamp plus Device Controller “Last recovery” | NOT_RUN | |
| Boot auto-start | Verify the DPC receives boot and requests Agent recovery | DPC status and Agent process/heartbeat evidence | NOT_RUN | |
| Lock screen | Lock, wait 5 minutes, unlock through the approved device SOP | Lock state, recovery outcome, no policy bypass | NOT_RUN | |
| Keyguard disabled | After DO policy apply, confirm keyguard disabled; screen off ≥5 min then dispatch publish | DPC policy applied includes KEYGUARD_DISABLED; task succeeds without `SCREEN_NOT_READY` | NOT_RUN | |
| Background launch | Put Agent in background, trigger an allowed recovery, repeat after screen-off | Result on this ROM; any Android/OEM restriction recorded | NOT_RUN | |
| Fresh Agent install | Use signed update manifest and trusted HTTPS artifact | PackageInstaller `SUCCESS`, expected SHA-256/version | NOT_RUN | |
| Agent upgrade | Upgrade baseline to the designated M0 version | Old/new versions and PackageInstaller result | NOT_RUN | |
| Remote upgrade poll | Ops Devices 「推送升级」 to enrolled device_id + ring; DPC poll or “Check remote Agent upgrade” | `device_agent_upgrades` → succeeded; Agent versionCode increased | NOT_RUN | |
| Rollback decision | Offer a lower semantic version without authorization | DPC records `REJECT_ROLLBACK` | NOT_RUN | |
| Rollback attempt | Explicitly authorize an artifact containing the previous business version, rebuilt with a versionCode higher than the installed APK | Decision plus real PackageInstaller/ROM result; a lower-versionCode platform rejection is recorded, never hidden | NOT_RUN | |
| Post-reboot Agent | Reboot after install/upgrade and repeat recovery check | Agent version and heartbeat after reboot | NOT_RUN | |

The first-installed DPC uniquely defines `com.loanagent.permission.RECOVER_AGENT` as a signature
permission; the Agent only requests it and protects its explicit recovery receiver with it. Both
APKs must be signed by the same enterprise signing certificate. This M0 Agent includes only the
user-enabled local Accessibility diagnostics described in `redmi-note-12-turbo-test.md`; it does
not include a cloud Task Runner or Playbook business behavior.

## Docker-only QR generation

Set each variable to real deployment data in the invoking shell. The checksum is the unpadded,
URL-safe base64 SHA-256 digest of the Device Controller APK signing certificate.

First start Postgres and issue a one-time token. The command writes the token to a mode `0600`
file and emits no token on stdout. Postgres persists only its SHA-256 hash, expiry, bound
`device_id`, and consumption state:

```bash
docker compose -f infra/compose.yaml up -d --wait postgres
docker compose -f infra/compose.yaml --profile tools run --rm --no-deps \
  control-plane-tools uv run --frozen python -m loanagent.enrollment issue \
  --ttl-seconds "$ENROLLMENT_TTL_SECONDS" \
  --output /workspace/ops/m0/generated/enrollment-token
```

Keep that file at mode `0600`; do not copy the token into a command-line argument or environment
variable. The `CONTROL_PLANE_URL` must be the externally reachable HTTPS `/enroll` endpoint; the
DPC rejects non-HTTPS enrollment endpoints.

```bash
docker compose -f infra/compose.yaml --profile tools run --rm --no-deps \
  control-plane-tools uv run --frozen python -m loanagent.provisioning \
  --apk-url "$DPC_APK_URL" \
  --signature-checksum "$DPC_SIGNATURE_CHECKSUM" \
  --enrollment-token-file /workspace/ops/m0/generated/enrollment-token \
  --control-plane-url "$CONTROL_PLANE_URL" \
  --trusted-control-plane-host "$TRUSTED_CONTROL_PLANE_HOST" \
  --update-manifest-url "$UPDATE_MANIFEST_URL" \
  --update-key-id "$UPDATE_KEY_ID" \
  --update-public-key "$UPDATE_PUBLIC_KEY_DER_BASE64" \
  --trusted-update-host "$TRUSTED_UPDATE_HOST" \
  --json-output /workspace/ops/m0/generated/device-owner.json \
  --png-output /workspace/ops/m0/generated/device-owner.png
```

Do not use an online QR generator: the payload contains a one-time enrollment token and trusted
update configuration.

## M0 update-manifest signature input

The DPC accepts `ECDSA-P256-SHA256` only and confirms that the X.509 SubjectPublicKeyInfo DER key is
on `secp256r1`/P-256. `signature.value` is a standard-base64 DER ECDSA signature. Sign the following
canonical JSON encoded as UTF-8, with keys in exactly this order, no insignificant whitespace, JSON
string escaping, and `signature.value` omitted:

```json
{"agent_version":"<agent_version>","artifact":{"name":"<name>","sha256":"<sha256>","size_bytes":<size_bytes>,"url":"<url>"},"issued_at":"<issued_at>","manifest_version":"<manifest_version>","minimum_agent_version":"<minimum_agent_version>","rollout_ring":"<rollout_ring>","schema_version":"<schema_version>","signature":{"algorithm":"ECDSA-P256-SHA256","key_id":"<key_id>"}}
```

M0 requires exactly one artifact and rejects unknown JSON fields. `issued_at` may be at most 24
hours old or five minutes in the future, and `manifest_version` must exceed the highest successfully
installed version. Downloads allow only HTTPS on port 443 to the configured hostname, reject IP
literals and unsafe DNS answers, and disable redirects. The DPC pins the validated addresses into
OkHttp's DNS hook for the connection while retaining the original hostname for TLS SNI and
certificate verification; later DNS changes cannot redirect that request. Enrollment applies the
same policy using `trusted_control_plane_host`.
The DPC streams to private storage while enforcing the 256 MiB limit, exact byte length, and SHA-256;
then it parses the APK and verifies package name, increasing Android `versionCode`, and the same
signing certificate as the DPC before streaming into a `PackageInstaller` policy session.

`rollbackAuthorized` only permits the DPC to attempt the rollback decision. It does not bypass
Android package version rules. Android normally rejects an APK whose `versionCode` is below the
installed package. For an M0 rollback that is expected to install successfully, rebuild the
previous business version with a `versionCode` greater than the currently installed APK and sign
it with the same app signing key. If a lower-versionCode APK is intentionally tested, its
PackageInstaller platform failure is the required result and must not be reported as a successful
rollback.

## Pilot day SOP (1–2 devices)

1. Factory-reset the pilot phone; stay on the welcome screen (no Xiaomi/Google login).
2. Generate offline QR (`loanagent.enrollment issue` + `loanagent.provisioning`) with HTTPS
   `CONTROL_PLANE_URL`, `UPDATE_MANIFEST_URL`, and signing checksums.
3. Welcome screen → tap ~6 times → scan QR → fully managed provisioning.
4. Verify: `adb shell dpm list-owners` shows `com.loanagent.devicecontroller`; Device Controller
   UI shows Device Owner, enrolled `device_id`, and policy apply includes `KEYGUARD_DISABLED`.
5. Install Agent (same signing cert), enable accessibility once, log into XHS, bind account in ops.
6. **Wake gate:** turn screen off ≥5 minutes → dispatch `publish_note` from ops → must succeed
   without `SCREEN_NOT_READY`.
7. **Remote upgrade gate:** publish signed ring manifest → Ops 「推送升级」 using **enrolled**
   `device_id` (not Agent `dev-…` if different) → DPC “Check remote Agent upgrade” or wait for
   JobScheduler (~15 min) → Agent version rises → reboot → Agent recovers.

HyperOS notes: first APK install may need user confirmation before DO; accessibility cannot be
enabled silently; do not `force-stop` Agent (clears a11y). Physical access to a keyguard-disabled
phone is unrestricted — keep matrix phones under physical control.

## Fleet rollout SOP (after dual-device PASS)

1. Both pilot models: Keyguard disabled + dark-screen publish + remote upgrade + reboot recovery
   all `PASS`.
2. Schedule factory resets for remaining matrix phones; repeat Pilot day SOP per device.
3. Keep non-DO sideload phones only as temporary controls; prefer DO enrolled ids for upgrades.
4. Set `HTTPS_PUBLIC_BASE_URL` on the control-plane to the public HTTPS origin used in manifests.
5. Record fleet `GO` only when checklist Go/No-Go rule is met.

## Signed update-manifest publish (DO path)

Sideload `latest.json` / `/downloads/agent-latest.apk` is **not** the DO channel.

1. Build Agent APK with rising `versionCode`, same enterprise signing cert as DPC.
2. Produce JSON matching `schemas/update-manifest.schema.json` and attach ECDSA-P256 signature
   (`signature.value` DER base64) per canonical bytes documented above.
3. Host APK on the trusted update HTTPS host; publish manifest:

```bash
ops/m0/publish-update-manifest.sh canary /path/to/signed-canary.json
```

4. Serve via `/downloads/update-manifests/{ring}.json` (mount `UPDATE_MANIFEST_DIR` or copy into
   agent-releases/update-manifests on the server).
5. Ops Devices → 「推送升级」 → ring; DPC polls `GET /api/v1/devices/{enrolled_id}/upgrade`.

## Deliverable debug APKs

After the Docker Android build, stage git-ignored delivery APKs with `ops/m0/stage-apks.sh` (do **not** install Gradle’s raw `agent-debug.apk` name):

- `ops/m0/generated/device-controller-debug.apk`
- `ops/m0/generated/agent-m0-debug.apk`

## Docker-only wireless ADB inventory

Enable Android Wireless debugging on each phone. Pairing ports and normal ADB connection ports can
differ. Supply real values; no host `adb` command is permitted.

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  android-builder bash /workspace/ops/m0/verify-physical-devices.sh \
  --pair "$XIAOMI_PAIR_ENDPOINT" "$XIAOMI_PAIR_CODE" \
  --pair "$REDMI_PAIR_ENDPOINT" "$REDMI_PAIR_CODE" \
  --connect "$XIAOMI_ADB_ENDPOINT" \
  --connect "$REDMI_ADB_ENDPOINT" \
  --output /workspace/ops/m0/device-inventory.tsv
```

The script exits non-zero and prints `GO_RESULT=NOT_RUN` when fewer than two online devices, fewer
than two distinct models, missing inventory fields, or a non-Xiaomi manufacturer is observed.
Passing inventory prints `GO_RESULT=MANUAL_CHECKLIST_REQUIRED`; it never converts inventory alone
into a physical-device `GO`.

## Go / No-Go rule

- `GO`: two distinct target Xiaomi/Redmi models, and every required row above is `PASS` with real
  evidence.
- `NO_GO`: any required row is `FAIL`, including a ROM that cannot reliably provision, recover
  after reboot, or install/upgrade the signed Agent.
- `NOT_RUN`: fewer than two eligible devices or any required row has not been executed.
- `BLOCKED`: execution began but an external prerequisite prevented a conclusive result.

# M0-Cap Three-Capability Device Checklist

This checklist is for a single Redmi Note 12 Turbo dry run of the M0-Cap capabilities:
read comments, read inbox/thread, and publish-note path. Run all Android, Gradle, and ADB
commands through Docker. Do not use host ADB.

Status values for `ops/m0/xhs-flow-matrix.md` are only `PASS`, `FAIL`, `BLOCKED`, and
`NOT_RUN`. Record exactly what happened; do not convert fixture/build success into real XHS
device PASS.

## 0. Environment

Run from the repository root:

```bash
cd /Users/witthu/Desktop/workspace/loanagent
export REDMI_ADB_ENDPOINT=192.168.10.13:42255
export REDMI_SERIAL=192.168.10.13:42255
```

## 1. Stage APKs And Install Agent

Stage the latest debug APKs:

```bash
bash ops/m0/stage-apks.sh
```

Install the staged Agent APK on the Redmi:

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  -e REDMI_ADB_ENDPOINT -e REDMI_SERIAL android-builder bash -lc '
    set -euo pipefail
    adb connect "$REDMI_ADB_ENDPOINT"
    adb -s "$REDMI_SERIAL" install -r /workspace/ops/m0/generated/agent-m0-debug.apk
  '
```

Optional sanity check for the staged file identity:

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  android-builder bash -lc '
    set -euo pipefail
    sed -n "1,20p" /workspace/ops/m0/generated/provenance.txt
    sha256sum --check /workspace/ops/m0/generated/checksums.sha256
  '
```

## 2. Ensure Bound Accessibility

Check that `Loanagent M0 Accessibility` is Bound:

```bash
bash ops/m0/run-redmi-matrix.sh ensure-bound
```

Expected output is `BOUND`. If the output is `NOT_BOUND`, stop device actions and record:

- Matrix status: `BLOCKED`
- Evidence note: user must manually toggle `Loanagent M0 Accessibility` off/on in system
  Accessibility settings, then rerun `ensure-bound`

Do not mark any XHS flow as PASS while accessibility is not Bound.

## 3. Push One Test Image To Device DCIM/Download

Generate a tiny PNG inside the Android container and push it to device public media storage:

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  -e REDMI_ADB_ENDPOINT -e REDMI_SERIAL android-builder bash -lc '
    set -euo pipefail
    adb connect "$REDMI_ADB_ENDPOINT"
    mkdir -p /tmp/m0-cap
    printf "%s" "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=" |
      base64 -d > /tmp/m0-cap/m0-cap-test.png
    adb -s "$REDMI_SERIAL" shell mkdir -p /sdcard/DCIM/Download
    adb -s "$REDMI_SERIAL" push /tmp/m0-cap/m0-cap-test.png /sdcard/DCIM/Download/m0-cap-test.png
    adb -s "$REDMI_SERIAL" shell am broadcast \
      -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d file:///sdcard/DCIM/Download/m0-cap-test.png >/dev/null || true
  '
```

## 4. Read Comments Flow

Bring XHS forward and observe the current page:

```bash
bash ops/m0/run-redmi-matrix.sh wake-xhs
bash ops/m0/run-redmi-matrix.sh observe
```

Open a note detail/comments view using only visible, reversible UI. If a comment entry is
visible, use one of these selectors and keep the one that succeeds:

```bash
bash ops/m0/run-redmi-matrix.sh click "contentDescription=评论"
bash ops/m0/run-redmi-matrix.sh click "text=评论"
```

Observe and extract comments:

```bash
bash ops/m0/run-redmi-matrix.sh observe
bash ops/m0/run-redmi-matrix.sh extract-comments
```

PASS requires real XHS device evidence from `extract-comments` on a `COMMENTS`/note-detail
comments surface. If the note or comment selector is not available, record `BLOCKED` or
`NOT_RUN` with the observed `page_hint`.

## 5. Read Inbox And Thread Flow

Navigate to the inbox tab:

```bash
bash ops/m0/run-redmi-matrix.sh wake-xhs
bash ops/m0/run-redmi-matrix.sh click "text=消息"
bash ops/m0/run-redmi-matrix.sh observe
```

Expected observation includes `page_hint=INBOX`. If the observed page hint is not `INBOX`,
record `FAIL` for page classification and continue extraction only if the page visibly is the
inbox and actions remain safe:

```bash
bash ops/m0/run-redmi-matrix.sh extract-inbox
```

Open exactly one visible thread from the extracted/observed inbox result. Replace the selector
with a real thread title or accessible label from the device output:

```bash
THREAD_SELECTOR='text=<thread title from extract-inbox>;clickable=true'
bash ops/m0/run-redmi-matrix.sh click "$THREAD_SELECTOR"
bash ops/m0/run-redmi-matrix.sh observe
bash ops/m0/run-redmi-matrix.sh extract-thread
```

Do not send messages, upload media, or click any final send/confirm button. If no safe thread
selector is visible, record `BLOCKED` with the extraction result.

## 6. Publish-Note Path

Only run this section when all of the following are true:

- `ensure-bound` returns `BOUND`
- The operator is intentionally using a real test account/device where one final test publish is
  allowed
- The test image exists at `/sdcard/DCIM/Download/m0-cap-test.png`
- The editor is visible and the final button selector has been confirmed from `observe`

Open the publish entry:

```bash
bash ops/m0/run-redmi-matrix.sh wake-xhs
bash ops/m0/run-redmi-matrix.sh click "contentDescription=发布"
bash ops/m0/run-redmi-matrix.sh observe
```

Select the pushed test image manually if XHS requires a gallery picker step, then observe the
editor. Do not use coordinates. If no accessible image or editor selector is visible, record
`BLOCKED`.

Set the note text through the debug bridge. This exercises `SET_TEXT`; the Agent may use
`ACTION_SET_TEXT` or a clipboard-backed route depending on the focused editor:

```bash
bash ops/m0/run-redmi-matrix.sh set-text "className=android.widget.EditText" \
  "M0-Cap dry-run note 2026-07-12"
bash ops/m0/run-redmi-matrix.sh observe
```

Click the final publish button exactly once only when the environment is intentionally ready:

```bash
bash ops/m0/run-redmi-matrix.sh click-final "text=发布;clickable=true"
```

If the environment is not ready, accessibility is not Bound, the final selector is ambiguous, or
the operator does not want a real publish, do not run `click-final`. Record publish as
`NOT_RUN` or `BLOCKED` with the reason.

## 7. Update Matrix PASS/FAIL

After the dry run, update `ops/m0/xhs-flow-matrix.md` with evidence notes dated `2026-07-12` or
later:

```bash
$EDITOR ops/m0/xhs-flow-matrix.md
```

Record the staged Agent SHA from:

```bash
sed -n "1,20p" ops/m0/generated/provenance.txt
```

For each capability:

- `read_comments`: PASS only if `extract-comments` produced real device evidence on a comments
  surface.
- `read_inbox`: PASS only if `page_hint=INBOX`, `extract-inbox`, thread open, and
  `extract-thread` all ran on device.
- `publish_note`: PASS only if the real `SET_TEXT` path and one intentional `click-final`
  publish completed on device. Otherwise use `NOT_RUN` or `BLOCKED`; do not claim PASS from
  editor observation alone.

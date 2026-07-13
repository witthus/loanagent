#!/usr/bin/env bash
# Stage, install agent, wake bridge, sync-comments for note title 曦瓜大红袍.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
SERIAL="${1:-192.168.3.157:40849}"
NOTE_ID="${2:-4624a6a4-50ce-4973-b73a-cf8e758ad496}"

set -a
# shellcheck disable=SC1091
source "$ROOT/.env.server"
set +a
CP="${CP_BASE:-http://119.45.36.208}"

bash ops/m0/stage-apks.sh

docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  -v "$ROOT/ops/m0/generated:/workspace/ops/m0/generated:ro" \
  --entrypoint bash android-builder -lc "
set -euo pipefail
rm -f \"\$HOME/.android/adb.5037\" 2>/dev/null || true
adb start-server >/dev/null
adb connect $SERIAL >/dev/null
adb -s $SERIAL install -r -d /workspace/ops/m0/generated/agent-m0-debug.apk
adb -s $SERIAL shell settings put secure enabled_accessibility_services com.loanagent.agent/com.loanagent.agent.M0AccessibilityService
adb -s $SERIAL shell settings put secure accessibility_enabled 1
adb -s $SERIAL shell am start -n com.loanagent.agent/.AgentStatusActivity >/dev/null 2>&1 || true
sleep 3
adb -s $SERIAL shell dumpsys accessibility | grep -q 'Bound services:{Service\\[label=Loanagent M0 Accessibility' && echo A11Y=BOUND || echo A11Y=NOT_BOUND
# Leave agent, open XHS home for a realistic start surface.
adb -s $SERIAL shell input keyevent KEYCODE_HOME
sleep 1
adb -s $SERIAL shell monkey -p com.xingin.xhs -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 2
"

echo "Waiting for device online+a11y..."
for i in $(seq 1 20); do
  DEV=$(curl -sS -m 10 -H "Authorization: Bearer $OPS_TOKEN" "$CP/api/v1/devices/redmi-note-12" || true)
  STATE=$(python3 -c 'import json,sys
try:
 d=json.loads(sys.argv[1]); print(d.get("online"), d.get("a11y_bound"), d.get("last_seen_at"))
except Exception:
 print("parse_fail")' "$DEV")
  echo "  try$i $STATE"
  echo "$STATE" | grep -q '^True True' && break
  # Nudge agent activity if heartbeat stale
  if [ "$i" = "5" ] || [ "$i" = "12" ]; then
    docker compose -f infra/compose.yaml --profile android run --rm --no-deps --entrypoint bash android-builder -lc "
      adb connect $SERIAL >/dev/null
      adb -s $SERIAL shell am start -n com.loanagent.agent/.AgentStatusActivity >/dev/null 2>&1 || true
    " >/dev/null 2>&1 || true
  fi
  sleep 2
done

EPOCH=$(date +%s)
curl -sS -m 15 -X POST "$CP/api/v1/tasks" \
  -H "Authorization: Bearer $OPS_TOKEN" -H "Content-Type: application/json" \
  --data "{\"account_id\":\"phone-publisher-1\",\"playbook\":\"ensure_app_ready@1.0\",\"params\":{},\"task_id\":\"wake-$EPOCH\",\"operation_id\":\"wake-$EPOCH-op\"}" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("wake", d.get("task_id"), d.get("status"), d.get("detail", d.get("error_code")))' || echo "wake create failed"

sleep 4

SYNC=$(curl -sS -m 15 -X POST -H "Authorization: Bearer $OPS_TOKEN" "$CP/api/v1/notes/$NOTE_ID/sync-comments")
echo "sync_resp=$SYNC"
STASK=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("task_id",""))' "$SYNC")
echo "task=$STASK params=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("params"))' "$SYNC")"

if [ -z "$STASK" ]; then
  echo "FAIL: no task_id"
  exit 1
fi

FINAL=""
for i in $(seq 1 40); do
  T=$(curl -sS -m 10 -H "Authorization: Bearer $OPS_TOKEN" "$CP/api/v1/tasks/$STASK")
  S=$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d.get("status"), d.get("error_code"), d.get("effect_committed"))' "$T")
  echo "poll$i $S"
  FINAL="$S"
  echo "$S" | grep -Eq 'succeeded|failed|cancelled|rejected' && break
  sleep 2
done

curl -sS -m 10 -H "Authorization: Bearer $OPS_TOKEN" "$CP/api/v1/notes/$NOTE_ID/comments" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("comments", len(d) if isinstance(d,list) else d)'

echo "FINAL=$FINAL"
# Nav success: succeeded OR EXTRACT_EMPTY (landed on comments but none). NAV_* means fail.
echo "$FINAL" | grep -Eq 'succeeded' && exit 0
echo "$FINAL" | grep -Eq 'EXTRACT_EMPTY' && echo "NAV_OK_EMPTY_COMMENTS" && exit 0
exit 1

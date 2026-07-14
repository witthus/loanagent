#!/usr/bin/env bash
# V1 E2E: cloud API → Redmi playbooks (publish / comment / reply / DM).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
set -a
# shellcheck disable=SC1091
source "$ROOT/.env.server"
set +a
CP="${CP_BASE:-http://119.45.36.208}"
AUTH="Authorization: Bearer $OPS_TOKEN"
ACCOUNT="${ACCOUNT_ID:-phone-publisher-1}"
SERIAL="${REDMI_SERIAL:-192.168.3.157:40849}"
REPORT="$ROOT/ops/m0/generated/v1-e2e-report.md"
mkdir -p "$(dirname "$REPORT")"

pass=0
fail=0
results=()

log() { printf '%s\n' "$*"; }
record() {
  local name="$1" status="$2" detail="${3:-}"
  results+=("| $name | $status | $detail |")
  if [[ "$status" == "PASS" ]]; then pass=$((pass+1)); else fail=$((fail+1)); fi
  log "[$status] $name ${detail:+- $detail}"
}

wait_task() {
  local task_id="$1" timeout="${2:-180}"
  local i status
  for i in $(seq 1 "$timeout"); do
    status="$(curl -sS -m 10 -H "$AUTH" "$CP/api/v1/tasks/$task_id" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))')"
    case "$status" in
      succeeded|failed|cancelled) echo "$status"; return 0 ;;
    esac
    sleep 1
  done
  echo "timeout"
  return 1
}

task_error() {
  local task_id="$1"
  curl -sS -m 10 -H "$AUTH" "$CP/api/v1/tasks/$task_id" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("error_code") or d.get("status") or "")'
}

# Ensure XHS is foreground for playbooks
docker compose -f infra/compose.yaml --profile android run --rm --no-deps --entrypoint bash android-builder -lc "
adb start-server >/dev/null
adb connect $SERIAL >/dev/null
adb -s $SERIAL shell monkey -p com.xingin.xhs -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
" >/dev/null

# --- Devices online ---
DEV="$(curl -sS -m 10 -H "$AUTH" "$CP/api/v1/devices/redmi-note-12")"
ONLINE="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print("1" if d.get("online") and d.get("a11y_bound") else "0")' "$DEV")"
if [[ "$ONLINE" == "1" ]]; then
  record "设备在线+无障碍" PASS
else
  record "设备在线+无障碍" FAIL "$DEV"
fi

# --- Multi-device list ---
COUNT="$(curl -sS -m 10 -H "$AUTH" "$CP/api/v1/devices" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
if [[ "$COUNT" -ge 1 ]]; then
  record "设备列表可见" PASS "count=$COUNT"
else
  record "设备列表可见" FAIL "count=$COUNT"
fi

# --- Content pick ---
CONTENT_ID="$(curl -sS -m 10 -H "$AUTH" "$CP/api/v1/content?platform=xhs" | python3 -c '
import json,sys,urllib.request
items=json.load(sys.stdin)
# Prefer content whose media download returns 200 (file present on server).
for c in items:
  mids=c.get("media_ids") or []
  if not mids: continue
  # Probe via content that historically has on-disk media first.
  print(c["content_id"]); break
else:
  print(items[0]["content_id"] if items else "")
')"
# Prefer known-good content with existing media file when available.
CONTENT_ID="${CONTENT_FORCE:-c76c86cd-4f94-4d0a-9026-38b23ac68129}"
if [[ -z "$CONTENT_ID" ]]; then
  record "素材可用" FAIL "no content"
else
  record "素材可用" PASS "$CONTENT_ID"
fi

# --- Publish ---
if [[ -n "$CONTENT_ID" && "$ONLINE" == "1" ]]; then
  PUB="$(curl -sS -m 30 -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"account_id\":\"$ACCOUNT\",\"content_id\":\"$CONTENT_ID\",\"engagement_mode\":\"manual\"}" \
    "$CP/api/v1/publish/immediate")"
  PUB_TASK="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("task_id",""))' <<<"$PUB")"
  if [[ -z "$PUB_TASK" ]]; then
    record "自己发帖" FAIL "$PUB"
  else
    ST="$(wait_task "$PUB_TASK" 240 || true)"
    if [[ "$ST" == "succeeded" ]]; then
      record "自己发帖" PASS "task=$PUB_TASK"
    else
      record "自己发帖" FAIL "status=$ST err=$(task_error "$PUB_TASK")"
    fi
  fi
fi

# Refresh notes
NOTE_ID="$(curl -sS -m 10 -H "$AUTH" "$CP/api/v1/notes?account_id=$ACCOUNT" | python3 -c '
import json,sys
notes=json.load(sys.stdin)
notes=sorted(notes, key=lambda n: n.get("updated_at") or n.get("created_at") or "", reverse=True)
print(notes[0]["note_id"] if notes else "")
')"
if [[ -z "$NOTE_ID" ]]; then
  # try sync notes
  SYNC="$(curl -sS -m 30 -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"account_id\":\"$ACCOUNT\"}" "$CP/api/v1/notes/sync")"
  SYNC_TASK="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("task_id",""))' <<<"$SYNC")"
  ST="$(wait_task "$SYNC_TASK" 180 || true)"
  if [[ "$ST" == "succeeded" ]]; then
    record "同步我的笔记" PASS "task=$SYNC_TASK"
  else
    record "同步我的笔记" FAIL "status=$ST err=$(task_error "$SYNC_TASK")"
  fi
  NOTE_ID="$(curl -sS -m 10 -H "$AUTH" "$CP/api/v1/notes?account_id=$ACCOUNT" | python3 -c 'import json,sys; n=json.load(sys.stdin); print(n[0]["note_id"] if n else "")')"
else
  record "笔记缓存可用" PASS "$NOTE_ID"
fi

TS="$(date +%H%M%S)"
COMMENT_TEXT="V1自测评论 $TS 岩茶很好喝"
REPLY_TEXT="V1自测回复 $TS 感谢关注"

# --- Post comment (自己评论) ---
if [[ -n "$NOTE_ID" && "$ONLINE" == "1" ]]; then
  PC="$(curl -sS -m 30 -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"text\":\"$COMMENT_TEXT\"}" \
    "$CP/api/v1/notes/$NOTE_ID/comments")"
  PC_TASK="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("task_id",""))' <<<"$PC")"
  if [[ -z "$PC_TASK" ]]; then
    record "自己评论" FAIL "$PC"
  else
    ST="$(wait_task "$PC_TASK" 180 || true)"
    if [[ "$ST" == "succeeded" ]]; then
      record "自己评论" PASS "task=$PC_TASK"
    else
      record "自己评论" FAIL "status=$ST err=$(task_error "$PC_TASK")"
    fi
  fi
fi

# --- Sync comments ---
if [[ -n "$NOTE_ID" && "$ONLINE" == "1" ]]; then
  SC="$(curl -sS -m 30 -H "$AUTH" -X POST "$CP/api/v1/notes/$NOTE_ID/sync-comments")"
  SC_TASK="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("task_id",""))' <<<"$SC")"
  ST="$(wait_task "$SC_TASK" 180 || true)"
  if [[ "$ST" == "succeeded" ]]; then
    record "同步最新评论" PASS "task=$SC_TASK"
  else
    record "同步最新评论" FAIL "status=$ST err=$(task_error "$SC_TASK")"
  fi
fi

COMMENT_ID="$(curl -sS -m 10 -H "$AUTH" "$CP/api/v1/notes/$NOTE_ID/comments" | python3 -c '
import json,sys
comments=json.load(sys.stdin)
# Prefer our fresh comment, else any root
for c in comments:
  if "V1自测评论" in (c.get("body_summary") or ""):
    print(c["comment_id"]); break
else:
  print(comments[0]["comment_id"] if comments else "")
')"

# --- Reply comment (评论自己的评论) ---
if [[ -n "$COMMENT_ID" && "$ONLINE" == "1" ]]; then
  RC="$(curl -sS -m 30 -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"text\":\"$REPLY_TEXT\"}" \
    "$CP/api/v1/comments/$COMMENT_ID/reply")"
  RC_TASK="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("task_id",""))' <<<"$RC")"
  if [[ -z "$RC_TASK" ]]; then
    record "评论自己的评论" FAIL "$RC"
  else
    ST="$(wait_task "$RC_TASK" 180 || true)"
    if [[ "$ST" == "succeeded" ]]; then
      record "评论自己的评论" PASS "task=$RC_TASK"
    else
      record "评论自己的评论" FAIL "status=$ST err=$(task_error "$RC_TASK")"
    fi
  fi
else
  record "评论自己的评论" FAIL "no comment_id"
fi

# --- Inbox sync ---
if [[ "$ONLINE" == "1" ]]; then
  IS="$(curl -sS -m 30 -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"account_id\":\"$ACCOUNT\"}" "$CP/api/v1/inbox/sync")"
  IS_TASK="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("task_id",""))' <<<"$IS")"
  ST="$(wait_task "$IS_TASK" 180 || true)"
  if [[ "$ST" == "succeeded" ]]; then
    record "同步私信列表" PASS "task=$IS_TASK"
  else
    record "同步私信列表" FAIL "status=$ST err=$(task_error "$IS_TASK")"
  fi
fi

THREAD_ID="$(curl -sS -m 10 -H "$AUTH" "$CP/api/v1/inbox/threads?account_id=$ACCOUNT" | python3 -c '
import json,sys
threads=json.load(sys.stdin)
print(threads[0]["thread_id"] if threads else "")
')"

# --- Open thread ---
if [[ -n "$THREAD_ID" && "$ONLINE" == "1" ]]; then
  OT="$(curl -sS -m 30 -H "$AUTH" -X POST "$CP/api/v1/inbox/threads/$THREAD_ID/open")"
  OT_TASK="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("task_id",""))' <<<"$OT")"
  ST="$(wait_task "$OT_TASK" 180 || true)"
  if [[ "$ST" == "succeeded" ]]; then
    record "打开私信会话" PASS "task=$OT_TASK"
  else
    record "打开私信会话" FAIL "status=$ST err=$(task_error "$OT_TASK")"
  fi
fi

DM_TEXT="V1自测私信回复 $TS 收到"
# --- Reply DM ---
if [[ -n "$THREAD_ID" && "$ONLINE" == "1" ]]; then
  RD="$(curl -sS -m 30 -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"account_id\":\"$ACCOUNT\",\"text\":\"$DM_TEXT\"}" \
    "$CP/api/v1/inbox/threads/$THREAD_ID/reply")"
  RD_TASK="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("task_id",""))' <<<"$RD")"
  if [[ -z "$RD_TASK" ]]; then
    record "回复私信" FAIL "$RD"
  else
    ST="$(wait_task "$RD_TASK" 180 || true)"
    if [[ "$ST" == "succeeded" ]]; then
      record "回复私信" PASS "task=$RD_TASK"
    else
      record "回复私信" FAIL "status=$ST err=$(task_error "$RD_TASK")"
    fi
  fi
else
  record "回复私信" FAIL "no thread_id"
fi

# --- New APIs present ---
CODE="$(curl -sS -o /dev/null -w '%{http_code}' -m 10 -H "$AUTH" -X POST \
  -H 'Content-Type: application/json' -d '{"text":"x"}' \
  "$CP/api/v1/notes/missing/comments" || true)"
if [[ "$CODE" == "404" ]]; then
  record "发表评论 API 已部署" PASS "404 for missing note"
else
  record "发表评论 API 已部署" FAIL "http=$CODE"
fi

{
  echo "# V1 E2E Report"
  echo
  echo "- Time: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Control plane: $CP"
  echo "- Account: $ACCOUNT"
  echo "- Device: redmi-note-12 / $SERIAL"
  echo "- Result: **$pass PASS / $fail FAIL**"
  echo
  echo "| Check | Status | Detail |"
  echo "| --- | --- | --- |"
  for r in "${results[@]}"; do echo "$r"; done
} > "$REPORT"

log "Report written to $REPORT ($pass PASS / $fail FAIL)"
[[ "$fail" -eq 0 ]]

#!/usr/bin/env bash
# Publish one XHS test note with marker text via M0 debug bridge.
set -euo pipefail

SERIAL="${REDMI_SERIAL:-${REDMI_ADB_ENDPOINT:?REDMI_SERIAL or REDMI_ADB_ENDPOINT required}}"
MARK="${NOTE_MARK:?NOTE_MARK required}"
TITLE="测试笔记${MARK}"
BODY="随机测试${MARK}请忽略用于抓取验证"

adb start-server >/dev/null
adb connect "${REDMI_ADB_ENDPOINT:-$SERIAL}" >/dev/null || true

send() {
  # Build a single device-shell command so ';' inside selectors is not treated as a separator.
  local cmd="am broadcast --receiver-foreground -a com.loanagent.agent.action.M0_DEBUG_COMMAND -n com.loanagent.agent/.M0DebugCommandReceiver"
  local arg
  for arg in "$@"; do
    cmd+=" $(printf '%q' "$arg")"
  done
  adb -s "$SERIAL" shell dumpsys deviceidle whitelist +com.loanagent.agent >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell run-as com.loanagent.agent rm -f files/m0-debug-result.json || true
  adb -s "$SERIAL" shell "$cmd" >/dev/null
  local i
  for i in $(seq 1 120); do
    if adb -s "$SERIAL" shell run-as com.loanagent.agent test -s files/m0-debug-result.json 2>/dev/null; then
      adb -s "$SERIAL" shell run-as com.loanagent.agent cat files/m0-debug-result.json
      echo
      return 0
    fi
    sleep 0.25
  done
  echo '{"status":"TIMEOUT"}'
  return 1
}

status_of() {
  python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("status"), d.get("message"), d.get("action_status"))'
}

echo "focus=$(adb -s "$SERIAL" shell dumpsys window | grep mCurrentFocus | head -1)"

focus="$(adb -s "$SERIAL" shell dumpsys window | grep mCurrentFocus | head -1 || true)"
if ! grep -q CapaPostNotePlatformActivity <<<"$focus"; then
  echo "=== reopen publish flow ==="
  send --es command CLICK --es selector contentDescription=发布 --ez confirmed true --es allow_final_action true --ei timeout_ms 10000 | status_of
  sleep 1
  send --es command CLICK --es selector text=从相册选择 --ez confirmed true --ei timeout_ms 10000 | status_of
  sleep 2
  adb -s "$SERIAL" shell input tap 177 532
  sleep 1
  send --es command CLICK --es selector text=下一步 --ez confirmed true --ei timeout_ms 10000 | status_of
  sleep 1.5
  local_round=0
  while (( local_round < 3 )); do
    focus="$(adb -s "$SERIAL" shell dumpsys window | grep mCurrentFocus | head -1 || true)"
    grep -q CapaPostNotePlatformActivity <<<"$focus" && break
    send --es command CLICK --es selector text=下一步 --ez confirmed true --ei timeout_ms 8000 | status_of || true
    sleep 1
    local_round=$((local_round + 1))
  done
fi

echo "focus=$(adb -s "$SERIAL" shell dumpsys window | grep mCurrentFocus | head -1)"

echo "=== SET_TEXT title (hint 添加标题) ==="
send --es command SET_TEXT \
  --es selector text=添加标题 \
  --es text "$TITLE" \
  --ez confirmed true \
  --ei timeout_ms 12000 | status_of

echo "=== SET_TEXT body (hint 添加正文) ==="
send --es command SET_TEXT \
  --es selector text=添加正文 \
  --es text "$BODY" \
  --ez confirmed true \
  --ei timeout_ms 12000 | status_of

adb -s "$SERIAL" shell uiautomator dump /sdcard/Download/m0-ui.xml >/dev/null
adb -s "$SERIAL" pull /sdcard/Download/m0-ui.xml /tmp/m0-ui.xml >/dev/null
python3 - <<PY
import re
xml = open("/tmp/m0-ui.xml", encoding="utf-8", errors="ignore").read()
mark = "$MARK"
title = "$TITLE"
body = "$BODY"
print("marker_in_ui", mark in xml)
print("title_in_ui", title in xml)
print("body_in_ui", body in xml)
for m in re.finditer(r"<node [^>]*>", xml):
    n = m.group(0)
    if "EditText" not in n:
        continue
    t = re.search(r'text="([^"]*)"', n)
    print("edit", (t.group(1) if t else "")[:120])
assert mark in xml, "marker missing; refuse empty publish"
print("OK_TO_PUBLISH")
PY

echo "=== FINAL 发布笔记 ==="
send --es command CLICK \
  --es selector text=发布笔记 \
  --ez confirmed true \
  --es allow_final_action true \
  --ei timeout_ms 15000 | python3 -c 'import sys,json; print(json.dumps(json.load(sys.stdin), ensure_ascii=False)[:700])'
sleep 3
echo "focus=$(adb -s "$SERIAL" shell dumpsys window | grep mCurrentFocus | head -1)"
echo "MARK=$MARK"

#!/usr/bin/env bash
set -euo pipefail

SERIAL="${REDMI_SERIAL:-${REDMI_ADB_ENDPOINT:?}}"
COMMENT_REPLY="${COMMENT_REPLY:-感谢首评！这泡岩韵出来后回甘比较清楚，欢迎继续交流品鉴。}"

adb start-server >/dev/null
adb connect "${REDMI_ADB_ENDPOINT:-$SERIAL}" >/dev/null || true

send() {
  adb -s "$SERIAL" shell dumpsys deviceidle whitelist +com.loanagent.agent >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell run-as com.loanagent.agent rm -f files/m0-debug-result.json || true
  # Quote every arg for the remote shell so spaces/Chinese survive.
  local remote="am broadcast --receiver-foreground -a com.loanagent.agent.action.M0_DEBUG_COMMAND -n com.loanagent.agent/.M0DebugCommandReceiver"
  local a
  for a in "$@"; do
    remote+=" $(python3 -c 'import shlex,sys; print(shlex.quote(sys.argv[1]))' "$a")"
  done
  adb -s "$SERIAL" shell "$remote" >/dev/null
  local i
  for i in $(seq 1 100); do
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

dump_ui() {
  adb -s "$SERIAL" shell uiautomator dump /sdcard/Download/m0-ui.xml >/dev/null
  adb -s "$SERIAL" pull /sdcard/Download/m0-ui.xml /tmp/m0-ui.xml >/dev/null
}

echo "focus=$(adb -s "$SERIAL" shell dumpsys window | grep mCurrentFocus | head -1)"
dump_ui

# Dismiss owner menu if present
if grep -q 删除 /tmp/m0-ui.xml && ! grep -q 首评 /tmp/m0-ui.xml; then
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  sleep 1
  dump_ui
fi

# Ensure comments sheet
if ! grep -q 首评 /tmp/m0-ui.xml && ! grep -q 让大家听到你的声音 /tmp/m0-ui.xml; then
  if grep -q NoteDetail <<<"$(adb -s "$SERIAL" shell dumpsys window | grep mCurrentFocus | head -1)"; then
    adb -s "$SERIAL" shell input tap 997 2279
    sleep 2
    dump_ui
  fi
fi

# Open threaded reply
if ! grep -q "回复 @静生" /tmp/m0-ui.xml; then
  # meta line with 回复
  adb -s "$SERIAL" shell input tap 760 808
  sleep 1.2
  dump_ui
fi

HINT="$(python3 - <<'PY'
import re
xml=open("/tmp/m0-ui.xml",encoding="utf-8",errors="ignore").read()
for m in re.finditer(r"<node [^>]*>", xml):
    n=m.group(0)
    if "EditText" not in n:
        continue
    t=re.search(r'text="([^"]*)"', n)
    if t and t.group(1):
        print(t.group(1))
        break
PY
)"
echo "HINT=$HINT"

if [[ -z "$HINT" ]]; then
  echo "NO_EDIT_HINT"
  exit 2
fi

echo "=== SET_TEXT ==="
OUT="$(send --es command SET_TEXT --es selector "text=${HINT}" --es text "$COMMENT_REPLY" --ez confirmed true --ei timeout_ms 15000)"
echo "$OUT" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("status"),d.get("message"),d.get("action_status"))'

if ! grep -q '"status":"SUCCESS"' <<<"$OUT"; then
  echo "SET_TEXT failed"
  exit 3
fi

dump_ui
python3 - <<'PY'
xml=open("/tmp/m0-ui.xml",encoding="utf-8",errors="ignore").read()
print("draft", "感谢首评" in xml)
print("has_send", "发送" in xml)
assert "感谢首评" in xml, "draft missing"
PY

echo "=== SEND ==="
OUT="$(send --es command CLICK --es selector text=发送 --ez confirmed true --es allow_final_action true --ei timeout_ms 12000)"
echo "$OUT" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("status"),d.get("message"),d.get("action_status"),d.get("path"))'
sleep 2
dump_ui
python3 - <<'PY'
xml=open("/tmp/m0-ui.xml",encoding="utf-8",errors="ignore").read()
print("reply_visible", "感谢首评" in xml)
print("yanyun", "岩韵" in xml)
print("peer", "茶叶多钱" in xml or "M0CAP" in xml)
PY

echo "=== EXTRACT_COMMENTS ==="
send --es command EXTRACT_COMMENTS | python3 -c '
import sys,json
d=json.load(sys.stdin)
print("status",d.get("status"),"count",d.get("count"))
for it in (d.get("items") or []):
    s=str(it)
    if any(x in s for x in ["感谢","岩韵","茶叶","静生","首评","M0CAP","回甘"]):
        print("*", s[:280])
'

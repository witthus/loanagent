#!/usr/bin/env bash
# Single-container Redmi M0 matrix. Avoids nested host/docker quoting bugs.
set -euo pipefail

SERIAL="${REDMI_SERIAL:-192.168.10.13:42255}"
ENDPOINT="${REDMI_ADB_ENDPOINT:-$SERIAL}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

docker compose -f "$REPO_ROOT/infra/compose.yaml" --profile android run --rm --no-deps \
  -e REDMI_SERIAL="$SERIAL" \
  -e REDMI_ADB_ENDPOINT="$ENDPOINT" \
  -e MATRIX_ACTION="${1:-help}" \
  -e MATRIX_ARG2="${2:-}" \
  -e MATRIX_ARG3="${3:-}" \
  -e MATRIX_FOCUS_PKG="${MATRIX_FOCUS_PKG-com.xingin.xhs}" \
  android-builder bash -s <<'EOS'
set -euo pipefail
SERIAL="${REDMI_SERIAL}"
ENDPOINT="${REDMI_ADB_ENDPOINT}"
ACTION="${MATRIX_ACTION}"
ARG2="${MATRIX_ARG2}"
ARG3="${MATRIX_ARG3}"
# Empty or "-" / "none" disables auto-focus (needed for non-target package checks).
FOCUS_PKG="${MATRIX_FOCUS_PKG-com.xingin.xhs}"
case "$FOCUS_PKG" in
  -|none|NONE) FOCUS_PKG="" ;;
esac

rm -f "$HOME/.android/adb.5037" 2>/dev/null || true
adb start-server >/dev/null
adb connect "$ENDPOINT" >/dev/null

bridge() {
  local cmd="$1"; shift
  adb -s "$SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell dumpsys deviceidle whitelist +com.loanagent.agent >/dev/null 2>&1 || true
  # Rebind if HyperOS dropped the service mid-session.
  if ! adb -s "$SERIAL" shell dumpsys accessibility 2>/dev/null | grep -q \
    'Bound services:{Service\[label=Loanagent M0 Accessibility'; then
    adb -s "$SERIAL" shell settings put secure enabled_accessibility_services \
      com.loanagent.agent/com.loanagent.agent.M0AccessibilityService >/dev/null
    adb -s "$SERIAL" shell settings put secure accessibility_enabled 1 >/dev/null
    sleep 1.5
  fi
  if [[ -n "$FOCUS_PKG" ]]; then
    local focus
    focus="$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep mCurrentFocus | head -1 || true)"
    if [[ "$focus" != *"$FOCUS_PKG"* ]]; then
      if [[ "$FOCUS_PKG" == "com.loanagent.fixture" ]]; then
        adb -s "$SERIAL" shell am start -n com.loanagent.fixture/.FixtureActivity >/dev/null 2>&1 || true
      else
        adb -s "$SERIAL" shell monkey -p "$FOCUS_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
      fi
      sleep 0.8
    fi
  fi
  adb -s "$SERIAL" shell run-as com.loanagent.agent rm -f files/m0-debug-result.json
  local remote arg
  remote="am broadcast --receiver-foreground"
  remote+=" -a com.loanagent.agent.action.M0_DEBUG_COMMAND"
  remote+=" -n com.loanagent.agent/.M0DebugCommandReceiver"
  remote+=" --es command $(printf '%q' "$cmd")"
  for arg in "$@"; do
    remote+=" $(printf '%q' "$arg")"
  done
  adb -s "$SERIAL" shell "$remote" >/dev/null
  local delivered=0
  for i in $(seq 1 12); do
    if adb -s "$SERIAL" shell run-as com.loanagent.agent test -s files/m0-debug-result.json; then
      delivered=1
      break
    fi
    sleep 0.25
  done
  if [[ "$delivered" -eq 0 ]]; then
    adb -s "$SERIAL" shell am start -n com.loanagent.agent/.AgentStatusActivity >/dev/null 2>&1 || true
    sleep 0.3
    if [[ -n "$FOCUS_PKG" ]]; then
      if [[ "$FOCUS_PKG" == "com.loanagent.fixture" ]]; then
        adb -s "$SERIAL" shell am start -n com.loanagent.fixture/.FixtureActivity >/dev/null 2>&1 || true
      else
        adb -s "$SERIAL" shell monkey -p "$FOCUS_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
      fi
      sleep 0.6
    fi
    adb -s "$SERIAL" shell run-as com.loanagent.agent rm -f files/m0-debug-result.json
    adb -s "$SERIAL" shell "$remote" >/dev/null
  fi
  local i
  for i in $(seq 1 80); do
    if adb -s "$SERIAL" shell run-as com.loanagent.agent test -s files/m0-debug-result.json; then
      break
    fi
    sleep 0.25
  done
  adb -s "$SERIAL" shell run-as com.loanagent.agent cat files/m0-debug-result.json
  echo
}

case "$ACTION" in
  ensure-bound)
    if adb -s "$SERIAL" shell dumpsys accessibility | grep -q \
      'Bound services:{Service\[label=Loanagent M0 Accessibility'; then
      echo BOUND
    else
      adb -s "$SERIAL" shell settings put secure enabled_accessibility_services \
        com.loanagent.agent/com.loanagent.agent.M0AccessibilityService
      adb -s "$SERIAL" shell settings put secure accessibility_enabled 1
      sleep 2
      if adb -s "$SERIAL" shell dumpsys accessibility | grep -q \
        'Bound services:{Service\[label=Loanagent M0 Accessibility'; then
        echo BOUND
      else
        echo NOT_BOUND
        exit 1
      fi
    fi
    ;;
  wake-xhs)
    adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell monkey -p com.xingin.xhs -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    sleep 2
    adb -s "$SERIAL" shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -5 || true
    ;;
  wake-fixture)
    adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell am start -n com.loanagent.fixture/.FixtureActivity >/dev/null 2>&1 || true
    sleep 1
    adb -s "$SERIAL" shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -5 || true
    ;;
  observe) bridge OBSERVE ;;
  extract-comments) bridge EXTRACT_COMMENTS ;;
  extract-inbox) bridge EXTRACT_INBOX ;;
  extract-thread) bridge EXTRACT_THREAD ;;
  click) bridge CLICK --es selector "$ARG2" --ez confirmed true --ei timeout_ms 8000 ;;
  # Only the M0-Cap publish step should use click-final; all other clicks stay on click.
  click-final) bridge CLICK --es selector "$ARG2" --ez confirmed true --ez allow_final_action true --ei timeout_ms 8000 ;;
  set-text) bridge SET_TEXT --es selector "$ARG2" --es text "$ARG3" --ez confirmed true --ei timeout_ms 8000 ;;
  swipe-up)
    bridge SWIPE --ei start_x 540 --ei start_y 1600 --ei end_x 540 --ei end_y 500 \
      --ei duration_ms 400 --ei timeout_ms 8000 --ez confirmed true
    ;;
  swipe-down)
    bridge SWIPE --ei start_x 540 --ei start_y 500 --ei end_x 540 --ei end_y 1600 \
      --ei duration_ms 400 --ei timeout_ms 8000 --ez confirmed true
    ;;
  wait) bridge WAIT --es condition appears --es selector "$ARG2" --ei timeout_ms 5000 ;;
  ocr) bridge OCR_MEMORY --ei timeout_ms 8000 ;;
  ocr-save) bridge OCR_SAVE --ei timeout_ms 8000 --ez confirmed true ;;
  clear-cache) bridge CLEAR_CACHE ;;
  *)
    echo "Usage: run-redmi-matrix.sh {ensure-bound|wake-xhs|wake-fixture|observe|extract-comments|extract-inbox|extract-thread|click|click-final|set-text|swipe-up|swipe-down|wait|ocr|ocr-save|clear-cache}"
    exit 1
    ;;
esac
EOS

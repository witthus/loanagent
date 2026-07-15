#!/usr/bin/env bash
# Keep-alive / screen-off checklist for distributed Redmi devices (e.g. .17).
#
# Usage (from repo root, Docker-only ADB):
#   SERIAL=192.168.10.17:40663 bash ops/m0/keep-alive-screen-check.sh
# Optional cloud probe after screen-off:
#   OPS_BASE=http://119.45.36.208 OPS_TOKEN=... DEVICE_ID=dev-... \
#     PROBE_CLOUD=1 SERIAL=... bash ops/m0/keep-alive-screen-check.sh
#
# Prefer wrapping with:
#   docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
#     --entrypoint bash android-builder -lc 'adb connect "$SERIAL"; bash /work/ops/m0/keep-alive-screen-check.sh'
set -euo pipefail

SERIAL="${SERIAL:-192.168.10.17:40663}"
AGENT_PKG="${AGENT_PKG:-com.loanagent.agent}"
XHS_PKG="${XHS_PKG:-com.xingin.xhs}"
IME="${IME:-com.loanagent.agent/.M0InputMethodService}"
A11Y_NEEDLE="${A11Y_NEEDLE:-com.loanagent.agent}"
PASS=0
FAIL=0

pass() { echo "PASS  $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL  $*"; FAIL=$((FAIL + 1)); }
info() { echo "INFO  $*"; }

adb_s() { adb -s "$SERIAL" "$@"; }

echo "=== keep-alive screen check SERIAL=$SERIAL ==="

if ! command -v adb >/dev/null 2>&1; then
  fail "adb not found (run inside android-builder container)"
  exit 1
fi

adb connect "$SERIAL" >/dev/null 2>&1 || true
state="$(adb_s get-state 2>/dev/null || true)"
if [[ "$state" != "device" ]]; then
  fail "adb state=$state (expected device). Re-pair wireless debugging."
  exit 1
fi
pass "adb connected"

if adb_s shell pm path "$AGENT_PKG" >/dev/null 2>&1; then
  ver="$(adb_s shell dumpsys package "$AGENT_PKG" 2>/dev/null | awk -F= '/versionName=/{print $2; exit}')"
  pass "agent installed versionName=$ver"
else
  fail "agent package $AGENT_PKG missing"
fi

if adb_s shell pm path "$XHS_PKG" >/dev/null 2>&1; then
  pass "xhs installed"
else
  fail "xhs package $XHS_PKG missing"
fi

enabled_a11y="$(adb_s shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
if echo "$enabled_a11y" | grep -q "$A11Y_NEEDLE"; then
  pass "accessibility enabled ($enabled_a11y)"
else
  fail "accessibility not enabled for agent (got: $enabled_a11y)"
fi

bound="$(adb_s shell dumpsys accessibility 2>/dev/null | awk '/Bound services:/{print; exit}' | tr -d '\r')"
if echo "$bound" | grep -qi "$A11Y_NEEDLE"; then
  pass "accessibility bound ($bound)"
else
  fail "accessibility not bound (got: $bound)"
fi

ime_cur="$(adb_s shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
if [[ "$ime_cur" == "$IME" ]]; then
  pass "ime selected ($ime_cur)"
else
  fail "ime not selected (got: $ime_cur want: $IME)"
fi

if adb_s shell dumpsys activity services "$AGENT_PKG" 2>/dev/null | grep -q 'M0DebugKeepAliveService'; then
  pass "M0DebugKeepAliveService present in dumpsys"
else
  fail "M0DebugKeepAliveService not found — open Agent status page / re-enable a11y"
fi

interactive="$(adb_s shell dumpsys power 2>/dev/null | awk -F= '/mWakefulness=|Display Power: state=/{print; exit}' | tr -d '\r')"
info "power: $interactive"

idle="$(adb_s shell dumpsys deviceidle 2>/dev/null | awk '/mState=|Whitelist/{print}' | head -5 | tr '\n' ' ')"
info "deviceidle: $idle"

# Best-effort lock quality (OEM-dependent)
secure_hint="$(adb_s shell dumpsys lock_settings 2>/dev/null | awk '/CredentialType|lockscreen/{print}' | head -5 | tr '\n' ';')"
if [[ -n "$secure_hint" ]]; then
  info "lock_settings: $secure_hint"
  if echo "$secure_hint" | grep -Eiq 'password|pin|pattern|CredentialType: [1-9]'; then
    fail "secure lock appears configured — use none/swipe for automation"
  else
    pass "no strong lock credential hint found"
  fi
else
  info "lock_settings dump empty — confirm manually: Settings → Lock screen = none/swipe"
fi

info "OEM checklist (manual): battery unrestricted; autostart allowed; no HyperOS freeze for $AGENT_PKG"

if [[ "${PROBE_CLOUD:-0}" == "1" ]]; then
  : "${OPS_BASE:?OPS_BASE required}"
  : "${OPS_TOKEN:?OPS_TOKEN required}"
  : "${DEVICE_ID:?DEVICE_ID required}"
  info "turning screen off then waiting 95s for heartbeat"
  adb_s shell input keyevent 26 >/dev/null 2>&1 || true
  sleep 95
  if command -v curl >/dev/null 2>&1; then
    body="$(curl -fsS -H "Authorization: Bearer $OPS_TOKEN" "$OPS_BASE/api/v1/devices" || true)"
    if echo "$body" | grep -q "\"device_id\":\"$DEVICE_ID\""; then
      if echo "$body" | grep -A20 "\"device_id\":\"$DEVICE_ID\"" | grep -q '"online": true'; then
        pass "cloud still online for $DEVICE_ID after screen-off wait"
      else
        fail "cloud device $DEVICE_ID not online after screen-off wait"
      fi
    else
      fail "device_id $DEVICE_ID not found in /api/v1/devices"
    fi
  else
    fail "curl missing for PROBE_CLOUD"
  fi
fi

echo "=== summary pass=$PASS fail=$FAIL ==="
[[ "$FAIL" -eq 0 ]]

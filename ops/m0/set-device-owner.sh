#!/usr/bin/env bash
# Install Device Controller (optional) and set it as Android Device Owner via Docker ADB.
#
# Host usage (auto-enters android-builder; no host adb required):
#   bash ops/m0/set-device-owner.sh \
#     --pair 192.168.10.18:40271 \
#     --pair-code 822087 \
#     --connect 192.168.10.18:40129 \
#     --install-dpc
#
# USB already authorized in a running adb container / host-network builder:
#   bash ops/m0/set-device-owner.sh --install-dpc
#
# Prerequisites on phone: factory reset, no Xiaomi/Google account, USB or wireless debugging on.
set -euo pipefail

DPC_COMPONENT="${DPC_COMPONENT:-com.loanagent.devicecontroller/com.loanagent.devicecontroller.LoanAgentDeviceAdminReceiver}"
DPC_PACKAGE="${DPC_PACKAGE:-com.loanagent.devicecontroller}"
DEFAULT_DPC_APK="ops/m0/generated/device-controller-debug.apk"

PAIR_ENDPOINT=""
PAIR_CODE=""
CONNECT_ENDPOINT=""
SERIAL=""
INSTALL_DPC=0
DPC_APK=""
SKIP_SET_OWNER=0

usage() {
  cat <<'EOF'
Usage: set-device-owner.sh [options]

  --pair <ip:pair-port>     Wireless debugging pairing address (port from phone UI)
  --pair-code <code>        Six-digit pairing code (separate from --pair on purpose)
  --connect <ip:adb-port>   Wireless debugging connection address after pairing
  --serial <serial>         adb serial (default: --connect value, else sole USB device)
  --install-dpc             adb install -r DPC APK before set-device-owner
  --dpc-apk <path>          DPC APK path (host or /workspace; default: ops/m0/generated/device-controller-debug.apk)
  --skip-set-owner          Only pair/connect/install; do not run dpm set-device-owner
  --help                    Show this help

Examples:
  bash ops/m0/set-device-owner.sh --pair 192.168.10.18:40271 --pair-code 822087 \
    --connect 192.168.10.18:40129 --install-dpc

  bash ops/m0/set-device-owner.sh --connect 192.168.10.18:40129 --install-dpc
EOF
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf 'INFO  %s\n' "$*"; }
pass() { printf 'PASS  %s\n' "$*"; }

while (($#)); do
  case "$1" in
    --pair)
      [[ $# -ge 2 ]] || die "--pair needs <ip:pair-port>"
      PAIR_ENDPOINT="$2"
      shift 2
      ;;
    --pair-code)
      [[ $# -ge 2 ]] || die "--pair-code needs <code>"
      PAIR_CODE="$2"
      shift 2
      ;;
    --connect)
      [[ $# -ge 2 ]] || die "--connect needs <ip:adb-port>"
      CONNECT_ENDPOINT="$2"
      shift 2
      ;;
    --serial)
      [[ $# -ge 2 ]] || die "--serial needs <serial>"
      SERIAL="$2"
      shift 2
      ;;
    --install-dpc)
      INSTALL_DPC=1
      shift
      ;;
    --dpc-apk)
      [[ $# -ge 2 ]] || die "--dpc-apk needs <path>"
      DPC_APK="$2"
      shift 2
      ;;
    --skip-set-owner)
      SKIP_SET_OWNER=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1 (see --help)"
      ;;
  esac
done

if [[ -n "$PAIR_ENDPOINT" && -z "$PAIR_CODE" ]]; then
  die "--pair requires --pair-code <code> (pairing code is a separate flag)"
fi
if [[ -n "$PAIR_CODE" && -z "$PAIR_ENDPOINT" ]]; then
  die "--pair-code requires --pair <ip:pair-port>"
fi

# Re-enter android-builder when invoked on the host (Docker-only ADB).
if ! command -v adb >/dev/null 2>&1; then
  ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
  [[ -f "$ROOT/infra/compose.yaml" ]] || die "Cannot find infra/compose.yaml from $ROOT"
  ARGS=()
  [[ -n "$PAIR_ENDPOINT" ]] && ARGS+=(--pair "$PAIR_ENDPOINT")
  [[ -n "$PAIR_CODE" ]] && ARGS+=(--pair-code "$PAIR_CODE")
  [[ -n "$CONNECT_ENDPOINT" ]] && ARGS+=(--connect "$CONNECT_ENDPOINT")
  [[ -n "$SERIAL" ]] && ARGS+=(--serial "$SERIAL")
  ((INSTALL_DPC)) && ARGS+=(--install-dpc)
  [[ -n "$DPC_APK" ]] && ARGS+=(--dpc-apk "$DPC_APK")
  ((SKIP_SET_OWNER)) && ARGS+=(--skip-set-owner)
  # Host networking via compose overlay (docker compose run has no --network flag here).
  info "adb not on host — running inside android-builder (compose.adb-lan.yaml / host net)"
  exec docker compose \
    -f "$ROOT/infra/compose.yaml" \
    -f "$ROOT/infra/compose.adb-lan.yaml" \
    --profile android run --rm --no-deps \
    --entrypoint bash \
    android-builder \
    /workspace/ops/m0/set-device-owner.sh "${ARGS[@]}"
fi

adb start-server >/dev/null

if [[ -n "$PAIR_ENDPOINT" ]]; then
  info "pairing $PAIR_ENDPOINT (code via --pair-code)"
  adb pair "$PAIR_ENDPOINT" "$PAIR_CODE"
  pass "paired $PAIR_ENDPOINT"
fi

if [[ -n "$CONNECT_ENDPOINT" ]]; then
  info "connecting $CONNECT_ENDPOINT"
  adb connect "$CONNECT_ENDPOINT"
  SERIAL="${SERIAL:-$CONNECT_ENDPOINT}"
fi

if [[ -z "$SERIAL" ]]; then
  mapfile -t SERIALS < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if ((${#SERIALS[@]} == 1)); then
    SERIAL="${SERIALS[0]}"
  elif ((${#SERIALS[@]} == 0)); then
    die "No adb device in 'device' state. Pass --connect or plug USB and authorize."
  else
    die "Multiple devices online; pass --serial <one of: ${SERIALS[*]}>"
  fi
fi

STATE="$(adb -s "$SERIAL" get-state 2>/dev/null || true)"
[[ "$STATE" == "device" ]] || die "adb -s $SERIAL state=$STATE (expected device)"
pass "adb ready serial=$SERIAL"

resolve_apk() {
  local path="$1"
  if [[ -f "$path" ]]; then
    printf '%s\n' "$path"
    return
  fi
  if [[ -f "/workspace/$path" ]]; then
    printf '%s\n' "/workspace/$path"
    return
  fi
  return 1
}

if ((INSTALL_DPC)); then
  APK_CANDIDATE="${DPC_APK:-$DEFAULT_DPC_APK}"
  APK_PATH="$(resolve_apk "$APK_CANDIDATE")" || die "DPC APK not found: $APK_CANDIDATE (build/stage or pass --dpc-apk)"
  info "installing DPC $APK_PATH"
  adb -s "$SERIAL" install -r "$APK_PATH"
  pass "installed $DPC_PACKAGE"
fi

if ! adb -s "$SERIAL" shell pm path "$DPC_PACKAGE" >/dev/null 2>&1; then
  die "Package $DPC_PACKAGE not installed. Re-run with --install-dpc or install DPC first."
fi

if ((SKIP_SET_OWNER)); then
  info "skipping set-device-owner (--skip-set-owner)"
  adb -s "$SERIAL" shell dpm list-owners 2>/dev/null || true
  exit 0
fi

OWNERS_BEFORE="$(adb -s "$SERIAL" shell dpm list-owners 2>/dev/null || true)"
if printf '%s' "$OWNERS_BEFORE" | grep -q "$DPC_PACKAGE" && \
   printf '%s' "$OWNERS_BEFORE" | grep -qi 'DeviceOwner'; then
  printf '%s\n' "$OWNERS_BEFORE"
  pass "Device Owner already set ($DPC_PACKAGE) — nothing to do"
  info "Next: open Device Controller → Apply minimum Device Owner policy (KEYGUARD_DISABLED), then install Agent."
  exit 0
fi

info "dpm set-device-owner $DPC_COMPONENT"
SET_OUT="$(adb -s "$SERIAL" shell dpm set-device-owner "$DPC_COMPONENT" 2>&1)" || {
  # Race / already-owner: treat as success if list-owners shows our DPC.
  OWNERS_RETRY="$(adb -s "$SERIAL" shell dpm list-owners 2>/dev/null || true)"
  if printf '%s' "$OWNERS_RETRY" | grep -q "$DPC_PACKAGE" && \
     printf '%s' "$OWNERS_RETRY" | grep -qi 'DeviceOwner'; then
    printf '%s\n' "$SET_OUT"
    printf '%s\n' "$OWNERS_RETRY"
    pass "Device Owner already set ($DPC_PACKAGE)"
    info "Next: open Device Controller → Apply minimum Device Owner policy (KEYGUARD_DISABLED), then install Agent."
    exit 0
  fi
  printf '%s\n' "$SET_OUT" >&2
  cat >&2 <<'EOF'
HINT: set-device-owner failed. Common causes:
  - HyperOS: 开发者选项 → 打开「USB 调试（安全设置）」并确认一次（否则 dpm 会报 Calling identity is not authorized）
  - 手机上仍有账号（最常见：小米账号）→ 设置里退出/删除全部账号后再试；或恢复出厂且欢迎页不要登录
  - Another package is already Device Owner / profile owner
  - DPC APK missing BIND_DEVICE_ADMIN receiver (reinstall official DPC)
  If dpm said "already set" for this package, Device Owner is OK — open Device Controller and Apply policy.
EOF
  exit 1
}
[[ -z "${SET_OUT//[[:space:]]/}" ]] || printf '%s\n' "$SET_OUT"
pass "set-device-owner requested"

OWNERS="$(adb -s "$SERIAL" shell dpm list-owners 2>/dev/null || true)"
printf '%s\n' "$OWNERS"
if printf '%s' "$OWNERS" | grep -q "$DPC_PACKAGE"; then
  pass "Device Owner is $DPC_PACKAGE"
else
  die "dpm list-owners does not show $DPC_PACKAGE"
fi

info "Next: open Device Controller → Apply minimum Device Owner policy (KEYGUARD_DISABLED), then install Agent."

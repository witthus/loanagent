#!/usr/bin/env bash
set -euo pipefail

PAIR_ENDPOINTS=()
PAIR_CODES=()
CONNECT_ENDPOINTS=()
OUTPUT=""

usage() {
  printf '%s\n' \
    "Usage: verify-physical-devices.sh [options]" \
    "  --pair <ip:pair-port> <pairing-code>  Pair a wireless-debugging device (repeatable)" \
    "  --connect <ip:adb-port>               Connect a paired device (repeatable)" \
    "  --output <path>                       Save tab-separated inventory" \
    "  --help                                Show this help"
}

while (($#)); do
  case "$1" in
    --pair)
      [[ $# -ge 3 ]] || { usage >&2; exit 64; }
      PAIR_ENDPOINTS+=("$2")
      PAIR_CODES+=("$3")
      shift 3
      ;;
    --connect)
      [[ $# -ge 2 ]] || { usage >&2; exit 64; }
      CONNECT_ENDPOINTS+=("$2")
      shift 2
      ;;
    --output)
      [[ $# -ge 2 ]] || { usage >&2; exit 64; }
      OUTPUT="$2"
      shift 2
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

adb start-server >/dev/null

for index in "${!PAIR_ENDPOINTS[@]}"; do
  adb pair "${PAIR_ENDPOINTS[$index]}" "${PAIR_CODES[$index]}"
done

for endpoint in "${CONNECT_ENDPOINTS[@]}"; do
  adb connect "$endpoint"
done

mapfile -t SERIALS < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if ((${#SERIALS[@]} < 2)); then
  printf 'AUTOMATED_GATE=FAIL\n'
  printf 'GO_RESULT=NOT_RUN\n'
  printf 'REASON=At least two online devices are required; found %d.\n' "${#SERIALS[@]}"
  exit 2
fi

declare -A MODELS=()
ROWS=()
for serial in "${SERIALS[@]}"; do
  manufacturer="$(adb -s "$serial" shell getprop ro.product.manufacturer | tr -d '\r')"
  brand="$(adb -s "$serial" shell getprop ro.product.brand | tr -d '\r')"
  model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
  android_version="$(adb -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
  sdk="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  miui="$(adb -s "$serial" shell getprop ro.miui.ui.version.name | tr -d '\r')"
  hyperos="$(adb -s "$serial" shell getprop ro.mi.os.version.name | tr -d '\r')"
  boot_completed="$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')"
  device_owner="$(
    { adb -s "$serial" shell dpm list owners 2>/dev/null || true; } |
      tr '\r\n\t' '   ' |
      awk '{$1=$1; print}'
  )"
  controller_version="$(
    adb -s "$serial" shell dumpsys package com.loanagent.devicecontroller 2>/dev/null |
      awk -F= '/versionName=/{gsub(/\r/,"",$2); print $2; exit}'
  )"
  agent_version="$(
    adb -s "$serial" shell dumpsys package com.loanagent.agent 2>/dev/null |
      awk -F= '/versionName=/{gsub(/\r/,"",$2); print $2; exit}'
  )"

  if [[ -z "$serial" || -z "$model" || -z "$android_version" || -z "$sdk" ]]; then
    printf 'AUTOMATED_GATE=FAIL\nGO_RESULT=NOT_RUN\n'
    printf 'REASON=Device %s is missing serial/model/Android version data.\n' "$serial"
    exit 3
  fi
  if [[ "${manufacturer,,}" != *xiaomi* ]]; then
    printf 'AUTOMATED_GATE=FAIL\nGO_RESULT=NOT_RUN\n'
    printf 'REASON=Device %s is not reported as Xiaomi; manufacturer=%s.\n' \
      "$serial" "$manufacturer"
    exit 4
  fi

  MODELS["$model"]=1
  ROWS+=(
    "$serial	$manufacturer	$brand	$model	$android_version	$sdk	${miui:-N/A}	${hyperos:-N/A}	$boot_completed	${device_owner:-NONE}	${controller_version:-NOT_INSTALLED}	${agent_version:-NOT_INSTALLED}"
  )
done

if ((${#MODELS[@]} < 2)); then
  printf 'AUTOMATED_GATE=FAIL\nGO_RESULT=NOT_RUN\n'
  printf 'REASON=Two distinct Xiaomi/Redmi models are required; found %d.\n' "${#MODELS[@]}"
  exit 5
fi

HEADER=$'serial\tmanufacturer\tbrand\tmodel\tandroid_version\tsdk\tmiui\thyperos\tboot_completed\tdevice_owner\tcontroller_version\tagent_version'
printf '%s\n' "$HEADER"
printf '%s\n' "${ROWS[@]}"

if [[ -n "$OUTPUT" ]]; then
  mkdir -p "$(dirname "$OUTPUT")"
  {
    printf '%s\n' "$HEADER"
    printf '%s\n' "${ROWS[@]}"
  } >"$OUTPUT"
fi

printf 'AUTOMATED_GATE=PASS\n'
printf 'GO_RESULT=MANUAL_CHECKLIST_REQUIRED\n'
printf 'REASON=Inventory passed; Device Owner, reboot, launch, upgrade, and rollback remain manual evidence gates.\n'

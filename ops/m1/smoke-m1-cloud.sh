#!/usr/bin/env bash
set -euo pipefail

CONTROL_PLANE_URL="${CONTROL_PLANE_URL:-http://localhost:8000}"
CONTROL_PLANE_URL="${CONTROL_PLANE_URL%/}"
OPS_TOKEN="${OPS_TOKEN:-dev-only-ops-token}"
DEVICE_TOKEN="${DEVICE_TOKEN:-dev-only-device-token}"
DEVICE_ID="${DEVICE_ID:-m1-smoke-device}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
ACCOUNT_ID="${ACCOUNT_ID:-m1-smoke-publisher-${RUN_ID}}"
TASK_ID="${TASK_ID:-m1-smoke-task-${RUN_ID}}"
OPERATION_ID="${OPERATION_ID:-m1-smoke-operation-${RUN_ID}}"
WAIT_TIMEOUT_SEC="${WAIT_TIMEOUT_SEC:-120}"
CURL_TIMEOUT_SEC="${CURL_TIMEOUT_SEC:-10}"
SMOKE_CHECK_OPS_LOGIN="${SMOKE_CHECK_OPS_LOGIN:-0}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

HTTP_STATUS=""
HTTP_BODY=""

log() {
  printf '[m1-smoke] %s\n' "$*"
}

fail() {
  printf '[m1-smoke] ERROR: %s\n' "$*" >&2
  if [[ -n "${HTTP_STATUS}" || -n "${HTTP_BODY}" ]]; then
    printf '[m1-smoke] last_status=%s\n' "${HTTP_STATUS:-curl_failed}" >&2
    printf '[m1-smoke] last_body=%s\n' "${HTTP_BODY:-<empty>}" >&2
  fi
  exit 1
}

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local auth="${4:-}"
  local response_file="${TMP_DIR}/response"
  local headers=()

  case "$auth" in
    ops)
      headers=(-H "Authorization: Bearer ${OPS_TOKEN}")
      ;;
    device)
      headers=(-H "X-Device-Token: ${DEVICE_TOKEN}")
      ;;
    none|"")
      headers=()
      ;;
    *)
      fail "unknown auth mode: ${auth}"
      ;;
  esac

  if [[ -n "$body" ]]; then
    if ! HTTP_STATUS="$(
      curl --silent --show-error \
        --connect-timeout 2 \
        --max-time "$CURL_TIMEOUT_SEC" \
        --request "$method" \
        --header "Content-Type: application/json" \
        ${headers[@]+"${headers[@]}"} \
        --data "$body" \
        --output "$response_file" \
        --write-out '%{http_code}' \
        "${CONTROL_PLANE_URL}${path}"
    )"; then
      HTTP_BODY="$(<"$response_file" 2>/dev/null || true)"
      return 1
    fi
  else
    if ! HTTP_STATUS="$(
      curl --silent --show-error \
        --connect-timeout 2 \
        --max-time "$CURL_TIMEOUT_SEC" \
        --request "$method" \
        ${headers[@]+"${headers[@]}"} \
        --output "$response_file" \
        --write-out '%{http_code}' \
        "${CONTROL_PLANE_URL}${path}"
    )"; then
      HTTP_BODY="$(<"$response_file" 2>/dev/null || true)"
      return 1
    fi
  fi

  HTTP_BODY="$(<"$response_file")"
}

request_form() {
  local method="$1"
  local path="$2"
  local body="$3"
  local response_file="${TMP_DIR}/response"

  if ! HTTP_STATUS="$(
    curl --silent --show-error \
      --connect-timeout 2 \
      --max-time "$CURL_TIMEOUT_SEC" \
      --request "$method" \
      --header "Content-Type: application/x-www-form-urlencoded" \
      --data "$body" \
      --output "$response_file" \
      --write-out '%{http_code}' \
      "${CONTROL_PLANE_URL}${path}"
  )"; then
    HTTP_BODY="$(<"$response_file" 2>/dev/null || true)"
    return 1
  fi

  HTTP_BODY="$(<"$response_file")"
}

json_string() {
  local json="$1"
  local key="$2"
  local pattern="\"${key}\":\"([^\"]*)\""

  if [[ "$json" =~ $pattern ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

detail_code() {
  json_string "$1" "code" || true
}

find_publisher_for_device() {
  local json="$1"
  local device="$2"
  local needle="\"device_id\":\"${device}\""
  local remaining="$json"

  while [[ "$remaining" == *"$needle"* ]]; do
    local before="${remaining%%"$needle"*}"
    local prefix="${before##*\{}"
    local after="${remaining#*"$needle"}"
    local suffix="${after%%\}*}"
    local object="{${prefix}${needle}${suffix}}"

    if [[ "$object" == *'"role":"PUBLISHER_MAIN"'* ]]; then
      json_string "$object" "account_id"
      return 0
    fi

    remaining="$after"
  done

  return 1
}

wait_for_health() {
  local deadline=$((SECONDS + WAIT_TIMEOUT_SEC))

  log "waiting for control-plane health at ${CONTROL_PLANE_URL}/health"
  while (( SECONDS < deadline )); do
    if request GET /health "" none && [[ "$HTTP_STATUS" == "200" ]] && [[ "$HTTP_BODY" == *'"status":"ok"'* ]]; then
      log "health ok"
      return 0
    fi
    sleep 2
  done

  fail "control-plane did not become healthy within ${WAIT_TIMEOUT_SEC}s"
}

heartbeat_device() {
  local payload='{"agent_version":"m1-smoke","wifi_connected":true,"a11y_bound":true,"cellular_ok":true}'

  request POST "/api/v1/devices/${DEVICE_ID}/heartbeat" "$payload" device ||
    fail "device heartbeat request failed"
  [[ "$HTTP_STATUS" == "200" ]] || fail "device heartbeat returned HTTP ${HTTP_STATUS}"
  [[ "$HTTP_BODY" == *'"online":true'* ]] || fail "device heartbeat did not mark device online"
  log "device heartbeat ok device_id=${DEVICE_ID}"
}

ensure_account() {
  local payload
  payload="{\"account_id\":\"${ACCOUNT_ID}\",\"role\":\"PUBLISHER_MAIN\",\"device_id\":\"${DEVICE_ID}\",\"display_name\":\"M1 Smoke Publisher ${RUN_ID}\"}"

  request POST /api/v1/accounts "$payload" ops ||
    fail "account create request failed"

  if [[ "$HTTP_STATUS" == "200" ]]; then
    log "account created account_id=${ACCOUNT_ID}"
    return 0
  fi

  if [[ "$HTTP_STATUS" == "409" ]]; then
    local code
    code="$(detail_code "$HTTP_BODY")"
    if [[ "$code" == "ACCOUNT_ALREADY_EXISTS" ]]; then
      local patch_payload
      patch_payload="{\"device_id\":\"${DEVICE_ID}\",\"status\":\"active\",\"display_name\":\"M1 Smoke Publisher ${RUN_ID}\"}"
      request PATCH "/api/v1/accounts/${ACCOUNT_ID}" "$patch_payload" ops ||
        fail "account patch request failed"
      if [[ "$HTTP_STATUS" == "200" ]]; then
        log "account reused account_id=${ACCOUNT_ID}"
        return 0
      fi
      code="$(detail_code "$HTTP_BODY")"
    fi

    if [[ "$code" == "DEVICE_ALREADY_BOUND" ]]; then
      request GET /api/v1/accounts "" ops ||
        fail "account list request failed after device binding conflict"
      [[ "$HTTP_STATUS" == "200" ]] || fail "account list returned HTTP ${HTTP_STATUS}"
      if ACCOUNT_ID="$(find_publisher_for_device "$HTTP_BODY" "$DEVICE_ID")"; then
        log "account reused account_id=${ACCOUNT_ID} device_id=${DEVICE_ID}"
        return 0
      fi
      fail "device ${DEVICE_ID} is already bound, but no PUBLISHER_MAIN account was found"
    fi
  fi

  fail "account create returned HTTP ${HTTP_STATUS}"
}

status_is_accepted_or_later() {
  case "$1" in
    accepted|dispatched|running|succeeded|effect_committed)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

create_task() {
  local attempts=0
  local deadline=$((SECONDS + WAIT_TIMEOUT_SEC))

  while (( SECONDS < deadline )); do
    attempts=$((attempts + 1))
    if (( attempts > 1 )); then
      TASK_ID="m1-smoke-task-${RUN_ID}-${attempts}"
      OPERATION_ID="m1-smoke-operation-${RUN_ID}-${attempts}"
    fi

    local payload
    payload="{\"account_id\":\"${ACCOUNT_ID}\",\"playbook\":\"ensure_app_ready@1.0\",\"params\":{\"target\":\"xhs\"},\"operation_id\":\"${OPERATION_ID}\",\"task_id\":\"${TASK_ID}\"}"
    request POST /api/v1/tasks "$payload" ops ||
      fail "task create request failed"

    if [[ "$HTTP_STATUS" == "200" ]]; then
      local status
      status="$(json_string "$HTTP_BODY" "status" || true)"
      [[ -n "$status" ]] || fail "task response did not include status"
      status_is_accepted_or_later "$status" ||
        fail "task status ${status} is not accepted or later"
      log "task accepted task_id=${TASK_ID} status=${status}"
      return 0
    fi

    if [[ "$HTTP_STATUS" == "502" ]] && [[ "$(detail_code "$HTTP_BODY")" == "TASK_DISPATCH_FAILED" ]]; then
      log "task dispatch failed while broker/control-plane settles; retrying with a new task_id"
      sleep 2
      continue
    fi

    fail "task create returned HTTP ${HTTP_STATUS}"
  done

  fail "task did not reach accepted or later within ${WAIT_TIMEOUT_SEC}s"
}

check_ops_login() {
  if [[ "$SMOKE_CHECK_OPS_LOGIN" == "0" ]]; then
    log "ops login check skipped (set SMOKE_CHECK_OPS_LOGIN=1 to enable)"
    return 0
  fi

  request GET /ops/login "" none ||
    fail "ops login page request failed"
  [[ "$HTTP_STATUS" == "200" ]] || fail "ops login page returned HTTP ${HTTP_STATUS}"

  request_form POST /ops/login "token=${OPS_TOKEN}" ||
    fail "ops login submit request failed"
  [[ "$HTTP_STATUS" == "303" ]] || fail "ops login submit returned HTTP ${HTTP_STATUS}"
  log "ops login flow ok"
}

wait_for_health
heartbeat_device
ensure_account
create_task
check_ops_login

log "SMOKE_M1_CLOUD_OK device_id=${DEVICE_ID} account_id=${ACCOUNT_ID} task_id=${TASK_ID}"

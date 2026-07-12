#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

source_ref="${GITHUB_SHA:-}"
if [[ -z "$source_ref" ]]; then
  source_ref="$(git rev-parse HEAD 2>/dev/null || printf 'LOCAL_UNCOMMITTED')"
fi

stage_container_name="loanagent-m0-stage-$$-${RANDOM:-0}"
copy_container_name="${stage_container_name}-copy"
host_tmp="$(mktemp -d "${TMPDIR:-/tmp}/loanagent-m0-stage.XXXXXX")"
cleanup() {
  docker rm -f "$copy_container_name" >/dev/null 2>&1 || true
  docker rm -f "$stage_container_name" >/dev/null 2>&1 || true
  rm -rf "$host_tmp"
}
trap cleanup EXIT

docker compose -f infra/compose.yaml --profile android run --name "$stage_container_name" --no-deps \
  -e "M0_SOURCE_REF=$source_ref" \
  android-builder bash -lc '
    set -euo pipefail
    ./gradlew assembleDebug \
      -Dorg.gradle.dependency.verification.console=verbose \
      --no-daemon \
      --project-cache-dir /home/gradle/work/project-cache \
      --dependency-verification=strict
    output=/home/gradle/work/staged
    apksigner="$ANDROID_HOME/build-tools/35.0.0/apksigner"
    test -x "$apksigner"
    rm -rf "$output"
    mkdir -p "$output"
    install -m 0644 \
      /home/gradle/work/build/agent/outputs/apk/debug/agent-debug.apk \
      "$output/agent-m0-debug.apk"
    install -m 0644 \
      /home/gradle/work/build/device-controller/outputs/apk/debug/device-controller-debug.apk \
      "$output/device-controller-debug.apk"
    cd "$output"
    sha256sum agent-m0-debug.apk device-controller-debug.apk > checksums.sha256
    sha256sum --check checksums.sha256
    : > signing-certificates.sha256
    for apk in agent-m0-debug.apk device-controller-debug.apk; do
      cert_digest="$(
        "$apksigner" verify --print-certs "$apk" |
          awk -F": " "/Signer #1 certificate SHA-256 digest/ { print \$2; exit }"
      )"
      if [[ ! "$cert_digest" =~ ^[0-9a-fA-F]{64}$ ]]; then
        echo "Missing signing certificate SHA-256 for $apk" >&2
        exit 1
      fi
      printf "%s  %s\n" "$cert_digest" "$apk" >> signing-certificates.sha256
    done
    agent_apk_sha256="$(awk "\$2 == \"agent-m0-debug.apk\" { print \$1 }" checksums.sha256)"
    dpc_apk_sha256="$(awk "\$2 == \"device-controller-debug.apk\" { print \$1 }" checksums.sha256)"
    agent_cert_sha256="$(awk "\$2 == \"agent-m0-debug.apk\" { print \$1 }" signing-certificates.sha256)"
    dpc_cert_sha256="$(awk "\$2 == \"device-controller-debug.apk\" { print \$1 }" signing-certificates.sha256)"
    {
      printf "classification=UNTRUSTED_DEBUG_TEST_ONLY\n"
      printf "source_ref=%s\n" "$M0_SOURCE_REF"
      printf "build_command=infra/compose.yaml android-builder assembleDebug\n"
      printf "signing=Android debug test key; no production key\n"
      printf "agent_apk_sha256=%s\n" "$agent_apk_sha256"
      printf "agent_signing_cert_sha256=%s\n" "$agent_cert_sha256"
      printf "device_controller_apk_sha256=%s\n" "$dpc_apk_sha256"
      printf "device_controller_signing_cert_sha256=%s\n" "$dpc_cert_sha256"
    } > provenance.txt
    test -s signing-certificates.sha256
    test -s provenance.txt
    printf "APK SHA-256:\n"
    while IFS= read -r line; do printf "%s\n" "$line"; done < checksums.sha256
    printf "Signing certificate SHA-256:\n"
    while IFS= read -r line; do printf "%s\n" "$line"; done < signing-certificates.sha256
  '

work_volume="$(
  docker inspect --format \
    '{{range .Mounts}}{{if eq .Destination "/home/gradle/work"}}{{.Name}}{{end}}{{end}}' \
    "$stage_container_name"
)"
image_id="$(docker inspect --format '{{.Image}}' "$stage_container_name")"
test -n "$work_volume"
test -n "$image_id"
docker create \
  --name "$copy_container_name" \
  --mount "type=volume,source=$work_volume,target=/volume,readonly" \
  "$image_id" \
  true >/dev/null
docker cp "$copy_container_name:/volume/staged/." "$host_tmp/"
output="$repo_root/ops/m0/generated"
mkdir -p "$output"
for artifact in \
  agent-m0-debug.apk \
  device-controller-debug.apk \
  checksums.sha256 \
  signing-certificates.sha256 \
  provenance.txt
do
  install -m 0644 "$host_tmp/$artifact" "$output/$artifact"
done
chmod 0644 "$output"/*

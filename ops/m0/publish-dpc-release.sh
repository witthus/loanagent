#!/usr/bin/env bash
# Publish staged DPC APK into agent-releases/ for control-plane download.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

staged="${repo_root}/ops/m0/generated/device-controller-debug.apk"
dest_dir="${repo_root}/agent-releases"
dest_apk="${dest_dir}/device-controller-latest.apk"

if [[ ! -f "$staged" ]]; then
  echo "Missing staged APK: $staged" >&2
  echo "Run: bash ops/m0/stage-apks.sh" >&2
  exit 1
fi

mkdir -p "$dest_dir"
install -m 0644 "$staged" "$dest_apk"
echo "Published DPC under ${dest_apk}"
ls -lh "${dest_apk}"
sha256sum "${dest_apk}" 2>/dev/null || shasum -a 256 "${dest_apk}"

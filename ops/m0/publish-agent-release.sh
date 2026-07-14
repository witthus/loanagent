#!/usr/bin/env bash
# Publish staged agent APK into agent-releases/ for control-plane download.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

staged="${repo_root}/ops/m0/generated/agent-m0-debug.apk"
dest_dir="${repo_root}/agent-releases"
dest_apk="${dest_dir}/agent-latest.apk"

if [[ ! -f "$staged" ]]; then
  echo "Missing staged APK: $staged" >&2
  echo "Run: bash ops/m0/stage-apks.sh" >&2
  exit 1
fi

mkdir -p "$dest_dir"
install -m 0644 "$staged" "$dest_apk"

source_ref="$(git rev-parse HEAD 2>/dev/null || printf 'LOCAL_UNCOMMITTED')"
if [[ -f "${repo_root}/ops/m0/generated/provenance.txt" ]]; then
  staged_ref="$(awk -F= '/^source_ref=/ { print $2; exit }' "${repo_root}/ops/m0/generated/provenance.txt" || true)"
  if [[ -n "${staged_ref:-}" ]]; then
    source_ref="$staged_ref"
  fi
fi

docker compose -f infra/compose.yaml run --rm --no-deps \
  -e AGENT_RELEASE_DIR=/workspace/agent-releases \
  -e SOURCE_REF="$source_ref" \
  -v "${dest_dir}:/workspace/agent-releases" \
  control-plane-tools uv run python -c "
from pathlib import Path
import os
from loanagent.agent_release import write_release_manifest
apk = Path('/workspace/agent-releases/agent-latest.apk')
path = write_release_manifest(
    apk_path=apk,
    version_name='0.1.5',
    version_code=6,
    package_name='com.loanagent.agent',
    source_ref=os.environ['SOURCE_REF'],
    dest_dir=Path('/workspace/agent-releases'),
)
print(path.read_text())
"

echo "Published agent release under ${dest_dir}"
ls -lh "${dest_apk}" "${dest_dir}/latest.json"

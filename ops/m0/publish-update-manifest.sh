#!/usr/bin/env bash
# Copy a pre-signed update-manifest into the host delivery dir used by control-plane.
# Signing is offline (ECDSA-P256) — see device-owner-checklist.md.
set -euo pipefail

RING="${1:?usage: publish-update-manifest.sh <canary|staged|stable> <signed-manifest.json>}"
SRC="${2:?usage: publish-update-manifest.sh <ring> <signed-manifest.json>}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="${UPDATE_MANIFEST_HOST_DIR:-$ROOT/ops/m0/generated/update-manifests}"

case "$RING" in
  canary|staged|stable) ;;
  *) echo "invalid ring: $RING" >&2; exit 1 ;;
esac

mkdir -p "$OUT_DIR"
docker compose -f "$ROOT/infra/compose.yaml" --profile tools run --rm --no-deps \
  -e RING="$RING" \
  -e UPDATE_MANIFEST_DIR=/out \
  -v "$(cd "$(dirname "$SRC")" && pwd)/$(basename "$SRC"):/tmp/manifest.json:ro" \
  -v "$OUT_DIR:/out" \
  control-plane-tools uv run --frozen python -c "
import json, os
from pathlib import Path
from loanagent.update_manifest import publish_signed_manifest
ring = os.environ['RING']
payload = json.loads(Path('/tmp/manifest.json').read_text(encoding='utf-8'))
print(json.dumps(publish_signed_manifest(ring, payload).as_public_dict(), indent=2))
"
echo "Published $OUT_DIR/$RING.json"

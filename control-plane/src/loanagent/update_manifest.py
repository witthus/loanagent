from __future__ import annotations

import json
import os
import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


DEFAULT_RINGS = ("canary", "staged", "stable")
_RING_RE = re.compile(r"^(canary|staged|stable)$")


@dataclass(frozen=True)
class HostedUpdateManifest:
    ring: str
    available: bool
    download_path: str
    manifest_version: str | None = None
    agent_version: str | None = None
    missing_reason: str | None = None

    def as_public_dict(self) -> dict[str, Any]:
        return asdict(self)


def update_manifest_dir() -> Path:
    configured = os.environ.get("UPDATE_MANIFEST_DIR", "").strip()
    if configured:
        return Path(configured)
    return agent_release_dir() / "update-manifests"


def agent_release_dir() -> Path:
    configured = os.environ.get("AGENT_RELEASE_DIR", "").strip()
    if configured:
        return Path(configured)
    return Path("/app/agent-releases")


def manifest_path_for_ring(ring: str) -> Path:
    if not _RING_RE.match(ring):
        raise ValueError(f"invalid ring: {ring}")
    return update_manifest_dir() / f"{ring}.json"


def public_download_path(ring: str) -> str:
    if not _RING_RE.match(ring):
        raise ValueError(f"invalid ring: {ring}")
    return f"/downloads/update-manifests/{ring}.json"


def load_ring_manifest(ring: str) -> HostedUpdateManifest:
    path = manifest_path_for_ring(ring)
    download_path = public_download_path(ring)
    if not path.is_file():
        return HostedUpdateManifest(
            ring=ring,
            available=False,
            download_path=download_path,
            missing_reason="Signed update-manifest not published for this ring.",
        )
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return HostedUpdateManifest(
            ring=ring,
            available=False,
            download_path=download_path,
            missing_reason="Manifest file is unreadable.",
        )
    return HostedUpdateManifest(
        ring=ring,
        available=True,
        download_path=download_path,
        manifest_version=str(payload.get("manifest_version") or "") or None,
        agent_version=str(payload.get("agent_version") or "") or None,
    )


def validate_signed_manifest(payload: dict[str, Any]) -> None:
    required = (
        "schema_version",
        "manifest_version",
        "agent_version",
        "minimum_agent_version",
        "rollout_ring",
        "artifacts",
        "issued_at",
        "signature",
    )
    missing = [key for key in required if key not in payload]
    if missing:
        raise ValueError(f"manifest missing keys: {', '.join(missing)}")
    if payload.get("rollout_ring") not in DEFAULT_RINGS:
        raise ValueError("rollout_ring must be canary|staged|stable")
    artifacts = payload.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise ValueError("artifacts must be a non-empty list")
    signature = payload.get("signature")
    if not isinstance(signature, dict):
        raise ValueError("signature must be an object")
    if signature.get("algorithm") not in {"ECDSA-P256-SHA256", "Ed25519"}:
        raise ValueError("unsupported signature.algorithm")
    if not str(signature.get("key_id") or "").strip():
        raise ValueError("signature.key_id required")
    if not str(signature.get("value") or "").strip():
        raise ValueError("signature.value required")
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise ValueError("artifact must be object")
        url = str(artifact.get("url") or "")
        if not url.startswith("https://"):
            raise ValueError("artifact.url must be https")
        parsed = urlparse(url)
        if not parsed.hostname:
            raise ValueError("artifact.url host required")


def publish_signed_manifest(ring: str, payload: dict[str, Any]) -> HostedUpdateManifest:
    if not _RING_RE.match(ring):
        raise ValueError(f"invalid ring: {ring}")
    if payload.get("rollout_ring") != ring:
        raise ValueError("payload.rollout_ring must match target ring")
    validate_signed_manifest(payload)
    root = update_manifest_dir()
    root.mkdir(parents=True, exist_ok=True)
    path = manifest_path_for_ring(ring)
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
    path.write_text(encoded + "\n", encoding="utf-8")
    return load_ring_manifest(ring)


def build_unsigned_manifest_template(
    *,
    ring: str,
    agent_version: str,
    minimum_agent_version: str,
    manifest_version: str,
    apk_url: str,
    apk_sha256: str,
    apk_size_bytes: int,
    key_id: str,
    artifact_name: str = "agent.apk",
) -> dict[str, Any]:
    """Build canonical unsigned body; operator must attach ECDSA signature.value."""
    if not _RING_RE.match(ring):
        raise ValueError(f"invalid ring: {ring}")
    return {
        "schema_version": "1.0",
        "manifest_version": manifest_version,
        "agent_version": agent_version,
        "minimum_agent_version": minimum_agent_version,
        "rollout_ring": ring,
        "artifacts": [
            {
                "name": artifact_name,
                "url": apk_url,
                "sha256": apk_sha256,
                "size_bytes": apk_size_bytes,
            }
        ],
        "issued_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "signature": {
            "algorithm": "ECDSA-P256-SHA256",
            "key_id": key_id,
            "value": "REPLACE_WITH_DER_ECDSA_SIGNATURE_BASE64",
        },
    }

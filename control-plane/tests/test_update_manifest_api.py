from __future__ import annotations

import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from loanagent.main import app
from loanagent.update_manifest import publish_signed_manifest

OPS_TOKEN = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")


def _signed(ring: str = "canary") -> dict:
    return {
        "schema_version": "1.0",
        "manifest_version": "0.2.0",
        "agent_version": "0.2.0",
        "minimum_agent_version": "0.1.0",
        "rollout_ring": ring,
        "artifacts": [
            {
                "name": "agent.apk",
                "url": "https://updates.example.com/agent.apk",
                "sha256": "a" * 64,
                "size_bytes": 12,
            }
        ],
        "issued_at": "2026-07-15T12:00:00Z",
        "signature": {
            "algorithm": "ECDSA-P256-SHA256",
            "key_id": "m0-key",
            "value": "dGVzdA==",
        },
    }


def test_list_update_manifests(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("UPDATE_MANIFEST_DIR", str(tmp_path))
    publish_signed_manifest("canary", _signed())
    with TestClient(app) as client:
        response = client.get(
            "/api/v1/update-manifests",
            headers={"Authorization": f"Bearer {OPS_TOKEN}"},
        )
    assert response.status_code == 200
    rings = {row["ring"]: row for row in response.json()["rings"]}
    assert rings["canary"]["available"] is True
    assert rings["canary"]["agent_version"] == "0.2.0"
    assert rings["stable"]["available"] is False

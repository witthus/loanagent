from __future__ import annotations

import os

import pytest

from loanagent.db import migrate_fleet_schema
from loanagent.device_upgrades import DeviceUpgradeRepository
from loanagent.devices import DeviceRepository
from loanagent.update_manifest import publish_signed_manifest


DATABASE_URL = os.environ["DATABASE_URL"]


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


@pytest.fixture()
def upgrades(tmp_path, monkeypatch: pytest.MonkeyPatch) -> DeviceUpgradeRepository:
    monkeypatch.setenv("UPDATE_MANIFEST_DIR", str(tmp_path))
    migrate_fleet_schema(DATABASE_URL)
    devices = DeviceRepository(DATABASE_URL)
    devices.migrate()
    devices.create(device_id="dev-upgrade-1", agent_version="0.1.0")
    publish_signed_manifest("canary", _signed())
    return DeviceUpgradeRepository(DATABASE_URL)


def test_request_and_pending(upgrades: DeviceUpgradeRepository) -> None:
    record = upgrades.request_upgrade(
        "dev-upgrade-1",
        ring="canary",
        public_base_url="https://cp.example.com",
    )
    assert record.status == "pending"
    assert record.manifest_url == "https://cp.example.com/downloads/update-manifests/canary.json"
    pending = upgrades.pending_for_device("dev-upgrade-1")
    assert pending is not None
    upgrades.report_result("dev-upgrade-1", status="succeeded", detail="SUCCESS")
    assert upgrades.pending_for_device("dev-upgrade-1") is None

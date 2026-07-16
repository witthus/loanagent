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


def test_clear_upgrade_removes_row(upgrades: DeviceUpgradeRepository) -> None:
    upgrades.request_upgrade(
        "dev-upgrade-1",
        ring="canary",
        public_base_url="https://cp.example.com",
    )
    assert upgrades.clear("dev-upgrade-1") is True
    assert upgrades.get("dev-upgrade-1") is None
    assert upgrades.clear("dev-upgrade-1") is False


def test_reconcile_marks_succeeded_when_agent_covers_target(
    upgrades: DeviceUpgradeRepository,
) -> None:
    upgrades.request_upgrade(
        "dev-upgrade-1",
        ring="canary",
        public_base_url="https://cp.example.com",
    )
    resolved = upgrades.reconcile_with_agent_version("dev-upgrade-1", "0.2.0-debug")
    assert resolved is not None
    assert resolved.status == "succeeded"
    assert "AUTO_RESOLVED" in (resolved.detail or "")


def test_agent_version_covers() -> None:
    from loanagent.device_upgrades import agent_version_covers

    assert agent_version_covers("0.1.10-debug", "0.1.10") is True
    assert agent_version_covers("0.1.9", "0.1.10") is False
    assert agent_version_covers(None, "0.1.10") is False

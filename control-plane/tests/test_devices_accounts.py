from __future__ import annotations

import os
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from loanagent.main import app
from loanagent.roles import AccountRole


DATABASE_URL = os.environ["DATABASE_URL"]


def unique_id(prefix: str) -> str:
    return f"{prefix}-{uuid4()}"


def test_device_repository_creates_device_and_heartbeat_marks_online() -> None:
    from loanagent.devices import DeviceRepository

    device_id = unique_id("device")
    repository = DeviceRepository(DATABASE_URL)
    repository.migrate()

    created = repository.create(
        device_id=device_id,
        manufacturer="Xiaomi",
        model="Redmi Note 12 Turbo",
        agent_version="0.1.0",
    )
    heartbeat = repository.heartbeat(
        device_id=device_id,
        agent_version="0.2.0",
        wifi_connected=True,
        a11y_bound=True,
        cellular_ok=False,
    )

    assert created.device_id == device_id
    assert heartbeat.device_id == device_id
    assert heartbeat.online is True
    assert heartbeat.last_seen_at is not None
    assert heartbeat.agent_version == "0.2.0"
    assert heartbeat.manufacturer == "Xiaomi"
    assert heartbeat.model == "Redmi Note 12 Turbo"
    assert heartbeat.wifi_connected is True
    assert heartbeat.a11y_bound is True
    assert heartbeat.cellular_ok is False


def test_account_repository_defaults_and_rejects_second_device_binding() -> None:
    from loanagent.accounts import AccountDeviceAlreadyBoundError, AccountRepository
    from loanagent.devices import DeviceRepository

    device_id = unique_id("device")
    main_account_id = unique_id("account-main")
    engager_account_id = unique_id("account-engager")
    duplicate_account_id = unique_id("account-duplicate")
    devices = DeviceRepository(DATABASE_URL)
    accounts = AccountRepository(DATABASE_URL)
    devices.migrate()
    devices.create(device_id=device_id)

    main = accounts.create(
        account_id=main_account_id,
        role=AccountRole.PUBLISHER_MAIN,
        device_id=device_id,
    )
    engager = accounts.create(
        account_id=engager_account_id,
        role=AccountRole.ENGAGER,
    )

    assert main.role is AccountRole.PUBLISHER_MAIN
    assert main.device_id == device_id
    assert main.daily_publish_quota == 1
    assert main.inbox_sync_enabled is True
    assert engager.role is AccountRole.ENGAGER
    assert engager.daily_publish_quota == 0
    assert engager.inbox_sync_enabled is False
    with pytest.raises(AccountDeviceAlreadyBoundError):
        accounts.create(
            account_id=duplicate_account_id,
            role=AccountRole.PUBLISHER_MATRIX,
            device_id=device_id,
        )


def test_fleet_migration_records_version_10_without_reusing_enrollment_versions() -> None:
    from loanagent.db import migrate_fleet_schema

    migrate_fleet_schema(DATABASE_URL)

    with psycopg.connect(DATABASE_URL) as connection:
        versions = [
            row[0]
            for row in connection.execute(
                "SELECT version FROM loanagent_schema_migrations ORDER BY version"
            ).fetchall()
        ]
        devices_exists = connection.execute(
            "SELECT to_regclass('devices') IS NOT NULL"
        ).fetchone()[0]
        accounts_exists = connection.execute(
            "SELECT to_regclass('accounts') IS NOT NULL"
        ).fetchone()[0]

    assert 1 not in versions or 10 in versions
    assert 2 not in versions or 10 in versions
    assert 10 in versions
    assert devices_exists is True
    assert accounts_exists is True


def test_device_heartbeat_api_upserts_online_device_and_lists_it() -> None:
    device_id = unique_id("device-api")

    with TestClient(app) as client:
        heartbeat = client.post(
            f"/api/v1/devices/{device_id}/heartbeat",
            json={
                "agent_version": "0.2.0",
                "wifi_connected": True,
                "a11y_bound": True,
                "cellular_ok": True,
            },
        )
        devices = client.get("/api/v1/devices")

    assert heartbeat.status_code == 200
    row = heartbeat.json()
    assert row["device_id"] == device_id
    assert row["online"] is True
    assert row["last_seen_at"] is not None
    assert row["agent_version"] == "0.2.0"
    assert row["wifi_connected"] is True
    assert row["a11y_bound"] is True
    assert row["cellular_ok"] is True
    assert devices.status_code == 200
    assert any(device["device_id"] == device_id for device in devices.json())


def test_account_api_creates_lists_and_blocks_duplicate_device_binding() -> None:
    device_id = unique_id("device-api")
    main_account_id = unique_id("account-main-api")
    engager_account_id = unique_id("account-engager-api")
    duplicate_account_id = unique_id("account-duplicate-api")

    with TestClient(app) as client:
        assert client.post(f"/api/v1/devices/{device_id}/heartbeat", json={}).status_code == 200
        main = client.post(
            "/api/v1/accounts",
            json={
                "account_id": main_account_id,
                "role": AccountRole.PUBLISHER_MAIN.value,
                "device_id": device_id,
            },
        )
        engager = client.post(
            "/api/v1/accounts",
            json={
                "account_id": engager_account_id,
                "role": AccountRole.ENGAGER.value,
            },
        )
        duplicate = client.post(
            "/api/v1/accounts",
            json={
                "account_id": duplicate_account_id,
                "role": AccountRole.PUBLISHER_MATRIX.value,
                "device_id": device_id,
            },
        )
        accounts = client.get("/api/v1/accounts")

    assert main.status_code == 200
    assert main.json()["device_id"] == device_id
    assert main.json()["daily_publish_quota"] == 1
    assert main.json()["inbox_sync_enabled"] is True
    assert engager.status_code == 200
    assert engager.json()["daily_publish_quota"] == 0
    assert engager.json()["inbox_sync_enabled"] is False
    assert duplicate.status_code == 409
    assert duplicate.json()["detail"]["code"] == "DEVICE_ALREADY_BOUND"
    assert accounts.status_code == 200
    account_ids = {account["account_id"] for account in accounts.json()}
    assert {main_account_id, engager_account_id}.issubset(account_ids)


def test_account_api_patches_pauses_and_resumes_account() -> None:
    account_id = unique_id("account-api")

    with TestClient(app) as client:
        created = client.post(
            "/api/v1/accounts",
            json={
                "account_id": account_id,
                "role": AccountRole.ENGAGER.value,
            },
        )
        patched = client.patch(
            f"/api/v1/accounts/{account_id}",
            json={
                "display_name": "Engager Alpha",
                "daily_publish_quota": 3,
                "network_policy": "wifi_allowed",
            },
        )
        paused = client.post(f"/api/v1/accounts/{account_id}/pause")
        resumed = client.post(f"/api/v1/accounts/{account_id}/resume")

    assert created.status_code == 200
    assert patched.status_code == 200
    assert patched.json()["display_name"] == "Engager Alpha"
    assert patched.json()["daily_publish_quota"] == 3
    assert patched.json()["network_policy"] == "wifi_allowed"
    assert paused.status_code == 200
    assert paused.json()["status"] == "paused"
    assert resumed.status_code == 200
    assert resumed.json()["status"] == "active"

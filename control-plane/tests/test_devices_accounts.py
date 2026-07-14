from __future__ import annotations

import os
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from loanagent.main import app
from loanagent.roles import AccountRole


DATABASE_URL = os.environ["DATABASE_URL"]
OPS_TOKEN = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")
DEVICE_TOKEN = os.environ.setdefault("DEVICE_TOKEN", "dev-only-device-token")


def ops_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {OPS_TOKEN}"}


def device_headers() -> dict[str, str]:
    return {"X-Device-Token": DEVICE_TOKEN}


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


def test_account_list_requires_ops_bearer() -> None:
    with TestClient(app) as client:
        without_header = client.get("/api/v1/accounts")
        with_bearer = client.get("/api/v1/accounts", headers=ops_headers())

    assert without_header.status_code == 401
    assert without_header.json()["detail"] == "unauthorized"
    assert with_bearer.status_code == 200


def test_device_heartbeat_requires_device_token() -> None:
    device_id = unique_id("device-auth")

    with TestClient(app) as client:
        without_header = client.post(f"/api/v1/devices/{device_id}/heartbeat", json={})
        with_token = client.post(
            f"/api/v1/devices/{device_id}/heartbeat",
            headers=device_headers(),
            json={},
        )

    assert without_header.status_code == 401
    assert without_header.json()["detail"] == "unauthorized"
    assert with_token.status_code == 200


def test_device_heartbeat_api_upserts_online_device_and_lists_it() -> None:
    device_id = unique_id("device-api")

    with TestClient(app) as client:
        heartbeat = client.post(
            f"/api/v1/devices/{device_id}/heartbeat",
            headers=device_headers(),
            json={
                "agent_version": "0.2.0",
                "wifi_connected": True,
                "a11y_bound": True,
                "cellular_ok": True,
            },
        )
        devices = client.get("/api/v1/devices", headers=ops_headers())

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


def test_device_heartbeat_captures_peer_ip_without_blocking_on_geo(monkeypatch) -> None:
    from loanagent import geo_ip
    from loanagent import main as main_mod

    device_id = unique_id("device-geo")
    clear_calls: list[str] = []

    def boom_lookup(ip: str | None, force_refresh: bool = False) -> str | None:
        clear_calls.append(ip or "")
        return "湖北黄冈"

    monkeypatch.setattr(main_mod, "extract_client_ip", lambda _request: "1.2.3.4")
    monkeypatch.setattr(geo_ip, "cached_geo_label", lambda _ip: None)
    monkeypatch.setattr(geo_ip, "needs_geo_refresh", lambda _ip: True)
    monkeypatch.setattr(geo_ip, "lookup_geo_label", boom_lookup)
    # Allow begin_geo_refresh to claim the slot
    monkeypatch.setattr(geo_ip, "begin_geo_refresh", lambda _ip: True)
    monkeypatch.setattr(geo_ip, "end_geo_refresh", lambda _ip: None)

    with TestClient(app) as client:
        heartbeat = client.post(
            f"/api/v1/devices/{device_id}/heartbeat",
            headers={**device_headers(), "X-Forwarded-For": "9.9.9.9"},
            json={"agent_version": "0.1.2-debug", "a11y_bound": True},
        )

    assert heartbeat.status_code == 200
    row = heartbeat.json()
    assert row["public_ip"] == "1.2.3.4"
    # Sync path must not wait on network; geo may arrive via background task.
    assert row["geo_label"] is None or row["geo_label"] == "湖北黄冈"


def test_device_repository_heartbeat_clears_stale_geo_when_ip_changes() -> None:
    from loanagent.devices import DeviceRepository

    device_id = unique_id("device-ip-change")
    repository = DeviceRepository(DATABASE_URL)
    repository.migrate()
    first = repository.heartbeat(
        device_id=device_id,
        agent_version="0.1.2",
        public_ip="1.1.1.1",
        geo_label="广东深圳",
    )
    second = repository.heartbeat(
        device_id=device_id,
        agent_version="0.1.2",
        public_ip="2.2.2.2",
        geo_label=None,
    )
    assert first.geo_label == "广东深圳"
    assert second.public_ip == "2.2.2.2"
    assert second.geo_label is None


def test_account_api_creates_lists_and_blocks_duplicate_device_binding() -> None:
    device_id = unique_id("device-api")
    main_account_id = unique_id("account-main-api")
    engager_account_id = unique_id("account-engager-api")
    duplicate_account_id = unique_id("account-duplicate-api")

    with TestClient(app) as client:
        assert (
            client.post(
                f"/api/v1/devices/{device_id}/heartbeat",
                headers=device_headers(),
                json={},
            ).status_code
            == 200
        )
        main = client.post(
            "/api/v1/accounts",
            headers=ops_headers(),
            json={
                "account_id": main_account_id,
                "role": AccountRole.PUBLISHER_MAIN.value,
                "device_id": device_id,
            },
        )
        engager = client.post(
            "/api/v1/accounts",
            headers=ops_headers(),
            json={
                "account_id": engager_account_id,
                "role": AccountRole.ENGAGER.value,
            },
        )
        duplicate = client.post(
            "/api/v1/accounts",
            headers=ops_headers(),
            json={
                "account_id": duplicate_account_id,
                "role": AccountRole.PUBLISHER_MATRIX.value,
                "device_id": device_id,
            },
        )
        accounts = client.get("/api/v1/accounts", headers=ops_headers())

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
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "role": AccountRole.ENGAGER.value,
            },
        )
        patched = client.patch(
            f"/api/v1/accounts/{account_id}",
            headers=ops_headers(),
            json={
                "display_name": "Engager Alpha",
                "daily_publish_quota": 3,
                "network_policy": "wifi_allowed",
                "role": AccountRole.PUBLISHER_MATRIX.value,
            },
        )
        paused = client.post(
            f"/api/v1/accounts/{account_id}/pause",
            headers=ops_headers(),
        )
        resumed = client.post(
            f"/api/v1/accounts/{account_id}/resume",
            headers=ops_headers(),
        )

    assert created.status_code == 200
    assert patched.status_code == 200
    assert patched.json()["display_name"] == "Engager Alpha"
    assert patched.json()["daily_publish_quota"] == 3
    assert patched.json()["network_policy"] == "wifi_allowed"
    assert patched.json()["role"] == AccountRole.PUBLISHER_MATRIX.value
    assert paused.status_code == 200
    assert paused.json()["status"] == "paused"
    assert resumed.status_code == 200
    assert resumed.json()["status"] == "active"


def test_account_api_deletes_account_and_unbinds_device() -> None:
    from loanagent.accounts import AccountNotFoundError, AccountRepository
    from loanagent.devices import DeviceRepository
    from loanagent.tasks import TaskService

    class RecordingMqttBus:
        def publish(self, topic: str, payload: dict) -> None:
            return None

    device_id = unique_id("device-adel")
    account_id = unique_id("account-adel")
    DeviceRepository(DATABASE_URL).migrate()
    DeviceRepository(DATABASE_URL).heartbeat(
        device_id=device_id,
        agent_version="0.1.5",
        a11y_bound=True,
    )

    with TestClient(app) as client:
        created = client.post(
            "/api/v1/accounts",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "role": AccountRole.PUBLISHER_MATRIX.value,
                "device_id": device_id,
                "display_name": "待删矩阵号",
            },
        )
        assert created.status_code == 200

        service = TaskService(DATABASE_URL, RecordingMqttBus())
        task = service._insert_queued_task(
            task_id=unique_id("task-adel"),
            operation_id=unique_id("op-adel"),
            device_id=device_id,
            account_id=account_id,
            playbook="sync_notes@1.0",
            params={},
            effect_class="readonly",
            priority=100,
            timeout_sec=120,
            source="manual",
        )
        service._update_status(task.task_id, status="accepted")

        deleted = client.delete(
            f"/api/v1/accounts/{account_id}",
            headers=ops_headers(),
        )
        assert deleted.status_code == 200
        assert deleted.json()["ok"] is True

        missing = client.get(
            f"/api/v1/accounts",
            headers=ops_headers(),
        )
        assert missing.status_code == 200
        assert account_id not in {row["account_id"] for row in missing.json()}

        device = DeviceRepository(DATABASE_URL).get(device_id)
        assert device.device_id == device_id

        again = client.delete(
            f"/api/v1/accounts/{account_id}",
            headers=ops_headers(),
        )
        assert again.status_code == 404
        assert again.json()["detail"]["code"] == "ACCOUNT_NOT_FOUND"

    with pytest.raises(AccountNotFoundError):
        AccountRepository(DATABASE_URL).get(account_id)


def test_mark_stale_offline_clears_online_flag() -> None:
    from loanagent.devices import DeviceRepository

    device_id = unique_id("device-stale")
    repository = DeviceRepository(DATABASE_URL)
    repository.migrate()
    repository.heartbeat(device_id=device_id, agent_version="0.1.0", a11y_bound=True)
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            """
            UPDATE devices
            SET last_seen_at = CURRENT_TIMESTAMP - INTERVAL '5 minutes'
            WHERE device_id = %s
            """,
            (device_id,),
        )
        connection.commit()
    changed = repository.mark_stale_offline(stale_after_sec=90)
    device = repository.get(device_id)
    assert changed >= 1
    assert device.online is False


def test_device_get_and_patch_routes() -> None:
    device_id = unique_id("device-api")
    with TestClient(app) as client:
        client.post(
            f"/api/v1/devices/{device_id}/heartbeat",
            headers=device_headers(),
            json={"agent_version": "0.5.0", "a11y_bound": True},
        )
        got = client.get(f"/api/v1/devices/{device_id}", headers=ops_headers())
        patched = client.patch(
            f"/api/v1/devices/{device_id}",
            headers=ops_headers(),
            json={"manufacturer": "Xiaomi", "model": "Turbo", "display_name": "红米主号机"},
        )
        missing = client.get("/api/v1/devices/does-not-exist", headers=ops_headers())
    assert got.status_code == 200
    assert got.json()["device_id"] == device_id
    assert got.json()["a11y_bound"] is True
    assert patched.status_code == 200
    assert patched.json()["manufacturer"] == "Xiaomi"
    assert patched.json()["model"] == "Turbo"
    assert patched.json()["display_name"] == "红米主号机"
    assert missing.status_code == 404


def test_cancel_task_route_stops_executing_task() -> None:
    from loanagent.devices import DeviceRepository
    from loanagent.tasks import TaskService

    class RecordingMqttBus:
        def publish(self, topic: str, payload: dict) -> None:
            return None

    device_id = unique_id("device-cancel")
    account_id = unique_id("account-cancel")
    DeviceRepository(DATABASE_URL).migrate()
    DeviceRepository(DATABASE_URL).heartbeat(
        device_id=device_id,
        agent_version="0.1.0",
        a11y_bound=True,
    )
    with TestClient(app) as client:
        client.post(
            "/api/v1/accounts",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "role": "PUBLISHER_MATRIX",
                "device_id": device_id,
            },
        )
        service = TaskService(DATABASE_URL, RecordingMqttBus())
        task = service._insert_queued_task(
            task_id=unique_id("task-cancel"),
            operation_id=unique_id("op-cancel"),
            device_id=device_id,
            account_id=account_id,
            playbook="sync_notes@1.0",
            params={},
            effect_class="readonly",
            priority=100,
            timeout_sec=120,
            source="manual",
        )
        service._update_status(task.task_id, status="executing")
        cancelled = client.post(
            f"/api/v1/tasks/{task.task_id}/cancel",
            headers=ops_headers(),
        )
        assert cancelled.status_code == 200
        assert cancelled.json()["status"] == "cancelled"
        again = client.post(
            f"/api/v1/tasks/{task.task_id}/cancel",
            headers=ops_headers(),
        )
        assert again.status_code == 409
        assert again.json()["detail"]["code"] == "TASK_ALREADY_TERMINAL"

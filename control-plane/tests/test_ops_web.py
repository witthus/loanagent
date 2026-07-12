from __future__ import annotations

import os
from uuid import uuid4

from fastapi.testclient import TestClient

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.main import app
from loanagent.roles import AccountRole
from loanagent.tasks import TaskService


DATABASE_URL = os.environ["DATABASE_URL"]
OPS_TOKEN = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")


class RecordingMqttBus:
    def __init__(self) -> None:
        self.published: list[tuple[str, dict]] = []

    def publish(self, topic: str, payload: dict) -> None:
        self.published.append((topic, payload))


def unique_id(prefix: str) -> str:
    return f"{prefix}-{uuid4()}"


def test_ops_login_page_renders() -> None:
    with TestClient(app) as client:
        response = client.get("/ops/login")

    assert response.status_code == 200
    assert "Ops Login" in response.text


def test_ops_login_rejects_wrong_token_with_unauthorized_page() -> None:
    with TestClient(app) as client:
        response = client.post("/ops/login", data={"token": "wrong-token"})

    assert response.status_code == 401
    assert "Invalid ops token" in response.text


def test_ops_login_sets_cookie_and_redirects_to_dashboard() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/ops/login",
            data={"token": OPS_TOKEN},
            follow_redirects=False,
        )

    assert response.status_code == 303
    assert response.headers["location"] == "/ops/"
    assert response.cookies["ops_session"] == OPS_TOKEN


def test_ops_devices_page_requires_cookie_and_renders_seeded_device() -> None:
    device_id = unique_id("ops-device")
    DeviceRepository(DATABASE_URL).create(device_id=device_id, manufacturer="Xiaomi")

    with TestClient(app) as client:
        unauthenticated = client.get("/ops/devices", follow_redirects=False)
        client.cookies.set("ops_session", OPS_TOKEN)
        authenticated = client.get("/ops/devices")

    assert unauthenticated.status_code == 303
    assert unauthenticated.headers["location"] == "/ops/login"
    assert authenticated.status_code == 200
    assert device_id in authenticated.text
    assert "Xiaomi" in authenticated.text


def test_ops_accounts_and_tasks_pages_render_repository_tables() -> None:
    device_id = unique_id("ops-device")
    account_id = unique_id("ops-account")
    task_id = unique_id("ops-task")
    devices = DeviceRepository(DATABASE_URL)
    accounts = AccountRepository(DATABASE_URL)
    devices.heartbeat(device_id=device_id, agent_version="0.4.0")
    accounts.create(
        account_id=account_id,
        role=AccountRole.PUBLISHER_MAIN,
        device_id=device_id,
    )
    service = TaskService(DATABASE_URL, RecordingMqttBus())
    service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=task_id,
    )

    with TestClient(app) as client:
        client.cookies.set("ops_session", OPS_TOKEN)
        accounts_response = client.get("/ops/accounts")
        tasks_response = client.get("/ops/tasks")

    assert accounts_response.status_code == 200
    assert account_id in accounts_response.text
    assert AccountRole.PUBLISHER_MAIN.value in accounts_response.text
    assert "active" in accounts_response.text
    assert tasks_response.status_code == 200
    assert task_id in tasks_response.text
    assert "ensure_app_ready@1.0" in tasks_response.text
    assert "accepted" in tasks_response.text

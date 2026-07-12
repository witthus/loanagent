from __future__ import annotations

import json
import os
import sys
import types
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.main import app
from loanagent.mqtt_bus import MqttCommandBus
from loanagent.roles import AccountRole
from loanagent.tasks import DuplicateTaskError, TaskService


DATABASE_URL = os.environ["DATABASE_URL"]
OPS_TOKEN = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")
SCHEMA_ROOT = Path(__file__).resolve().parents[2] / "schemas"


def ops_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {OPS_TOKEN}"}


class RecordingMqttBus:
    def __init__(self, *, database_url: str | None = None, task_id: str | None = None) -> None:
        self.database_url = database_url
        self.task_id = task_id
        self.published: list[tuple[str, dict]] = []
        self.status_seen_at_publish: str | None = None

    def publish(self, topic: str, payload: dict) -> None:
        if self.database_url is not None and self.task_id is not None:
            with psycopg.connect(self.database_url) as connection:
                self.status_seen_at_publish = connection.execute(
                    "SELECT status FROM tasks WHERE task_id = %s",
                    (self.task_id,),
                ).fetchone()[0]
        self.published.append((topic, payload))


class FailingMqttBus:
    def publish(self, topic: str, payload: dict) -> None:
        raise RuntimeError("broker unavailable")


def unique_id(prefix: str) -> str:
    return f"{prefix}-{uuid4()}"


def create_bound_account(
    *,
    device_id: str | None = None,
    account_id: str | None = None,
    role: AccountRole = AccountRole.PUBLISHER_MAIN,
    online: bool = True,
) -> tuple[str, str]:
    device_id = device_id or unique_id("device")
    account_id = account_id or unique_id("account")
    devices = DeviceRepository(DATABASE_URL)
    accounts = AccountRepository(DATABASE_URL)
    devices.migrate()
    if online:
        devices.heartbeat(device_id=device_id, agent_version="0.3.0")
    else:
        devices.create(device_id=device_id, agent_version="0.3.0")
    accounts.create(account_id=account_id, role=role, device_id=device_id)
    return device_id, account_id


def task_schema_validator() -> Draft202012Validator:
    schema = json.loads((SCHEMA_ROOT / "task.schema.json").read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def test_mqtt_command_bus_starts_network_loop_and_waits_with_timeout(monkeypatch) -> None:
    class FakePublishResult:
        rc = 0

        def __init__(self) -> None:
            self.timeout: float | None = None

        def wait_for_publish(self, timeout: float | None = None) -> None:
            self.timeout = timeout

        def is_published(self) -> bool:
            return True

    class FakeClient:
        def __init__(self, callback_api_version: object) -> None:
            self.callback_api_version = callback_api_version
            self.calls: list[str] = []
            self.result = FakePublishResult()
            clients.append(self)

        def username_pw_set(self, username: str, password: str | None) -> None:
            self.calls.append(f"username:{username}:{password}")

        def connect(self, host: str, port: int, keepalive: int) -> int:
            self.calls.append(f"connect:{host}:{port}:{keepalive}")
            return 0

        def loop_start(self) -> int:
            self.calls.append("loop_start")
            return 0

        def publish(self, topic: str, payload: str, qos: int) -> FakePublishResult:
            self.calls.append(f"publish:{topic}:{payload}:{qos}")
            return self.result

        def disconnect(self) -> int:
            self.calls.append("disconnect")
            return 0

        def loop_stop(self) -> int:
            self.calls.append("loop_stop")
            return 0

    clients: list[FakeClient] = []
    client_module = types.ModuleType("paho.mqtt.client")
    client_module.CallbackAPIVersion = types.SimpleNamespace(VERSION2=object())
    client_module.MQTT_ERR_SUCCESS = 0
    client_module.Client = FakeClient
    client_module.error_string = lambda rc: f"mqtt error {rc}"
    mqtt_package = types.ModuleType("paho.mqtt")
    mqtt_package.client = client_module
    paho_package = types.ModuleType("paho")
    paho_package.mqtt = mqtt_package
    monkeypatch.setitem(sys.modules, "paho", paho_package)
    monkeypatch.setitem(sys.modules, "paho.mqtt", mqtt_package)
    monkeypatch.setitem(sys.modules, "paho.mqtt.client", client_module)

    MqttCommandBus("mqtt://user:secret@emqx:1883").publish("devices/device-1/commands", {"a": 1})

    assert clients[0].calls == [
        "username:user:secret",
        "connect:emqx:1883:30",
        "loop_start",
        'publish:devices/device-1/commands:{"a":1}:1',
        "disconnect",
        "loop_stop",
    ]
    assert clients[0].result.timeout is not None


def test_create_and_dispatch_persists_queued_then_marks_accepted_after_publish() -> None:
    task_id = unique_id("task")
    operation_id = unique_id("operation")
    device_id, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus(database_url=DATABASE_URL, task_id=task_id)
    service = TaskService(DATABASE_URL, mqtt_bus)

    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        params={"target": "xhs"},
        operation_id=operation_id,
        task_id=task_id,
    )

    assert mqtt_bus.status_seen_at_publish == "queued"
    assert task.status == "accepted"
    assert task.effect_class == "readonly"
    assert task.effect_committed is False
    assert mqtt_bus.published == [
        (
            f"devices/{device_id}/commands",
            {
                "schema_version": "1.0",
                "task_id": task_id,
                "operation_id": operation_id,
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {"target": "xhs"},
                "effect_class": "readonly",
                "effect_committed": False,
                "status": "queued",
                "reconcile_required": False,
                "priority": 100,
                "timeout_sec": 120,
                "source": "manual",
            },
        )
    ]


def test_create_and_dispatch_publishes_schema_valid_account_scoped_command() -> None:
    task_id = unique_id("task")
    operation_id = unique_id("operation")
    _, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()
    service = TaskService(DATABASE_URL, mqtt_bus)

    service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        params={},
        operation_id=operation_id,
        task_id=task_id,
    )

    _, payload = mqtt_bus.published[0]
    assert payload["account_id"] == account_id
    assert "device_id" not in payload
    task_schema_validator().validate(payload)


def test_create_task_route_requires_ops_bearer() -> None:
    _, account_id = create_bound_account()

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        response = client.post(
            "/api/v1/tasks",
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
            },
        )

    assert response.status_code == 401
    assert response.json()["detail"] == "unauthorized"


def test_create_task_route_marks_task_failed_when_publish_fails() -> None:
    _, account_id = create_bound_account()
    task_id = unique_id("task")
    service = TaskService(DATABASE_URL, FailingMqttBus())

    with TestClient(app, raise_server_exceptions=False) as client:
        client.app.state.task_service = service
        response = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
                "task_id": task_id,
            },
        )

    assert response.status_code == 502
    assert response.json()["detail"]["code"] == "TASK_DISPATCH_FAILED"
    tasks = service.list(account_id=account_id)
    assert [(task.task_id, task.status) for task in tasks] == [(task_id, "failed")]


def test_create_task_route_rejects_publish_note_for_engager() -> None:
    _, account_id = create_bound_account(role=AccountRole.ENGAGER)

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        response = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "publish_note@1.0",
                "params": {},
            },
        )

    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "PLAYBOOK_FORBIDDEN"


def test_create_task_route_rejects_paused_account() -> None:
    _, account_id = create_bound_account()
    AccountRepository(DATABASE_URL).pause(account_id)

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        response = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
            },
        )

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "ACCOUNT_UNAVAILABLE"


def test_create_task_route_rejects_offline_device() -> None:
    _, account_id = create_bound_account(online=False)

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        response = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
            },
        )

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "DEVICE_UNAVAILABLE"


def test_create_task_route_rejects_unbound_device() -> None:
    account_id = unique_id("account-unbound")
    AccountRepository(DATABASE_URL).create(account_id=account_id, role=AccountRole.ENGAGER)

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        response = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
            },
        )

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "DEVICE_UNAVAILABLE"


def test_create_task_route_rejects_duplicate_task_id_without_republishing() -> None:
    _, account_id = create_bound_account()
    task_id = unique_id("task")
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, mqtt_bus)
        first = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
                "task_id": task_id,
            },
        )
        duplicate = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
                "task_id": task_id,
            },
        )

    assert first.status_code == 200
    assert duplicate.status_code == 409
    assert duplicate.json()["detail"]["code"] == "TASK_ALREADY_EXISTS"
    assert len(mqtt_bus.published) == 1


def test_duplicate_task_id_is_rejected_by_service() -> None:
    _, account_id = create_bound_account()
    task_id = unique_id("task")
    service = TaskService(DATABASE_URL, RecordingMqttBus())

    service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        params={},
        task_id=task_id,
    )

    with pytest.raises(DuplicateTaskError):
        service.create_and_dispatch(
            account_id=account_id,
            playbook="ensure_app_ready@1.0",
            params={},
            task_id=task_id,
        )


def test_task_route_lists_tasks_with_filters() -> None:
    _, account_id = create_bound_account()
    task_id = unique_id("task")

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        created = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
                "task_id": task_id,
            },
        )
        listed = client.get(
            "/api/v1/tasks",
            headers=ops_headers(),
            params={"account_id": account_id, "status": "accepted"},
        )

    assert created.status_code == 200
    assert listed.status_code == 200
    assert [task["task_id"] for task in listed.json()] == [task_id]


def test_device_event_hook_marks_readonly_task_succeeded_and_effect_committed() -> None:
    device_id, account_id = create_bound_account()
    task_id = unique_id("task")

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        created = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
                "task_id": task_id,
            },
        )
        event = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers=ops_headers(),
            json={"task_id": task_id, "status": "succeeded"},
        )

    assert created.status_code == 200
    assert event.status_code == 200
    body = event.json()
    assert body["task_id"] == task_id
    assert body["status"] == "succeeded"
    assert body["effect_committed"] is True

from __future__ import annotations

import os
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.main import app
from loanagent.roles import AccountRole
from loanagent.tasks import DuplicateTaskError, TaskService


DATABASE_URL = os.environ["DATABASE_URL"]


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


def test_create_task_route_rejects_publish_note_for_engager() -> None:
    _, account_id = create_bound_account(role=AccountRole.ENGAGER)

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        response = client.post(
            "/api/v1/tasks",
            json={
                "account_id": account_id,
                "playbook": "publish_note@1.0",
                "params": {},
            },
        )

    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "PLAYBOOK_FORBIDDEN"


def test_create_task_route_rejects_duplicate_task_id_without_republishing() -> None:
    _, account_id = create_bound_account()
    task_id = unique_id("task")
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, mqtt_bus)
        first = client.post(
            "/api/v1/tasks",
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
                "task_id": task_id,
            },
        )
        duplicate = client.post(
            "/api/v1/tasks",
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
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
                "task_id": task_id,
            },
        )
        listed = client.get(
            "/api/v1/tasks",
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
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "params": {},
                "task_id": task_id,
            },
        )
        event = client.post(
            f"/api/v1/devices/{device_id}/events",
            json={"task_id": task_id, "status": "succeeded"},
        )

    assert created.status_code == 200
    assert event.status_code == 200
    body = event.json()
    assert body["task_id"] == task_id
    assert body["status"] == "succeeded"
    assert body["effect_committed"] is True

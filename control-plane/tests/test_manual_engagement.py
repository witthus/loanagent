from __future__ import annotations

import os
from uuid import uuid4

from fastapi.testclient import TestClient

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.engagement import EngagementService
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


def _headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {OPS_TOKEN}"}


def test_manual_engagement_skips_auto_and_creates_chain() -> None:
    devices = DeviceRepository(DATABASE_URL)
    accounts = AccountRepository(DATABASE_URL)
    accounts.migrate()
    publisher_device = f"man-pub-dev-{uuid4()}"
    engager_device = f"man-eng-dev-{uuid4()}"
    publisher_id = f"man-pub-{uuid4()}"
    engager_id = f"man-eng-{uuid4()}"
    devices.heartbeat(
        device_id=publisher_device,
        agent_version="0.4.0",
        a11y_bound=True,
        cellular_ok=True,
    )
    devices.heartbeat(
        device_id=engager_device,
        agent_version="0.4.0",
        a11y_bound=True,
        cellular_ok=True,
    )
    accounts.create(
        account_id=publisher_id,
        role=AccountRole.PUBLISHER_MAIN,
        device_id=publisher_device,
    )
    accounts.create(
        account_id=engager_id,
        role=AccountRole.ENGAGER,
        device_id=engager_device,
    )

    mqtt_bus = RecordingMqttBus()
    task_id = f"man-publish-{uuid4()}"
    with TestClient(app) as client:
        task_service = TaskService(DATABASE_URL, mqtt_bus)
        engagement = EngagementService(DATABASE_URL, task_service)
        task_service.engagement_service = engagement
        client.app.state.task_service = task_service
        client.app.state.engagement_service = engagement

        created = client.post(
            "/api/v1/tasks",
            headers=_headers(),
            json={
                "account_id": publisher_id,
                "playbook": "publish_note@1.0",
                "params": {
                    "title": "重要笔记",
                    "body": "手动互动",
                    "engagement_mode": "manual",
                },
                "task_id": task_id,
            },
        )
        event = client.post(
            f"/api/v1/devices/{publisher_device}/events",
            headers=_headers(),
            json={"task_id": task_id, "status": "succeeded"},
        )
        chains_before = client.get("/api/v1/engagement/chains", headers=_headers()).json()
        matching_before = [c for c in chains_before if c["publish_task_id"] == task_id]
        manual = client.post(
            "/api/v1/engagement/chains",
            headers=_headers(),
            json={
                "publish_task_id": task_id,
                "engager_account_ids": [engager_id],
                "platform": "xhs",
            },
        )
        chains_after = client.get("/api/v1/engagement/chains", headers=_headers()).json()
        matching_after = [c for c in chains_after if c["publish_task_id"] == task_id]

    assert created.status_code == 200
    assert event.status_code == 200
    assert matching_before == []
    assert manual.status_code == 200
    body = manual.json()
    assert body["mode"] == "manual"
    assert body["engager_account_id"] == engager_id
    assert body["engager_account_ids"] == [engager_id]
    assert body["status"] == "pending"
    assert len(matching_after) == 1

from __future__ import annotations

import os
from uuid import uuid4

import psycopg
from fastapi.testclient import TestClient
from psycopg.types.json import Jsonb

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


def ops_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {OPS_TOKEN}"}


def unique_id(prefix: str) -> str:
    return f"{prefix}-{uuid4()}"


def create_bound_account(
    *,
    role: AccountRole = AccountRole.PUBLISHER_MAIN,
    online: bool = True,
    a11y_bound: bool = True,
) -> tuple[str, str]:
    device_id = unique_id("device")
    account_id = unique_id("account")
    devices = DeviceRepository(DATABASE_URL)
    accounts = AccountRepository(DATABASE_URL)
    devices.migrate()
    if online:
        devices.heartbeat(
            device_id=device_id,
            agent_version="0.3.0",
            a11y_bound=a11y_bound,
            wifi_connected=False,
            cellular_ok=True,
        )
    else:
        devices.create(device_id=device_id, agent_version="0.3.0")
    accounts.create(account_id=account_id, role=role, device_id=device_id)
    return device_id, account_id


def wire_services(client: TestClient, mqtt_bus: RecordingMqttBus) -> TaskService:
    task_service = TaskService(DATABASE_URL, mqtt_bus)
    engagement = EngagementService(DATABASE_URL, task_service)
    task_service.engagement_service = engagement
    client.app.state.task_service = task_service
    client.app.state.engagement_service = engagement
    return task_service


def set_chain_delay(chain_id: str, delay_sec: int = 0) -> None:
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            """
            UPDATE engagement_chains
            SET config = config || %s::jsonb,
                updated_at = CURRENT_TIMESTAMP
            WHERE chain_id = %s
            """,
            (Jsonb({"delay_sec": delay_sec}), chain_id),
        )


def test_publish_success_creates_engagement_chain() -> None:
    publisher_device, publisher_id = create_bound_account()
    _, engager_id = create_bound_account(role=AccountRole.ENGAGER)
    mqtt_bus = RecordingMqttBus()
    task_id = unique_id("publish")

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        created = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": publisher_id,
                "playbook": "publish_note@1.0",
                "params": {"title": "探店", "body": "不错"},
                "task_id": task_id,
            },
        )
        event = client.post(
            f"/api/v1/devices/{publisher_device}/events",
            headers=ops_headers(),
            json={"task_id": task_id, "status": "succeeded"},
        )
        chains = client.get("/api/v1/engagement/chains", headers=ops_headers())

    assert created.status_code == 200
    assert event.status_code == 200
    assert event.json()["effect_committed"] is True
    assert chains.status_code == 200
    matching = [c for c in chains.json() if c["publish_task_id"] == task_id]
    assert len(matching) == 1
    chain = matching[0]
    assert chain["account_id"] == publisher_id
    assert chain["engager_account_id"] == engager_id
    assert chain["status"] == "pending"


def test_advance_with_zero_delay_creates_post_comment_for_engager() -> None:
    publisher_device, publisher_id = create_bound_account()
    engager_device, engager_id = create_bound_account(role=AccountRole.ENGAGER)
    mqtt_bus = RecordingMqttBus()
    task_id = unique_id("publish")

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": publisher_id,
                "playbook": "publish_note@1.0",
                "params": {"title": "探店", "body": "不错"},
                "task_id": task_id,
            },
        )
        client.post(
            f"/api/v1/devices/{publisher_device}/events",
            headers=ops_headers(),
            json={"task_id": task_id, "status": "succeeded"},
        )
        chains = client.get("/api/v1/engagement/chains", headers=ops_headers()).json()
        chain_id = next(c["chain_id"] for c in chains if c["publish_task_id"] == task_id)
        set_chain_delay(chain_id, 0)

        advanced = client.post(
            f"/api/v1/engagement/chains/{chain_id}/advance",
            headers=ops_headers(),
        )
        tasks = client.get(
            "/api/v1/tasks",
            headers=ops_headers(),
            params={"account_id": engager_id},
        )

    assert advanced.status_code == 200
    body = advanced.json()
    assert body["status"] == "running"
    assert body["post_comment_task_ids"]
    assert tasks.status_code == 200
    comment_tasks = [t for t in tasks.json() if t["playbook"] == "post_comment@1.0"]
    assert len(comment_tasks) == 1
    assert comment_tasks[0]["account_id"] == engager_id
    assert comment_tasks[0]["device_id"] == engager_device
    assert comment_tasks[0]["params"]["text"] == "看起来不错，求同款渠道～"
    assert any(
        topic == f"devices/{engager_device}/commands"
        and payload["playbook"] == "post_comment@1.0"
        for topic, payload in mqtt_bus.published
    )


def test_post_comment_success_creates_reply_comment_for_publisher() -> None:
    publisher_device, publisher_id = create_bound_account()
    engager_device, engager_id = create_bound_account(role=AccountRole.ENGAGER)
    mqtt_bus = RecordingMqttBus()
    publish_task_id = unique_id("publish")

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": publisher_id,
                "playbook": "publish_note@1.0",
                "params": {"title": "探店", "body": "不错"},
                "task_id": publish_task_id,
            },
        )
        client.post(
            f"/api/v1/devices/{publisher_device}/events",
            headers=ops_headers(),
            json={"task_id": publish_task_id, "status": "succeeded"},
        )
        chains = client.get("/api/v1/engagement/chains", headers=ops_headers()).json()
        chain_id = next(c["chain_id"] for c in chains if c["publish_task_id"] == publish_task_id)
        set_chain_delay(chain_id, 0)
        advanced = client.post(
            f"/api/v1/engagement/chains/{chain_id}/advance",
            headers=ops_headers(),
        ).json()
        post_comment_task_id = advanced["post_comment_task_ids"][0]

        event = client.post(
            f"/api/v1/devices/{engager_device}/events",
            headers=ops_headers(),
            json={"task_id": post_comment_task_id, "status": "succeeded"},
        )
        chain = client.get("/api/v1/engagement/chains", headers=ops_headers()).json()
        chain = next(c for c in chain if c["chain_id"] == chain_id)
        publisher_tasks = client.get(
            "/api/v1/tasks",
            headers=ops_headers(),
            params={"account_id": publisher_id},
        ).json()

    assert event.status_code == 200
    assert chain["status"] == "awaiting_reply"
    assert chain["reply_comment_task_ids"]
    reply_tasks = [t for t in publisher_tasks if t["playbook"] == "reply_comment@1.0"]
    assert len(reply_tasks) == 1
    assert reply_tasks[0]["account_id"] == publisher_id
    assert reply_tasks[0]["task_id"] == chain["reply_comment_task_ids"][0]


def test_business_blocked_stops_chain_and_creates_alert() -> None:
    publisher_device, publisher_id = create_bound_account()
    engager_device, _engager_id = create_bound_account(role=AccountRole.ENGAGER)
    mqtt_bus = RecordingMqttBus()
    publish_task_id = unique_id("publish")

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": publisher_id,
                "playbook": "publish_note@1.0",
                "params": {"title": "探店", "body": "不错"},
                "task_id": publish_task_id,
            },
        )
        client.post(
            f"/api/v1/devices/{publisher_device}/events",
            headers=ops_headers(),
            json={"task_id": publish_task_id, "status": "succeeded"},
        )
        chains = client.get("/api/v1/engagement/chains", headers=ops_headers()).json()
        chain_id = next(c["chain_id"] for c in chains if c["publish_task_id"] == publish_task_id)
        set_chain_delay(chain_id, 0)
        advanced = client.post(
            f"/api/v1/engagement/chains/{chain_id}/advance",
            headers=ops_headers(),
        ).json()
        post_comment_task_id = advanced["post_comment_task_ids"][0]

        event = client.post(
            f"/api/v1/devices/{engager_device}/events",
            headers=ops_headers(),
            json={
                "task_id": post_comment_task_id,
                "status": "failed",
                "error_code": "BUSINESS_BLOCKED",
            },
        )
        chain = next(
            c
            for c in client.get("/api/v1/engagement/chains", headers=ops_headers()).json()
            if c["chain_id"] == chain_id
        )
        alerts = client.get("/api/v1/alerts", headers=ops_headers())

    assert event.status_code == 200
    assert chain["status"] == "stopped"
    assert chain["stop_reason"] == "BUSINESS_BLOCKED"
    assert alerts.status_code == 200
    matching = [a for a in alerts.json() if a["ref_id"] == chain_id]
    assert matching
    assert matching[0]["kind"] == "engagement_stopped"
    assert "BUSINESS_BLOCKED" in matching[0]["message"]

from __future__ import annotations

import os
from uuid import uuid4

from fastapi.testclient import TestClient

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.inbox import InboxService, reject_plaintext_contact
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
    inbox = InboxService(DATABASE_URL, task_service)
    client.app.state.task_service = task_service
    client.app.state.inbox_service = inbox
    return task_service


def test_reject_plaintext_contact_patterns() -> None:
    reject_plaintext_contact("谢谢支持，详情可私信了解～")
    for bad in (
        "加我微信号 abc_loan",
        "微信：wxid_hello",
        "电话 13812345678",
        "联系我 15900001111",
        "my wx is loan_helper88",
    ):
        try:
            reject_plaintext_contact(bad)
            raise AssertionError(f"expected reject for: {bad}")
        except ValueError:
            pass


def test_ingest_upserts_threads() -> None:
    _, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        first = client.post(
            "/api/v1/inbox/ingest",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "threads": [
                    {
                        "title_summary": "用户甲",
                        "preview_summary": "你好",
                        "unread": True,
                        "messages": [
                            {"sender_summary": "用户甲", "body_summary": "你好"},
                        ],
                    }
                ],
            },
        )
        second = client.post(
            "/api/v1/inbox/ingest",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "threads": [
                    {
                        "title_summary": "用户甲",
                        "preview_summary": "想了解一下",
                        "unread": False,
                    }
                ],
            },
        )
        listed = client.get(
            "/api/v1/inbox/threads",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert first.status_code == 200
    assert second.status_code == 200
    assert listed.status_code == 200
    threads = listed.json()
    matching = [t for t in threads if t["title_summary"] == "用户甲"]
    assert len(matching) == 1
    assert matching[0]["preview_summary"] == "想了解一下"
    assert matching[0]["unread"] is False
    assert matching[0]["account_id"] == account_id


def test_reply_with_wechat_or_phone_rejected() -> None:
    _, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        wechat = client.post(
            "/api/v1/inbox/reply",
            headers=ops_headers(),
            json={"account_id": account_id, "text": "我的微信号是 abc_loan"},
        )
        phone = client.post(
            "/api/v1/inbox/reply",
            headers=ops_headers(),
            json={"account_id": account_id, "text": "打我电话 13812345678"},
        )

    assert wechat.status_code == 400
    assert wechat.json()["detail"]["code"] == "CONTACT_FORBIDDEN"
    assert phone.status_code == 400
    assert phone.json()["detail"]["code"] == "CONTACT_FORBIDDEN"
    assert mqtt_bus.published == []


def test_reply_clean_text_creates_task() -> None:
    device_id, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        reply = client.post(
            "/api/v1/inbox/reply",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "text": "谢谢关注，详情可私信了解～",
                "thread_id": "thread-demo",
            },
        )
        tasks = client.get(
            "/api/v1/tasks",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert reply.status_code == 200
    body = reply.json()
    assert body["playbook"] == "reply_dm@1.0"
    assert body["account_id"] == account_id
    assert body["params"]["text"] == "谢谢关注，详情可私信了解～"
    assert body["params"]["thread_id"] == "thread-demo"
    assert tasks.status_code == 200
    dm_tasks = [t for t in tasks.json() if t["playbook"] == "reply_dm@1.0"]
    assert len(dm_tasks) == 1
    assert any(
        topic == f"devices/{device_id}/commands"
        and payload["playbook"] == "reply_dm@1.0"
        for topic, payload in mqtt_bus.published
    )


def test_sync_creates_inbox_sync_task() -> None:
    device_id, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        sync = client.post(
            "/api/v1/inbox/sync",
            headers=ops_headers(),
            json={"account_id": account_id},
        )
        tasks = client.get(
            "/api/v1/tasks",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert sync.status_code == 200
    body = sync.json()
    assert body["playbook"] == "inbox_sync@1.0"
    assert body["effect_class"] == "readonly"
    assert body["account_id"] == account_id
    assert tasks.status_code == 200
    sync_tasks = [t for t in tasks.json() if t["playbook"] == "inbox_sync@1.0"]
    assert len(sync_tasks) == 1
    assert any(
        topic == f"devices/{device_id}/commands"
        and payload["playbook"] == "inbox_sync@1.0"
        for topic, payload in mqtt_bus.published
    )


def test_lead_mark_works() -> None:
    _, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        ingest = client.post(
            "/api/v1/inbox/ingest",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "threads": [
                    {
                        "title_summary": "高意向用户",
                        "preview_summary": "想办",
                        "unread": True,
                    }
                ],
            },
        )
        threads = client.get(
            "/api/v1/inbox/threads",
            headers=ops_headers(),
            params={"account_id": account_id},
        ).json()
        thread_id = next(t["thread_id"] for t in threads if t["title_summary"] == "高意向用户")
        lead = client.post(
            "/api/v1/inbox/leads",
            headers=ops_headers(),
            json={"thread_id": thread_id, "status": "hot", "note": "已沟通"},
        )
        listed = client.get("/api/v1/inbox/leads", headers=ops_headers())

    assert ingest.status_code == 200
    assert lead.status_code == 200
    body = lead.json()
    assert body["thread_id"] == thread_id
    assert body["status"] == "hot"
    assert body["note"] == "已沟通"
    assert listed.status_code == 200
    matching = [item for item in listed.json() if item["thread_id"] == thread_id]
    assert len(matching) == 1
    assert matching[0]["status"] == "hot"

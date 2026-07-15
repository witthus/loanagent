from __future__ import annotations

import os
from uuid import uuid4

import psycopg
from fastapi.testclient import TestClient

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.inbox import InboxService
from loanagent.main import app
from loanagent.notes import NotesService
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
    notes = NotesService(DATABASE_URL, task_service)
    inbox = InboxService(DATABASE_URL, task_service)
    client.app.state.task_service = task_service
    client.app.state.notes_service = notes
    client.app.state.inbox_service = inbox
    return task_service


def test_comments_event_creates_note_comments_rows() -> None:
    device_id, account_id = create_bound_account()
    task_id = unique_id("task")
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        created = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "read_comments@1.0",
                "params": {},
                "task_id": task_id,
            },
        )
        event = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers=ops_headers(),
            json={
                "task_id": task_id,
                "status": "succeeded",
                "result_payload": {
                    "kind": "comments",
                    "note_ref": "note-hint-1",
                    "items": [
                        {
                            "author_summary": "用户甲",
                            "body_summary": "求同款",
                            "locator_hint": "index:0",
                        },
                        {
                            "author_summary": "用户乙",
                            "body_summary": "看起来不错",
                        },
                    ],
                },
            },
        )
        notes = client.get(
            "/api/v1/notes",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert created.status_code == 200
    assert event.status_code == 200
    assert notes.status_code == 200
    note_list = notes.json()
    assert len(note_list) >= 1
    note_id = note_list[0]["note_id"]

    with psycopg.connect(DATABASE_URL) as connection:
        rows = connection.execute(
            """
            SELECT author_summary, body_summary, locator_hint, source_task_id
            FROM note_comments
            WHERE note_id = %s
            """,
            (note_id,),
        ).fetchall()
    assert len(rows) == 2
    by_author = {row[0]: row for row in rows}
    assert by_author["用户甲"][1] == "求同款"
    assert by_author["用户甲"][2] == "index:0"
    assert by_author["用户甲"][3] == task_id
    assert by_author["用户乙"][1] == "看起来不错"


def test_inbox_event_creates_threads() -> None:
    device_id, account_id = create_bound_account()
    task_id = unique_id("task")
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        created = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "inbox_sync@1.0",
                "params": {},
                "task_id": task_id,
            },
        )
        event = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers=ops_headers(),
            json={
                "task_id": task_id,
                "status": "succeeded",
                "result_payload": {
                    "kind": "inbox",
                    "threads": [
                        {
                            "title_summary": "静生百慧茶叶馆",
                            "preview_summary": "在吗",
                            "unread": True,
                            "messages": [
                                {
                                    "sender_summary": "静生百慧茶叶馆",
                                    "body_summary": "在吗",
                                }
                            ],
                        }
                    ],
                },
            },
        )
        threads = client.get(
            "/api/v1/inbox/threads",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert created.status_code == 200
    assert event.status_code == 200
    assert threads.status_code == 200
    matching = [t for t in threads.json() if t["title_summary"] == "静生百慧茶叶馆"]
    assert len(matching) == 1
    thread_id = matching[0]["thread_id"]

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        messages = client.get(
            f"/api/v1/inbox/threads/{thread_id}/messages",
            headers=ops_headers(),
        )
    assert messages.status_code == 200
    assert len(messages.json()) == 1
    assert messages.json()[0]["body_summary"] == "在吗"


def test_sync_comments_creates_read_comments_task() -> None:
    device_id, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()
    notes = NotesService(DATABASE_URL, TaskService(DATABASE_URL, mqtt_bus))
    note = notes._insert_note(
        note_id=unique_id("note"),
        account_id=account_id,
        xhs_hint=None,
        title_summary="demo note",
    )

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        sync = client.post(
            f"/api/v1/notes/{note.note_id}/sync-comments",
            headers=ops_headers(),
        )
        tasks = client.get(
            "/api/v1/tasks",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert sync.status_code == 200
    body = sync.json()
    assert body["playbook"] == "read_comments@1.0"
    assert body["params"]["note_id"] == note.note_id
    assert body["params"]["title_summary"] == "demo note"
    assert "xhs_hint" not in body["params"]
    assert tasks.status_code == 200
    assert any(t["playbook"] == "read_comments@1.0" for t in tasks.json())
    assert any(
        topic == f"devices/{device_id}/commands" and payload["playbook"] == "read_comments@1.0"
        for topic, payload in mqtt_bus.published
    )


def test_reply_comment_creates_reply_comment_task() -> None:
    device_id, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()
    task_service = TaskService(DATABASE_URL, mqtt_bus)
    notes = NotesService(DATABASE_URL, task_service)
    note = notes._insert_note(
        note_id=unique_id("note"),
        account_id=account_id,
        xhs_hint="hint-1",
        title_summary="demo",
    )
    comments = notes.upsert_comments_from_payload(
        account_id=account_id,
        source_task_id=None,
        payload={
            "note_id": note.note_id,
            "items": [
                {
                    "author_summary": "粉丝A",
                    "body_summary": "怎么买",
                    "locator_hint": "index:2",
                }
            ],
        },
    )
    comment_id = comments[0].comment_id

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        reply = client.post(
            f"/api/v1/comments/{comment_id}/reply",
            headers=ops_headers(),
            json={"text": "谢谢关注，详情可私信了解～"},
        )

    assert reply.status_code == 200
    body = reply.json()
    assert body["playbook"] == "reply_comment@1.0"
    assert body["params"]["text"] == "谢谢关注，详情可私信了解～"
    assert body["params"]["comment_id"] == comment_id
    assert body["params"]["title_summary"] == "demo"
    assert body["params"]["locator_hint"] == "index:2"
    assert any(
        topic == f"devices/{device_id}/commands" and payload["playbook"] == "reply_comment@1.0"
        for topic, payload in mqtt_bus.published
    )


def test_comment_route_returns_ambiguous_reconciliation_contract() -> None:
    _device_id, account_id = create_bound_account()

    class RecordThenFailBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            raise RuntimeError("broker acknowledgement lost")

    bus = RecordThenFailBus()
    task_service = TaskService(DATABASE_URL, bus)
    note = NotesService(DATABASE_URL, task_service)._insert_note(
        note_id=unique_id("note"),
        account_id=account_id,
        xhs_hint="hint-1",
        title_summary="demo",
    )

    with TestClient(app, raise_server_exceptions=False) as client:
        wire_services(client, bus)
        response = client.post(
            f"/api/v1/notes/{note.note_id}/comments",
            headers=ops_headers(),
            json={"text": "谢谢关注"},
        )
        detail = response.json()["detail"]
        persisted = client.get(
            f"/api/v1/tasks/{detail['task_id']}",
            headers=ops_headers(),
        )

    assert response.status_code == 409
    assert detail["code"] == "TASK_DISPATCH_AMBIGUOUS"
    assert detail["reconcile_required"] is True
    assert detail["error_code"] == "EFFECT_UNKNOWN"
    assert detail["retry_permitted"] is False
    assert detail["action"] == "RECONCILE_ORIGINAL_TASK"
    assert "new task_id" not in detail["message"]
    assert persisted.status_code == 200
    assert persisted.json()["status"] == "reconcile_required"


def test_publish_event_creates_published_notes() -> None:
    device_id, account_id = create_bound_account()
    task_id = unique_id("task")
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        created = client.post(
            "/api/v1/tasks",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "playbook": "publish_note@1.0",
                "params": {"title": "春日茶会", "body": "欢迎品鉴"},
                "task_id": task_id,
            },
        )
        event = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers=ops_headers(),
            json={
                "task_id": task_id,
                "status": "succeeded",
                "result_payload": {
                    "kind": "publish",
                    "title_summary": "春日茶会",
                    "note_ref": "xhs-ref-9",
                },
            },
        )
        notes = client.get(
            "/api/v1/notes",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert created.status_code == 200
    assert event.status_code == 200
    assert notes.status_code == 200
    matching = [n for n in notes.json() if n["publish_task_id"] == task_id]
    assert len(matching) == 1
    assert matching[0]["title_summary"] == "春日茶会"
    assert matching[0]["xhs_hint"] == "xhs-ref-9"


def test_sync_notes_dispatches_and_reconciles_deletes() -> None:
    device_id, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()
    task_service = TaskService(DATABASE_URL, mqtt_bus)
    notes = NotesService(DATABASE_URL, task_service)
    keep = notes._insert_note(
        note_id=unique_id("note"),
        account_id=account_id,
        title_summary="保留笔记",
        xhs_hint=None,
    )
    stale = notes._insert_note(
        note_id=unique_id("note"),
        account_id=account_id,
        title_summary="已删笔记",
        xhs_hint=None,
    )

    with TestClient(app) as client:
        wire_services(client, mqtt_bus)
        sync = client.post(
            "/api/v1/notes/sync",
            headers=ops_headers(),
            json={"account_id": account_id},
        )
        assert sync.status_code == 200
        assert sync.json()["playbook"] == "sync_notes@1.0"
        task_id = sync.json()["task_id"]
        event = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers=ops_headers(),
            json={
                "task_id": task_id,
                "status": "succeeded",
                "result_payload": {
                    "kind": "notes",
                    "items": [
                        {
                            "title_summary": "保留笔记",
                            "like_count": 5,
                            "collect_count": 2,
                            "read_count": 40,
                        },
                        {
                            "title_summary": "新同步笔记",
                            "like_count": 1,
                        },
                    ],
                },
            },
        )
        listed = client.get(
            "/api/v1/notes",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert event.status_code == 200
    assert listed.status_code == 200
    titles = {row["title_summary"] for row in listed.json()}
    assert titles == {"保留笔记", "新同步笔记"}
    assert stale.note_id not in {row["note_id"] for row in listed.json()}
    kept = next(row for row in listed.json() if row["note_id"] == keep.note_id)
    assert kept["like_count"] == 5
    assert kept["collect_count"] == 2
    assert any(
        topic == f"devices/{device_id}/commands" and payload["playbook"] == "sync_notes@1.0"
        for topic, payload in mqtt_bus.published
    )


def test_comment_sync_replaces_stale_comments() -> None:
    _, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()
    notes = NotesService(DATABASE_URL, TaskService(DATABASE_URL, mqtt_bus))
    note = notes._insert_note(
        note_id=unique_id("note"),
        account_id=account_id,
        title_summary="demo",
        xhs_hint="hint",
    )
    notes.upsert_comments_from_payload(
        account_id=account_id,
        source_task_id=None,
        payload={
            "note_id": note.note_id,
            "items": [
                {"author_summary": "旧粉", "body_summary": "旧评论"},
                {"author_summary": "留粉", "body_summary": "还在"},
            ],
        },
    )
    notes.upsert_comments_from_payload(
        account_id=account_id,
        source_task_id=None,
        payload={
            "note_id": note.note_id,
            "items": [
                {
                    "author_summary": "留粉",
                    "body_summary": "还在",
                    "posted_at_text": "昨天 01:10",
                    "replies": [
                        {
                            "author_summary": "店主",
                            "body_summary": "谢谢",
                            "posted_at_text": "昨天 01:12",
                            "reply_to_author": "留粉",
                        }
                    ],
                },
                {
                    "author_summary": "新粉",
                    "body_summary": "新评论",
                    "posted_at_text": "昨天 01:17",
                },
            ],
        },
    )
    listed = notes.list_comments(note.note_id)
    bodies = {(c.author_summary, c.body_summary, c.depth) for c in listed}
    assert bodies == {("留粉", "还在", 0), ("店主", "谢谢", 1), ("新粉", "新评论", 0)}
    reply = next(c for c in listed if c.author_summary == "店主")
    assert reply.reply_to_author == "留粉"
    assert reply.posted_at_text == "昨天 01:12"
    assert reply.parent_node_id is not None

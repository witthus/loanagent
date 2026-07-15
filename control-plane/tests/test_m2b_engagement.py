from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import os
from threading import Condition, Lock
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient
from psycopg.types.json import Jsonb

from loanagent import engagement as engagement_module
from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.engagement import EngagementService
from loanagent.main import app
from loanagent.roles import AccountRole
from loanagent.tasks import DuplicateTaskError, TaskService


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
        topic == f"devices/{engager_device}/commands" and payload["playbook"] == "post_comment@1.0"
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


def test_reply_dispatch_crash_prelinks_same_task_before_recovery() -> None:
    publisher_device, publisher_id = create_bound_account()
    engager_device, _engager_id = create_bound_account(role=AccountRole.ENGAGER)
    mqtt_bus = RecordingMqttBus()
    task_service = TaskService(DATABASE_URL, mqtt_bus)
    engagement = EngagementService(DATABASE_URL, task_service)
    task_service.engagement_service = engagement
    publish_task = task_service.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        params={"title": "探店", "body": "不错"},
        task_id=unique_id("publish"),
    )
    task_service.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)
    advanced = engagement.advance(chain.chain_id)
    post_comment_task_id = advanced.post_comment_task_ids[0]

    class CrashAfterReplyDispatch(TaskService):
        def create_and_dispatch(self, **kwargs):
            created = super().create_and_dispatch(**kwargs)
            if kwargs["playbook"].startswith("reply_comment"):
                raise RuntimeError("crash after reply dispatch")
            return created

    crashing = CrashAfterReplyDispatch(DATABASE_URL, mqtt_bus)
    crashing_engagement = EngagementService(DATABASE_URL, crashing)
    crashing.engagement_service = crashing_engagement

    with pytest.raises(RuntimeError, match="crash after reply dispatch"):
        crashing.mark_from_event(
            device_id=engager_device,
            task_id=post_comment_task_id,
            status="succeeded",
        )

    after_crash = engagement.get(chain.chain_id)
    with psycopg.connect(DATABASE_URL) as connection:
        source_marker = connection.execute(
            """
            SELECT engagement_processed_at
            FROM tasks
            WHERE task_id = %s
            """,
            (post_comment_task_id,),
        ).fetchone()[0]
        first_dispatch = connection.execute(
            """
            SELECT task_id, operation_id
            FROM tasks
            WHERE account_id = %s AND playbook = 'reply_comment@1.0'
              AND params->>'chain_id' = %s
            """,
            (publisher_id, chain.chain_id),
        ).fetchall()
    assert source_marker is None
    assert len(first_dispatch) == 1
    assert after_crash.status == "awaiting_reply"
    assert after_crash.reply_comment_task_ids == [first_dispatch[0][0]]

    restarted = TaskService(DATABASE_URL, mqtt_bus)
    restarted_engagement = EngagementService(DATABASE_URL, restarted)
    restarted.engagement_service = restarted_engagement
    restarted.mark_from_event(
        device_id=engager_device,
        task_id=post_comment_task_id,
        status="succeeded",
    )

    recovered = restarted_engagement.get(chain.chain_id)
    with psycopg.connect(DATABASE_URL) as connection:
        recovered_marker = connection.execute(
            """
            SELECT engagement_processed_at
            FROM tasks
            WHERE task_id = %s
            """,
            (post_comment_task_id,),
        ).fetchone()[0]
        recovered_dispatches = connection.execute(
            """
            SELECT task_id, operation_id
            FROM tasks
            WHERE account_id = %s AND playbook = 'reply_comment@1.0'
              AND params->>'chain_id' = %s
            """,
            (publisher_id, chain.chain_id),
        ).fetchall()
    reply_publishes = [
        payload
        for _topic, payload in mqtt_bus.published
        if payload["playbook"] == "reply_comment@1.0"
        and payload["params"].get("chain_id") == chain.chain_id
    ]

    assert recovered_dispatches == first_dispatch
    assert recovered.reply_comment_task_ids == [first_dispatch[0][0]]
    assert recovered_marker is not None
    assert len(reply_publishes) == 1


def test_immediate_reply_completion_is_linked_before_dispatch_crash() -> None:
    publisher_device, publisher_id = create_bound_account()
    engager_device, _engager_id = create_bound_account(role=AccountRole.ENGAGER)
    setup_bus = RecordingMqttBus()
    setup_tasks = TaskService(DATABASE_URL, setup_bus)
    setup_engagement = EngagementService(DATABASE_URL, setup_tasks)
    setup_tasks.engagement_service = setup_engagement
    publish_task = setup_tasks.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        params={"title": "探店", "body": "不错"},
        task_id=unique_id("publish"),
    )
    setup_tasks.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)
    post_comment_task = setup_engagement.advance(chain.chain_id)
    post_comment_task_id = post_comment_task.post_comment_task_ids[0]

    class CompleteReplyDuringPublish(RecordingMqttBus):
        service: TaskService

        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            if payload["playbook"] == "reply_comment@1.0":
                self.service.mark_from_event(
                    device_id=publisher_device,
                    task_id=payload["task_id"],
                    status="succeeded",
                )

    class CrashAfterImmediateCompletion(TaskService):
        def create_and_dispatch(self, **kwargs):
            created = super().create_and_dispatch(**kwargs)
            if kwargs["playbook"] == "reply_comment@1.0":
                raise RuntimeError("crash after immediate completion")
            return created

    reentrant_bus = CompleteReplyDuringPublish()
    crashing = CrashAfterImmediateCompletion(DATABASE_URL, reentrant_bus)
    reentrant_bus.service = crashing
    crashing_engagement = EngagementService(DATABASE_URL, crashing)
    crashing.engagement_service = crashing_engagement

    with pytest.raises(RuntimeError, match="crash after immediate completion"):
        crashing.mark_from_event(
            device_id=engager_device,
            task_id=post_comment_task_id,
            status="succeeded",
        )

    with psycopg.connect(DATABASE_URL) as connection:
        reply = connection.execute(
            """
            SELECT task_id, engagement_processed_at
            FROM tasks
            WHERE playbook = 'reply_comment@1.0'
              AND params->>'chain_id' = %s
            """,
            (chain.chain_id,),
        ).fetchone()
        source_marker = connection.execute(
            "SELECT engagement_processed_at FROM tasks WHERE task_id = %s",
            (post_comment_task_id,),
        ).fetchone()[0]
    after_crash = crashing_engagement.get(chain.chain_id)

    assert reply is not None
    assert reply[1] is not None
    assert source_marker is None
    assert after_crash.status == "done"
    assert after_crash.reply_comment_task_ids == [reply[0]]

    restarted = TaskService(DATABASE_URL, reentrant_bus)
    restarted_engagement = EngagementService(DATABASE_URL, restarted)
    restarted.engagement_service = restarted_engagement
    restarted.mark_from_event(
        device_id=engager_device,
        task_id=post_comment_task_id,
        status="succeeded",
    )

    recovered = restarted_engagement.get(chain.chain_id)
    reply_publishes = [
        payload
        for _topic, payload in reentrant_bus.published
        if payload["playbook"] == "reply_comment@1.0"
    ]
    assert recovered.status == "done"
    assert recovered.reply_comment_task_ids == [reply[0]]
    assert len(reply_publishes) == 1


def test_post_comment_first_candidate_prepublish_failure_uses_second_identity() -> None:
    publisher_device, publisher_id = create_bound_account()
    _first_device, first_account = create_bound_account(role=AccountRole.ENGAGER)
    second_device, second_account = create_bound_account(role=AccountRole.ENGAGER)
    setup_tasks = TaskService(DATABASE_URL, RecordingMqttBus())
    setup_engagement = EngagementService(DATABASE_URL, setup_tasks)
    setup_tasks.engagement_service = setup_engagement
    publish_task = setup_tasks.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    setup_tasks.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            """
            UPDATE engagement_chains
            SET engager_account_id = %s,
                engager_account_ids = %s,
                config = config || %s::jsonb
            WHERE chain_id = %s
            """,
            (
                first_account,
                Jsonb([first_account, second_account]),
                Jsonb({"delay_sec": 0}),
                chain.chain_id,
            ),
        )
        connection.execute(
            "UPDATE accounts SET status = 'paused' WHERE account_id = %s",
            (first_account,),
        )

    bus = RecordingMqttBus()
    service = TaskService(DATABASE_URL, bus)
    engagement = EngagementService(DATABASE_URL, service)
    service.engagement_service = engagement

    advanced = engagement.advance(chain.chain_id)

    with psycopg.connect(DATABASE_URL) as connection:
        dispatched = connection.execute(
            """
            SELECT task_id, account_id, status
            FROM tasks
            WHERE playbook = 'post_comment@1.0'
              AND params->>'chain_id' = %s
            ORDER BY account_id
            """,
            (chain.chain_id,),
        ).fetchall()

    assert advanced.status == "running"
    assert advanced.engager_account_id == second_account
    assert len(dispatched) == 1
    assert dispatched[0][1:] == (second_account, "accepted")
    assert any(topic == f"devices/{second_device}/commands" for topic, _ in bus.published)


def test_existing_failed_candidate_is_not_treated_as_dispatched() -> None:
    publisher_device, publisher_id = create_bound_account()
    first_device, first_account = create_bound_account(role=AccountRole.ENGAGER)
    second_device, second_account = create_bound_account(role=AccountRole.ENGAGER)
    setup = TaskService(DATABASE_URL, RecordingMqttBus())
    setup_engagement = EngagementService(DATABASE_URL, setup)
    setup.engagement_service = setup_engagement
    publish_task = setup.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    setup.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            """
            UPDATE engagement_chains
            SET engager_account_id = %s,
                engager_account_ids = %s,
                config = config || %s::jsonb
            WHERE chain_id = %s
            """,
            (
                first_account,
                Jsonb([first_account, second_account]),
                Jsonb({"delay_sec": 0}),
                chain.chain_id,
            ),
        )
    first_task_id, first_operation_id = engagement_module._engagement_task_identity(
        chain.chain_id,
        step="post_comment",
        source_task_id=chain.publish_task_id,
        account_id=first_account,
    )

    failed_service = TaskService(DATABASE_URL, RecordingMqttBus())
    failed_service.create_and_dispatch(
        account_id=first_account,
        playbook="post_comment@1.0",
        params={
            "text": "看起来不错，求同款渠道～",
            "note_ref": chain.publish_task_id,
            "chain_id": chain.chain_id,
        },
        task_id=first_task_id,
        operation_id=first_operation_id,
        source="scheduler",
    )
    failed_service.mark_from_event(
        device_id=first_device,
        task_id=first_task_id,
        status="failed",
        error_code="DEVICE_REJECTED",
    )

    recovery_bus = RecordingMqttBus()
    recovery_service = TaskService(DATABASE_URL, recovery_bus)
    recovery = EngagementService(DATABASE_URL, recovery_service)
    recovered = recovery.advance(chain.chain_id)

    assert recovered.status == "running"
    assert recovered.engager_account_id == second_account
    assert recovered.post_comment_task_ids != [first_task_id]
    assert any(
        topic == f"devices/{second_device}/commands" for topic, _payload in recovery_bus.published
    )


def test_existing_succeeded_candidate_replays_consumed_engagement_step() -> None:
    publisher_device, publisher_id = create_bound_account()
    engager_device, engager_account = create_bound_account(role=AccountRole.ENGAGER)
    bus = RecordingMqttBus()
    service = TaskService(DATABASE_URL, bus)
    engagement = EngagementService(DATABASE_URL, service)
    service.engagement_service = engagement
    publish_task = service.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    service.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)
    task_id, operation_id = engagement_module._engagement_task_identity(
        chain.chain_id,
        step="post_comment",
        source_task_id=chain.publish_task_id,
        account_id=engager_account,
    )
    orphaned = service.create_and_dispatch(
        account_id=engager_account,
        playbook="post_comment@1.0",
        params={
            "text": "看起来不错，求同款渠道～",
            "note_ref": chain.publish_task_id,
            "chain_id": chain.chain_id,
        },
        task_id=task_id,
        operation_id=operation_id,
        source="scheduler",
    )
    service.mark_from_event(
        device_id=engager_device,
        task_id=orphaned.task_id,
        status="succeeded",
    )
    assert engagement.get(chain.chain_id).post_comment_task_ids == []
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            "UPDATE accounts SET status = 'paused' WHERE account_id = %s",
            (engager_account,),
        )

    recovered = engagement.advance(chain.chain_id)

    assert recovered.status == "awaiting_reply"
    assert recovered.post_comment_task_ids == [orphaned.task_id]
    assert len(recovered.reply_comment_task_ids) == 1


def test_post_comment_same_candidate_crash_is_prelinked_and_reused() -> None:
    publisher_device, publisher_id = create_bound_account()
    _engager_device, engager_account = create_bound_account(role=AccountRole.ENGAGER)
    setup_tasks = TaskService(DATABASE_URL, RecordingMqttBus())
    setup_engagement = EngagementService(DATABASE_URL, setup_tasks)
    setup_tasks.engagement_service = setup_engagement
    publish_task = setup_tasks.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    setup_tasks.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)

    class CrashAfterPostDispatch(TaskService):
        def create_and_dispatch(self, **kwargs):
            created = super().create_and_dispatch(**kwargs)
            if kwargs["playbook"] == "post_comment@1.0":
                raise RuntimeError("crash after post dispatch")
            return created

    bus = RecordingMqttBus()
    crashing = CrashAfterPostDispatch(DATABASE_URL, bus)
    crashing_engagement = EngagementService(DATABASE_URL, crashing)
    crashing.engagement_service = crashing_engagement

    with pytest.raises(RuntimeError, match="crash after post dispatch"):
        crashing_engagement.advance(chain.chain_id)

    after_crash = crashing_engagement.get(chain.chain_id)
    assert after_crash.status == "running"
    assert after_crash.engager_account_id == engager_account
    assert len(after_crash.post_comment_task_ids) == 1

    restarted = TaskService(DATABASE_URL, bus)
    restarted_engagement = EngagementService(DATABASE_URL, restarted)
    recovered = restarted_engagement.advance(chain.chain_id)
    with psycopg.connect(DATABASE_URL) as connection:
        tasks = connection.execute(
            """
            SELECT task_id
            FROM tasks
            WHERE playbook = 'post_comment@1.0'
              AND params->>'chain_id' = %s
            """,
            (chain.chain_id,),
        ).fetchall()
    publishes = [
        payload for _topic, payload in bus.published if payload["playbook"] == "post_comment@1.0"
    ]

    assert recovered.post_comment_task_ids == after_crash.post_comment_task_ids
    assert tasks == [(after_crash.post_comment_task_ids[0],)]
    assert len(publishes) == 1


def test_post_comment_reservation_recovers_crash_before_publish() -> None:
    publisher_device, publisher_id = create_bound_account()
    _engager_device, _engager_account = create_bound_account(role=AccountRole.ENGAGER)
    setup = TaskService(DATABASE_URL, RecordingMqttBus())
    setup_engagement = EngagementService(DATABASE_URL, setup)
    setup.engagement_service = setup_engagement
    publish_task = setup.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    setup.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)

    class CrashAfterPostAssociation(TaskService):
        def create_and_dispatch(self, **kwargs):
            callback = kwargs.get("_before_publish")
            if kwargs["playbook"] == "post_comment@1.0" and callback is not None:

                def crash_after_association(task):
                    callback(task)
                    raise RuntimeError("crash before post publish")

                kwargs["_before_publish"] = crash_after_association
            return super().create_and_dispatch(**kwargs)

    bus = RecordingMqttBus()
    crashing = CrashAfterPostAssociation(DATABASE_URL, bus)
    crashing_engagement = EngagementService(DATABASE_URL, crashing)
    crashing.engagement_service = crashing_engagement

    with pytest.raises(RuntimeError, match="crash before post publish"):
        crashing_engagement.advance(chain.chain_id)

    reserved = crashing_engagement.get(chain.chain_id)
    assert reserved.status == "running"
    assert len(reserved.post_comment_task_ids) == 1
    assert bus.published == []
    assert crashing.get(reserved.post_comment_task_ids[0]).status == "queued"

    restarted = TaskService(DATABASE_URL, bus)
    restarted_engagement = EngagementService(DATABASE_URL, restarted)
    recovered = restarted_engagement.advance(chain.chain_id)

    assert recovered.status == "running"
    assert restarted.get(reserved.post_comment_task_ids[0]).status == "accepted"
    post_publishes = [
        payload for _topic, payload in bus.published if payload["playbook"] == "post_comment@1.0"
    ]
    assert len(post_publishes) == 1


def test_reply_reservation_recovers_crash_before_publish() -> None:
    publisher_device, publisher_id = create_bound_account()
    engager_device, _engager_account = create_bound_account(role=AccountRole.ENGAGER)
    setup = TaskService(DATABASE_URL, RecordingMqttBus())
    setup_engagement = EngagementService(DATABASE_URL, setup)
    setup.engagement_service = setup_engagement
    publish_task = setup.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    setup.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)
    running = setup_engagement.advance(chain.chain_id)
    post_task_id = running.post_comment_task_ids[0]

    class CrashAfterReplyAssociation(TaskService):
        def create_and_dispatch(self, **kwargs):
            callback = kwargs.get("_before_publish")
            if kwargs["playbook"] == "reply_comment@1.0" and callback is not None:

                def crash_after_association(task):
                    callback(task)
                    raise RuntimeError("crash before reply publish")

                kwargs["_before_publish"] = crash_after_association
            return super().create_and_dispatch(**kwargs)

    bus = RecordingMqttBus()
    crashing = CrashAfterReplyAssociation(DATABASE_URL, bus)
    crashing_engagement = EngagementService(DATABASE_URL, crashing)
    crashing.engagement_service = crashing_engagement

    with pytest.raises(RuntimeError, match="crash before reply publish"):
        crashing.mark_from_event(
            device_id=engager_device,
            task_id=post_task_id,
            status="succeeded",
        )

    reserved = crashing_engagement.get(chain.chain_id)
    assert reserved.status == "awaiting_reply"
    assert len(reserved.reply_comment_task_ids) == 1
    assert bus.published == []
    assert crashing.get(reserved.reply_comment_task_ids[0]).status == "queued"

    restarted = TaskService(DATABASE_URL, bus)
    restarted_engagement = EngagementService(DATABASE_URL, restarted)
    restarted.engagement_service = restarted_engagement
    recovered = restarted_engagement.advance(chain.chain_id)
    reply_task_id = reserved.reply_comment_task_ids[0]

    assert recovered.status == "awaiting_reply"
    assert restarted.get(reply_task_id).status == "accepted"
    reply_publishes = [
        payload for _topic, payload in bus.published if payload["playbook"] == "reply_comment@1.0"
    ]
    assert len(reply_publishes) == 1

    restarted.mark_from_event(
        device_id=publisher_device,
        task_id=reply_task_id,
        status="succeeded",
    )
    assert restarted_engagement.get(chain.chain_id).status == "done"


def test_ambiguous_post_publish_does_not_fallback_to_second_candidate() -> None:
    publisher_device, publisher_id = create_bound_account()
    first_device, first_account = create_bound_account(role=AccountRole.ENGAGER)
    _second_device, second_account = create_bound_account(role=AccountRole.ENGAGER)
    setup = TaskService(DATABASE_URL, RecordingMqttBus())
    setup_engagement = EngagementService(DATABASE_URL, setup)
    setup.engagement_service = setup_engagement
    publish_task = setup.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    setup.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            """
            UPDATE engagement_chains
            SET engager_account_id = %s,
                engager_account_ids = %s,
                config = config || %s::jsonb
            WHERE chain_id = %s
            """,
            (
                first_account,
                Jsonb([first_account, second_account]),
                Jsonb({"delay_sec": 0}),
                chain.chain_id,
            ),
        )

    class RecordThenFailBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            if topic == f"devices/{first_device}/commands":
                raise RuntimeError("broker acknowledgement lost")

    bus = RecordThenFailBus()
    service = TaskService(DATABASE_URL, bus)
    engagement = EngagementService(DATABASE_URL, service)
    service.engagement_service = engagement

    reconciled = engagement.advance(chain.chain_id)

    post_commands = [
        payload for _topic, payload in bus.published if payload["playbook"] == "post_comment@1.0"
    ]
    with psycopg.connect(DATABASE_URL) as connection:
        tasks = connection.execute(
            """
            SELECT account_id, status, reconcile_required, error_code
            FROM tasks
            WHERE playbook = 'post_comment@1.0'
              AND params->>'chain_id' = %s
            """,
            (chain.chain_id,),
        ).fetchall()
        alerts = connection.execute(
            """
            SELECT kind
            FROM alerts
            WHERE ref_id = %s
              AND kind = 'engagement_reconciliation_required'
            """,
            (chain.chain_id,),
        ).fetchall()

    assert len(post_commands) == 1
    assert tasks == [(first_account, "reconcile_required", True, "EFFECT_UNKNOWN")]
    assert reconciled.status == "failed"
    assert reconciled.stop_reason == "EFFECT_UNKNOWN"
    assert alerts == [("engagement_reconciliation_required",)]


def test_reconciliation_chain_and_alert_commit_atomically_and_idempotently() -> None:
    publisher_device, publisher_id = create_bound_account()
    first_device, first_account = create_bound_account(role=AccountRole.ENGAGER)
    setup = TaskService(DATABASE_URL, RecordingMqttBus())
    setup_engagement = EngagementService(DATABASE_URL, setup)
    setup.engagement_service = setup_engagement
    publish_task = setup.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    setup.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)

    class RecordThenFailBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            if topic == f"devices/{first_device}/commands":
                raise RuntimeError("broker acknowledgement lost")

    class FailAlertInsertEngagement(EngagementService):
        def _insert_alert_on_connection(self, connection, **kwargs):
            raise RuntimeError("alert insert failed")

    bus = RecordThenFailBus()
    service = TaskService(DATABASE_URL, bus)
    failing = FailAlertInsertEngagement(DATABASE_URL, service)
    service.engagement_service = failing

    with pytest.raises(RuntimeError, match="alert insert failed"):
        failing.advance(chain.chain_id)

    after_failure = failing.get(chain.chain_id)
    with psycopg.connect(DATABASE_URL) as connection:
        alert_count = connection.execute(
            """
            SELECT count(*)
            FROM alerts
            WHERE ref_id = %s
              AND kind = 'engagement_reconciliation_required'
            """,
            (chain.chain_id,),
        ).fetchone()[0]
    assert after_failure.status == "running"
    assert after_failure.stop_reason is None
    assert alert_count == 0

    restarted = EngagementService(DATABASE_URL, service)
    recovered = restarted.advance(chain.chain_id)
    restarted._mark_reconciliation_required(chain.chain_id)
    with psycopg.connect(DATABASE_URL) as connection:
        alerts = connection.execute(
            """
            SELECT alert_id, kind
            FROM alerts
            WHERE ref_id = %s
              AND kind = 'engagement_reconciliation_required'
            """,
            (chain.chain_id,),
        ).fetchall()

    assert recovered.status == "failed"
    assert recovered.stop_reason == "EFFECT_UNKNOWN"
    assert len(alerts) == 1


def test_two_concurrent_advances_claim_queued_reservation_once() -> None:
    publisher_device, publisher_id = create_bound_account()
    _engager_device, _engager_account = create_bound_account(role=AccountRole.ENGAGER)
    setup = TaskService(DATABASE_URL, RecordingMqttBus())
    setup_engagement = EngagementService(DATABASE_URL, setup)
    setup.engagement_service = setup_engagement
    publish_task = setup.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    setup.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = setup_engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)

    association_lock = Lock()
    association_calls = 0

    class CountingEngagementService(EngagementService):
        def _associate_post_comment(self, chain_id: str, task):
            nonlocal association_calls
            with association_lock:
                association_calls += 1
            return super()._associate_post_comment(chain_id, task)

    class CrashAfterAssociation(TaskService):
        def create_and_dispatch(self, **kwargs):
            callback = kwargs.get("_before_publish")
            if kwargs["playbook"] == "post_comment@1.0" and callback is not None:

                def crash_after_association(task):
                    callback(task)
                    raise RuntimeError("reservation created")

                kwargs["_before_publish"] = crash_after_association
            return super().create_and_dispatch(**kwargs)

    crashing = CrashAfterAssociation(DATABASE_URL, RecordingMqttBus())
    crashing_engagement = CountingEngagementService(DATABASE_URL, crashing)
    with pytest.raises(RuntimeError, match="reservation created"):
        crashing_engagement.advance(chain.chain_id)
    reservation = crashing_engagement.get(chain.chain_id)
    task_id = reservation.post_comment_task_ids[0]

    condition = Condition()
    queued_reads = 0

    class CoordinatedTaskService(TaskService):
        def get(self, requested_task_id: str):
            nonlocal queued_reads
            task = super().get(requested_task_id)
            if requested_task_id == task_id and task.status == "queued":
                with condition:
                    queued_reads += 1
                    condition.notify_all()
                    condition.wait_for(lambda: queued_reads >= 2, timeout=1)
            return task

    bus = RecordingMqttBus()
    first_tasks = CoordinatedTaskService(DATABASE_URL, bus)
    second_tasks = CoordinatedTaskService(DATABASE_URL, bus)
    first = CountingEngagementService(DATABASE_URL, first_tasks)
    second = CountingEngagementService(DATABASE_URL, second_tasks)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(
            pool.map(
                lambda service: service.advance(chain.chain_id),
                (first, second),
            )
        )

    commands = [
        payload for _topic, payload in bus.published if payload["playbook"] == "post_comment@1.0"
    ]
    assert [result.status for result in results] == ["running", "running"]
    assert association_calls == 1
    assert len(commands) == 1
    assert first_tasks.get(task_id).status == "accepted"


def test_post_comment_existing_identity_collision_is_rejected() -> None:
    publisher_device, publisher_id = create_bound_account()
    _engager_device, engager_account = create_bound_account(role=AccountRole.ENGAGER)
    bus = RecordingMqttBus()
    service = TaskService(DATABASE_URL, bus)
    engagement = EngagementService(DATABASE_URL, service)
    service.engagement_service = engagement
    publish_task = service.create_and_dispatch(
        account_id=publisher_id,
        playbook="publish_note@1.0",
        task_id=unique_id("publish"),
    )
    service.mark_from_event(
        device_id=publisher_device,
        task_id=publish_task.task_id,
        status="succeeded",
    )
    chain = engagement.get_by_publish_task(publish_task.task_id)
    assert chain is not None
    set_chain_delay(chain.chain_id, 0)
    task_id, operation_id = engagement_module._engagement_task_identity(
        chain.chain_id,
        step="post_comment",
        source_task_id=chain.publish_task_id,
        account_id=engager_account,
    )
    service.create_and_dispatch(
        account_id=engager_account,
        playbook="post_comment@1.0",
        params={"chain_id": chain.chain_id, "text": "tampered"},
        task_id=task_id,
        operation_id=operation_id,
        source="scheduler",
    )

    with pytest.raises(DuplicateTaskError):
        engagement.advance(chain.chain_id)

    unchanged = engagement.get(chain.chain_id)
    assert unchanged.status == "pending"
    assert unchanged.post_comment_task_ids == []


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

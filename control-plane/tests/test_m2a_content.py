from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
from threading import Barrier, Event
from uuid import NAMESPACE_URL, uuid4, uuid5

import psycopg
import pytest
from fastapi.testclient import TestClient

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.main import app
from loanagent.roles import AccountRole
from loanagent.schedules import ScheduleRepository
from loanagent.sensitivity import assert_clean, scan_text
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


def test_scan_text_finds_default_sensitive_words() -> None:
    hits = scan_text("今天天气不错", "无抵押秒贷低息放款")
    assert "无抵押" in hits
    assert "秒贷" in hits
    assert_clean("普通笔记标题", "分享生活日常")


def test_assert_clean_raises_on_sensitive_hit() -> None:
    with pytest.raises(ValueError, match="sensitive"):
        assert_clean("套现攻略")


def test_content_create_rejects_sensitive_text(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, mqtt_bus)
        response = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={
                "title": "无抵押贷款",
                "body": "欢迎咨询",
                "media_ids": [],
            },
        )

    assert response.status_code == 400
    assert response.json()["detail"]["code"] == "SENSITIVITY_REJECTED"


def test_media_upload_and_signed_download(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    monkeypatch.setenv("MEDIA_SIGNING_SECRET", "test-media-secret")
    monkeypatch.setenv("PUBLIC_BASE_URL", "http://127.0.0.1:8000")
    mqtt_bus = RecordingMqttBus()
    payload = b"fake-image-bytes"

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, mqtt_bus)
        upload = client.post(
            "/api/v1/media",
            headers=ops_headers(),
            files={"file": ("note.jpg", BytesIO(payload), "image/jpeg")},
        )
        assert upload.status_code == 200
        body = upload.json()
        assert body["content_type"] == "image/jpeg"
        assert body["byte_size"] == len(payload)
        assert body["sha256"]
        media_id = body["media_id"]

        # Unsigned download must fail.
        denied = client.get(f"/api/v1/media/{media_id}/download")
        assert denied.status_code == 401

        from loanagent.media import MediaRepository

        media_repo: MediaRepository = client.app.state.media_repository
        signed = media_repo.signed_download_url(media_id)
        downloaded = client.get(signed.removeprefix("http://127.0.0.1:8000"))
        assert downloaded.status_code == 200
        assert downloaded.content == payload
        assert downloaded.headers["content-type"].startswith("image/jpeg")


def test_media_upload_rejects_oversized_payload(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    monkeypatch.setenv("MEDIA_SIGNING_SECRET", "test-media-secret")
    monkeypatch.setenv("PUBLIC_BASE_URL", "http://127.0.0.1:8000")
    oversized = b"x" * (25 * 1024 * 1024 + 1)

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, RecordingMqttBus())
        upload = client.post(
            "/api/v1/media",
            headers={
                **ops_headers(),
                "Content-Length": str(len(oversized) + 200),
            },
            files={"file": ("huge.bin", BytesIO(oversized), "application/octet-stream")},
        )

    assert upload.status_code == 413
    assert upload.json()["detail"]["code"] == "UPLOAD_TOO_LARGE"


def test_immediate_publish_creates_accepted_publish_note_task(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    monkeypatch.setenv("MEDIA_SIGNING_SECRET", "test-media-secret")
    monkeypatch.setenv("PUBLIC_BASE_URL", "http://127.0.0.1:8000")
    device_id, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, mqtt_bus)
        upload = client.post(
            "/api/v1/media",
            headers=ops_headers(),
            files={"file": ("cover.png", BytesIO(b"png-bytes"), "image/png")},
        )
        media_id = upload.json()["media_id"]

        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={
                "title": "周末探店",
                "body": "咖啡很好喝",
                "media_ids": [media_id],
            },
        )
        assert content.status_code == 200
        content_id = content.json()["content_id"]

        publish = client.post(
            "/api/v1/publish/immediate",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content_id},
        )

    assert publish.status_code == 200
    task = publish.json()
    assert task["status"] == "accepted"
    assert task["playbook"] == "publish_note@1.0"
    assert task["account_id"] == account_id
    assert mqtt_bus.published
    topic, envelope = mqtt_bus.published[0]
    assert topic == f"devices/{device_id}/commands"
    assert envelope["playbook"] == "publish_note@1.0"
    params = envelope["params"]
    assert params["title"] == "周末探店"
    assert params["body"] == "咖啡很好喝"
    assert params["start_in_editor"] is False
    assert len(params["media_urls"]) == 1
    assert params["media_urls"][0]["filename"]
    assert "sig=" in params["media_urls"][0]["url"]


def test_immediate_publish_returns_ambiguous_reconciliation_contract(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    _device_id, account_id = create_bound_account()

    class RecordThenFailBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            raise RuntimeError("broker acknowledgement lost")

    service = TaskService(DATABASE_URL, RecordThenFailBus())
    with TestClient(app, raise_server_exceptions=False) as client:
        client.app.state.task_service = service
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "普通标题", "body": "普通正文", "media_ids": []},
        )
        response = client.post(
            "/api/v1/publish/immediate",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content.json()["content_id"]},
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


def test_immediate_publish_requires_a11y_online_account(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    _, account_id = create_bound_account(a11y_bound=False)
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, mqtt_bus)
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "普通标题", "body": "普通正文", "media_ids": []},
        )
        content_id = content.json()["content_id"]
        response = client.post(
            "/api/v1/publish/immediate",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content_id},
        )

    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "A11Y_DOWN"
    assert mqtt_bus.published == []


def test_schedule_dispatch_creates_publish_note_task(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    monkeypatch.setenv("MEDIA_SIGNING_SECRET", "test-media-secret")
    monkeypatch.setenv("PUBLIC_BASE_URL", "http://127.0.0.1:8000")
    device_id, account_id = create_bound_account()
    mqtt_bus = RecordingMqttBus()

    with TestClient(app) as client:
        client.app.state.task_service = TaskService(DATABASE_URL, mqtt_bus)
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "排期笔记", "body": "正文内容", "media_ids": []},
        )
        content_id = content.json()["content_id"]
        schedule = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content_id},
        )
        assert schedule.status_code == 200
        schedule_id = schedule.json()["schedule_id"]
        assert schedule.json()["status"] == "ready"

        dispatched = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )

    assert dispatched.status_code == 200
    body = dispatched.json()
    assert body["status"] == "dispatched"
    assert body["task_id"]
    assert mqtt_bus.published
    topic, envelope = mqtt_bus.published[0]
    assert topic == f"devices/{device_id}/commands"
    assert envelope["playbook"] == "publish_note@1.0"


def test_schedule_dispatch_recovers_reservation_crash_before_publish(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    _device_id, account_id = create_bound_account()
    bus = RecordingMqttBus()
    service = TaskService(DATABASE_URL, bus)

    with TestClient(app, raise_server_exceptions=False) as client:
        client.app.state.task_service = service
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "排期笔记", "body": "正文内容", "media_ids": []},
        )
        schedule = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content.json()["content_id"]},
        )
        schedule_id = schedule.json()["schedule_id"]
        expected_task_id = str(
            uuid5(
                NAMESPACE_URL,
                f"loanagent:schedule:{schedule_id}:dispatch:v1:task",
            )
        )
        expected_operation_id = str(
            uuid5(
                NAMESPACE_URL,
                f"loanagent:schedule:{schedule_id}:dispatch:v1:operation",
            )
        )
        dispatch_queued = service._dispatch_queued_task

        def crash_before_publish(_task):
            raise RuntimeError("crash after schedule reservation")

        monkeypatch.setattr(service, "_dispatch_queued_task", crash_before_publish)
        crashed = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )
        reserved = next(
            item
            for item in client.get("/api/v1/schedules", headers=ops_headers()).json()
            if item["schedule_id"] == schedule_id
        )
        reserved_task = service.get(expected_task_id)
        with psycopg.connect(DATABASE_URL) as connection:
            dispatch_started_at = connection.execute(
                "SELECT dispatch_started_at FROM tasks WHERE task_id = %s",
                (expected_task_id,),
            ).fetchone()[0]
        monkeypatch.setattr(service, "_dispatch_queued_task", dispatch_queued)
        recovered = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )

    assert crashed.status_code == 500
    assert reserved["task_id"] == expected_task_id
    assert reserved_task.operation_id == expected_operation_id
    assert reserved_task.status == "queued"
    assert dispatch_started_at is None
    assert service.get(expected_task_id).status == "accepted"
    assert recovered.status_code == 200
    assert recovered.json()["status"] == "dispatched"
    assert recovered.json()["task_id"] == expected_task_id
    assert len(bus.published) == 1


def test_schedule_dispatch_recovers_crash_after_publish_before_completion(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    _device_id, account_id = create_bound_account()
    bus = RecordingMqttBus()
    service = TaskService(DATABASE_URL, bus)

    with TestClient(app, raise_server_exceptions=False) as client:
        client.app.state.task_service = service
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "排期笔记", "body": "正文内容", "media_ids": []},
        )
        schedule = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content.json()["content_id"]},
        )
        schedule_id = schedule.json()["schedule_id"]
        expected_task_id = str(
            uuid5(
                NAMESPACE_URL,
                f"loanagent:schedule:{schedule_id}:dispatch:v1:task",
            )
        )
        update_schedule = ScheduleRepository._update

        def crash_after_publish(repository, target_schedule_id, **changes):
            if changes["status"] == "dispatched":
                raise RuntimeError("crash before schedule completion")
            return update_schedule(repository, target_schedule_id, **changes)

        monkeypatch.setattr(ScheduleRepository, "_update", crash_after_publish)
        crashed = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )
        monkeypatch.setattr(ScheduleRepository, "_update", update_schedule)
        recovered = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )

    assert crashed.status_code == 500
    assert recovered.status_code == 200
    assert recovered.json()["task_id"] == expected_task_id
    assert recovered.json()["status"] == "dispatched"
    assert service.get(expected_task_id).status == "accepted"
    assert len(bus.published) == 1


def test_two_concurrent_schedule_dispatches_publish_once(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    _device_id, account_id = create_bound_account()
    publish_started = Event()
    release_publish = Event()

    class BlockingBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            publish_started.set()
            assert release_publish.wait(timeout=5)

    bus = BlockingBus()
    service = TaskService(DATABASE_URL, bus)
    with TestClient(app) as client:
        client.app.state.task_service = service
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "排期笔记", "body": "正文内容", "media_ids": []},
        )
        schedule = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content.json()["content_id"]},
        )
        schedule_id = schedule.json()["schedule_id"]
        expected_task_id = str(
            uuid5(
                NAMESPACE_URL,
                f"loanagent:schedule:{schedule_id}:dispatch:v1:task",
            )
        )

    repository = ScheduleRepository(DATABASE_URL, service)
    start = Barrier(2)

    def dispatch():
        start.wait(timeout=5)
        return repository.dispatch(schedule_id)

    with ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(dispatch)
        second = executor.submit(dispatch)
        assert publish_started.wait(timeout=5)
        release_publish.set()
        records = [first.result(timeout=5), second.result(timeout=5)]

    assert {record.status for record in records} == {"dispatched"}
    assert len({record.task_id for record in records}) == 1
    assert len(bus.published) == 1
    with psycopg.connect(DATABASE_URL) as connection:
        task_count = connection.execute(
            "SELECT COUNT(*) FROM tasks WHERE task_id = %s",
            (expected_task_id,),
        ).fetchone()[0]
    assert task_count == 1


def test_failed_schedule_without_reservation_retries_same_identity(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    device_id, account_id = create_bound_account(online=False)
    bus = RecordingMqttBus()
    service = TaskService(DATABASE_URL, bus)

    with TestClient(app) as client:
        client.app.state.task_service = service
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "排期笔记", "body": "正文内容", "media_ids": []},
        )
        schedule = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content.json()["content_id"]},
        )
        schedule_id = schedule.json()["schedule_id"]
        expected_task_id = str(
            uuid5(
                NAMESPACE_URL,
                f"loanagent:schedule:{schedule_id}:dispatch:v1:task",
            )
        )
        unavailable = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )
        DeviceRepository(DATABASE_URL).heartbeat(
            device_id=device_id,
            agent_version="0.3.0",
            a11y_bound=True,
        )
        recovered = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )

    assert unavailable.status_code == 200
    assert unavailable.json()["status"] == "failed"
    assert unavailable.json()["task_id"] is None
    assert unavailable.json()["error_code"] == "DEVICE_UNAVAILABLE"
    assert recovered.status_code == 200
    assert recovered.json()["status"] == "dispatched"
    assert recovered.json()["task_id"] == expected_task_id
    assert len(bus.published) == 1
    with psycopg.connect(DATABASE_URL) as connection:
        task_count = connection.execute(
            "SELECT COUNT(*) FROM tasks WHERE task_id = %s",
            (expected_task_id,),
        ).fetchone()[0]
    assert task_count == 1


def test_schedule_reservation_blocks_edit_and_delete_during_publish(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    _device_id, account_id = create_bound_account()
    publish_started = Event()
    release_publish = Event()

    class BlockingBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            publish_started.set()
            assert release_publish.wait(timeout=5)

    bus = BlockingBus()
    service = TaskService(DATABASE_URL, bus)
    with TestClient(app) as client:
        client.app.state.task_service = service
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "排期笔记", "body": "正文内容", "media_ids": []},
        )
        schedule = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content.json()["content_id"]},
        )
        schedule_id = schedule.json()["schedule_id"]
        repository = ScheduleRepository(DATABASE_URL, service)
        with ThreadPoolExecutor(max_workers=1) as executor:
            dispatch = executor.submit(repository.dispatch, schedule_id)
            assert publish_started.wait(timeout=5)
            reserved = next(
                item
                for item in client.get("/api/v1/schedules", headers=ops_headers()).json()
                if item["schedule_id"] == schedule_id
            )
            edited = client.patch(
                f"/api/v1/schedules/{schedule_id}",
                headers=ops_headers(),
                json={"window_start": "2026-07-16T09:00:00+08:00"},
            )
            deleted = client.delete(
                f"/api/v1/schedules/{schedule_id}",
                headers=ops_headers(),
            )
            release_publish.set()
            dispatched = dispatch.result(timeout=5) if deleted.status_code == 409 else None

    assert reserved["task_id"] is not None
    assert edited.status_code == 409
    assert edited.json()["detail"]["code"] == "SCHEDULE_NOT_EDITABLE"
    assert deleted.status_code == 409
    assert deleted.json()["detail"]["code"] == "SCHEDULE_NOT_EDITABLE"
    assert dispatched is not None
    assert dispatched.status == "dispatched"
    assert dispatched.task_id == reserved["task_id"]
    assert len(bus.published) == 1


def test_schedule_dispatch_preserves_ambiguous_task_identity(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    _device_id, account_id = create_bound_account()

    class RecordThenFailBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            raise RuntimeError("broker acknowledgement lost")

    service = TaskService(DATABASE_URL, RecordThenFailBus())
    with TestClient(app, raise_server_exceptions=False) as client:
        client.app.state.task_service = service
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "排期笔记", "body": "正文内容", "media_ids": []},
        )
        schedule = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content.json()["content_id"]},
        )
        first = client.post(
            f"/api/v1/schedules/{schedule.json()['schedule_id']}/dispatch",
            headers=ops_headers(),
        )
        second = client.post(
            f"/api/v1/schedules/{schedule.json()['schedule_id']}/dispatch",
            headers=ops_headers(),
        )
        tasks = client.get(
            "/api/v1/tasks",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert first.status_code == 409
    assert second.status_code == 409
    assert first.json()["detail"]["code"] == "TASK_DISPATCH_AMBIGUOUS"
    assert second.json()["detail"] == first.json()["detail"]
    task_id = first.json()["detail"]["task_id"]
    assert [task["task_id"] for task in tasks.json()] == [task_id]
    assert service.get(task_id).status == "reconcile_required"
    assert len(service.mqtt_bus.published) == 1


def test_schedule_dispatch_converges_after_successful_reconciliation(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    _device_id, account_id = create_bound_account()

    class RecordThenFailBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            raise RuntimeError("broker acknowledgement lost")

    bus = RecordThenFailBus()
    service = TaskService(DATABASE_URL, bus)
    with TestClient(app, raise_server_exceptions=False) as client:
        client.app.state.task_service = service
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "排期笔记", "body": "正文内容", "media_ids": []},
        )
        schedule = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={"account_id": account_id, "content_id": content.json()["content_id"]},
        )
        schedule_id = schedule.json()["schedule_id"]
        ambiguous = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )
        task_id = ambiguous.json()["detail"]["task_id"]
        with psycopg.connect(DATABASE_URL) as connection:
            connection.execute(
                """
                UPDATE tasks
                SET status = 'succeeded',
                    reconcile_required = FALSE,
                    error_code = NULL,
                    effect_committed = TRUE,
                    terminal_at = COALESCE(terminal_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = %s
                  AND status = 'reconcile_required'
                """,
                (task_id,),
            )
        converged = client.post(
            f"/api/v1/schedules/{schedule_id}/dispatch",
            headers=ops_headers(),
        )
        tasks = client.get(
            "/api/v1/tasks",
            headers=ops_headers(),
            params={"account_id": account_id},
        )

    assert ambiguous.status_code == 409
    assert converged.status_code == 200
    assert converged.json()["status"] == "dispatched"
    assert converged.json()["task_id"] == task_id
    assert converged.json()["error_code"] is None
    assert [task["task_id"] for task in tasks.json()] == [task_id]
    assert len(bus.published) == 1


def test_schedule_create_update_and_delete_with_publish_window(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("MEDIA_ROOT", str(tmp_path))
    monkeypatch.setenv("MEDIA_SIGNING_SECRET", "test-media-secret")
    monkeypatch.setenv("PUBLIC_BASE_URL", "http://127.0.0.1:8000")
    _device_id, account_id = create_bound_account()
    window_start = "2026-07-15T10:30:00+08:00"
    window_end = "2026-07-15T11:00:00+08:00"

    with TestClient(app) as client:
        content = client.post(
            "/api/v1/content",
            headers=ops_headers(),
            json={"title": "带时间窗排期", "body": "正文", "media_ids": []},
        )
        content_id = content.json()["content_id"]
        created = client.post(
            "/api/v1/schedules",
            headers=ops_headers(),
            json={
                "account_id": account_id,
                "content_id": content_id,
                "window_start": window_start,
                "window_end": window_end,
            },
        )
        assert created.status_code == 200
        schedule_id = created.json()["schedule_id"]
        assert created.json()["window_start"] is not None
        assert created.json()["window_end"] is not None

        patched = client.patch(
            f"/api/v1/schedules/{schedule_id}",
            headers=ops_headers(),
            json={
                "window_start": "2026-07-16T09:00:00+08:00",
                "window_end": None,
            },
        )
        assert patched.status_code == 200
        assert patched.json()["window_start"].startswith("2026-07-16T01:00:00")
        assert patched.json()["window_end"] is None

        deleted = client.delete(
            f"/api/v1/schedules/{schedule_id}",
            headers=ops_headers(),
        )
        assert deleted.status_code == 200
        assert deleted.json()["ok"] is True

        listed = client.get("/api/v1/schedules", headers=ops_headers())
        assert schedule_id not in {row["schedule_id"] for row in listed.json()}

        missing = client.delete(
            f"/api/v1/schedules/{schedule_id}",
            headers=ops_headers(),
        )
        assert missing.status_code == 404
        assert missing.json()["detail"]["code"] == "SCHEDULE_NOT_FOUND"

from __future__ import annotations

import os
from io import BytesIO
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.main import app
from loanagent.roles import AccountRole
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

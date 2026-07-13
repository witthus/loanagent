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


def test_ops_api_login_sets_cookie() -> None:
    with TestClient(app) as client:
        bad = client.post("/ops/api/login", json={"token": "wrong"})
        good = client.post("/ops/api/login", json={"token": OPS_TOKEN})

    assert bad.status_code == 401
    assert good.status_code == 200
    assert good.json()["ok"] is True
    assert good.cookies["ops_session"] == OPS_TOKEN
    client2 = TestClient(app)
    client2.cookies.set("ops_session", OPS_TOKEN)
    assert client2.get("/ops/api/session").status_code == 200
    assert client2.get("/api/v1/devices").status_code == 200


def test_ops_login_page_redirects_to_spa() -> None:
    with TestClient(app) as client:
        response = client.get("/ops/login", follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "/login"


def test_ops_login_rejects_wrong_token() -> None:
    with TestClient(app) as client:
        response = client.post("/ops/login", data={"token": "wrong-token"})

    assert response.status_code == 401


def test_ops_login_sets_cookie_and_redirects_to_spa_home() -> None:
    with TestClient(app) as client:
        response = client.post(
            "/ops/login",
            data={"token": OPS_TOKEN},
            follow_redirects=False,
        )

    assert response.status_code == 303
    assert response.headers["location"] == "/"
    assert response.cookies["ops_session"] == OPS_TOKEN


def test_ops_devices_page_redirects_into_spa() -> None:
    with TestClient(app) as client:
        unauthenticated = client.get("/ops/devices", follow_redirects=False)
        client.cookies.set("ops_session", OPS_TOKEN)
        authenticated = client.get("/ops/devices", follow_redirects=False)

    assert unauthenticated.status_code == 303
    assert unauthenticated.headers["location"] == "/login"
    assert authenticated.status_code == 303
    assert authenticated.headers["location"] == "/devices"


def test_ops_accounts_and_tasks_pages_redirect() -> None:
    device_id = unique_id("ops-device")
    account_id = unique_id("ops-account")
    task_id = unique_id("ops-task")
    devices = DeviceRepository(DATABASE_URL)
    accounts = AccountRepository(DATABASE_URL)
    accounts.migrate()
    devices.heartbeat(
        device_id=device_id,
        agent_version="0.4.0",
        a11y_bound=True,
        cellular_ok=True,
    )
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
        accounts_response = client.get("/ops/accounts", follow_redirects=False)
        tasks_response = client.get("/ops/tasks", follow_redirects=False)

    assert accounts_response.status_code == 303
    assert accounts_response.headers["location"] == "/accounts"
    assert tasks_response.status_code == 303
    assert tasks_response.headers["location"] == "/tasks"


def test_spa_serves_index_when_dist_present(tmp_path, monkeypatch) -> None:
    dist = tmp_path / "ops-web-dist"
    dist.mkdir()
    (dist / "index.html").write_text("<!doctype html><title>矩阵助手</title>", encoding="utf-8")
    monkeypatch.setenv("OPS_WEB_DIST", str(dist))

    with TestClient(app) as client:
        home = client.get("/", follow_redirects=False)
        login = client.get("/login", follow_redirects=False)
        publish = client.get("/publish", follow_redirects=False)
        health = client.get("/health")

    assert home.status_code == 200
    assert "矩阵助手" in home.text
    assert login.status_code == 200
    assert publish.status_code == 200
    assert health.status_code == 200
    assert health.json()["status"] == "ok"

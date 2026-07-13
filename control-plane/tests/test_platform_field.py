from __future__ import annotations

import os
from uuid import uuid4

from fastapi.testclient import TestClient

from loanagent.accounts import AccountRepository
from loanagent.content import ContentRepository
from loanagent.devices import DeviceRepository
from loanagent.main import app
from loanagent.roles import AccountRole


DATABASE_URL = os.environ["DATABASE_URL"]
OPS_TOKEN = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")


def _headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {OPS_TOKEN}"}


def test_account_defaults_to_xhs_platform() -> None:
    device_id = f"plat-device-{uuid4()}"
    account_id = f"plat-account-{uuid4()}"
    accounts = AccountRepository(DATABASE_URL)
    accounts.migrate()
    DeviceRepository(DATABASE_URL).create(device_id=device_id)
    account = accounts.create(
        account_id=account_id,
        role=AccountRole.PUBLISHER_MAIN,
        device_id=device_id,
    )
    assert account.platform == "xhs"

    with TestClient(app) as client:
        listed = client.get("/api/v1/accounts?platform=xhs", headers=_headers())
    assert listed.status_code == 200
    assert any(row["account_id"] == account_id for row in listed.json())


def test_content_carries_platform() -> None:
    ContentRepository(DATABASE_URL).migrate()
    with TestClient(app) as client:
        created = client.post(
            "/api/v1/content",
            headers=_headers(),
            json={"title": "茶香测试", "body": "正文内容足够长", "platform": "xhs"},
        )
    assert created.status_code == 200
    body = created.json()
    assert body["platform"] == "xhs"
    content = ContentRepository(DATABASE_URL).get(body["content_id"])
    assert content.platform == "xhs"

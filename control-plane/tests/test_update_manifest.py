from __future__ import annotations

import json
from pathlib import Path

import pytest

from loanagent.update_manifest import (
    build_unsigned_manifest_template,
    load_ring_manifest,
    publish_signed_manifest,
    validate_signed_manifest,
)


def _signed(ring: str = "canary") -> dict:
    body = build_unsigned_manifest_template(
        ring=ring,
        agent_version="0.2.0",
        minimum_agent_version="0.1.0",
        manifest_version="0.2.0",
        apk_url="https://updates.example.com/agent.apk",
        apk_sha256="a" * 64,
        apk_size_bytes=12,
        key_id="m0-key",
    )
    body["signature"]["value"] = "dGVzdA=="
    return body


def test_publish_and_load_ring(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("UPDATE_MANIFEST_DIR", str(tmp_path))
    published = publish_signed_manifest("canary", _signed())
    assert published.available is True
    assert published.agent_version == "0.2.0"
    loaded = load_ring_manifest("canary")
    assert loaded.available is True
    assert (tmp_path / "canary.json").is_file()


def test_validate_rejects_http_artifact() -> None:
    payload = _signed()
    payload["artifacts"][0]["url"] = "http://updates.example.com/agent.apk"
    with pytest.raises(ValueError, match="https"):
        validate_signed_manifest(payload)


def test_publish_requires_matching_ring() -> None:
    with pytest.raises(ValueError, match="rollout_ring"):
        publish_signed_manifest("stable", _signed("canary"))

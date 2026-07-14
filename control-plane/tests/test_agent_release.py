"""Tests for agent APK release metadata + download route."""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from loanagent.agent_release import load_latest_release, write_release_manifest
from loanagent.main import download_device_bind_guide_pdf, download_latest_agent_apk


def test_load_latest_missing(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("AGENT_RELEASE_DIR", str(tmp_path))
    release = load_latest_release()
    assert release.available is False
    assert release.download_path == "/downloads/agent-latest.apk"
    assert release.guide_available is False


def test_load_latest_with_guide(tmp_path: Path, monkeypatch) -> None:
    guide = tmp_path / "device-bind-guide.pdf"
    guide.write_bytes(b"%PDF-1.4 guide")
    monkeypatch.setenv("AGENT_RELEASE_DIR", str(tmp_path))
    release = load_latest_release()
    assert release.guide_available is True
    assert release.guide_download_path == "/downloads/device-bind-guide.pdf"


def test_download_latest_apk(tmp_path: Path, monkeypatch) -> None:
    apk = tmp_path / "agent-latest.apk"
    apk.write_bytes(b"PK\x03\x04-fake-apk-bytes")
    write_release_manifest(
        apk_path=apk,
        version_name="0.1.0",
        version_code=1,
        package_name="com.loanagent.agent",
        source_ref="test-ref",
        dest_dir=tmp_path,
    )
    monkeypatch.setenv("AGENT_RELEASE_DIR", str(tmp_path))

    release = load_latest_release()
    assert release.available is True
    assert release.version_name == "0.1.0"
    assert release.sha256
    assert release.byte_size == apk.stat().st_size

    mini = FastAPI()
    mini.get("/downloads/agent-latest.apk")(download_latest_agent_apk)
    with TestClient(mini) as client:
        download = client.get("/downloads/agent-latest.apk")

    assert download.status_code == 200
    assert download.content == b"PK\x03\x04-fake-apk-bytes"
    assert "attachment" in download.headers.get("content-disposition", "")


def test_download_guide_pdf(tmp_path: Path, monkeypatch) -> None:
    guide = tmp_path / "device-bind-guide.pdf"
    guide.write_bytes(b"%PDF-1.4 fake-guide")
    monkeypatch.setenv("AGENT_RELEASE_DIR", str(tmp_path))

    mini = FastAPI()
    mini.get("/downloads/device-bind-guide.pdf")(download_device_bind_guide_pdf)
    with TestClient(mini) as client:
        download = client.get("/downloads/device-bind-guide.pdf")

    assert download.status_code == 200
    assert download.content == b"%PDF-1.4 fake-guide"
    assert "application/pdf" in download.headers.get("content-type", "")
    assert "attachment" in download.headers.get("content-disposition", "")

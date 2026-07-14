from __future__ import annotations

import hashlib
import json
import os
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_APK_NAME = "agent-latest.apk"
DEFAULT_MANIFEST_NAME = "latest.json"
DEFAULT_GUIDE_PDF_NAME = "device-bind-guide.pdf"
DEFAULT_GUIDE_DOWNLOAD_PATH = "/downloads/device-bind-guide.pdf"


@dataclass(frozen=True)
class AgentRelease:
    available: bool
    filename: str
    download_path: str
    version_name: str | None = None
    version_code: int | None = None
    package_name: str | None = None
    sha256: str | None = None
    source_ref: str | None = None
    built_at: str | None = None
    byte_size: int | None = None
    classification: str | None = None
    missing_reason: str | None = None
    guide_download_path: str | None = None
    guide_available: bool = False

    def as_public_dict(self) -> dict:
        return asdict(self)


class AgentReleaseNotFoundError(Exception):
    pass


def agent_release_dir() -> Path:
    configured = os.environ.get("AGENT_RELEASE_DIR", "").strip()
    if configured:
        return Path(configured)
    return Path("/app/agent-releases")


def load_latest_release() -> AgentRelease:
    root = agent_release_dir()
    apk_path = root / DEFAULT_APK_NAME
    manifest_path = root / DEFAULT_MANIFEST_NAME
    guide_path = root / DEFAULT_GUIDE_PDF_NAME
    guide_available = guide_path.is_file()

    if not apk_path.is_file():
        return AgentRelease(
            available=False,
            filename=DEFAULT_APK_NAME,
            download_path="/downloads/agent-latest.apk",
            missing_reason="APK not published on this server yet.",
            guide_download_path=DEFAULT_GUIDE_DOWNLOAD_PATH if guide_available else None,
            guide_available=guide_available,
        )

    meta: dict = {}
    if manifest_path.is_file():
        try:
            meta = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            meta = {}

    sha256 = str(meta.get("sha256") or "").strip() or _sha256_file(apk_path)
    return AgentRelease(
        available=True,
        filename=str(meta.get("filename") or DEFAULT_APK_NAME),
        download_path="/downloads/agent-latest.apk",
        version_name=str(meta.get("version_name") or "0.1.0"),
        version_code=int(meta["version_code"]) if meta.get("version_code") is not None else 1,
        package_name=str(meta.get("package_name") or "com.loanagent.agent"),
        sha256=sha256,
        source_ref=str(meta.get("source_ref") or "") or None,
        built_at=str(meta.get("built_at") or "") or None,
        byte_size=apk_path.stat().st_size,
        classification=str(meta.get("classification") or "UNTRUSTED_DEBUG_TEST_ONLY"),
        guide_download_path=DEFAULT_GUIDE_DOWNLOAD_PATH if guide_available else None,
        guide_available=guide_available,
    )


def resolve_apk_path() -> Path:
    path = agent_release_dir() / DEFAULT_APK_NAME
    if not path.is_file():
        raise AgentReleaseNotFoundError(str(path))
    return path


def resolve_guide_pdf_path() -> Path:
    path = agent_release_dir() / DEFAULT_GUIDE_PDF_NAME
    if not path.is_file():
        raise AgentReleaseNotFoundError(str(path))
    return path


def write_release_manifest(
    *,
    apk_path: Path,
    version_name: str,
    version_code: int,
    package_name: str,
    source_ref: str,
    classification: str = "UNTRUSTED_DEBUG_TEST_ONLY",
    dest_dir: Path | None = None,
) -> Path:
    """Write latest.json next to a published APK (used by publish script / tests)."""
    root = dest_dir or agent_release_dir()
    root.mkdir(parents=True, exist_ok=True)
    sha256 = _sha256_file(apk_path)
    payload = {
        "filename": DEFAULT_APK_NAME,
        "version_name": version_name,
        "version_code": version_code,
        "package_name": package_name,
        "sha256": sha256,
        "source_ref": source_ref,
        "built_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "byte_size": apk_path.stat().st_size,
        "classification": classification,
        "download_path": "/downloads/agent-latest.apk",
    }
    manifest = root / DEFAULT_MANIFEST_NAME
    manifest.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return manifest


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()

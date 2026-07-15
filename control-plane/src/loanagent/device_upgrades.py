from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any
from urllib.parse import urlparse

import psycopg

from loanagent.update_manifest import load_ring_manifest, public_download_path


@dataclass(frozen=True)
class DeviceUpgradeRecord:
    device_id: str
    status: str
    ring: str | None
    manifest_url: str | None
    detail: str | None
    requested_at: datetime | None
    updated_at: datetime | None
    request_id: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "device_id": self.device_id,
            "status": self.status,
            "ring": self.ring,
            "manifest_url": self.manifest_url,
            "detail": self.detail,
            "requested_at": self.requested_at.isoformat() if self.requested_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
            "request_id": self.request_id,
            "pending": self.status == "pending",
        }


class DeviceUpgradeRepository:
    def __init__(self, database_url: str) -> None:
        self.database_url = database_url

    def request_upgrade(
        self,
        device_id: str,
        *,
        ring: str | None = None,
        manifest_url: str | None = None,
        public_base_url: str | None = None,
    ) -> DeviceUpgradeRecord:
        if not device_id.strip():
            raise ValueError("device_id required")
        resolved_url = manifest_url
        resolved_ring = ring
        if resolved_url is None:
            if not resolved_ring:
                raise ValueError("ring or manifest_url required")
            hosted = load_ring_manifest(resolved_ring)
            if not hosted.available:
                raise FileNotFoundError(hosted.missing_reason or "manifest missing")
            if not public_base_url:
                raise ValueError("public_base_url required when using ring")
            resolved_url = public_base_url.rstrip("/") + public_download_path(resolved_ring)
        _require_https(resolved_url)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                INSERT INTO device_agent_upgrades (
                    device_id, status, ring, manifest_url, detail, requested_at, updated_at
                )
                VALUES (%s, 'pending', %s, %s, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (device_id) DO UPDATE
                SET status = 'pending',
                    ring = EXCLUDED.ring,
                    manifest_url = EXCLUDED.manifest_url,
                    detail = NULL,
                    requested_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING device_id, status, ring, manifest_url, detail,
                          requested_at, updated_at, request_id
                """,
                (device_id, resolved_ring, resolved_url),
            ).fetchone()
        return _from_row(row)

    def get(self, device_id: str) -> DeviceUpgradeRecord | None:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT device_id, status, ring, manifest_url, detail,
                       requested_at, updated_at, request_id
                FROM device_agent_upgrades
                WHERE device_id = %s
                """,
                (device_id,),
            ).fetchone()
        if row is None:
            return None
        return _from_row(row)

    def pending_for_device(self, device_id: str) -> DeviceUpgradeRecord | None:
        record = self.get(device_id)
        if record is None or record.status != "pending":
            return None
        return record

    def report_result(
        self,
        device_id: str,
        *,
        status: str,
        detail: str | None = None,
    ) -> DeviceUpgradeRecord:
        if status not in {"in_progress", "succeeded", "failed", "pending"}:
            raise ValueError("invalid status")
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                UPDATE device_agent_upgrades
                SET status = %s,
                    detail = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE device_id = %s
                RETURNING device_id, status, ring, manifest_url, detail,
                          requested_at, updated_at, request_id
                """,
                (status, detail, device_id),
            ).fetchone()
        if row is None:
            raise KeyError(device_id)
        return _from_row(row)


def _from_row(row: tuple[Any, ...]) -> DeviceUpgradeRecord:
    return DeviceUpgradeRecord(
        device_id=row[0],
        status=row[1],
        ring=row[2],
        manifest_url=row[3],
        detail=row[4],
        requested_at=row[5],
        updated_at=row[6],
        request_id=str(row[7]) if row[7] is not None else None,
    )


def _require_https(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("manifest_url must be https with host")

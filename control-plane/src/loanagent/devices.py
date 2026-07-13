from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

import psycopg

from loanagent.db import migrate_fleet_schema

_DEVICE_COLUMNS = """
    device_id, agent_version, manufacturer, model, online, last_seen_at,
    wifi_connected, a11y_bound, cellular_ok, created_at, updated_at, display_name
"""


@dataclass(frozen=True)
class DeviceRecord:
    device_id: str
    agent_version: str | None
    manufacturer: str | None
    model: str | None
    online: bool
    last_seen_at: datetime | None
    wifi_connected: bool | None
    a11y_bound: bool | None
    cellular_ok: bool | None
    created_at: datetime
    updated_at: datetime
    display_name: str | None = None


class DeviceNotFoundError(Exception):
    pass


class DeviceRepository:
    def __init__(self, database_url: str) -> None:
        self.database_url = database_url

    def migrate(self) -> None:
        migrate_fleet_schema(self.database_url)

    def create(
        self,
        *,
        device_id: str,
        agent_version: str | None = None,
        manufacturer: str | None = None,
        model: str | None = None,
        display_name: str | None = None,
    ) -> DeviceRecord:
        self._validate_device_id(device_id)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                INSERT INTO devices (device_id, agent_version, manufacturer, model, display_name)
                VALUES (%s, %s, %s, %s, %s)
                ON CONFLICT (device_id) DO UPDATE
                SET agent_version = COALESCE(EXCLUDED.agent_version, devices.agent_version),
                    manufacturer = COALESCE(EXCLUDED.manufacturer, devices.manufacturer),
                    model = COALESCE(EXCLUDED.model, devices.model),
                    display_name = COALESCE(EXCLUDED.display_name, devices.display_name),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING {_DEVICE_COLUMNS}
                """,
                (device_id, agent_version, manufacturer, model, display_name),
            ).fetchone()
        return _device_from_row(row)

    def heartbeat(
        self,
        *,
        device_id: str,
        agent_version: str | None = None,
        wifi_connected: bool | None = None,
        a11y_bound: bool | None = None,
        cellular_ok: bool | None = None,
    ) -> DeviceRecord:
        self._validate_device_id(device_id)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                INSERT INTO devices (
                    device_id, agent_version, online, last_seen_at, wifi_connected,
                    a11y_bound, cellular_ok
                )
                VALUES (%s, %s, TRUE, CURRENT_TIMESTAMP, %s, %s, %s)
                ON CONFLICT (device_id) DO UPDATE
                SET agent_version = COALESCE(EXCLUDED.agent_version, devices.agent_version),
                    online = TRUE,
                    last_seen_at = CURRENT_TIMESTAMP,
                    wifi_connected = COALESCE(EXCLUDED.wifi_connected, devices.wifi_connected),
                    a11y_bound = COALESCE(EXCLUDED.a11y_bound, devices.a11y_bound),
                    cellular_ok = COALESCE(EXCLUDED.cellular_ok, devices.cellular_ok),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING {_DEVICE_COLUMNS}
                """,
                (device_id, agent_version, wifi_connected, a11y_bound, cellular_ok),
            ).fetchone()
        return _device_from_row(row)

    def get(self, device_id: str) -> DeviceRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                SELECT {_DEVICE_COLUMNS}
                FROM devices
                WHERE device_id = %s
                """,
                (device_id,),
            ).fetchone()
        if row is None:
            raise DeviceNotFoundError(device_id)
        return _device_from_row(row)

    def list(self) -> list[DeviceRecord]:
        self.mark_stale_offline()
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                f"""
                SELECT {_DEVICE_COLUMNS}
                FROM devices
                ORDER BY device_id
                """
            ).fetchall()
        return [_device_from_row(row) for row in rows]

    def patch(
        self,
        device_id: str,
        *,
        manufacturer: str | None = None,
        model: str | None = None,
        online: bool | None = None,
        display_name: str | None = None,
    ) -> DeviceRecord:
        self.get(device_id)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                UPDATE devices
                SET manufacturer = COALESCE(%s, manufacturer),
                    model = COALESCE(%s, model),
                    online = COALESCE(%s, online),
                    display_name = COALESCE(%s, display_name),
                    updated_at = CURRENT_TIMESTAMP
                WHERE device_id = %s
                RETURNING {_DEVICE_COLUMNS}
                """,
                (manufacturer, model, online, display_name, device_id),
            ).fetchone()
        return _device_from_row(row)

    def mark_stale_offline(self, *, stale_after_sec: int = 90) -> int:
        """Mark devices offline when heartbeat is older than stale_after_sec."""
        if stale_after_sec < 1:
            raise ValueError("stale_after_sec must be >= 1")
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                UPDATE devices
                SET online = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE online = TRUE
                  AND (
                    last_seen_at IS NULL
                    OR last_seen_at < CURRENT_TIMESTAMP - (%s * INTERVAL '1 second')
                  )
                RETURNING device_id
                """,
                (stale_after_sec,),
            ).fetchall()
        return len(rows)

    @staticmethod
    def _validate_device_id(device_id: str) -> None:
        if not device_id:
            raise ValueError("device_id must not be empty")


def _device_from_row(row: tuple) -> DeviceRecord:
    return DeviceRecord(
        device_id=row[0],
        agent_version=row[1],
        manufacturer=row[2],
        model=row[3],
        online=row[4],
        last_seen_at=row[5],
        wifi_connected=row[6],
        a11y_bound=row[7],
        cellular_ok=row[8],
        created_at=row[9],
        updated_at=row[10],
        display_name=row[11] if len(row) > 11 else None,
    )

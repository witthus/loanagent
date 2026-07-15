from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

import psycopg

from loanagent.db import migrate_fleet_schema

_DEVICE_COLUMNS = """
    device_id, agent_version, manufacturer, model, online, last_seen_at,
    wifi_connected, a11y_bound, cellular_ok, created_at, updated_at, display_name,
    public_ip, geo_label
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
    public_ip: str | None = None
    geo_label: str | None = None


class DeviceNotFoundError(Exception):
    pass


class DeviceBoundError(Exception):
    pass


class DeviceStillOnlineError(Exception):
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
        manufacturer: str | None = None,
        model: str | None = None,
        public_ip: str | None = None,
        geo_label: str | None = None,
    ) -> DeviceRecord:
        self._validate_device_id(device_id)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                INSERT INTO devices (
                    device_id, agent_version, manufacturer, model, online, last_seen_at,
                    wifi_connected, a11y_bound, cellular_ok, public_ip, geo_label
                )
                VALUES (%s, %s, %s, %s, TRUE, CURRENT_TIMESTAMP, %s, %s, %s, %s, %s)
                ON CONFLICT (device_id) DO UPDATE
                SET agent_version = COALESCE(EXCLUDED.agent_version, devices.agent_version),
                    manufacturer = COALESCE(EXCLUDED.manufacturer, devices.manufacturer),
                    model = COALESCE(EXCLUDED.model, devices.model),
                    online = TRUE,
                    last_seen_at = CURRENT_TIMESTAMP,
                    wifi_connected = COALESCE(EXCLUDED.wifi_connected, devices.wifi_connected),
                    a11y_bound = COALESCE(EXCLUDED.a11y_bound, devices.a11y_bound),
                    cellular_ok = COALESCE(EXCLUDED.cellular_ok, devices.cellular_ok),
                    public_ip = COALESCE(EXCLUDED.public_ip, devices.public_ip),
                    geo_label = CASE
                        WHEN EXCLUDED.public_ip IS NOT NULL
                             AND EXCLUDED.public_ip IS DISTINCT FROM devices.public_ip
                        THEN EXCLUDED.geo_label
                        ELSE COALESCE(EXCLUDED.geo_label, devices.geo_label)
                    END,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING {_DEVICE_COLUMNS}
                """,
                (
                    device_id,
                    agent_version,
                    manufacturer,
                    model,
                    wifi_connected,
                    a11y_bound,
                    cellular_ok,
                    public_ip,
                    geo_label,
                ),
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
                ORDER BY
                    CASE WHEN geo_label IS NULL OR geo_label = '' THEN 1 ELSE 0 END,
                    geo_label NULLS LAST,
                    device_id
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

    def mark_stale_offline(self, *, stale_after_sec: int = 90) -> list[str]:
        """Mark devices offline when heartbeat is older than stale_after_sec.

        Returns the device_ids that transitioned to offline so callers can cancel
        their open tasks.
        """
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
        return [str(row[0]) for row in rows]

    def delete_unbound_offline(self, device_id: str) -> None:
        """Hard-delete an unbound offline device and its tasks.

        Refreshes stale online flags first so recently silent devices can be removed.
        """
        self.mark_stale_offline()
        with psycopg.connect(self.database_url) as connection:
            with connection.transaction():
                row = connection.execute(
                    """
                    SELECT online
                    FROM devices
                    WHERE device_id = %s
                    FOR UPDATE
                    """,
                    (device_id,),
                ).fetchone()
                if row is None:
                    raise DeviceNotFoundError(device_id)
                if row[0] is True:
                    raise DeviceStillOnlineError(device_id)
                bound = connection.execute(
                    """
                    SELECT account_id
                    FROM accounts
                    WHERE device_id = %s
                    LIMIT 1
                    """,
                    (device_id,),
                ).fetchone()
                if bound is not None:
                    raise DeviceBoundError(device_id)
                connection.execute(
                    "DELETE FROM tasks WHERE device_id = %s",
                    (device_id,),
                )
                deleted = connection.execute(
                    """
                    DELETE FROM devices
                    WHERE device_id = %s
                    RETURNING device_id
                    """,
                    (device_id,),
                ).fetchone()
                if deleted is None:
                    raise DeviceNotFoundError(device_id)

    def update_geo_if_ip_matches(
        self,
        device_id: str,
        *,
        public_ip: str,
        geo_label: str | None,
    ) -> DeviceRecord | None:
        """Persist geo only when the device still reports the same public IP."""
        self._validate_device_id(device_id)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                UPDATE devices
                SET geo_label = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE device_id = %s
                  AND public_ip = %s
                RETURNING {_DEVICE_COLUMNS}
                """,
                (geo_label, device_id, public_ip),
            ).fetchone()
        if row is None:
            return None
        return _device_from_row(row)

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
        public_ip=row[12] if len(row) > 12 else None,
        geo_label=row[13] if len(row) > 13 else None,
    )

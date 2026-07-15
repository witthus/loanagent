from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any

import psycopg

from loanagent.db import migrate_fleet_schema
from loanagent.platforms import normalize_platform
from loanagent.roles import AccountRole


VALID_STATUSES = {"active", "paused", "blocked", "needs_login"}
PATCHABLE_FIELDS = {
    "device_id",
    "status",
    "network_policy",
    "daily_publish_quota",
    "inbox_sync_enabled",
    "display_name",
    "platform",
    "role",
}


@dataclass(frozen=True)
class AccountRecord:
    account_id: str
    role: AccountRole
    device_id: str | None
    status: str
    network_policy: str
    daily_publish_quota: int
    inbox_sync_enabled: bool
    display_name: str | None
    platform: str
    created_at: datetime
    updated_at: datetime


class AccountAlreadyExistsError(Exception):
    pass


class AccountDeviceAlreadyBoundError(Exception):
    pass


class AccountDeviceNotFoundError(Exception):
    pass


class AccountNotFoundError(Exception):
    pass


class AccountRepository:
    def __init__(self, database_url: str) -> None:
        self.database_url = database_url

    def migrate(self) -> None:
        migrate_fleet_schema(self.database_url)

    def create(
        self,
        *,
        account_id: str,
        role: AccountRole | str,
        device_id: str | None = None,
        display_name: str | None = None,
        platform: str | None = None,
    ) -> AccountRecord:
        self._validate_account_id(account_id)
        role = AccountRole(role)
        platform_value = normalize_platform(platform)
        daily_publish_quota, inbox_sync_enabled = _defaults_for_role(role)
        try:
            with psycopg.connect(self.database_url) as connection:
                row = connection.execute(
                    """
                    INSERT INTO accounts (
                        account_id, role, device_id, daily_publish_quota,
                        inbox_sync_enabled, display_name, platform
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                    RETURNING account_id, role, device_id, status, network_policy,
                        daily_publish_quota, inbox_sync_enabled, display_name, platform,
                        created_at, updated_at
                    """,
                    (
                        account_id,
                        role.value,
                        device_id,
                        daily_publish_quota,
                        inbox_sync_enabled,
                        display_name,
                        platform_value,
                    ),
                ).fetchone()
        except psycopg.errors.UniqueViolation as error:
            _raise_unique_violation(error)
        except psycopg.errors.ForeignKeyViolation as error:
            raise AccountDeviceNotFoundError(device_id) from error
        return _account_from_row(row)

    def get(self, account_id: str) -> AccountRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT account_id, role, device_id, status, network_policy,
                    daily_publish_quota, inbox_sync_enabled, display_name, platform,
                    created_at, updated_at
                FROM accounts
                WHERE account_id = %s
                """,
                (account_id,),
            ).fetchone()
        if row is None:
            raise AccountNotFoundError(account_id)
        return _account_from_row(row)

    def find_by_device_id(self, device_id: str) -> AccountRecord | None:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT account_id, role, device_id, status, network_policy,
                    daily_publish_quota, inbox_sync_enabled, display_name, platform,
                    created_at, updated_at
                FROM accounts
                WHERE device_id = %s
                """,
                (device_id,),
            ).fetchone()
        if row is None:
            return None
        return _account_from_row(row)

    def list(self, *, platform: str | None = None) -> list[AccountRecord]:
        query = """
            SELECT account_id, role, device_id, status, network_policy,
                daily_publish_quota, inbox_sync_enabled, display_name, platform,
                created_at, updated_at
            FROM accounts
        """
        params: list[Any] = []
        if platform is not None:
            query += " WHERE platform = %s"
            params.append(normalize_platform(platform))
        query += " ORDER BY account_id"
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(query, params).fetchall()
        return [_account_from_row(row) for row in rows]

    def update(self, account_id: str, **changes: Any) -> AccountRecord:
        invalid_fields = set(changes) - PATCHABLE_FIELDS
        if invalid_fields:
            raise ValueError(f"unsupported account fields: {sorted(invalid_fields)}")
        if "status" in changes and changes["status"] not in VALID_STATUSES:
            raise ValueError("unsupported account status")
        if "daily_publish_quota" in changes and changes["daily_publish_quota"] < 0:
            raise ValueError("daily_publish_quota must not be negative")
        if "platform" in changes:
            changes["platform"] = normalize_platform(changes["platform"])
        if "role" in changes:
            changes["role"] = AccountRole(changes["role"]).value
        if not changes:
            return self.get(account_id)

        assignments = [f"{field} = %s" for field in changes]
        values = [*changes.values(), account_id]
        try:
            with psycopg.connect(self.database_url) as connection:
                row = connection.execute(
                    f"""
                    UPDATE accounts
                    SET {", ".join(assignments)},
                        updated_at = CURRENT_TIMESTAMP
                    WHERE account_id = %s
                    RETURNING account_id, role, device_id, status, network_policy,
                        daily_publish_quota, inbox_sync_enabled, display_name, platform,
                        created_at, updated_at
                    """,
                    values,
                ).fetchone()
        except psycopg.errors.UniqueViolation as error:
            _raise_unique_violation(error)
        except psycopg.errors.ForeignKeyViolation as error:
            raise AccountDeviceNotFoundError(changes.get("device_id")) from error
        if row is None:
            raise AccountNotFoundError(account_id)
        return _account_from_row(row)

    def pause(self, account_id: str) -> AccountRecord:
        return self.update(account_id, status="paused")

    def resume(self, account_id: str) -> AccountRecord:
        return self.update(account_id, status="active")

    def delete(self, account_id: str) -> None:
        """Remove account and account-scoped history; device row is kept unbound."""
        self.get(account_id)
        with psycopg.connect(self.database_url) as connection:
            connection.execute(
                """
                DELETE FROM leads
                WHERE thread_id IN (
                    SELECT thread_id FROM inbox_threads WHERE account_id = %s
                )
                """,
                (account_id,),
            )
            connection.execute(
                """
                DELETE FROM inbox_messages
                WHERE thread_id IN (
                    SELECT thread_id FROM inbox_threads WHERE account_id = %s
                )
                """,
                (account_id,),
            )
            connection.execute(
                "DELETE FROM inbox_threads WHERE account_id = %s",
                (account_id,),
            )
            connection.execute(
                """
                DELETE FROM note_comment_nodes
                WHERE account_id = %s
                   OR note_id IN (
                        SELECT note_id FROM published_notes WHERE account_id = %s
                   )
                """,
                (account_id, account_id),
            )
            connection.execute(
                """
                DELETE FROM note_comments
                WHERE account_id = %s
                   OR note_id IN (
                        SELECT note_id FROM published_notes WHERE account_id = %s
                   )
                """,
                (account_id, account_id),
            )
            connection.execute(
                "DELETE FROM published_notes WHERE account_id = %s",
                (account_id,),
            )
            connection.execute(
                "DELETE FROM schedule_items WHERE account_id = %s",
                (account_id,),
            )
            connection.execute(
                """
                DELETE FROM engagement_chains
                WHERE account_id = %s
                   OR engager_account_id = %s
                   OR engager_account_ids ? %s
                """,
                (account_id, account_id, account_id),
            )
            connection.execute(
                "DELETE FROM tasks WHERE account_id = %s",
                (account_id,),
            )
            deleted = connection.execute(
                """
                DELETE FROM accounts
                WHERE account_id = %s
                RETURNING account_id
                """,
                (account_id,),
            ).fetchone()
        if deleted is None:
            raise AccountNotFoundError(account_id)

    @staticmethod
    def _validate_account_id(account_id: str) -> None:
        if not account_id:
            raise ValueError("account_id must not be empty")


def _defaults_for_role(role: AccountRole) -> tuple[int, bool]:
    if role is AccountRole.PUBLISHER_MAIN:
        return 1, True
    if role is AccountRole.PUBLISHER_MATRIX:
        return 2, True
    return 0, False


def _raise_unique_violation(error: psycopg.errors.UniqueViolation) -> None:
    if error.diag.constraint_name == "accounts_device_id_key":
        raise AccountDeviceAlreadyBoundError from error
    raise AccountAlreadyExistsError from error


def _account_from_row(row: tuple) -> AccountRecord:
    return AccountRecord(
        account_id=row[0],
        role=AccountRole(row[1]),
        device_id=row[2],
        status=row[3],
        network_policy=row[4],
        daily_publish_quota=row[5],
        inbox_sync_enabled=row[6],
        display_name=row[7],
        platform=row[8],
        created_at=row[9],
        updated_at=row[10],
    )

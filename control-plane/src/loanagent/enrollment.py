from __future__ import annotations

import argparse
import hashlib
import os
import secrets
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from pathlib import Path

import psycopg
from psycopg.types.json import Jsonb

from loanagent.secure_files import atomic_write_mode_0600


@dataclass(frozen=True)
class DeviceIdentity:
    device_id: str
    manufacturer: str
    model: str
    android_version: str
    controller_version: str


class EnrollmentConsumeStatus(Enum):
    CONSUMED = "consumed"
    IDEMPOTENT_RETRY = "idempotent_retry"
    DEVICE_CONFLICT = "device_conflict"
    EXPIRED = "expired"
    INVALID = "invalid"


@dataclass(frozen=True)
class EnrollmentConsumeResult:
    status: EnrollmentConsumeStatus
    consumed_at: datetime | None = None


class EnrollmentRepository:
    def __init__(self, database_url: str) -> None:
        self.database_url = database_url

    @staticmethod
    def hash_token(token: str) -> bytes:
        return hashlib.sha256(token.encode("utf-8")).digest()

    def migrate(self) -> None:
        with psycopg.connect(self.database_url) as connection:
            connection.execute(
                "SELECT pg_advisory_xact_lock(hashtext(%s))",
                ("loanagent-enrollment-schema",),
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS loanagent_schema_migrations (
                    version INTEGER PRIMARY KEY CHECK (version > 0),
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            applied = {
                row[0]
                for row in connection.execute(
                    "SELECT version FROM loanagent_schema_migrations"
                ).fetchall()
            }
            table_exists = connection.execute(
                "SELECT to_regclass('enrollment_tokens') IS NOT NULL"
            ).fetchone()[0]
            if table_exists and not applied:
                columns = self._enrollment_columns(connection)
                if {"device_id", "device_identity"}.issubset(columns):
                    self._record_migration(connection, 1)
                    self._record_migration(connection, 2)
                    return
                self._record_migration(connection, 1)
                applied.add(1)

            if 1 not in applied:
                connection.execute(
                    """
                    CREATE TABLE enrollment_tokens (
                        token_hash BYTEA PRIMARY KEY,
                        expires_at TIMESTAMPTZ NOT NULL,
                        consumed_at TIMESTAMPTZ,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """
                )
                self._record_migration(connection, 1)

            if 2 not in applied:
                connection.execute(
                    "ALTER TABLE enrollment_tokens ADD COLUMN device_id TEXT"
                )
                connection.execute(
                    "ALTER TABLE enrollment_tokens ADD COLUMN device_identity JSONB"
                )
                connection.execute(
                    """
                    UPDATE enrollment_tokens
                    SET device_id = 'legacy-unbound:' || encode(token_hash, 'hex'),
                        device_identity = jsonb_build_object(
                            'device_id',
                            'legacy-unbound:' || encode(token_hash, 'hex')
                        )
                    WHERE consumed_at IS NOT NULL
                    """
                )
                connection.execute(
                    """
                    ALTER TABLE enrollment_tokens
                    ADD CONSTRAINT enrollment_tokens_token_hash_length
                    CHECK (octet_length(token_hash) = 32)
                    """
                )
                connection.execute(
                    """
                    ALTER TABLE enrollment_tokens
                    ADD CONSTRAINT enrollment_tokens_consumption_binding
                    CHECK (
                        (consumed_at IS NULL AND device_id IS NULL
                            AND device_identity IS NULL)
                        OR
                        (consumed_at IS NOT NULL AND device_id IS NOT NULL
                            AND device_identity IS NOT NULL)
                    )
                    """
                )
                self._record_migration(connection, 2)

    @staticmethod
    def _record_migration(connection: psycopg.Connection, version: int) -> None:
        connection.execute(
            """
            INSERT INTO loanagent_schema_migrations (version)
            VALUES (%s)
            ON CONFLICT (version) DO NOTHING
            """,
            (version,),
        )

    @staticmethod
    def _enrollment_columns(connection: psycopg.Connection) -> set[str]:
        return {
            row[0]
            for row in connection.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                    AND table_name = 'enrollment_tokens'
                """
            ).fetchall()
        }

    def issue(self, token: str, expires_at: datetime) -> None:
        if not token:
            raise ValueError("token must not be empty")
        if expires_at.tzinfo is None:
            raise ValueError("expires_at must be timezone-aware")
        with psycopg.connect(self.database_url) as connection:
            connection.execute(
                "INSERT INTO enrollment_tokens (token_hash, expires_at) VALUES (%s, %s)",
                (self.hash_token(token), expires_at),
            )

    def issue_new(self, ttl: timedelta) -> str:
        if ttl <= timedelta(0):
            raise ValueError("ttl must be positive")
        token = secrets.token_urlsafe(32)
        self.issue(token, datetime.now(UTC) + ttl)
        return token

    def consume(
        self,
        token: str,
        identity: DeviceIdentity,
    ) -> EnrollmentConsumeResult:
        token_hash = self.hash_token(token)
        with psycopg.connect(self.database_url) as connection:
            state = connection.execute(
                """
                SELECT expires_at, consumed_at, device_id
                FROM enrollment_tokens
                WHERE token_hash = %s
                FOR UPDATE
                """,
                (token_hash,),
            ).fetchone()
            if state is None:
                return EnrollmentConsumeResult(EnrollmentConsumeStatus.INVALID)
            expires_at, consumed_at, device_id = state
            if consumed_at is not None:
                return EnrollmentConsumeResult(
                    (
                        EnrollmentConsumeStatus.IDEMPOTENT_RETRY
                        if device_id == identity.device_id
                        else EnrollmentConsumeStatus.DEVICE_CONFLICT
                    ),
                    consumed_at=consumed_at,
                )
            if expires_at <= datetime.now(UTC):
                return EnrollmentConsumeResult(EnrollmentConsumeStatus.EXPIRED)
            consumed_at = connection.execute(
                """
                UPDATE enrollment_tokens
                SET consumed_at = CURRENT_TIMESTAMP,
                    device_id = %s,
                    device_identity = %s
                WHERE token_hash = %s
                RETURNING consumed_at
                """,
                (identity.device_id, Jsonb(asdict(identity)), token_hash),
            ).fetchone()
            if consumed_at is None:
                return EnrollmentConsumeResult(EnrollmentConsumeStatus.INVALID)
            return EnrollmentConsumeResult(
                EnrollmentConsumeStatus.CONSUMED,
                consumed_at=consumed_at[0],
            )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Issue one-time M0 enrollment tokens.")
    parser.add_argument("command", choices=["issue"])
    parser.add_argument("--ttl-seconds", type=int, default=900)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    database_url = os.environ["DATABASE_URL"]
    repository = EnrollmentRepository(database_url)
    repository.migrate()
    token = repository.issue_new(timedelta(seconds=args.ttl_seconds))
    atomic_write_mode_0600(args.output, f"{token}\n".encode())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

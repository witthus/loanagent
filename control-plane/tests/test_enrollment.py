from __future__ import annotations

import os
import stat
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from loanagent.enrollment import (
    DeviceIdentity,
    EnrollmentConsumeStatus,
    EnrollmentRepository,
    main as enrollment_main,
)
from loanagent.main import app


DATABASE_URL = os.environ["DATABASE_URL"]


def repository() -> EnrollmentRepository:
    result = EnrollmentRepository(DATABASE_URL)
    result.migrate()
    return result


def unique_token() -> str:
    return f"m0-{uuid4()}"


def identity(suffix: str = "a") -> DeviceIdentity:
    return DeviceIdentity(
        device_id=f"device-{suffix}",
        manufacturer="Xiaomi",
        model=f"Model {suffix}",
        android_version="15",
        controller_version="0.1.0",
    )


def test_migrates_legacy_table_transactionally_and_records_versions() -> None:
    token = unique_token()
    token_hash = EnrollmentRepository.hash_token(token)
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute("DROP TABLE IF EXISTS loanagent_schema_migrations")
        connection.execute("DROP TABLE IF EXISTS enrollment_tokens")
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
        connection.execute(
            """
            INSERT INTO enrollment_tokens (token_hash, expires_at, consumed_at)
            VALUES (%s, %s, CURRENT_TIMESTAMP)
            """,
            (token_hash, datetime.now(UTC) + timedelta(minutes=10)),
        )

    EnrollmentRepository(DATABASE_URL).migrate()

    with psycopg.connect(DATABASE_URL) as connection:
        versions = [
            row[0]
            for row in connection.execute(
                "SELECT version FROM loanagent_schema_migrations ORDER BY version"
            ).fetchall()
        ]
        migrated = connection.execute(
            """
            SELECT device_id, device_identity
            FROM enrollment_tokens
            WHERE token_hash = %s
            """,
            (token_hash,),
        ).fetchone()
    assert versions == [1, 2]
    assert migrated is not None
    assert migrated[0].startswith("legacy-unbound:")
    assert migrated[1]["device_id"] == migrated[0]


def test_failed_migration_rolls_back_schema_and_version_records() -> None:
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute("DROP TABLE IF EXISTS loanagent_schema_migrations")
        connection.execute("DROP TABLE IF EXISTS enrollment_tokens")
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
        connection.execute(
            """
            INSERT INTO enrollment_tokens (token_hash, expires_at)
            VALUES (%s, %s)
            """,
            (b"too-short", datetime.now(UTC) + timedelta(minutes=10)),
        )

    with pytest.raises(psycopg.errors.CheckViolation):
        EnrollmentRepository(DATABASE_URL).migrate()

    with psycopg.connect(DATABASE_URL) as connection:
        migration_table_exists = connection.execute(
            "SELECT to_regclass('loanagent_schema_migrations') IS NOT NULL"
        ).fetchone()[0]
        columns = {
            row[0]
            for row in connection.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'enrollment_tokens'
                """
            ).fetchall()
        }
        connection.execute("DROP TABLE enrollment_tokens")
    assert migration_table_exists is False
    assert "device_id" not in columns
    EnrollmentRepository(DATABASE_URL).migrate()


def test_repository_same_device_concurrent_retry_is_idempotent() -> None:
    token = unique_token()
    repo = repository()
    repo.issue(token, datetime.now(UTC) + timedelta(minutes=10))

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(
            executor.map(lambda _: repo.consume(token, identity("same")), range(2))
        )

    assert sorted(result.status.value for result in results) == [
        EnrollmentConsumeStatus.CONSUMED.value,
        EnrollmentConsumeStatus.IDEMPOTENT_RETRY.value,
    ]
    assert results[0].consumed_at == results[1].consumed_at


def test_repository_different_devices_compete_with_one_conflict() -> None:
    token = unique_token()
    repo = repository()
    repo.issue(token, datetime.now(UTC) + timedelta(minutes=10))

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(
            executor.map(
                lambda suffix: repo.consume(token, identity(suffix)),
                ("a", "b"),
            )
        )

    assert sorted(result.status.value for result in results) == [
        EnrollmentConsumeStatus.CONSUMED.value,
        EnrollmentConsumeStatus.DEVICE_CONFLICT.value,
    ]
    winner = next(
        identity(suffix).device_id
        for suffix, result in zip(("a", "b"), results, strict=True)
        if result.status is EnrollmentConsumeStatus.CONSUMED
    )
    with psycopg.connect(DATABASE_URL) as connection:
        persisted = connection.execute(
            """
            SELECT device_id, consumed_at
            FROM enrollment_tokens
            WHERE token_hash = %s
            """,
            (EnrollmentRepository.hash_token(token),),
        ).fetchone()
    assert persisted is not None
    assert persisted[0] == winner
    assert persisted[1] is not None


def test_repository_rejects_expired_token_without_consuming_it() -> None:
    token = unique_token()
    repo = repository()
    repo.issue(token, datetime.now(UTC) - timedelta(seconds=1))

    result = repo.consume(token, identity())

    assert result.status is EnrollmentConsumeStatus.EXPIRED
    assert result.consumed_at is None


def test_repository_persists_only_token_hash() -> None:
    token = unique_token()
    repo = repository()
    repo.issue(token, datetime.now(UTC) + timedelta(minutes=10))

    with psycopg.connect(DATABASE_URL) as connection:
        row = connection.execute(
            "SELECT token_hash, expires_at, consumed_at "
            "FROM enrollment_tokens WHERE token_hash = %s",
            (EnrollmentRepository.hash_token(token),),
        ).fetchone()
        columns = {
            item[0]
            for item in connection.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_name = 'enrollment_tokens'"
            ).fetchall()
        }

    assert row is not None
    assert row[0] == EnrollmentRepository.hash_token(token)
    assert token.encode() not in row[0]
    assert "token" not in columns
    assert "token_hash" in columns


def test_token_issuer_writes_secret_file_without_stdout(tmp_path, capsys) -> None:
    output = tmp_path / "enrollment-token"

    assert enrollment_main(
        ["issue", "--ttl-seconds", "60", "--output", str(output)]
    ) == 0

    captured = capsys.readouterr()
    assert captured.out == ""
    assert output.read_text(encoding="utf-8").strip()
    assert stat.S_IMODE(output.stat().st_mode) == 0o600


def test_enrollment_endpoint_is_idempotent_after_response_loss() -> None:
    repo = repository()
    valid_token = unique_token()
    repo.issue(valid_token, datetime.now(UTC) + timedelta(minutes=10))
    payload = {
        "token": valid_token,
        "device": {
            "device_id": "device-endpoint",
            "manufacturer": "Xiaomi",
            "model": "Redmi Test",
            "android_version": "15",
            "controller_version": "0.1.0",
        },
    }

    with TestClient(app) as client:
        first = client.post("/enroll", json=payload)
        retry = client.post("/enroll", json=payload)

    assert first.status_code == 200
    assert retry.status_code == 200
    assert retry.json()["status"] == "already_enrolled"
    assert retry.json()["device_id"] == "device-endpoint"


def test_enrollment_endpoint_rejects_other_device_and_expired_token() -> None:
    repo = repository()
    valid_token = unique_token()
    expired_token = unique_token()
    repo.issue(valid_token, datetime.now(UTC) + timedelta(minutes=10))
    repo.issue(expired_token, datetime.now(UTC) - timedelta(seconds=1))
    first_payload = {
        "token": valid_token,
        "device": {
            "device_id": "device-first",
            "manufacturer": "Xiaomi",
            "model": "Redmi Test",
            "android_version": "15",
            "controller_version": "0.1.0",
        },
    }
    with TestClient(app) as client:
        assert client.post("/enroll", json=first_payload).status_code == 200
        conflict = client.post(
            "/enroll",
            json={
                **first_payload,
                "device": first_payload["device"] | {"device_id": "device-other"},
            },
        )
        expired = client.post(
            "/enroll",
            json=first_payload | {"token": expired_token},
        )

    assert conflict.status_code == 409
    assert conflict.json()["detail"]["code"] == "TOKEN_DEVICE_CONFLICT"
    assert expired.status_code == 410
    assert expired.json()["detail"]["code"] == "TOKEN_EXPIRED"

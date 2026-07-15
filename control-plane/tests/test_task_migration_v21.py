from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
from uuid import uuid4

from fastapi.encoders import jsonable_encoder
from jsonschema import Draft202012Validator
import psycopg
from psycopg import sql
from psycopg.conninfo import make_conninfo
import pytest

from loanagent.db import migrate_fleet_schema
from loanagent.tasks import TaskService, serialize_task_record


DATABASE_URL = os.environ["DATABASE_URL"]


class NullMqttBus:
    def publish(self, topic: str, payload: dict) -> None:
        return None


def create_v20_fixture(*, wip_version_21_recorded: bool) -> tuple[str, str]:
    schema_name = f"task_v20_{uuid4().hex}"
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema_name)))
    fixture_url = make_conninfo(
        DATABASE_URL,
        options=f"-csearch_path={schema_name}",
    )
    with psycopg.connect(fixture_url) as connection:
        connection.execute(
            """
            CREATE TABLE loanagent_schema_migrations (
                version INTEGER PRIMARY KEY,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        )
        last_version = 21 if wip_version_21_recorded else 20
        connection.execute(
            """
            INSERT INTO loanagent_schema_migrations (version)
            SELECT generate_series(10, %s)
            """,
            (last_version,),
        )
        connection.execute(
            """
            CREATE TABLE devices (
                device_id TEXT PRIMARY KEY,
                public_ip TEXT,
                geo_label TEXT
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE accounts (
                account_id TEXT PRIMARY KEY,
                device_id TEXT UNIQUE REFERENCES devices(device_id)
            )
            """
        )
        connection.execute(
            """
            INSERT INTO devices (device_id)
            VALUES ('device-legacy')
            """
        )
        connection.execute(
            """
            INSERT INTO accounts (account_id, device_id)
            VALUES ('account-legacy', 'device-legacy')
            """
        )
        connection.execute(
            """
            CREATE TABLE tasks (
                task_id TEXT PRIMARY KEY,
                operation_id TEXT NOT NULL,
                device_id TEXT NOT NULL REFERENCES devices(device_id),
                account_id TEXT NOT NULL REFERENCES accounts(account_id),
                playbook TEXT NOT NULL,
                params JSONB NOT NULL DEFAULT '{}'::jsonb,
                effect_class TEXT NOT NULL CHECK (
                    effect_class IN ('readonly','idempotent','non_idempotent')
                ),
                effect_committed BOOLEAN NOT NULL DEFAULT FALSE,
                status TEXT NOT NULL CHECK (
                    status IN (
                        'queued','accepted','executing','effect_committed','reported',
                        'succeeded','failed','cancelled','unknown','reconcile_required'
                    )
                ),
                reconcile_required BOOLEAN NOT NULL DEFAULT FALSE,
                priority INT NOT NULL DEFAULT 100,
                timeout_sec INT NOT NULL DEFAULT 120,
                source TEXT NOT NULL DEFAULT 'manual',
                error_code TEXT,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            )
            """
        )
        historical = datetime(2020, 1, 1, tzinfo=timezone.utc)
        connection.cursor().executemany(
            """
            INSERT INTO tasks (
                task_id, operation_id, device_id, account_id, playbook,
                effect_class, effect_committed, status, created_at, updated_at
            )
            VALUES (%s, %s, 'device-legacy', 'account-legacy', %s, %s, %s, %s, %s, %s)
            """,
            [
                (
                    "legacy-accepted",
                    "op-accepted",
                    "ensure_app_ready@1.0",
                    "readonly",
                    False,
                    "accepted",
                    historical,
                    historical,
                ),
                (
                    "legacy-executing",
                    "op-executing",
                    "dismiss_interruptions@1.0",
                    "idempotent",
                    False,
                    "executing",
                    historical,
                    historical,
                ),
                (
                    "legacy-effect",
                    "op-effect",
                    "publish_note@1.0",
                    "non_idempotent",
                    True,
                    "effect_committed",
                    historical,
                    historical,
                ),
                (
                    "legacy-unknown-readonly",
                    "op-unknown-readonly",
                    "ensure_app_ready@1.0",
                    "readonly",
                    False,
                    "unknown",
                    historical,
                    historical,
                ),
                (
                    "legacy-unknown-idempotent",
                    "op-unknown-idempotent",
                    "dismiss_interruptions@1.0",
                    "idempotent",
                    False,
                    "unknown",
                    historical,
                    historical,
                ),
                (
                    "legacy-unknown-non-idempotent",
                    "op-unknown-non-idempotent",
                    "publish_note@1.0",
                    "non_idempotent",
                    True,
                    "unknown",
                    historical,
                    historical,
                ),
            ],
        )
    return schema_name, fixture_url


def drop_fixture_schema(schema_name: str) -> None:
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema_name)))


@pytest.mark.parametrize("wip_version_21_recorded", [False, True])
def test_v20_migration_grants_open_tasks_fresh_grace_and_builds_scan_shape(
    wip_version_21_recorded: bool,
) -> None:
    schema_name, fixture_url = create_v20_fixture(wip_version_21_recorded=wip_version_21_recorded)
    before = datetime.now(timezone.utc)
    try:
        migrate_fleet_schema(fixture_url)
        after = datetime.now(timezone.utc)

        with psycopg.connect(fixture_url) as connection:
            columns = {
                row[0]: row[1:]
                for row in connection.execute(
                    """
                    SELECT a.attname, format_type(a.atttypid, a.atttypmod),
                           NOT a.attnotnull
                    FROM pg_attribute a
                    WHERE a.attrelid = 'tasks'::regclass
                      AND a.attnum > 0
                      AND NOT a.attisdropped
                    """
                ).fetchall()
            }
            rows = {
                row[0]: row[1:]
                for row in connection.execute(
                    """
                    SELECT task_id, accepted_at, executing_at, effect_committed_at
                    FROM tasks
                    ORDER BY task_id
                    """
                ).fetchall()
            }
            constraint = connection.execute(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid = 'tasks'::regclass
                  AND conname = 'tasks_timeout_phase_check'
                """
            ).fetchone()
            status_constraints = [
                row[0]
                for row in connection.execute(
                    """
                    SELECT pg_get_constraintdef(oid)
                    FROM pg_constraint
                    WHERE conrelid = 'tasks'::regclass
                      AND contype = 'c'
                    """
                ).fetchall()
            ]
            indexes = {
                row[0]: row[1:]
                for row in connection.execute(
                    """
                    SELECT c.relname,
                           pg_get_indexdef(i.indexrelid, 1, true),
                           pg_get_expr(i.indpred, i.indrelid, true)
                    FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema()
                      AND i.indrelid = 'tasks'::regclass
                    """
                ).fetchall()
            }
            device_column = connection.execute(
                """
                SELECT data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'tasks'
                  AND column_name = 'device_id'
                """
            ).fetchone()
            device_foreign_keys = connection.execute(
                """
                SELECT a.attname, af.attname, c.confdeltype
                FROM pg_constraint c
                JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                JOIN pg_attribute af
                  ON af.attrelid = c.confrelid AND af.attnum = ANY(c.confkey)
                WHERE c.conrelid = 'tasks'::regclass
                  AND c.confrelid = 'devices'::regclass
                  AND c.contype = 'f'
                """
            ).fetchall()
            unknowns = {
                row[0]: row[1:]
                for row in connection.execute(
                    """
                SELECT task_id, status, effect_committed, reconcile_required, error_code
                FROM tasks
                WHERE task_id LIKE 'legacy-unknown-%'
                ORDER BY task_id
                """
                ).fetchall()
            }

        assert columns["accepted_at"] == ("timestamp with time zone", True)
        assert columns["executing_at"] == ("timestamp with time zone", True)
        assert columns["effect_committed_at"] == ("timestamp with time zone", True)
        assert columns["reported_at"] == ("timestamp with time zone", True)
        assert columns["terminal_at"] == ("timestamp with time zone", True)
        assert columns["timeout_phase"] == ("text", True)
        assert columns["dispatch_started_at"] == ("timestamp with time zone", True)
        assert columns["terminal_result_payload"] == ("jsonb", True)
        assert columns["engagement_processed_at"] == ("timestamp with time zone", True)
        assert columns["result_processed_at"] == ("timestamp with time zone", True)
        assert before <= rows["legacy-accepted"][0] <= after
        assert before <= rows["legacy-executing"][1] <= after
        assert before <= rows["legacy-effect"][2] <= after
        assert constraint is not None
        assert all(phase in constraint[0] for phase in ("queued", "execution", "report"))
        assert all(
            phase not in constraint[0]
            for phase in ("accepted", "executing", "effect_committed", "reported")
        )
        assert any(
            "reconcile_required" in definition and "effect_committed" in definition
            for definition in status_constraints
        )
        expected_indexes = {
            "tasks_timeout_queued_idx": ("updated_at", "status = 'queued'::text"),
            "tasks_timeout_accepted_idx": ("accepted_at", "status = 'accepted'::text"),
            "tasks_timeout_executing_idx": ("executing_at", "status = 'executing'::text"),
            "tasks_timeout_effect_committed_idx": (
                "effect_committed_at",
                "status = 'effect_committed'::text",
            ),
            "tasks_timeout_reported_idx": ("reported_at", "status = 'reported'::text"),
        }
        for name, expected_shape in expected_indexes.items():
            assert indexes[name] == expected_shape
        assert device_column == ("text", "YES")
        assert device_foreign_keys == [("device_id", "device_id", "n")]
        assert unknowns == {
            "legacy-unknown-idempotent": (
                "failed",
                False,
                False,
                "EFFECT_UNKNOWN",
            ),
            "legacy-unknown-non-idempotent": (
                "reconcile_required",
                True,
                True,
                "EFFECT_UNKNOWN",
            ),
            "legacy-unknown-readonly": (
                "failed",
                False,
                False,
                "EFFECT_UNKNOWN",
            ),
        }

        service = TaskService(fixture_url, NullMqttBus())
        assert service.scan_timeouts(now=after) == []
        task_record_schema = json.loads(
            (Path(__file__).parents[2] / "schemas" / "task-record.schema.json").read_text()
        )
        validator = Draft202012Validator(task_record_schema)
        for task_id in unknowns:
            validator.validate(jsonable_encoder(serialize_task_record(service.get(task_id))))

        with psycopg.connect(fixture_url) as connection:
            connection.execute(
                "UPDATE accounts SET device_id = NULL WHERE account_id = 'account-legacy'"
            )
            connection.execute("DELETE FROM devices WHERE device_id = 'device-legacy'")
            retained_device_ids = connection.execute(
                "SELECT DISTINCT device_id FROM tasks"
            ).fetchall()
        assert retained_device_ids == [(None,)]
    finally:
        drop_fixture_schema(schema_name)


def test_migration_21_rebuilds_same_named_malformed_shape() -> None:
    schema_name, fixture_url = create_v20_fixture(wip_version_21_recorded=False)
    try:
        migrate_fleet_schema(fixture_url)
        with psycopg.connect(fixture_url) as connection:
            connection.execute("DROP INDEX tasks_timeout_accepted_idx")
            connection.execute(
                """
                ALTER TABLE tasks
                ALTER COLUMN accepted_at TYPE TEXT USING accepted_at::text
                """
            )
            connection.execute(
                """
                ALTER TABLE tasks
                ALTER COLUMN dispatch_started_at TYPE TEXT
                    USING dispatch_started_at::text
                """
            )
            connection.execute(
                """
                CREATE INDEX tasks_timeout_accepted_idx
                ON tasks (created_at)
                WHERE status = 'failed'
                """
            )
            connection.execute(
                """
                ALTER TABLE tasks
                DROP CONSTRAINT tasks_timeout_phase_check,
                ADD CONSTRAINT tasks_timeout_phase_check CHECK (
                    timeout_phase IS NULL OR timeout_phase IN (
                        'queued', 'execution', 'report', 'accepted'
                    )
                )
                """
            )

        migrate_fleet_schema(fixture_url)

        with psycopg.connect(fixture_url) as connection:
            accepted_column = connection.execute(
                """
                SELECT format_type(a.atttypid, a.atttypmod), NOT a.attnotnull
                FROM pg_attribute a
                WHERE a.attrelid = 'tasks'::regclass
                  AND a.attname = 'accepted_at'
                """
            ).fetchone()
            dispatch_column = connection.execute(
                """
                SELECT format_type(a.atttypid, a.atttypmod), NOT a.attnotnull
                FROM pg_attribute a
                WHERE a.attrelid = 'tasks'::regclass
                  AND a.attname = 'dispatch_started_at'
                """
            ).fetchone()
            constraint = connection.execute(
                """
                SELECT pg_get_expr(conbin, conrelid, true)
                FROM pg_constraint
                WHERE conrelid = 'tasks'::regclass
                  AND conname = 'tasks_timeout_phase_check'
                """
            ).fetchone()
            accepted_index = connection.execute(
                """
                SELECT pg_get_indexdef(i.indexrelid, 1, true),
                       pg_get_expr(i.indpred, i.indrelid, true)
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = 'tasks_timeout_accepted_idx'
                """
            ).fetchone()

        assert accepted_column == ("timestamp with time zone", True)
        assert dispatch_column == ("timestamp with time zone", True)
        assert constraint is not None
        assert "'accepted'::text" not in constraint[0]
        assert accepted_index == ("accepted_at", "status = 'accepted'::text")
    finally:
        drop_fixture_schema(schema_name)

from __future__ import annotations

import psycopg


FLEET_SCHEMA_VERSION = 11


def migrate_fleet_schema(database_url: str) -> None:
    with psycopg.connect(database_url) as connection:
        connection.execute(
            "SELECT pg_advisory_xact_lock(hashtext(%s))",
            ("loanagent-fleet-schema",),
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

        if 10 not in applied:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS devices (
                    device_id TEXT PRIMARY KEY,
                    agent_version TEXT,
                    manufacturer TEXT,
                    model TEXT,
                    online BOOLEAN NOT NULL DEFAULT FALSE,
                    last_seen_at TIMESTAMPTZ,
                    wifi_connected BOOLEAN,
                    a11y_bound BOOLEAN,
                    cellular_ok BOOLEAN,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    account_id TEXT PRIMARY KEY,
                    role TEXT NOT NULL CHECK (
                        role IN ('PUBLISHER_MAIN','PUBLISHER_MATRIX','ENGAGER')
                    ),
                    device_id TEXT UNIQUE REFERENCES devices(device_id),
                    status TEXT NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','paused','blocked','needs_login')),
                    network_policy TEXT NOT NULL DEFAULT 'cellular_only',
                    daily_publish_quota INT NOT NULL DEFAULT 0,
                    inbox_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    display_name TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            _record_migration(connection, 10)

        if 11 not in applied:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS tasks (
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
                    priority INT NOT NULL DEFAULT 100 CHECK (priority >= 0 AND priority <= 100),
                    timeout_sec INT NOT NULL DEFAULT 120 CHECK (timeout_sec >= 1),
                    source TEXT NOT NULL DEFAULT 'manual' CHECK (
                        source IN ('scheduler','agent','manual')
                    ),
                    error_code TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            _record_migration(connection, 11)


def _record_migration(connection: psycopg.Connection, version: int) -> None:
    connection.execute(
        """
        INSERT INTO loanagent_schema_migrations (version)
        VALUES (%s)
        ON CONFLICT (version) DO NOTHING
        """,
        (version,),
    )

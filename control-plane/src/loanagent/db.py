from __future__ import annotations

import psycopg


FLEET_SCHEMA_VERSION = 20


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

        if 12 not in applied:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS media_objects (
                    media_id TEXT PRIMARY KEY,
                    content_type TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
                    storage_path TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS content_assets (
                    content_id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    media_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
                    geo_tags JSONB,
                    sensitivity_status TEXT NOT NULL DEFAULT 'clean',
                    sensitivity_hits JSONB NOT NULL DEFAULT '[]'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS schedule_items (
                    schedule_id TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL REFERENCES accounts(account_id),
                    content_id TEXT NOT NULL REFERENCES content_assets(content_id),
                    window_start TIMESTAMPTZ,
                    window_end TIMESTAMPTZ,
                    status TEXT NOT NULL DEFAULT 'ready' CHECK (
                        status IN (
                            'draft', 'ready', 'dispatched', 'done', 'failed', 'cancelled'
                        )
                    ),
                    task_id TEXT,
                    error_code TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            _record_migration(connection, 12)

        if 13 not in applied:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS engagement_chains (
                    chain_id TEXT PRIMARY KEY,
                    publish_task_id TEXT NOT NULL UNIQUE,
                    account_id TEXT NOT NULL,
                    engager_account_id TEXT,
                    note_ref TEXT,
                    status TEXT NOT NULL CHECK (
                        status IN (
                            'pending', 'running', 'awaiting_reply',
                            'done', 'stopped', 'failed'
                        )
                    ),
                    config JSONB NOT NULL DEFAULT
                        '{"engager_comments":1,"delay_sec":600}'::jsonb,
                    post_comment_task_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
                    reply_comment_task_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
                    stop_reason TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS alerts (
                    alert_id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    message TEXT NOT NULL,
                    ref_id TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            _record_migration(connection, 13)

        if 14 not in applied:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS inbox_threads (
                    thread_id TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL REFERENCES accounts(account_id),
                    title_summary TEXT NOT NULL,
                    preview_summary TEXT,
                    unread BOOLEAN NOT NULL DEFAULT FALSE,
                    last_sync_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (account_id, title_summary)
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS inbox_messages (
                    message_id TEXT PRIMARY KEY,
                    thread_id TEXT NOT NULL REFERENCES inbox_threads(thread_id),
                    sender_summary TEXT,
                    body_summary TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS leads (
                    lead_id TEXT PRIMARY KEY,
                    thread_id TEXT NOT NULL UNIQUE REFERENCES inbox_threads(thread_id),
                    status TEXT NOT NULL CHECK (
                        status IN ('new', 'warm', 'hot', 'closed')
                    ),
                    note TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            _record_migration(connection, 14)

        if 15 not in applied:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS published_notes (
                    note_id TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL REFERENCES accounts(account_id),
                    publish_task_id TEXT UNIQUE,
                    content_id TEXT,
                    title_summary TEXT,
                    xhs_hint TEXT,
                    last_synced_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS note_comments (
                    comment_id TEXT PRIMARY KEY,
                    note_id TEXT NOT NULL REFERENCES published_notes(note_id),
                    account_id TEXT NOT NULL REFERENCES accounts(account_id),
                    author_summary TEXT NOT NULL,
                    body_summary TEXT NOT NULL,
                    locator_hint TEXT,
                    source_task_id TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (note_id, author_summary, body_summary)
                )
                """
            )
            _record_migration(connection, 15)

        if 16 not in applied:
            connection.execute(
                """
                ALTER TABLE accounts
                ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'xhs'
                """
            )
            connection.execute(
                """
                ALTER TABLE content_assets
                ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'xhs'
                """
            )
            connection.execute(
                """
                ALTER TABLE engagement_chains
                ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'xhs'
                """
            )
            connection.execute(
                """
                ALTER TABLE engagement_chains
                ADD COLUMN IF NOT EXISTS mode TEXT NOT NULL DEFAULT 'auto'
                """
            )
            connection.execute(
                """
                ALTER TABLE engagement_chains
                ADD COLUMN IF NOT EXISTS engager_account_ids JSONB NOT NULL DEFAULT '[]'::jsonb
                """
            )
            connection.execute(
                """
                ALTER TABLE published_notes
                ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'xhs'
                """
            )
            connection.execute(
                """
                ALTER TABLE inbox_threads
                ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'xhs'
                """
            )
            _record_migration(connection, 16)

        if 17 not in applied:
            connection.execute(
                """
                ALTER TABLE devices
                ADD COLUMN IF NOT EXISTS display_name TEXT
                """
            )
            _record_migration(connection, 17)

        if 18 not in applied:
            connection.execute(
                """
                ALTER TABLE published_notes
                ADD COLUMN IF NOT EXISTS like_count INTEGER,
                ADD COLUMN IF NOT EXISTS collect_count INTEGER,
                ADD COLUMN IF NOT EXISTS read_count INTEGER
                """
            )
            _record_migration(connection, 18)

        if 19 not in applied:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS note_comment_nodes (
                    node_id TEXT PRIMARY KEY,
                    note_id TEXT NOT NULL REFERENCES published_notes(note_id),
                    account_id TEXT NOT NULL REFERENCES accounts(account_id),
                    parent_node_id TEXT,
                    root_node_id TEXT NOT NULL,
                    depth INTEGER NOT NULL DEFAULT 0,
                    author_summary TEXT NOT NULL,
                    body_summary TEXT NOT NULL,
                    posted_at_text TEXT,
                    reply_to_author TEXT,
                    sort_index INTEGER NOT NULL DEFAULT 0,
                    locator_hint TEXT,
                    source_task_id TEXT,
                    synced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (note_id, sort_index)
                )
                """
            )
            connection.execute(
                """
                ALTER TABLE inbox_messages
                ADD COLUMN IF NOT EXISTS sort_index INTEGER NOT NULL DEFAULT 0,
                ADD COLUMN IF NOT EXISTS posted_at_text TEXT
                """
            )
            _record_migration(connection, 19)

        if 20 not in applied:
            connection.execute(
                """
                ALTER TABLE devices
                ADD COLUMN IF NOT EXISTS public_ip TEXT,
                ADD COLUMN IF NOT EXISTS geo_label TEXT
                """
            )
            _record_migration(connection, 20)


def _record_migration(connection: psycopg.Connection, version: int) -> None:
    connection.execute(
        """
        INSERT INTO loanagent_schema_migrations (version)
        VALUES (%s)
        ON CONFLICT (version) DO NOTHING
        """,
        (version,),
    )

from __future__ import annotations

import psycopg
from psycopg import sql


FLEET_SCHEMA_VERSION = 22


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

        task_columns = {
            row[0]: (row[1], row[2])
            for row in connection.execute(
                """
                SELECT a.attname, format_type(a.atttypid, a.atttypmod),
                       NOT a.attnotnull
                FROM pg_attribute a
                JOIN pg_class t ON t.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = current_schema()
                  AND t.relname = 'tasks'
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                  AND a.attname IN (
                      'accepted_at', 'executing_at', 'effect_committed_at',
                      'reported_at', 'terminal_at', 'timeout_phase', 'device_id',
                      'dispatch_started_at', 'terminal_result_payload',
                      'engagement_processed_at', 'result_processed_at'
                  )
                """
            ).fetchall()
        }
        expected_task_columns = {
            "accepted_at": ("timestamp with time zone", True),
            "executing_at": ("timestamp with time zone", True),
            "effect_committed_at": ("timestamp with time zone", True),
            "reported_at": ("timestamp with time zone", True),
            "terminal_at": ("timestamp with time zone", True),
            "timeout_phase": ("text", True),
            "device_id": ("text", True),
            "dispatch_started_at": ("timestamp with time zone", True),
            "terminal_result_payload": ("jsonb", True),
            "engagement_processed_at": ("timestamp with time zone", True),
            "result_processed_at": ("timestamp with time zone", True),
        }
        engagement_marker_preexisting = "engagement_processed_at" in task_columns
        timing_constraint = connection.execute(
            """
            SELECT pg_get_expr(conbin, conrelid, true), convalidated
            FROM pg_constraint
            WHERE conrelid = 'tasks'::regclass
              AND conname = 'tasks_timeout_phase_check'
              AND contype = 'c'
            """
        ).fetchone()
        timing_constraint_valid = timing_constraint == (
            "timeout_phase IS NULL OR (timeout_phase = ANY "
            "(ARRAY['queued'::text, 'execution'::text, 'report'::text]))",
            True,
        )
        timing_indexes = {
            row[0]: row[1:]
            for row in connection.execute(
                """
                SELECT c.relname,
                       pg_get_indexdef(i.indexrelid, 1, true),
                       pg_get_expr(i.indpred, i.indrelid, true),
                       i.indisunique, i.indnkeyatts, i.indnatts, am.amname
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_am am ON am.oid = c.relam
                WHERE n.nspname = current_schema()
                  AND i.indrelid = 'tasks'::regclass
                  AND c.relname IN (
                      'tasks_timeout_queued_idx',
                      'tasks_timeout_accepted_idx',
                      'tasks_timeout_executing_idx',
                      'tasks_timeout_effect_committed_idx',
                      'tasks_timeout_reported_idx'
                  )
                """
            ).fetchall()
        }
        expected_timing_indexes = {
            "tasks_timeout_queued_idx": (
                "updated_at",
                "status = 'queued'::text",
                False,
                1,
                1,
                "btree",
            ),
            "tasks_timeout_accepted_idx": (
                "accepted_at",
                "status = 'accepted'::text",
                False,
                1,
                1,
                "btree",
            ),
            "tasks_timeout_executing_idx": (
                "executing_at",
                "status = 'executing'::text",
                False,
                1,
                1,
                "btree",
            ),
            "tasks_timeout_effect_committed_idx": (
                "effect_committed_at",
                "status = 'effect_committed'::text",
                False,
                1,
                1,
                "btree",
            ),
            "tasks_timeout_reported_idx": (
                "reported_at",
                "status = 'reported'::text",
                False,
                1,
                1,
                "btree",
            ),
        }
        device_foreign_keys = connection.execute(
            """
            SELECT c.conname,
                   ARRAY(
                       SELECT a.attname::text
                       FROM unnest(c.conkey) WITH ORDINALITY AS key(attnum, ord)
                       JOIN pg_attribute a
                         ON a.attrelid = c.conrelid AND a.attnum = key.attnum
                       ORDER BY key.ord
                   ),
                   ref.relname,
                   ref_ns.nspname = current_schema(),
                   ARRAY(
                       SELECT a.attname::text
                       FROM unnest(c.confkey) WITH ORDINALITY AS key(attnum, ord)
                       JOIN pg_attribute a
                         ON a.attrelid = c.confrelid AND a.attnum = key.attnum
                       ORDER BY key.ord
                   ),
                   c.confdeltype, c.confupdtype, c.confmatchtype,
                   c.condeferrable, c.condeferred, c.convalidated
            FROM pg_constraint c
            JOIN pg_class ref ON ref.oid = c.confrelid
            JOIN pg_namespace ref_ns ON ref_ns.oid = ref.relnamespace
            WHERE c.conrelid = 'tasks'::regclass
              AND c.contype = 'f'
              AND (
                  c.conname = 'tasks_device_id_fkey'
                  OR (
                      SELECT array_agg(a.attname::text ORDER BY key.ord)
                      FROM unnest(c.conkey) WITH ORDINALITY AS key(attnum, ord)
                      JOIN pg_attribute a
                        ON a.attrelid = c.conrelid AND a.attnum = key.attnum
                  ) = ARRAY['device_id']
              )
            ORDER BY c.conname
            """
        ).fetchall()
        device_foreign_key_valid = device_foreign_keys == [
            (
                "tasks_device_id_fkey",
                ["device_id"],
                "devices",
                True,
                ["device_id"],
                "n",
                "a",
                "s",
                False,
                False,
                True,
            )
        ]
        unknown_rows_normalized = connection.execute(
            """
            SELECT NOT EXISTS (
                SELECT 1
                FROM tasks
                WHERE status = 'unknown'
            )
            """
        ).fetchone()[0]
        # An earlier WIP revision could already have recorded version 21 for a
        # different compatibility migration. Repair that collision by checking
        # the migration's actual shape as well as its ledger entry.
        timing_shape_complete = (
            task_columns == expected_task_columns
            and timing_constraint_valid
            and timing_indexes == expected_timing_indexes
            and device_foreign_key_valid
            and unknown_rows_normalized
        )
        if 21 not in applied or not timing_shape_complete:
            connection.execute(
                """
                ALTER TABLE tasks
                ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ,
                ADD COLUMN IF NOT EXISTS executing_at TIMESTAMPTZ,
                ADD COLUMN IF NOT EXISTS effect_committed_at TIMESTAMPTZ,
                ADD COLUMN IF NOT EXISTS reported_at TIMESTAMPTZ,
                ADD COLUMN IF NOT EXISTS terminal_at TIMESTAMPTZ,
                ADD COLUMN IF NOT EXISTS timeout_phase TEXT,
                ADD COLUMN IF NOT EXISTS dispatch_started_at TIMESTAMPTZ,
                ADD COLUMN IF NOT EXISTS terminal_result_payload JSONB,
                ADD COLUMN IF NOT EXISTS engagement_processed_at TIMESTAMPTZ,
                ADD COLUMN IF NOT EXISTS result_processed_at TIMESTAMPTZ
                """
            )
            device_constraints = connection.execute(
                """
                SELECT DISTINCT c.conname
                FROM pg_constraint c
                LEFT JOIN LATERAL unnest(c.conkey) AS key(attnum) ON TRUE
                LEFT JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attnum = key.attnum
                WHERE c.conrelid = 'tasks'::regclass
                  AND c.contype = 'f'
                  AND (
                      c.conname = 'tasks_device_id_fkey'
                      OR a.attname = 'device_id'
                  )
                """
            ).fetchall()
            for (constraint_name,) in device_constraints:
                connection.execute(
                    sql.SQL("ALTER TABLE tasks DROP CONSTRAINT {}").format(
                        sql.Identifier(constraint_name)
                    )
                )
            column_types = {
                "accepted_at": "TIMESTAMPTZ",
                "executing_at": "TIMESTAMPTZ",
                "effect_committed_at": "TIMESTAMPTZ",
                "reported_at": "TIMESTAMPTZ",
                "terminal_at": "TIMESTAMPTZ",
                "timeout_phase": "TEXT",
                "device_id": "TEXT",
                "dispatch_started_at": "TIMESTAMPTZ",
                "terminal_result_payload": "JSONB",
                "engagement_processed_at": "TIMESTAMPTZ",
                "result_processed_at": "TIMESTAMPTZ",
            }
            refreshed_columns = {
                row[0]: row[1]
                for row in connection.execute(
                    """
                    SELECT a.attname, format_type(a.atttypid, a.atttypmod)
                    FROM pg_attribute a
                    JOIN pg_class t ON t.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = t.relnamespace
                    WHERE n.nspname = current_schema()
                      AND t.relname = 'tasks'
                      AND a.attname = ANY(%s)
                      AND a.attnum > 0
                      AND NOT a.attisdropped
                    """,
                    (list(column_types),),
                ).fetchall()
            }
            expected_type_names = {
                "TIMESTAMPTZ": "timestamp with time zone",
                "TEXT": "text",
                "JSONB": "jsonb",
            }
            for column_name, sql_type in column_types.items():
                if refreshed_columns[column_name] != expected_type_names[sql_type]:
                    connection.execute(
                        sql.SQL("ALTER TABLE tasks ALTER COLUMN {} TYPE {} USING {}::{}").format(
                            sql.Identifier(column_name),
                            sql.SQL(sql_type),
                            sql.Identifier(column_name),
                            sql.SQL(sql_type),
                        )
                    )
                connection.execute(
                    sql.SQL("ALTER TABLE tasks ALTER COLUMN {} DROP NOT NULL").format(
                        sql.Identifier(column_name)
                    )
                )
            connection.execute(
                """
                ALTER TABLE tasks
                ADD CONSTRAINT tasks_device_id_fkey
                FOREIGN KEY (device_id) REFERENCES devices(device_id)
                ON DELETE SET NULL
                """
            )
            connection.execute(
                """
                UPDATE tasks
                SET status = CASE
                        WHEN effect_class = 'non_idempotent'
                            THEN 'reconcile_required'
                        ELSE 'failed'
                    END,
                    reconcile_required = (effect_class = 'non_idempotent'),
                    error_code = 'EFFECT_UNKNOWN'
                WHERE status = 'unknown'
                """
            )
            if not engagement_marker_preexisting:
                connection.execute(
                    """
                    UPDATE tasks
                    SET engagement_processed_at = CURRENT_TIMESTAMP
                    WHERE status IN (
                        'succeeded', 'failed', 'cancelled', 'unknown',
                        'reconcile_required'
                    )
                    """
                )
            connection.execute(
                """
                UPDATE tasks
                SET updated_at = CURRENT_TIMESTAMP,
                    accepted_at = CASE
                        WHEN status IN (
                            'accepted', 'executing', 'effect_committed', 'reported'
                        ) THEN CURRENT_TIMESTAMP
                        ELSE accepted_at
                    END,
                    executing_at = CASE
                        WHEN status IN ('executing', 'effect_committed', 'reported')
                            THEN CURRENT_TIMESTAMP
                        ELSE executing_at
                    END,
                    effect_committed_at = CASE
                        WHEN status IN ('effect_committed', 'reported')
                            THEN CURRENT_TIMESTAMP
                        ELSE effect_committed_at
                    END,
                    reported_at = CASE
                        WHEN status = 'reported' THEN CURRENT_TIMESTAMP
                        ELSE reported_at
                    END
                WHERE status IN (
                    'queued', 'accepted', 'executing', 'effect_committed', 'reported'
                )
                """
            )
            connection.execute(
                """
                UPDATE tasks
                SET reported_at = COALESCE(reported_at, updated_at)
                WHERE status IN (
                    'succeeded', 'failed', 'unknown', 'reconcile_required'
                )
                """
            )
            connection.execute(
                """
                UPDATE tasks
                SET terminal_at = COALESCE(terminal_at, updated_at)
                WHERE status IN (
                    'succeeded', 'failed', 'cancelled', 'unknown', 'reconcile_required'
                )
                """
            )
            connection.execute(
                """
                ALTER TABLE tasks
                DROP CONSTRAINT IF EXISTS tasks_timeout_phase_check
                """
            )
            connection.execute(
                """
                UPDATE tasks
                SET timeout_phase = CASE
                    WHEN timeout_phase IN ('queued', 'accepted') THEN 'queued'
                    WHEN timeout_phase IN ('execution', 'executing') THEN 'execution'
                    WHEN timeout_phase IN (
                        'report', 'effect_committed', 'reported'
                    ) THEN 'report'
                    ELSE NULL
                END
                WHERE timeout_phase IS NOT NULL
                """
            )
            connection.execute(
                """
                ALTER TABLE tasks
                ADD CONSTRAINT tasks_timeout_phase_check CHECK (
                    timeout_phase IS NULL OR timeout_phase IN (
                        'queued', 'execution', 'report'
                    )
                )
                """
            )
            connection.execute(
                """
                DROP INDEX IF EXISTS
                    tasks_open_timeout_scan_idx,
                    tasks_timeout_queued_idx,
                    tasks_timeout_accepted_idx,
                    tasks_timeout_executing_idx,
                    tasks_timeout_effect_committed_idx,
                    tasks_timeout_reported_idx
                """
            )
            connection.execute(
                """
                CREATE INDEX tasks_timeout_queued_idx
                ON tasks (updated_at)
                WHERE status = 'queued'
                """
            )
            connection.execute(
                """
                CREATE INDEX tasks_timeout_accepted_idx
                ON tasks (accepted_at)
                WHERE status = 'accepted'
                """
            )
            connection.execute(
                """
                CREATE INDEX tasks_timeout_executing_idx
                ON tasks (executing_at)
                WHERE status = 'executing'
                """
            )
            connection.execute(
                """
                CREATE INDEX tasks_timeout_effect_committed_idx
                ON tasks (effect_committed_at)
                WHERE status = 'effect_committed'
                """
            )
            connection.execute(
                """
                CREATE INDEX tasks_timeout_reported_idx
                ON tasks (reported_at)
                WHERE status = 'reported'
                """
            )
            _record_migration(connection, 21)

        if 22 not in applied:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS device_agent_upgrades (
                    device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
                    status TEXT NOT NULL CHECK (
                        status IN ('pending', 'in_progress', 'succeeded', 'failed')
                    ),
                    ring TEXT CHECK (
                        ring IS NULL OR ring IN ('canary', 'staged', 'stable')
                    ),
                    manifest_url TEXT,
                    detail TEXT,
                    request_id UUID NOT NULL DEFAULT gen_random_uuid(),
                    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            _record_migration(connection, 22)

        # Compat: an earlier main revision used migrations 20/21 for tasks.device_id.
        # Keep WIP geo columns available even when those versions are already recorded.
        connection.execute(
            """
            ALTER TABLE devices
            ADD COLUMN IF NOT EXISTS public_ip TEXT,
            ADD COLUMN IF NOT EXISTS geo_label TEXT
            """
        )


def _record_migration(connection: psycopg.Connection, version: int) -> None:
    connection.execute(
        """
        INSERT INTO loanagent_schema_migrations (version)
        VALUES (%s)
        ON CONFLICT (version) DO NOTHING
        """,
        (version,),
    )

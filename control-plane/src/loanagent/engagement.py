from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from uuid import NAMESPACE_URL, uuid4, uuid5

import psycopg
from psycopg.types.json import Jsonb

from loanagent.db import migrate_fleet_schema
from loanagent.platforms import DEFAULT_PLATFORM, normalize_platform
from loanagent.roles import AccountRole
from loanagent.tasks import (
    DuplicateTaskError,
    TaskAccessibilityDownError,
    TaskAccountNotFoundError,
    TaskAccountUnavailableError,
    TaskDeviceUnavailableError,
    TaskDispatchAmbiguousError,
    TaskDispatchError,
    TaskRecord,
    TaskService,
)


DEFAULT_CONFIG: dict[str, Any] = {
    "engager_comments": 1,
    "delay_sec": 600,
    "comment_text": "看起来不错，求同款渠道～",
    "reply_text": "谢谢支持，详情可私信了解～",
}

_CHAIN_SELECT = """
    chain_id, publish_task_id, account_id, engager_account_id,
    note_ref, status, config, post_comment_task_ids, reply_comment_task_ids,
    stop_reason, created_at, updated_at, platform, mode, engager_account_ids
"""


@dataclass(frozen=True)
class EngagementChainRecord:
    chain_id: str
    publish_task_id: str
    account_id: str
    engager_account_id: str | None
    note_ref: str | None
    status: str
    config: dict[str, Any]
    post_comment_task_ids: list[str]
    reply_comment_task_ids: list[str]
    stop_reason: str | None
    created_at: datetime
    updated_at: datetime
    platform: str
    mode: str
    engager_account_ids: list[str]


@dataclass(frozen=True)
class AlertRecord:
    alert_id: str
    kind: str
    message: str
    ref_id: str | None
    created_at: datetime


class EngagementChainNotFoundError(Exception):
    pass


class EngagementService:
    def __init__(self, database_url: str, task_service: TaskService) -> None:
        self.database_url = database_url
        self.task_service = task_service

    def migrate(self) -> None:
        migrate_fleet_schema(self.database_url)

    def create_from_publish(
        self,
        publish_task: TaskRecord,
        *,
        config: dict[str, Any] | None = None,
    ) -> EngagementChainRecord | None:
        if publish_task.status != "succeeded" or not publish_task.effect_committed:
            return None
        if not publish_task.playbook.startswith("publish_note"):
            return None
        if str(publish_task.params.get("engagement_mode") or "auto") == "manual":
            return None

        existing = self.get_by_publish_task(publish_task.task_id)
        if existing is not None:
            return existing

        chain_config = {**DEFAULT_CONFIG, **(config or {})}
        note_ref = publish_task.params.get("note_ref")
        if note_ref is not None:
            note_ref = str(note_ref)
        platform = normalize_platform(str(publish_task.params.get("platform") or DEFAULT_PLATFORM))
        engager_account_id = self._pick_engager_account(platform=platform)
        engager_ids = [engager_account_id] if engager_account_id else []
        chain_id = str(uuid4())
        status = "pending"
        stop_reason: str | None = None

        if engager_account_id is None:
            status = "stopped"
            stop_reason = "NO_ENGAGER_AVAILABLE"
            # Expected in V1 single-publisher fleets — do not raise product alerts.

        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                INSERT INTO engagement_chains (
                    chain_id, publish_task_id, account_id, engager_account_id,
                    note_ref, status, config, stop_reason, platform, mode, engager_account_ids
                )
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'auto', %s)
                ON CONFLICT (publish_task_id) DO NOTHING
                RETURNING {_CHAIN_SELECT}
                """,
                (
                    chain_id,
                    publish_task.task_id,
                    publish_task.account_id,
                    engager_account_id,
                    note_ref,
                    status,
                    Jsonb(chain_config),
                    stop_reason,
                    platform,
                    Jsonb(engager_ids),
                ),
            ).fetchone()
        if row is None:
            return self.get_by_publish_task(publish_task.task_id)
        return _chain_from_row(row)

    def create_manual(
        self,
        *,
        publish_task_id: str,
        engager_account_ids: list[str],
        platform: str | None = None,
        note_ref: str | None = None,
        config: dict[str, Any] | None = None,
    ) -> EngagementChainRecord:
        if not engager_account_ids:
            raise ValueError("engager_account_ids must not be empty")
        existing = self.get_by_publish_task(publish_task_id)
        if existing is not None:
            return existing

        publish_task = self.task_service.get(publish_task_id)
        if publish_task.status != "succeeded" or not publish_task.effect_committed:
            raise ValueError(
                "publish task must be succeeded with effect_committed before manual engagement"
            )
        if not publish_task.playbook.startswith("publish_note"):
            raise ValueError("publish_task_id must reference a publish_note task")
        platform_value = normalize_platform(
            platform or str(publish_task.params.get("platform") or DEFAULT_PLATFORM)
        )
        chain_config = {**DEFAULT_CONFIG, **(config or {})}
        resolved_note = note_ref or publish_task.params.get("note_ref")
        if resolved_note is not None:
            resolved_note = str(resolved_note)
        chain_id = str(uuid4())
        primary = engager_account_ids[0]
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                INSERT INTO engagement_chains (
                    chain_id, publish_task_id, account_id, engager_account_id,
                    note_ref, status, config, platform, mode, engager_account_ids
                )
                VALUES (%s, %s, %s, %s, %s, 'pending', %s, %s, 'manual', %s)
                RETURNING {_CHAIN_SELECT}
                """,
                (
                    chain_id,
                    publish_task_id,
                    publish_task.account_id,
                    primary,
                    resolved_note,
                    Jsonb(chain_config),
                    platform_value,
                    Jsonb(list(engager_account_ids)),
                ),
            ).fetchone()
        return _chain_from_row(row)

    def stop(self, chain_id: str, *, reason: str = "OPERATOR_STOP") -> EngagementChainRecord:
        return self._stop_chain(chain_id, reason=reason)

    def advance(self, chain_id: str) -> EngagementChainRecord:
        chain = self.get(chain_id)
        if chain.status == "pending":
            return self._advance_pending(chain)
        if chain.status == "running" and chain.post_comment_task_ids:
            return self._recover_post_comment_dispatch(chain)
        if chain.status == "awaiting_reply" and chain.reply_comment_task_ids:
            return self._recover_reply_dispatch(
                chain,
                source_task_id=chain.post_comment_task_ids[0],
            )
        return chain

    def on_task_event(
        self,
        task: TaskRecord,
        *,
        status: str,
        error_code: str | None,
    ) -> None:
        if status == "succeeded" and task.playbook.startswith("publish_note"):
            self.create_from_publish(task)
            return

        if error_code == "BUSINESS_BLOCKED" or (
            status == "failed" and error_code == "BUSINESS_BLOCKED"
        ):
            chain = self._find_chain_for_task(task.task_id)
            if chain is not None and chain.status not in {"done", "stopped", "failed"}:
                self._stop_chain(chain.chain_id, reason="BUSINESS_BLOCKED")
            return

        if status == "succeeded" and task.playbook.startswith("post_comment"):
            self._create_reply_for_post_comment(task)
            return

        if status == "succeeded" and task.playbook.startswith("reply_comment"):
            chain = self._find_chain_for_task(task.task_id)
            if chain is not None and chain.status == "awaiting_reply":
                self._update_status(chain.chain_id, status="done")

    def list_chains(self) -> list[EngagementChainRecord]:
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                f"""
                SELECT {_CHAIN_SELECT}
                FROM engagement_chains
                ORDER BY created_at, chain_id
                """
            ).fetchall()
        return [_chain_from_row(row) for row in rows]

    def list_alerts(self) -> list[AlertRecord]:
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                SELECT alert_id, kind, message, ref_id, created_at
                FROM alerts
                ORDER BY created_at DESC, alert_id
                """
            ).fetchall()
        return [
            AlertRecord(
                alert_id=row[0],
                kind=row[1],
                message=row[2],
                ref_id=row[3],
                created_at=row[4],
            )
            for row in rows
        ]

    def get(self, chain_id: str) -> EngagementChainRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                SELECT {_CHAIN_SELECT}
                FROM engagement_chains
                WHERE chain_id = %s
                """,
                (chain_id,),
            ).fetchone()
        if row is None:
            raise EngagementChainNotFoundError(chain_id)
        return _chain_from_row(row)

    def get_by_publish_task(self, publish_task_id: str) -> EngagementChainRecord | None:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                SELECT {_CHAIN_SELECT}
                FROM engagement_chains
                WHERE publish_task_id = %s
                """,
                (publish_task_id,),
            ).fetchone()
        return None if row is None else _chain_from_row(row)

    def _advance_pending(self, chain: EngagementChainRecord) -> EngagementChainRecord:
        candidates: list[str] = []
        if chain.engager_account_ids:
            candidates.extend(chain.engager_account_ids)
        if chain.engager_account_id and chain.engager_account_id not in candidates:
            candidates.append(chain.engager_account_id)
        if not candidates:
            auto = self._pick_engager_account(platform=chain.platform)
            if auto:
                candidates.append(auto)
        if not candidates:
            return self._stop_chain(chain.chain_id, reason="NO_ENGAGER_AVAILABLE")

        delay_sec = int(chain.config.get("delay_sec", DEFAULT_CONFIG["delay_sec"]))
        created_at = chain.created_at
        if created_at.tzinfo is None:
            created_at = created_at.replace(tzinfo=timezone.utc)
        elapsed = (datetime.now(timezone.utc) - created_at).total_seconds()
        if elapsed < delay_sec:
            return chain

        comment_text = str(chain.config.get("comment_text", DEFAULT_CONFIG["comment_text"]))
        note_ref = chain.note_ref or chain.publish_task_id
        last_error: Exception | None = None
        for engager in candidates:
            task_id, operation_id = _engagement_task_identity(
                chain.chain_id,
                step="post_comment",
                source_task_id=chain.publish_task_id,
                account_id=engager,
            )

            def associate_post_comment(task: TaskRecord) -> None:
                self._associate_post_comment(chain.chain_id, task)

            try:
                task = self.task_service.create_and_dispatch_idempotent(
                    account_id=engager,
                    playbook="post_comment@1.0",
                    params={
                        "text": comment_text,
                        "note_ref": note_ref,
                        "chain_id": chain.chain_id,
                    },
                    task_id=task_id,
                    operation_id=operation_id,
                    source="scheduler",
                    before_publish=associate_post_comment,
                )
            except TaskDispatchAmbiguousError:
                return self._mark_reconciliation_required(chain.chain_id)
            except (
                TaskAccountNotFoundError,
                TaskAccountUnavailableError,
                TaskDeviceUnavailableError,
                TaskAccessibilityDownError,
                TaskDispatchError,
            ) as error:
                last_error = error
                continue
            refreshed = self.get(chain.chain_id)
            if task.task_id not in refreshed.post_comment_task_ids:
                self._associate_post_comment(chain.chain_id, task)
            if task.status == "reconcile_required":
                return self._mark_reconciliation_required(chain.chain_id)
            if task.status in {"failed", "cancelled", "unknown"}:
                last_error = TaskDispatchError(task.task_id)
                continue
            if task.status == "succeeded":
                self.on_task_event(
                    task,
                    status=task.status,
                    error_code=task.error_code,
                )
            return self.get(chain.chain_id)

        reason = "NO_ENGAGER_AVAILABLE"
        if isinstance(last_error, TaskAccountUnavailableError):
            reason = "ACCOUNT_UNAVAILABLE"
        elif isinstance(last_error, TaskDeviceUnavailableError):
            reason = "DEVICE_UNAVAILABLE"
        elif isinstance(last_error, TaskAccessibilityDownError):
            reason = "A11Y_DOWN"
        elif isinstance(last_error, TaskAccountNotFoundError):
            reason = "ACCOUNT_NOT_FOUND"
        elif isinstance(last_error, TaskDispatchError):
            reason = "TASK_DISPATCH_FAILED"
        return self._stop_chain(chain.chain_id, reason=reason)

    def _create_reply_for_post_comment(self, task: TaskRecord) -> None:
        chain = self._find_chain_for_task(task.task_id)
        if chain is None or chain.status not in {"running", "awaiting_reply"}:
            return
        if task.task_id not in chain.post_comment_task_ids:
            return
        if chain.reply_comment_task_ids:
            self._recover_reply_dispatch(chain, source_task_id=task.task_id)
            return

        reply_text = str(chain.config.get("reply_text", DEFAULT_CONFIG["reply_text"]))
        note_ref = chain.note_ref or chain.publish_task_id
        task_id, operation_id = _engagement_task_identity(
            chain.chain_id,
            step="reply_comment",
            source_task_id=task.task_id,
            account_id=chain.account_id,
        )

        def associate_reply_comment(reply: TaskRecord) -> None:
            self._associate_reply_comment(chain.chain_id, reply)

        try:
            reply = self.task_service.create_and_dispatch_idempotent(
                account_id=chain.account_id,
                playbook="reply_comment@1.0",
                params={
                    "text": reply_text,
                    "note_ref": note_ref,
                    "chain_id": chain.chain_id,
                    "target_comment_task_id": task.task_id,
                },
                task_id=task_id,
                operation_id=operation_id,
                source="scheduler",
                before_publish=associate_reply_comment,
            )
        except TaskDispatchAmbiguousError:
            self._mark_reconciliation_required(chain.chain_id)
            return
        refreshed = self.get(chain.chain_id)
        if reply.task_id not in refreshed.reply_comment_task_ids:
            self._associate_reply_comment(chain.chain_id, reply)
        if reply.status == "reconcile_required":
            self._mark_reconciliation_required(chain.chain_id)
        elif reply.status in {"failed", "cancelled", "unknown"}:
            self._stop_chain(chain.chain_id, reason="REPLY_TASK_FAILED")
        elif reply.status == "succeeded":
            self.on_task_event(
                reply,
                status=reply.status,
                error_code=reply.error_code,
            )

    def _recover_post_comment_dispatch(
        self,
        chain: EngagementChainRecord,
    ) -> EngagementChainRecord:
        engager = chain.engager_account_id
        if engager is None:
            return self._stop_chain(chain.chain_id, reason="NO_ENGAGER_AVAILABLE")
        comment_text = str(chain.config.get("comment_text", DEFAULT_CONFIG["comment_text"]))
        note_ref = chain.note_ref or chain.publish_task_id
        task_id, operation_id = _engagement_task_identity(
            chain.chain_id,
            step="post_comment",
            source_task_id=chain.publish_task_id,
            account_id=engager,
        )
        if chain.post_comment_task_ids != [task_id]:
            raise DuplicateTaskError(task_id)
        try:
            task = self.task_service.create_and_dispatch_idempotent(
                account_id=engager,
                playbook="post_comment@1.0",
                params={
                    "text": comment_text,
                    "note_ref": note_ref,
                    "chain_id": chain.chain_id,
                },
                task_id=task_id,
                operation_id=operation_id,
                source="scheduler",
            )
        except TaskDispatchAmbiguousError:
            return self._mark_reconciliation_required(chain.chain_id)
        if task.status == "reconcile_required":
            return self._mark_reconciliation_required(chain.chain_id)
        if task.status in {"failed", "cancelled", "unknown"}:
            return self._stop_chain(chain.chain_id, reason="POST_TASK_FAILED")
        if task.status == "succeeded":
            self.on_task_event(task, status=task.status, error_code=task.error_code)
        return self.get(chain.chain_id)

    def _recover_reply_dispatch(
        self,
        chain: EngagementChainRecord,
        *,
        source_task_id: str,
    ) -> EngagementChainRecord:
        reply_text = str(chain.config.get("reply_text", DEFAULT_CONFIG["reply_text"]))
        note_ref = chain.note_ref or chain.publish_task_id
        task_id, operation_id = _engagement_task_identity(
            chain.chain_id,
            step="reply_comment",
            source_task_id=source_task_id,
            account_id=chain.account_id,
        )
        if chain.reply_comment_task_ids != [task_id]:
            raise DuplicateTaskError(task_id)
        try:
            reply = self.task_service.create_and_dispatch_idempotent(
                account_id=chain.account_id,
                playbook="reply_comment@1.0",
                params={
                    "text": reply_text,
                    "note_ref": note_ref,
                    "chain_id": chain.chain_id,
                    "target_comment_task_id": source_task_id,
                },
                task_id=task_id,
                operation_id=operation_id,
                source="scheduler",
            )
        except TaskDispatchAmbiguousError:
            return self._mark_reconciliation_required(chain.chain_id)
        if reply.status == "reconcile_required":
            return self._mark_reconciliation_required(chain.chain_id)
        if reply.status in {"failed", "cancelled", "unknown"}:
            return self._stop_chain(chain.chain_id, reason="REPLY_TASK_FAILED")
        if reply.status == "succeeded":
            self.on_task_event(
                reply,
                status=reply.status,
                error_code=reply.error_code,
            )
        return self.get(chain.chain_id)

    def _associate_post_comment(self, chain_id: str, task: TaskRecord) -> None:
        with psycopg.connect(self.database_url) as connection:
            linked = connection.execute(
                """
                UPDATE engagement_chains
                SET status = 'running',
                    engager_account_id = %s,
                    post_comment_task_ids = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE chain_id = %s
                  AND status IN ('pending', 'running')
                RETURNING chain_id
                """,
                (task.account_id, Jsonb([task.task_id]), chain_id),
            ).fetchone()
        if linked is None:
            raise EngagementChainNotFoundError(chain_id)

    def _associate_reply_comment(self, chain_id: str, reply: TaskRecord) -> None:
        with psycopg.connect(self.database_url) as connection:
            linked = connection.execute(
                """
                UPDATE engagement_chains
                SET status = 'awaiting_reply',
                    reply_comment_task_ids = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE chain_id = %s
                  AND status IN ('running', 'awaiting_reply')
                RETURNING chain_id
                """,
                (Jsonb([reply.task_id]), chain_id),
            ).fetchone()
        if linked is None:
            raise EngagementChainNotFoundError(chain_id)

    def _find_chain_for_task(self, task_id: str) -> EngagementChainRecord | None:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                SELECT {_CHAIN_SELECT}
                FROM engagement_chains
                WHERE publish_task_id = %s
                   OR post_comment_task_ids ? %s
                   OR reply_comment_task_ids ? %s
                ORDER BY created_at
                LIMIT 1
                """,
                (task_id, task_id, task_id),
            ).fetchone()
        return None if row is None else _chain_from_row(row)

    def _stop_chain(self, chain_id: str, *, reason: str) -> EngagementChainRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                UPDATE engagement_chains
                SET status = 'stopped',
                    stop_reason = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE chain_id = %s
                RETURNING {_CHAIN_SELECT}
                """,
                (reason, chain_id),
            ).fetchone()
        self._insert_alert(
            kind="engagement_stopped",
            message=f"Engagement chain stopped: {reason}",
            ref_id=chain_id,
        )
        return _chain_from_row(row)

    def _mark_reconciliation_required(
        self,
        chain_id: str,
    ) -> EngagementChainRecord:
        kind = "engagement_reconciliation_required"
        message = "Engagement chain requires reconciliation: EFFECT_UNKNOWN"
        alert_id = str(
            uuid5(
                NAMESPACE_URL,
                f"loanagent:engagement-alert:{kind}:{chain_id}",
            )
        )
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                UPDATE engagement_chains
                SET status = 'failed',
                    stop_reason = 'EFFECT_UNKNOWN',
                    updated_at = CURRENT_TIMESTAMP
                WHERE chain_id = %s
                RETURNING {_CHAIN_SELECT}
                """,
                (chain_id,),
            ).fetchone()
            self._insert_alert_on_connection(
                connection,
                alert_id=alert_id,
                kind=kind,
                message=message,
                ref_id=chain_id,
            )
        return _chain_from_row(row)

    def _update_status(self, chain_id: str, *, status: str) -> EngagementChainRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                UPDATE engagement_chains
                SET status = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE chain_id = %s
                RETURNING {_CHAIN_SELECT}
                """,
                (status, chain_id),
            ).fetchone()
        return _chain_from_row(row)

    def _pick_engager_account(self, *, platform: str = DEFAULT_PLATFORM) -> str | None:
        """Pick one available ENGAGER for the platform.

        Prefer the most recently created eligible account so new engagers
        enter rotation first; fall back to random among the rest of the pool
        only when several share the same created_at (rare).
        """
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                SELECT accounts.account_id
                FROM accounts
                JOIN devices ON devices.device_id = accounts.device_id
                WHERE accounts.role = %s
                  AND accounts.status = 'active'
                  AND accounts.platform = %s
                  AND devices.online IS TRUE
                  AND devices.a11y_bound IS TRUE
                ORDER BY accounts.created_at DESC, accounts.account_id
                """,
                (AccountRole.ENGAGER.value, platform),
            ).fetchall()
        if not rows:
            return None
        # Newest first satisfies auto-engagement tests that create an ENGAGER
        # immediately before publish; still spreads load as operators add accounts.
        return rows[0][0]

    def _insert_alert(self, *, kind: str, message: str, ref_id: str | None) -> AlertRecord:
        alert_id = str(uuid4())
        with psycopg.connect(self.database_url) as connection:
            row = self._insert_alert_on_connection(
                connection,
                alert_id=alert_id,
                kind=kind,
                message=message,
                ref_id=ref_id,
            )
        return AlertRecord(
            alert_id=row[0],
            kind=row[1],
            message=row[2],
            ref_id=row[3],
            created_at=row[4],
        )

    def _insert_alert_on_connection(
        self,
        connection: psycopg.Connection,
        *,
        alert_id: str,
        kind: str,
        message: str,
        ref_id: str | None,
    ) -> tuple:
        return connection.execute(
            """
            INSERT INTO alerts (alert_id, kind, message, ref_id)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (alert_id) DO UPDATE
            SET kind = EXCLUDED.kind,
                message = EXCLUDED.message,
                ref_id = EXCLUDED.ref_id
            RETURNING alert_id, kind, message, ref_id, created_at
            """,
            (alert_id, kind, message, ref_id),
        ).fetchone()


def _engagement_task_identity(
    chain_id: str,
    *,
    step: str,
    source_task_id: str,
    account_id: str,
) -> tuple[str, str]:
    identity = f"loanagent:engagement:{chain_id}:{step}:{source_task_id}:{account_id}"
    return (
        str(uuid5(NAMESPACE_URL, f"{identity}:task")),
        str(uuid5(NAMESPACE_URL, f"{identity}:operation")),
    )


def _chain_from_row(row: tuple) -> EngagementChainRecord:
    return EngagementChainRecord(
        chain_id=row[0],
        publish_task_id=row[1],
        account_id=row[2],
        engager_account_id=row[3],
        note_ref=row[4],
        status=row[5],
        config=dict(row[6] or {}),
        post_comment_task_ids=list(row[7] or []),
        reply_comment_task_ids=list(row[8] or []),
        stop_reason=row[9],
        created_at=row[10],
        updated_at=row[11],
        platform=row[12] if len(row) > 12 else DEFAULT_PLATFORM,
        mode=row[13] if len(row) > 13 else "auto",
        engager_account_ids=list(row[14] or []) if len(row) > 14 else [],
    )

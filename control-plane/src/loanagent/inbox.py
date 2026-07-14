from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Mapping
from uuid import uuid4

import psycopg

from loanagent.db import migrate_fleet_schema
from loanagent.tasks import TaskRecord, TaskService


_CONTACT_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"微信号"),
    re.compile(r"微信\s*[：:]"),
    re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"),
    re.compile(r"\bwxid_[a-zA-Z0-9_]+\b", re.IGNORECASE),
    re.compile(r"\bwx\s*(?:id|is)\b", re.IGNORECASE),
    re.compile(r"\bwx\s*[：:]", re.IGNORECASE),
)


class ContactForbiddenError(ValueError):
    pass


class InboxThreadNotFoundError(Exception):
    pass


def reject_plaintext_contact(text: str) -> None:
    """Reject wechat-like / phone plaintext in reply_dm params."""
    if not text:
        return
    for pattern in _CONTACT_PATTERNS:
        if pattern.search(text):
            raise ContactForbiddenError("plaintext contact info is forbidden")


@dataclass(frozen=True)
class InboxThreadRecord:
    thread_id: str
    account_id: str
    title_summary: str
    preview_summary: str | None
    unread: bool
    last_sync_at: datetime | None
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class InboxMessageRecord:
    message_id: str
    thread_id: str
    sender_summary: str | None
    body_summary: str | None
    created_at: datetime
    sort_index: int = 0
    posted_at_text: str | None = None


@dataclass(frozen=True)
class LeadRecord:
    lead_id: str
    thread_id: str
    status: str
    note: str | None
    created_at: datetime
    updated_at: datetime


class InboxService:
    def __init__(self, database_url: str, task_service: TaskService) -> None:
        self.database_url = database_url
        self.task_service = task_service

    def migrate(self) -> None:
        migrate_fleet_schema(self.database_url)

    def upsert_threads(
        self,
        account_id: str,
        threads: list[Mapping[str, Any]],
    ) -> list[InboxThreadRecord]:
        results: list[InboxThreadRecord] = []
        with psycopg.connect(self.database_url) as connection:
            # Preserve lead status across replace by matching title_summary.
            saved_leads = connection.execute(
                """
                SELECT t.title_summary, l.status, l.note
                FROM leads l
                JOIN inbox_threads t ON t.thread_id = l.thread_id
                WHERE t.account_id = %s
                """,
                (account_id,),
            ).fetchall()
            leads_by_title = {
                str(row[0]): (str(row[1]), row[2]) for row in saved_leads
            }

            # Successful inbox sync replaces the account thread list; stale false positives go away.
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
                """
                DELETE FROM leads
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
            for item in threads:
                title_summary = str(item["title_summary"])
                preview_summary = item.get("preview_summary")
                if preview_summary is not None:
                    preview_summary = str(preview_summary)
                unread = bool(item.get("unread", False))
                row = connection.execute(
                    """
                    INSERT INTO inbox_threads (
                        thread_id, account_id, title_summary, preview_summary,
                        unread, last_sync_at
                    )
                    VALUES (%s, %s, %s, %s, %s, CURRENT_TIMESTAMP)
                    ON CONFLICT (account_id, title_summary) DO UPDATE SET
                        preview_summary = EXCLUDED.preview_summary,
                        unread = EXCLUDED.unread,
                        last_sync_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    RETURNING thread_id, account_id, title_summary, preview_summary,
                        unread, last_sync_at, created_at, updated_at
                    """,
                    (
                        str(uuid4()),
                        account_id,
                        title_summary,
                        preview_summary,
                        unread,
                    ),
                ).fetchone()
                thread = _thread_from_row(row)
                messages = list(item.get("messages") or [])
                if messages:
                    inserted = self._add_messages(connection, thread.thread_id, messages)
                    preview = _preview_from_messages(inserted) or preview_summary
                    if preview != thread.preview_summary:
                        connection.execute(
                            """
                            UPDATE inbox_threads
                            SET preview_summary = %s, updated_at = CURRENT_TIMESTAMP
                            WHERE thread_id = %s
                            """,
                            (preview, thread.thread_id),
                        )
                        thread = InboxThreadRecord(
                            thread_id=thread.thread_id,
                            account_id=thread.account_id,
                            title_summary=thread.title_summary,
                            preview_summary=preview,
                            unread=thread.unread,
                            last_sync_at=thread.last_sync_at,
                            created_at=thread.created_at,
                            updated_at=thread.updated_at,
                        )
                saved = leads_by_title.get(title_summary)
                if saved is not None:
                    status, note = saved
                    connection.execute(
                        """
                        INSERT INTO leads (lead_id, thread_id, status, note)
                        VALUES (%s, %s, %s, %s)
                        ON CONFLICT (thread_id) DO UPDATE SET
                            status = EXCLUDED.status,
                            note = EXCLUDED.note,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                        (str(uuid4()), thread.thread_id, status, note),
                    )
                results.append(thread)
        return results

    def list_threads(self, account_id: str | None = None) -> list[InboxThreadRecord]:
        with psycopg.connect(self.database_url) as connection:
            if account_id is None:
                rows = connection.execute(
                    """
                    SELECT thread_id, account_id, title_summary, preview_summary,
                        unread, last_sync_at, created_at, updated_at
                    FROM inbox_threads
                    ORDER BY updated_at DESC, thread_id
                    """
                ).fetchall()
            else:
                rows = connection.execute(
                    """
                    SELECT thread_id, account_id, title_summary, preview_summary,
                        unread, last_sync_at, created_at, updated_at
                    FROM inbox_threads
                    WHERE account_id = %s
                    ORDER BY updated_at DESC, thread_id
                    """,
                    (account_id,),
                ).fetchall()
        return [_thread_from_row(row) for row in rows]

    def add_messages(
        self,
        thread_id: str,
        messages: list[Mapping[str, Any]],
    ) -> list[InboxMessageRecord]:
        with psycopg.connect(self.database_url) as connection:
            if not self._thread_exists(connection, thread_id):
                raise InboxThreadNotFoundError(thread_id)
            return self._add_messages(connection, thread_id, messages)

    def mark_lead(
        self,
        thread_id: str,
        status: str,
        note: str | None = None,
    ) -> LeadRecord:
        with psycopg.connect(self.database_url) as connection:
            if not self._thread_exists(connection, thread_id):
                raise InboxThreadNotFoundError(thread_id)
            row = connection.execute(
                """
                INSERT INTO leads (lead_id, thread_id, status, note)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (thread_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    note = EXCLUDED.note,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING lead_id, thread_id, status, note, created_at, updated_at
                """,
                (str(uuid4()), thread_id, status, note),
            ).fetchone()
        return _lead_from_row(row)

    def list_leads(self) -> list[LeadRecord]:
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                SELECT lead_id, thread_id, status, note, created_at, updated_at
                FROM leads
                ORDER BY updated_at DESC, lead_id
                """
            ).fetchall()
        return [_lead_from_row(row) for row in rows]

    def get_thread(self, thread_id: str) -> InboxThreadRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT thread_id, account_id, title_summary, preview_summary,
                    unread, last_sync_at, created_at, updated_at
                FROM inbox_threads
                WHERE thread_id = %s
                """,
                (thread_id,),
            ).fetchone()
        if row is None:
            raise InboxThreadNotFoundError(thread_id)
        return _thread_from_row(row)

    def list_messages(self, thread_id: str) -> list[InboxMessageRecord]:
        self.get_thread(thread_id)
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                SELECT message_id, thread_id, sender_summary, body_summary, created_at,
                    sort_index, posted_at_text
                FROM inbox_messages
                WHERE thread_id = %s
                ORDER BY sort_index, created_at, message_id
                """,
                (thread_id,),
            ).fetchall()
        return [
            InboxMessageRecord(
                message_id=row[0],
                thread_id=row[1],
                sender_summary=row[2],
                body_summary=row[3],
                created_at=row[4],
                sort_index=row[5] if len(row) > 5 else 0,
                posted_at_text=row[6] if len(row) > 6 else None,
            )
            for row in rows
        ]

    def reply_thread(self, thread_id: str, *, account_id: str, text: str) -> TaskRecord:
        thread = self.get_thread(thread_id)
        if thread.account_id != account_id:
            raise InboxThreadNotFoundError(thread_id)
        reject_plaintext_contact(text)
        params: dict[str, Any] = {
            "text": text,
            "thread_id": thread_id,
            "open_title_hint": thread.title_summary,
        }
        return self.task_service.create_and_dispatch(
            account_id=account_id,
            playbook="reply_dm@1.0",
            params=params,
            source="manual",
        )

    def ingest(
        self,
        account_id: str,
        threads: list[Mapping[str, Any]],
    ) -> list[InboxThreadRecord]:
        return self.upsert_threads(account_id, threads)

    def sync(self, account_id: str) -> TaskRecord:
        return self.task_service.create_and_dispatch(
            account_id=account_id,
            playbook="inbox_sync@1.0",
            # List first is reliable; opening every thread is flaky on mid-swipe surfaces.
            params={"max_items": 20, "max_threads": 5, "open_threads": True},
            source="manual",
        )

    def open_thread(self, thread_id: str) -> TaskRecord:
        thread = self.get_thread(thread_id)
        return self.task_service.create_and_dispatch(
            account_id=thread.account_id,
            playbook="inbox_open_thread@1.0",
            params={
                "thread_id": thread.thread_id,
                "open_title_hint": thread.title_summary,
                "max_items": 50,
            },
            source="manual",
        )

    def reply(
        self,
        account_id: str,
        text: str,
        thread_id: str | None = None,
    ) -> TaskRecord:
        reject_plaintext_contact(text)
        params: dict[str, Any] = {"text": text}
        if thread_id is not None:
            params["thread_id"] = thread_id
        return self.task_service.create_and_dispatch(
            account_id=account_id,
            playbook="reply_dm@1.0",
            params=params,
            source="manual",
        )

    def _add_messages(
        self,
        connection: psycopg.Connection,
        thread_id: str,
        messages: list[Mapping[str, Any]],
    ) -> list[InboxMessageRecord]:
        results: list[InboxMessageRecord] = []
        for index, item in enumerate(messages):
            sender = item.get("sender_summary")
            body = item.get("body_summary")
            posted_at = item.get("posted_at_text")
            sort_index = item.get("sort_index", index)
            try:
                sort_index = int(sort_index)
            except (TypeError, ValueError):
                sort_index = index
            row = connection.execute(
                """
                INSERT INTO inbox_messages (
                    message_id, thread_id, sender_summary, body_summary,
                    sort_index, posted_at_text
                )
                VALUES (%s, %s, %s, %s, %s, %s)
                RETURNING message_id, thread_id, sender_summary, body_summary, created_at,
                    sort_index, posted_at_text
                """,
                (
                    str(uuid4()),
                    thread_id,
                    None if sender is None else str(sender)[:256],
                    None if body is None else str(body)[:512],
                    sort_index,
                    None if posted_at is None else str(posted_at)[:64],
                ),
            ).fetchone()
            results.append(
                InboxMessageRecord(
                    message_id=row[0],
                    thread_id=row[1],
                    sender_summary=row[2],
                    body_summary=row[3],
                    created_at=row[4],
                    sort_index=row[5] if len(row) > 5 else sort_index,
                    posted_at_text=row[6] if len(row) > 6 else None,
                )
            )
        return results

    def _thread_exists(self, connection: psycopg.Connection, thread_id: str) -> bool:
        row = connection.execute(
            "SELECT 1 FROM inbox_threads WHERE thread_id = %s",
            (thread_id,),
        ).fetchone()
        return row is not None


def _preview_from_messages(messages: list[InboxMessageRecord], limit: int = 3) -> str | None:
    parts: list[str] = []
    for message in messages[-limit:]:
        body = (message.body_summary or "").strip()
        if not body:
            continue
        sender = (message.sender_summary or "").strip()
        parts.append(f"{sender}: {body}" if sender else body)
    if not parts:
        return None
    preview = " ｜ ".join(parts)
    return preview if len(preview) <= 240 else preview[:240]


def _thread_from_row(row: tuple) -> InboxThreadRecord:
    return InboxThreadRecord(
        thread_id=row[0],
        account_id=row[1],
        title_summary=row[2],
        preview_summary=row[3],
        unread=row[4],
        last_sync_at=row[5],
        created_at=row[6],
        updated_at=row[7],
    )


def _lead_from_row(row: tuple) -> LeadRecord:
    return LeadRecord(
        lead_id=row[0],
        thread_id=row[1],
        status=row[2],
        note=row[3],
        created_at=row[4],
        updated_at=row[5],
    )

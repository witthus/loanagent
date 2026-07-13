from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Mapping
from uuid import uuid4

import psycopg

from loanagent.db import migrate_fleet_schema
from loanagent.inbox import ContactForbiddenError, reject_plaintext_contact
from loanagent.sensitivity import assert_clean
from loanagent.tasks import TaskRecord, TaskService


SUMMARY_MAX_LEN = 256
COMMENTS_MAX_ITEMS = 50


class NoteNotFoundError(Exception):
    pass


class CommentNotFoundError(Exception):
    pass


class NotesComplianceError(ValueError):
    pass


@dataclass(frozen=True)
class PublishedNoteRecord:
    note_id: str
    account_id: str
    publish_task_id: str | None
    content_id: str | None
    title_summary: str | None
    xhs_hint: str | None
    last_synced_at: datetime | None
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class NoteCommentRecord:
    comment_id: str
    note_id: str
    account_id: str
    author_summary: str
    body_summary: str
    locator_hint: str | None
    source_task_id: str | None
    created_at: datetime


def _truncate(value: str | None, limit: int = SUMMARY_MAX_LEN) -> str | None:
    if value is None:
        return None
    text = str(value)
    return text if len(text) <= limit else text[:limit]


class NotesService:
    def __init__(self, database_url: str, task_service: TaskService) -> None:
        self.database_url = database_url
        self.task_service = task_service

    def migrate(self) -> None:
        migrate_fleet_schema(self.database_url)

    def create_from_publish_event(
        self,
        task: TaskRecord,
        payload: Mapping[str, Any] | None = None,
    ) -> PublishedNoteRecord:
        payload = payload or {}
        note_id = str(payload.get("note_id") or uuid4())
        title_summary = _truncate(payload.get("title_summary") or task.params.get("title"))
        xhs_hint = _truncate(payload.get("xhs_hint") or payload.get("note_ref"))
        content_id = payload.get("content_id")
        if content_id is not None:
            content_id = str(content_id)

        with psycopg.connect(self.database_url) as connection:
            existing = connection.execute(
                """
                SELECT note_id, account_id, publish_task_id, content_id, title_summary,
                    xhs_hint, last_synced_at, created_at, updated_at
                FROM published_notes
                WHERE publish_task_id = %s
                """,
                (task.task_id,),
            ).fetchone()
            if existing is not None:
                return _note_from_row(existing)

            row = connection.execute(
                """
                INSERT INTO published_notes (
                    note_id, account_id, publish_task_id, content_id,
                    title_summary, xhs_hint
                )
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (note_id) DO UPDATE SET
                    publish_task_id = COALESCE(
                        published_notes.publish_task_id, EXCLUDED.publish_task_id
                    ),
                    title_summary = COALESCE(
                        EXCLUDED.title_summary, published_notes.title_summary
                    ),
                    xhs_hint = COALESCE(EXCLUDED.xhs_hint, published_notes.xhs_hint),
                    content_id = COALESCE(EXCLUDED.content_id, published_notes.content_id),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING note_id, account_id, publish_task_id, content_id, title_summary,
                    xhs_hint, last_synced_at, created_at, updated_at
                """,
                (
                    note_id,
                    task.account_id,
                    task.task_id,
                    content_id,
                    title_summary,
                    xhs_hint,
                ),
            ).fetchone()
        return _note_from_row(row)

    def upsert_comments_from_payload(
        self,
        *,
        account_id: str,
        source_task_id: str | None,
        payload: Mapping[str, Any],
    ) -> list[NoteCommentRecord]:
        note = self._resolve_note_for_payload(account_id, payload)
        items = list(payload.get("items") or [])[:COMMENTS_MAX_ITEMS]
        results: list[NoteCommentRecord] = []
        with psycopg.connect(self.database_url) as connection:
            for item in items:
                author = _truncate(item.get("author_summary") or "") or ""
                body = _truncate(item.get("body_summary") or "") or ""
                if not author and not body:
                    continue
                locator = _truncate(item.get("locator_hint"))
                row = connection.execute(
                    """
                    INSERT INTO note_comments (
                        comment_id, note_id, account_id, author_summary,
                        body_summary, locator_hint, source_task_id
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (note_id, author_summary, body_summary) DO UPDATE SET
                        locator_hint = COALESCE(
                            EXCLUDED.locator_hint, note_comments.locator_hint
                        ),
                        source_task_id = COALESCE(
                            EXCLUDED.source_task_id, note_comments.source_task_id
                        )
                    RETURNING comment_id, note_id, account_id, author_summary,
                        body_summary, locator_hint, source_task_id, created_at
                    """,
                    (
                        str(uuid4()),
                        note.note_id,
                        account_id,
                        author,
                        body,
                        locator,
                        source_task_id,
                    ),
                ).fetchone()
                results.append(_comment_from_row(row))
            connection.execute(
                """
                UPDATE published_notes
                SET last_synced_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE note_id = %s
                """,
                (note.note_id,),
            )
        return results

    def list_notes(self, account_id: str | None = None) -> list[PublishedNoteRecord]:
        with psycopg.connect(self.database_url) as connection:
            if account_id is None:
                rows = connection.execute(
                    """
                    SELECT note_id, account_id, publish_task_id, content_id, title_summary,
                        xhs_hint, last_synced_at, created_at, updated_at
                    FROM published_notes
                    ORDER BY updated_at DESC, note_id
                    """
                ).fetchall()
            else:
                rows = connection.execute(
                    """
                    SELECT note_id, account_id, publish_task_id, content_id, title_summary,
                        xhs_hint, last_synced_at, created_at, updated_at
                    FROM published_notes
                    WHERE account_id = %s
                    ORDER BY updated_at DESC, note_id
                    """,
                    (account_id,),
                ).fetchall()
        return [_note_from_row(row) for row in rows]

    def get_note(self, note_id: str) -> PublishedNoteRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT note_id, account_id, publish_task_id, content_id, title_summary,
                    xhs_hint, last_synced_at, created_at, updated_at
                FROM published_notes
                WHERE note_id = %s
                """,
                (note_id,),
            ).fetchone()
        if row is None:
            raise NoteNotFoundError(note_id)
        return _note_from_row(row)

    def list_comments(self, note_id: str) -> list[NoteCommentRecord]:
        self.get_note(note_id)
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                SELECT comment_id, note_id, account_id, author_summary, body_summary,
                    locator_hint, source_task_id, created_at
                FROM note_comments
                WHERE note_id = %s
                ORDER BY created_at, comment_id
                """,
                (note_id,),
            ).fetchall()
        return [_comment_from_row(row) for row in rows]

    def get_comment(self, comment_id: str) -> NoteCommentRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT comment_id, note_id, account_id, author_summary, body_summary,
                    locator_hint, source_task_id, created_at
                FROM note_comments
                WHERE comment_id = %s
                """,
                (comment_id,),
            ).fetchone()
        if row is None:
            raise CommentNotFoundError(comment_id)
        return _comment_from_row(row)

    def sync_comments(self, note_id: str) -> TaskRecord:
        note = self.get_note(note_id)
        params: dict[str, Any] = {"note_id": note.note_id}
        if note.title_summary:
            params["title_summary"] = note.title_summary
        if note.xhs_hint:
            params["xhs_hint"] = note.xhs_hint
        return self.task_service.create_and_dispatch(
            account_id=note.account_id,
            playbook="read_comments@1.0",
            params=params,
            source="manual",
        )

    def reply_comment(self, comment_id: str, text: str) -> TaskRecord:
        try:
            assert_clean(text)
        except ValueError as error:
            raise NotesComplianceError(str(error)) from error
        try:
            reject_plaintext_contact(text)
        except ContactForbiddenError as error:
            raise NotesComplianceError(str(error)) from error

        comment = self.get_comment(comment_id)
        note = self.get_note(comment.note_id)
        params: dict[str, Any] = {
            "text": text,
            "note_id": note.note_id,
            "comment_id": comment.comment_id,
            "input_selector": "text=有话要说，快来评论",
            "composer_tap_x": 393,
            "composer_tap_y": 494,
        }
        if note.title_summary:
            params["title_summary"] = note.title_summary
        if comment.locator_hint:
            params["locator_hint"] = comment.locator_hint
        if note.xhs_hint:
            params["xhs_hint"] = note.xhs_hint
        return self.task_service.create_and_dispatch(
            account_id=comment.account_id,
            playbook="reply_comment@1.0",
            params=params,
            source="manual",
        )

    def _resolve_note_for_payload(
        self,
        account_id: str,
        payload: Mapping[str, Any],
    ) -> PublishedNoteRecord:
        note_id = payload.get("note_id")
        note_ref = payload.get("note_ref")
        if note_id is not None:
            try:
                return self.get_note(str(note_id))
            except NoteNotFoundError:
                return self._insert_note(
                    note_id=str(note_id),
                    account_id=account_id,
                    xhs_hint=_truncate(note_ref),
                    title_summary=_truncate(payload.get("title_summary")),
                )

        if note_ref is not None:
            ref = str(note_ref)
            with psycopg.connect(self.database_url) as connection:
                row = connection.execute(
                    """
                    SELECT note_id, account_id, publish_task_id, content_id, title_summary,
                        xhs_hint, last_synced_at, created_at, updated_at
                    FROM published_notes
                    WHERE account_id = %s AND (note_id = %s OR xhs_hint = %s)
                    ORDER BY updated_at DESC
                    LIMIT 1
                    """,
                    (account_id, ref, ref),
                ).fetchone()
            if row is not None:
                return _note_from_row(row)
            return self._insert_note(
                note_id=str(uuid4()),
                account_id=account_id,
                xhs_hint=_truncate(ref),
                title_summary=_truncate(payload.get("title_summary")),
            )

        return self._insert_note(
            note_id=str(uuid4()),
            account_id=account_id,
            xhs_hint=None,
            title_summary=_truncate(payload.get("title_summary")),
        )

    def _insert_note(
        self,
        *,
        note_id: str,
        account_id: str,
        xhs_hint: str | None,
        title_summary: str | None,
    ) -> PublishedNoteRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                INSERT INTO published_notes (
                    note_id, account_id, title_summary, xhs_hint
                )
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (note_id) DO UPDATE SET
                    title_summary = COALESCE(
                        EXCLUDED.title_summary, published_notes.title_summary
                    ),
                    xhs_hint = COALESCE(EXCLUDED.xhs_hint, published_notes.xhs_hint),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING note_id, account_id, publish_task_id, content_id, title_summary,
                    xhs_hint, last_synced_at, created_at, updated_at
                """,
                (note_id, account_id, title_summary, xhs_hint),
            ).fetchone()
        return _note_from_row(row)


def _note_from_row(row: tuple) -> PublishedNoteRecord:
    return PublishedNoteRecord(
        note_id=row[0],
        account_id=row[1],
        publish_task_id=row[2],
        content_id=row[3],
        title_summary=row[4],
        xhs_hint=row[5],
        last_synced_at=row[6],
        created_at=row[7],
        updated_at=row[8],
    )


def _comment_from_row(row: tuple) -> NoteCommentRecord:
    return NoteCommentRecord(
        comment_id=row[0],
        note_id=row[1],
        account_id=row[2],
        author_summary=row[3],
        body_summary=row[4],
        locator_hint=row[5],
        source_task_id=row[6],
        created_at=row[7],
    )

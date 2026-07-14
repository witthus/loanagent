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
    like_count: int | None = None
    collect_count: int | None = None
    read_count: int | None = None


@dataclass(frozen=True)
class NoteCommentRecord:
    """Legacy flat comment row (compat) or API-facing comment node."""

    comment_id: str
    note_id: str
    account_id: str
    author_summary: str
    body_summary: str
    locator_hint: str | None
    source_task_id: str | None
    created_at: datetime
    parent_node_id: str | None = None
    root_node_id: str | None = None
    depth: int = 0
    posted_at_text: str | None = None
    reply_to_author: str | None = None
    sort_index: int = 0


@dataclass(frozen=True)
class NoteCommentNodeRecord:
    node_id: str
    note_id: str
    account_id: str
    parent_node_id: str | None
    root_node_id: str
    depth: int
    author_summary: str
    body_summary: str
    posted_at_text: str | None
    reply_to_author: str | None
    sort_index: int
    locator_hint: str | None
    source_task_id: str | None
    synced_at: datetime
    created_at: datetime

    def as_comment_record(self) -> NoteCommentRecord:
        return NoteCommentRecord(
            comment_id=self.node_id,
            note_id=self.note_id,
            account_id=self.account_id,
            author_summary=self.author_summary,
            body_summary=self.body_summary,
            locator_hint=self.locator_hint,
            source_task_id=self.source_task_id,
            created_at=self.created_at,
            parent_node_id=self.parent_node_id,
            root_node_id=self.root_node_id,
            depth=self.depth,
            posted_at_text=self.posted_at_text,
            reply_to_author=self.reply_to_author,
            sort_index=self.sort_index,
        )


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
        """Replace comment tree for a note (supports nested replies in payload)."""
        return [
            node.as_comment_record()
            for node in self.replace_comment_tree_from_payload(
                account_id=account_id,
                source_task_id=source_task_id,
                payload=payload,
            )
        ]

    def replace_comment_tree_from_payload(
        self,
        *,
        account_id: str,
        source_task_id: str | None,
        payload: Mapping[str, Any],
    ) -> list[NoteCommentNodeRecord]:
        note = self._resolve_note_for_payload(account_id, payload)
        items = list(payload.get("items") or [])[:COMMENTS_MAX_ITEMS]
        results: list[NoteCommentNodeRecord] = []
        with psycopg.connect(self.database_url) as connection:
            connection.execute(
                "DELETE FROM note_comment_nodes WHERE note_id = %s",
                (note.note_id,),
            )
            # Keep legacy table in sync for older readers.
            connection.execute(
                "DELETE FROM note_comments WHERE note_id = %s",
                (note.note_id,),
            )
            sort_index = 0
            for item in items:
                root, sort_index = self._insert_comment_node(
                    connection,
                    note_id=note.note_id,
                    account_id=account_id,
                    source_task_id=source_task_id,
                    item=item,
                    parent_node_id=None,
                    root_node_id=None,
                    depth=0,
                    sort_index=sort_index,
                )
                if root is None:
                    continue
                results.append(root)
                parent_author = root.author_summary
                for reply in list(item.get("replies") or []):
                    child, sort_index = self._insert_comment_node(
                        connection,
                        note_id=note.note_id,
                        account_id=account_id,
                        source_task_id=source_task_id,
                        item=reply,
                        parent_node_id=root.node_id,
                        root_node_id=root.node_id,
                        depth=1,
                        sort_index=sort_index,
                        default_reply_to=parent_author,
                    )
                    if child is not None:
                        results.append(child)
                # Flat payload without replies still lands as depth-0 only.
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

    def _insert_comment_node(
        self,
        connection: psycopg.Connection,
        *,
        note_id: str,
        account_id: str,
        source_task_id: str | None,
        item: Mapping[str, Any],
        parent_node_id: str | None,
        root_node_id: str | None,
        depth: int,
        sort_index: int,
        default_reply_to: str | None = None,
    ) -> tuple[NoteCommentNodeRecord | None, int]:
        author = _truncate(item.get("author_summary") or "") or ""
        body = _truncate(item.get("body_summary") or "") or ""
        if not author and not body:
            return None, sort_index
        node_id = str(uuid4())
        root_id = root_node_id or node_id
        posted_at = _truncate(item.get("posted_at_text"), 64)
        reply_to = _truncate(
            item.get("reply_to_author") or default_reply_to,
            64,
        )
        locator = _truncate(item.get("locator_hint"))
        row = connection.execute(
            """
            INSERT INTO note_comment_nodes (
                node_id, note_id, account_id, parent_node_id, root_node_id, depth,
                author_summary, body_summary, posted_at_text, reply_to_author,
                sort_index, locator_hint, source_task_id, synced_at
            )
            VALUES (
                %s, %s, %s, %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s, %s, CURRENT_TIMESTAMP
            )
            RETURNING node_id, note_id, account_id, parent_node_id, root_node_id, depth,
                author_summary, body_summary, posted_at_text, reply_to_author,
                sort_index, locator_hint, source_task_id, synced_at, created_at
            """,
            (
                node_id,
                note_id,
                account_id,
                parent_node_id,
                root_id,
                depth,
                author,
                body,
                posted_at,
                reply_to if depth > 0 else None,
                sort_index,
                locator,
                source_task_id,
            ),
        ).fetchone()
        connection.execute(
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
            """,
            (node_id, note_id, account_id, author, body, locator, source_task_id),
        )
        return _node_from_row(row), sort_index + 1

    def list_notes(self, account_id: str | None = None) -> list[PublishedNoteRecord]:
        with psycopg.connect(self.database_url) as connection:
            if account_id is None:
                rows = connection.execute(
                    """
                    SELECT note_id, account_id, publish_task_id, content_id, title_summary,
                        xhs_hint, last_synced_at, created_at, updated_at,
                        like_count, collect_count, read_count
                    FROM published_notes
                    ORDER BY updated_at DESC, note_id
                    """
                ).fetchall()
            else:
                rows = connection.execute(
                    """
                    SELECT note_id, account_id, publish_task_id, content_id, title_summary,
                        xhs_hint, last_synced_at, created_at, updated_at,
                        like_count, collect_count, read_count
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
                    xhs_hint, last_synced_at, created_at, updated_at,
                    like_count, collect_count, read_count
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
                SELECT node_id, note_id, account_id, parent_node_id, root_node_id, depth,
                    author_summary, body_summary, posted_at_text, reply_to_author,
                    sort_index, locator_hint, source_task_id, synced_at, created_at
                FROM note_comment_nodes
                WHERE note_id = %s
                ORDER BY sort_index, created_at, node_id
                """,
                (note_id,),
            ).fetchall()
            if rows:
                return [_node_from_row(row).as_comment_record() for row in rows]
            legacy = connection.execute(
                """
                SELECT comment_id, note_id, account_id, author_summary, body_summary,
                    locator_hint, source_task_id, created_at
                FROM note_comments
                WHERE note_id = %s
                ORDER BY created_at, comment_id
                """,
                (note_id,),
            ).fetchall()
        return [_comment_from_row(row) for row in legacy]

    def get_comment(self, comment_id: str) -> NoteCommentRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT node_id, note_id, account_id, parent_node_id, root_node_id, depth,
                    author_summary, body_summary, posted_at_text, reply_to_author,
                    sort_index, locator_hint, source_task_id, synced_at, created_at
                FROM note_comment_nodes
                WHERE node_id = %s
                """,
                (comment_id,),
            ).fetchone()
            if row is not None:
                return _node_from_row(row).as_comment_record()
            legacy = connection.execute(
                """
                SELECT comment_id, note_id, account_id, author_summary, body_summary,
                    locator_hint, source_task_id, created_at
                FROM note_comments
                WHERE comment_id = %s
                """,
                (comment_id,),
            ).fetchone()
        if legacy is None:
            raise CommentNotFoundError(comment_id)
        return _comment_from_row(legacy)

    def sync_notes(self, account_id: str) -> TaskRecord:
        return self.task_service.create_and_dispatch(
            account_id=account_id,
            playbook="sync_notes@1.0",
            params={"max_items": 40},
            source="manual",
        )

    def replace_notes_from_payload(
        self,
        *,
        account_id: str,
        payload: Mapping[str, Any],
    ) -> list[PublishedNoteRecord]:
        """Replace account note cache with device-synced titles (reconcile deletes)."""
        items = list(payload.get("items") or [])
        results: list[PublishedNoteRecord] = []
        with psycopg.connect(self.database_url) as connection:
            keep_ids: list[str] = []
            for item in items:
                title = _truncate(item.get("title_summary") or "")
                if not title:
                    continue
                like_count = _optional_int(item.get("like_count"))
                collect_count = _optional_int(item.get("collect_count"))
                read_count = _optional_int(item.get("read_count"))
                locator = _truncate(item.get("locator_hint"))
                existing = connection.execute(
                    """
                    SELECT note_id FROM published_notes
                    WHERE account_id = %s AND title_summary = %s
                    ORDER BY updated_at DESC
                    LIMIT 1
                    """,
                    (account_id, title),
                ).fetchone()
                if existing is not None:
                    note_id = existing[0]
                    row = connection.execute(
                        """
                        UPDATE published_notes
                        SET like_count = COALESCE(%s, like_count),
                            collect_count = COALESCE(%s, collect_count),
                            read_count = COALESCE(%s, read_count),
                            xhs_hint = COALESCE(%s, xhs_hint),
                            last_synced_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE note_id = %s
                        RETURNING note_id, account_id, publish_task_id, content_id, title_summary,
                            xhs_hint, last_synced_at, created_at, updated_at,
                            like_count, collect_count, read_count
                        """,
                        (like_count, collect_count, read_count, locator, note_id),
                    ).fetchone()
                else:
                    note_id = str(uuid4())
                    row = connection.execute(
                        """
                        INSERT INTO published_notes (
                            note_id, account_id, title_summary, xhs_hint,
                            like_count, collect_count, read_count, last_synced_at
                        )
                        VALUES (%s, %s, %s, %s, %s, %s, %s, CURRENT_TIMESTAMP)
                        RETURNING note_id, account_id, publish_task_id, content_id, title_summary,
                            xhs_hint, last_synced_at, created_at, updated_at,
                            like_count, collect_count, read_count
                        """,
                        (
                            note_id,
                            account_id,
                            title,
                            locator,
                            like_count,
                            collect_count,
                            read_count,
                        ),
                    ).fetchone()
                keep_ids.append(row[0])
                results.append(_note_from_row(row))
            if keep_ids:
                connection.execute(
                    """
                    DELETE FROM note_comments
                    WHERE note_id IN (
                        SELECT note_id FROM published_notes
                        WHERE account_id = %s AND note_id <> ALL(%s)
                    )
                    """,
                    (account_id, keep_ids),
                )
                connection.execute(
                    """
                    DELETE FROM published_notes
                    WHERE account_id = %s AND note_id <> ALL(%s)
                    """,
                    (account_id, keep_ids),
                )
            else:
                connection.execute(
                    """
                    DELETE FROM note_comments
                    WHERE note_id IN (
                        SELECT note_id FROM published_notes WHERE account_id = %s
                    )
                    """,
                    (account_id,),
                )
                connection.execute(
                    "DELETE FROM published_notes WHERE account_id = %s",
                    (account_id,),
                )
        return results

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

    def post_comment(self, note_id: str, text: str) -> TaskRecord:
        try:
            assert_clean(text)
        except ValueError as error:
            raise NotesComplianceError(str(error)) from error
        try:
            reject_plaintext_contact(text)
        except ContactForbiddenError as error:
            raise NotesComplianceError(str(error)) from error

        note = self.get_note(note_id)
        params: dict[str, Any] = {
            "text": text,
            "note_id": note.note_id,
            "input_selector": "text=留下你的想法吧",
            "composer_tap_x": 350,
            "composer_tap_y": 1906,
        }
        if note.title_summary:
            params["title_summary"] = note.title_summary
        if note.xhs_hint:
            params["xhs_hint"] = note.xhs_hint
        return self.task_service.create_and_dispatch(
            account_id=note.account_id,
            playbook="post_comment@1.0",
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
            "input_selector": "text=留下你的想法吧",
            "composer_tap_x": 350,
            "composer_tap_y": 1906,
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


def _optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


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
        like_count=row[9] if len(row) > 9 else None,
        collect_count=row[10] if len(row) > 10 else None,
        read_count=row[11] if len(row) > 11 else None,
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


def _node_from_row(row: tuple) -> NoteCommentNodeRecord:
    return NoteCommentNodeRecord(
        node_id=row[0],
        note_id=row[1],
        account_id=row[2],
        parent_node_id=row[3],
        root_node_id=row[4],
        depth=int(row[5] or 0),
        author_summary=row[6],
        body_summary=row[7],
        posted_at_text=row[8],
        reply_to_author=row[9],
        sort_index=int(row[10] or 0),
        locator_hint=row[11],
        source_task_id=row[12],
        synced_at=row[13],
        created_at=row[14],
    )

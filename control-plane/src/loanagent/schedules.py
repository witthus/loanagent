from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

import psycopg

from loanagent.accounts import AccountRepository
from loanagent.content import ContentNotFoundError, ContentRepository
from loanagent.db import migrate_fleet_schema
from loanagent.media import MediaRepository
from loanagent.tasks import (
    TaskAccessibilityDownError,
    TaskAccountNotFoundError,
    TaskAccountUnavailableError,
    TaskDeviceUnavailableError,
    TaskDispatchError,
    TaskRecord,
    TaskService,
)


@dataclass(frozen=True)
class ScheduleRecord:
    schedule_id: str
    account_id: str
    content_id: str
    window_start: datetime | None
    window_end: datetime | None
    status: str
    task_id: str | None
    error_code: str | None
    created_at: datetime
    updated_at: datetime


class ScheduleNotFoundError(Exception):
    pass


class ScheduleNotDispatchableError(Exception):
    pass


class ScheduleNotEditableError(Exception):
    pass


PATCHABLE_FIELDS = {
    "account_id",
    "content_id",
    "window_start",
    "window_end",
}


class ScheduleRepository:
    def __init__(
        self,
        database_url: str,
        task_service: TaskService,
        media_repository: MediaRepository | None = None,
        content_repository: ContentRepository | None = None,
    ) -> None:
        self.database_url = database_url
        self.task_service = task_service
        self.media_repository = media_repository or MediaRepository(database_url)
        self.content_repository = content_repository or ContentRepository(database_url)

    def migrate(self) -> None:
        migrate_fleet_schema(self.database_url)

    def create(
        self,
        *,
        account_id: str,
        content_id: str,
        window_start: datetime | None = None,
        window_end: datetime | None = None,
    ) -> ScheduleRecord:
        self.content_repository.get(content_id)
        schedule_id = str(uuid4())
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                INSERT INTO schedule_items (
                    schedule_id, account_id, content_id, window_start, window_end, status
                )
                VALUES (%s, %s, %s, %s, %s, 'ready')
                RETURNING schedule_id, account_id, content_id, window_start, window_end,
                    status, task_id, error_code, created_at, updated_at
                """,
                (schedule_id, account_id, content_id, window_start, window_end),
            ).fetchone()
        return _schedule_from_row(row)

    def list(self) -> list[ScheduleRecord]:
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                SELECT schedule_id, account_id, content_id, window_start, window_end,
                    status, task_id, error_code, created_at, updated_at
                FROM schedule_items
                ORDER BY created_at DESC, schedule_id
                """
            ).fetchall()
        return [_schedule_from_row(row) for row in rows]

    def get(self, schedule_id: str) -> ScheduleRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT schedule_id, account_id, content_id, window_start, window_end,
                    status, task_id, error_code, created_at, updated_at
                FROM schedule_items
                WHERE schedule_id = %s
                """,
                (schedule_id,),
            ).fetchone()
        if row is None:
            raise ScheduleNotFoundError(schedule_id)
        return _schedule_from_row(row)

    def update(self, schedule_id: str, **changes: Any) -> ScheduleRecord:
        invalid = set(changes) - PATCHABLE_FIELDS
        if invalid:
            raise ValueError(f"unsupported schedule fields: {sorted(invalid)}")
        schedule = self.get(schedule_id)
        if schedule.status not in {"ready", "failed"}:
            raise ScheduleNotEditableError(schedule.status)
        if not changes:
            return schedule
        if "content_id" in changes and changes["content_id"] is not None:
            self.content_repository.get(changes["content_id"])
        assignments = [f"{field} = %s" for field in changes]
        values = [*changes.values(), schedule_id]
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                UPDATE schedule_items
                SET {", ".join(assignments)},
                    updated_at = CURRENT_TIMESTAMP
                WHERE schedule_id = %s
                RETURNING schedule_id, account_id, content_id, window_start, window_end,
                    status, task_id, error_code, created_at, updated_at
                """,
                values,
            ).fetchone()
        if row is None:
            raise ScheduleNotFoundError(schedule_id)
        return _schedule_from_row(row)

    def delete(self, schedule_id: str) -> None:
        self.get(schedule_id)
        with psycopg.connect(self.database_url) as connection:
            deleted = connection.execute(
                """
                DELETE FROM schedule_items
                WHERE schedule_id = %s
                RETURNING schedule_id
                """,
                (schedule_id,),
            ).fetchone()
        if deleted is None:
            raise ScheduleNotFoundError(schedule_id)

    def publish_immediate(
        self,
        *,
        account_id: str,
        content_id: str,
        engagement_mode: str | None = "auto",
    ) -> TaskRecord:
        content = self.content_repository.get(content_id)
        account = AccountRepository(self.database_url).get(account_id)
        if account.platform != content.platform:
            raise ValueError("account and content platform mismatch")
        params = self._publish_params(content.title, content.body, content.media_ids)
        params["engagement_mode"] = engagement_mode or "auto"
        params["platform"] = content.platform
        return self.task_service.create_and_dispatch(
            account_id=account_id,
            playbook="publish_note@1.0",
            params=params,
            source="manual",
        )

    def dispatch(self, schedule_id: str) -> ScheduleRecord:
        schedule = self.get(schedule_id)
        if schedule.status not in {"ready", "failed"}:
            raise ScheduleNotDispatchableError(schedule.status)
        content = self.content_repository.get(schedule.content_id)
        params = self._publish_params(content.title, content.body, content.media_ids)
        try:
            task = self.task_service.create_and_dispatch(
                account_id=schedule.account_id,
                playbook="publish_note@1.0",
                params=params,
                source="scheduler",
            )
        except (
            TaskAccountNotFoundError,
            TaskAccountUnavailableError,
            TaskDeviceUnavailableError,
            TaskAccessibilityDownError,
            TaskDispatchError,
        ) as error:
            error_code = _error_code_for_exception(error)
            return self._update(
                schedule_id,
                status="failed",
                error_code=error_code,
            )
        return self._update(
            schedule_id,
            status="dispatched",
            task_id=task.task_id,
            error_code=None,
        )

    def _publish_params(
        self,
        title: str,
        body: str,
        media_ids: list[str],
    ) -> dict[str, Any]:
        media_urls: list[dict[str, str]] = []
        for media_id in media_ids:
            media = self.media_repository.get(media_id)
            filename = Path(media.storage_path).name
            media_urls.append(
                {
                    "url": self.media_repository.signed_download_url(media_id),
                    "filename": filename,
                }
            )
        return {
            "title": title,
            "body": body,
            "media_urls": media_urls,
            "start_in_editor": False,
        }

    def _update(
        self,
        schedule_id: str,
        *,
        status: str,
        task_id: str | None = None,
        error_code: str | None = None,
    ) -> ScheduleRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                UPDATE schedule_items
                SET status = %s,
                    task_id = COALESCE(%s, task_id),
                    error_code = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE schedule_id = %s
                RETURNING schedule_id, account_id, content_id, window_start, window_end,
                    status, task_id, error_code, created_at, updated_at
                """,
                (status, task_id, error_code, schedule_id),
            ).fetchone()
        if row is None:
            raise ScheduleNotFoundError(schedule_id)
        return _schedule_from_row(row)


def _error_code_for_exception(error: Exception) -> str:
    if isinstance(error, TaskAccountNotFoundError):
        return "ACCOUNT_NOT_FOUND"
    if isinstance(error, TaskAccountUnavailableError):
        return "ACCOUNT_UNAVAILABLE"
    if isinstance(error, TaskDeviceUnavailableError):
        return "DEVICE_UNAVAILABLE"
    if isinstance(error, TaskAccessibilityDownError):
        return "A11Y_DOWN"
    if isinstance(error, TaskDispatchError):
        return "TASK_DISPATCH_FAILED"
    return "DISPATCH_FAILED"


def _schedule_from_row(row: tuple) -> ScheduleRecord:
    return ScheduleRecord(
        schedule_id=row[0],
        account_id=row[1],
        content_id=row[2],
        window_start=row[3],
        window_end=row[4],
        status=row[5],
        task_id=row[6],
        error_code=row[7],
        created_at=row[8],
        updated_at=row[9],
    )

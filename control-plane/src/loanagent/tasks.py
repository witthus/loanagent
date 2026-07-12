from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Protocol
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb

from loanagent.roles import AccountRole, playbook_allowed_for_role, playbook_base


TASK_SCHEMA_VERSION = "1.0"
DEFAULT_PRIORITY = 100
DEFAULT_TIMEOUT_SEC = 120
DEFAULT_SOURCE = "manual"

READONLY_PLAYBOOKS = {
    "ensure_app_ready",
    "read_comments",
    "inbox_sync",
    "inbox_open_thread",
}
NON_IDEMPOTENT_PLAYBOOKS = {"publish_note"}


class MqttBus(Protocol):
    def publish(self, topic: str, payload: dict[str, Any]) -> None:
        pass


@dataclass(frozen=True)
class TaskRecord:
    task_id: str
    operation_id: str
    device_id: str
    account_id: str
    playbook: str
    params: dict[str, Any]
    effect_class: str
    effect_committed: bool
    status: str
    reconcile_required: bool
    priority: int
    timeout_sec: int
    source: str
    error_code: str | None
    created_at: datetime
    updated_at: datetime


class DuplicateTaskError(Exception):
    pass


class TaskNotFoundError(Exception):
    pass


class TaskAccountNotFoundError(Exception):
    pass


class TaskAccountUnavailableError(Exception):
    pass


class TaskDeviceUnavailableError(Exception):
    pass


class PlaybookForbiddenError(Exception):
    pass


class ReadonlyTaskRequiredError(Exception):
    pass


class UnsupportedTaskEventError(Exception):
    pass


class TaskService:
    def __init__(self, database_url: str, mqtt_bus: MqttBus) -> None:
        self.database_url = database_url
        self.mqtt_bus = mqtt_bus

    def create_and_dispatch(
        self,
        *,
        account_id: str,
        playbook: str,
        params: Mapping[str, Any] | None = None,
        operation_id: str | None = None,
        task_id: str | None = None,
        priority: int = DEFAULT_PRIORITY,
        timeout_sec: int = DEFAULT_TIMEOUT_SEC,
        source: str = DEFAULT_SOURCE,
    ) -> TaskRecord:
        task_id = task_id or str(uuid4())
        operation_id = operation_id or str(uuid4())
        params = dict(params or {})
        device_id = self._resolve_dispatch_target(account_id, playbook)
        effect_class = effect_class_for_playbook(playbook)

        queued = self._insert_queued_task(
            task_id=task_id,
            operation_id=operation_id,
            device_id=device_id,
            account_id=account_id,
            playbook=playbook,
            params=params,
            effect_class=effect_class,
            priority=priority,
            timeout_sec=timeout_sec,
            source=source,
        )
        topic = f"devices/{device_id}/commands"
        self.mqtt_bus.publish(topic, task_command_envelope(queued))
        return self._update_status(task_id, status="accepted")

    def list(
        self,
        *,
        account_id: str | None = None,
        device_id: str | None = None,
        status: str | None = None,
    ) -> list[TaskRecord]:
        clauses: list[str] = []
        values: list[Any] = []
        if account_id is not None:
            clauses.append("account_id = %s")
            values.append(account_id)
        if device_id is not None:
            clauses.append("device_id = %s")
            values.append(device_id)
        if status is not None:
            clauses.append("status = %s")
            values.append(status)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                f"""
                SELECT task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at
                FROM tasks
                {where}
                ORDER BY created_at, task_id
                """,
                values,
            ).fetchall()
        return [_task_from_row(row) for row in rows]

    def mark_readonly_succeeded_from_event(self, *, device_id: str, task_id: str) -> TaskRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at
                FROM tasks
                WHERE task_id = %s AND device_id = %s
                """,
                (task_id, device_id),
            ).fetchone()
            if row is None:
                raise TaskNotFoundError(task_id)
            task = _task_from_row(row)
            if task.effect_class != "readonly":
                raise ReadonlyTaskRequiredError(task_id)
            updated = connection.execute(
                """
                UPDATE tasks
                SET status = 'succeeded',
                    effect_committed = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = %s
                RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at
                """,
                (task_id,),
            ).fetchone()
        return _task_from_row(updated)

    def _resolve_dispatch_target(self, account_id: str, playbook: str) -> str:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT accounts.role, accounts.device_id, accounts.status, devices.online
                FROM accounts
                LEFT JOIN devices ON devices.device_id = accounts.device_id
                WHERE accounts.account_id = %s
                """,
                (account_id,),
            ).fetchone()
        if row is None:
            raise TaskAccountNotFoundError(account_id)
        role = AccountRole(row[0])
        device_id = row[1]
        account_status = row[2]
        device_online = row[3]
        if not playbook_allowed_for_role(role, playbook):
            raise PlaybookForbiddenError(playbook)
        if account_status != "active":
            raise TaskAccountUnavailableError(account_id)
        if device_id is None or device_online is not True:
            raise TaskDeviceUnavailableError(account_id)
        return device_id

    def _insert_queued_task(
        self,
        *,
        task_id: str,
        operation_id: str,
        device_id: str,
        account_id: str,
        playbook: str,
        params: dict[str, Any],
        effect_class: str,
        priority: int,
        timeout_sec: int,
        source: str,
    ) -> TaskRecord:
        try:
            with psycopg.connect(self.database_url) as connection:
                row = connection.execute(
                    """
                    INSERT INTO tasks (
                        task_id, operation_id, device_id, account_id, playbook, params,
                        effect_class, status, priority, timeout_sec, source
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, 'queued', %s, %s, %s)
                    RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                        effect_class, effect_committed, status, reconcile_required, priority,
                        timeout_sec, source, error_code, created_at, updated_at
                    """,
                    (
                        task_id,
                        operation_id,
                        device_id,
                        account_id,
                        playbook,
                        Jsonb(params),
                        effect_class,
                        priority,
                        timeout_sec,
                        source,
                    ),
                ).fetchone()
        except psycopg.errors.UniqueViolation as error:
            raise DuplicateTaskError(task_id) from error
        return _task_from_row(row)

    def _update_status(self, task_id: str, *, status: str) -> TaskRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                UPDATE tasks
                SET status = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = %s
                RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at
                """,
                (status, task_id),
            ).fetchone()
        if row is None:
            raise TaskNotFoundError(task_id)
        return _task_from_row(row)


def effect_class_for_playbook(playbook: str) -> str:
    base = playbook_base(playbook)
    if base in READONLY_PLAYBOOKS:
        return "readonly"
    if base in NON_IDEMPOTENT_PLAYBOOKS:
        return "non_idempotent"
    return "idempotent"


def task_command_envelope(task: TaskRecord) -> dict[str, Any]:
    return {
        "schema_version": TASK_SCHEMA_VERSION,
        "task_id": task.task_id,
        "operation_id": task.operation_id,
        "playbook": task.playbook,
        "params": task.params,
        "effect_class": task.effect_class,
        "effect_committed": task.effect_committed,
        "status": task.status,
        "reconcile_required": task.reconcile_required,
        "priority": task.priority,
        "timeout_sec": task.timeout_sec,
        "source": task.source,
    }


def _task_from_row(row: tuple) -> TaskRecord:
    return TaskRecord(
        task_id=row[0],
        operation_id=row[1],
        device_id=row[2],
        account_id=row[3],
        playbook=row[4],
        params=row[5],
        effect_class=row[6],
        effect_committed=row[7],
        status=row[8],
        reconcile_required=row[9],
        priority=row[10],
        timeout_sec=row[11],
        source=row[12],
        error_code=row[13],
        created_at=row[14],
        updated_at=row[15],
    )

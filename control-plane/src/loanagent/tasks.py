from __future__ import annotations

import asyncio
from collections.abc import Callable, Mapping
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
import logging
import os
import re
from typing import Any, Protocol
from uuid import NAMESPACE_URL, uuid4, uuid5

import psycopg
from psycopg.types.json import Jsonb

from loanagent.devices import DeviceRepository
from loanagent.roles import AccountRole, playbook_allowed_for_role, playbook_base


logger = logging.getLogger(__name__)

TASK_SCHEMA_VERSION = "1.0"
TASK_RECORD_SCHEMA_ID = "task-record"
TASK_RECORD_SCHEMA_VERSION = "1.0"
TASK_ERROR_CODE_MIN_LENGTH = 2
TASK_ERROR_CODE_MAX_LENGTH = 64
TASK_ERROR_CODE_PATTERN = r"^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$"
TASK_RESULT_PAYLOAD_DEFAULT_MAX_BYTES = 1024 * 1024
TASK_RESULT_PAYLOAD_MIN_BYTES = 1024
TASK_RESULT_PAYLOAD_MAX_BYTES = 16 * 1024 * 1024
TASK_RESULT_KINDS = frozenset({"comments", "notes", "inbox", "thread", "publish"})
TASK_RESULT_KIND_BY_PLAYBOOK = {
    "read_comments": "comments",
    "sync_notes": "notes",
    "inbox_sync": "inbox",
    "inbox_open_thread": "thread",
    "publish_note": "publish",
}
TASK_RESULT_MAX_ITEMS = 100
TASK_RESULT_MAX_MESSAGES = 200
TASK_RESULT_MAX_REPLIES = 100
TASK_RESULT_SUMMARY_MAX_LENGTH = 512
TASK_RESULT_LOCATOR_MAX_LENGTH = 512
TASK_RESULT_REFERENCE_MAX_LENGTH = 256
DEFAULT_PRIORITY = 100
DEFAULT_TIMEOUT_SEC = 900
PLAYBOOK_TIMEOUT_SEC = {
    "ensure_app_ready": 60,
    "dismiss_interruptions": 60,
    "sync_notes": 180,
    "read_comments": 180,
    "inbox_sync": 180,
    "inbox_open_thread": 120,
}
DEFAULT_SOURCE = "manual"

READONLY_PLAYBOOKS = {
    "ensure_app_ready",
    "read_comments",
    "sync_notes",
    "inbox_sync",
    "inbox_open_thread",
}
NON_IDEMPOTENT_PLAYBOOKS = {
    "publish_note",
    "post_comment",
    "reply_comment",
    "reply_dm",
}
TERMINAL_TASK_STATUSES = {
    "succeeded",
    "failed",
    "cancelled",
    "unknown",
    "reconcile_required",
}
EVENT_TASK_STATUSES = {
    "accepted",
    "executing",
    "effect_committed",
    "reported",
    "succeeded",
    "failed",
    "unknown",
}
_INTERMEDIATE_STATUS_RANK = {
    "queued": 0,
    "accepted": 1,
    "executing": 2,
    "effect_committed": 3,
    "reported": 4,
}


class InvalidTaskErrorCodeError(ValueError):
    pass


class InvalidTaskResultPayloadError(ValueError):
    pass


def validate_task_error_code(error_code: str | None) -> str | None:
    if error_code is None:
        return None
    if not TASK_ERROR_CODE_MIN_LENGTH <= len(error_code) <= TASK_ERROR_CODE_MAX_LENGTH:
        raise InvalidTaskErrorCodeError(
            f"error_code must contain {TASK_ERROR_CODE_MIN_LENGTH}-"
            f"{TASK_ERROR_CODE_MAX_LENGTH} characters"
        )
    if re.fullmatch(TASK_ERROR_CODE_PATTERN, error_code) is None:
        raise InvalidTaskErrorCodeError("error_code must use UPPER_SNAKE_CASE")
    return error_code


_TIMEOUT_SCAN_SQL = """
    WITH expired AS (
        SELECT task_id,
            CASE
                WHEN status = 'queued' AND dispatch_started_at IS NOT NULL
                    THEN 'execution'
                WHEN status IN ('queued', 'accepted') THEN 'queued'
                WHEN status = 'executing' THEN 'execution'
                ELSE 'report'
            END AS timeout_phase,
            effect_class,
            (status = 'queued' AND dispatch_started_at IS NOT NULL)
                AS dispatch_ambiguous
        FROM tasks
        WHERE (
            status = 'queued'
            AND updated_at <= %s - %s * INTERVAL '1 second'
        ) OR (
            status = 'accepted'
            AND accepted_at <= %s - %s * INTERVAL '1 second'
        ) OR (
            status = 'executing'
            AND executing_at <= %s - %s * INTERVAL '1 second'
        ) OR (
            status = 'effect_committed'
            AND effect_committed_at <= %s - %s * INTERVAL '1 second'
        ) OR (
            status = 'reported'
            AND reported_at <= %s - %s * INTERVAL '1 second'
        )
        FOR UPDATE
    )
    UPDATE tasks AS task
    SET status = CASE
            WHEN expired.effect_class = 'non_idempotent'
                AND expired.timeout_phase IN ('execution', 'report')
            THEN 'reconcile_required'
            ELSE 'failed'
        END,
        reconcile_required = (
            expired.effect_class = 'non_idempotent'
            AND expired.timeout_phase IN ('execution', 'report')
        ),
        error_code = CASE
            WHEN expired.dispatch_ambiguous THEN 'EFFECT_UNKNOWN'
            ELSE %s
        END,
        timeout_phase = expired.timeout_phase,
        terminal_at = %s,
        updated_at = %s
    FROM expired
    WHERE task.task_id = expired.task_id
    RETURNING task.task_id, task.operation_id, task.device_id, task.account_id,
        task.playbook, task.params, task.effect_class, task.effect_committed,
        task.status, task.reconcile_required, task.priority, task.timeout_sec,
        task.source, task.error_code, task.created_at, task.updated_at,
        task.accepted_at, task.executing_at, task.effect_committed_at,
        task.reported_at, task.terminal_at, task.timeout_phase
"""


@dataclass(frozen=True)
class TaskTimeoutSettings:
    queue_timeout_sec: int = 300
    execution_timeout_sec: int = 900
    effect_report_grace_sec: int = 60
    scan_interval_sec: int = 30

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> TaskTimeoutSettings:
        values = os.environ if environ is None else environ
        return cls(
            queue_timeout_sec=_bounded_env_int(
                values, "TASK_QUEUE_TIMEOUT_SEC", default=300, maximum=604_800
            ),
            execution_timeout_sec=_bounded_env_int(
                values, "TASK_EXECUTION_TIMEOUT_SEC", default=900, maximum=604_800
            ),
            effect_report_grace_sec=_bounded_env_int(
                values, "TASK_EFFECT_REPORT_GRACE_SEC", default=60, maximum=86_400
            ),
            scan_interval_sec=_bounded_env_int(
                values, "TASK_TIMEOUT_SCAN_INTERVAL_SEC", default=30, maximum=3_600
            ),
        )


@dataclass(frozen=True)
class TaskResultPayloadSettings:
    max_bytes: int = TASK_RESULT_PAYLOAD_DEFAULT_MAX_BYTES

    @classmethod
    def from_env(
        cls,
        environ: Mapping[str, str] | None = None,
    ) -> TaskResultPayloadSettings:
        values = os.environ if environ is None else environ
        return cls(
            max_bytes=_bounded_env_int(
                values,
                "TASK_RESULT_PAYLOAD_MAX_BYTES",
                default=TASK_RESULT_PAYLOAD_DEFAULT_MAX_BYTES,
                minimum=TASK_RESULT_PAYLOAD_MIN_BYTES,
                maximum=TASK_RESULT_PAYLOAD_MAX_BYTES,
            )
        )


def _bounded_env_int(
    environ: Mapping[str, str],
    name: str,
    *,
    default: int,
    minimum: int = 1,
    maximum: int,
) -> int:
    raw = environ.get(name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def validate_task_result_payload(
    result_payload: Mapping[str, Any] | None,
    *,
    playbook: str,
    max_bytes: int,
) -> dict[str, Any] | None:
    if result_payload is None:
        return None
    if not isinstance(result_payload, Mapping):
        raise InvalidTaskResultPayloadError("result_payload must be an object")
    payload = dict(result_payload)
    try:
        encoded = json.dumps(
            payload,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise InvalidTaskResultPayloadError("result_payload must be JSON serializable") from error
    if len(encoded) > max_bytes:
        raise InvalidTaskResultPayloadError(f"result_payload must not exceed {max_bytes} bytes")

    kind = payload.get("kind")
    if not isinstance(kind, str) or kind not in TASK_RESULT_KINDS:
        raise InvalidTaskResultPayloadError(
            f"result_payload kind must be one of {sorted(TASK_RESULT_KINDS)}"
        )
    expected_kind = TASK_RESULT_KIND_BY_PLAYBOOK.get(playbook_base(playbook))
    if expected_kind is None:
        raise InvalidTaskResultPayloadError(f"playbook {playbook} does not accept result_payload")
    if kind != expected_kind:
        raise InvalidTaskResultPayloadError(
            f"playbook {playbook} requires result_payload kind {expected_kind}"
        )

    if kind == "comments":
        _validate_allowed_fields(payload, {"kind", "items", "note_ref", "note_id"}, "$")
        _validate_optional_string(payload, "note_ref", "$", TASK_RESULT_REFERENCE_MAX_LENGTH)
        _validate_optional_string(payload, "note_id", "$", TASK_RESULT_REFERENCE_MAX_LENGTH)
        for index, item in enumerate(
            _validate_object_list(payload, "items", "$", TASK_RESULT_MAX_ITEMS)
        ):
            _validate_comment(item, f"$.items[{index}]", allow_replies=True)
    elif kind == "notes":
        _validate_allowed_fields(payload, {"kind", "items"}, "$")
        for index, item in enumerate(
            _validate_object_list(payload, "items", "$", TASK_RESULT_MAX_ITEMS)
        ):
            path = f"$.items[{index}]"
            _validate_allowed_fields(
                item,
                {
                    "title_summary",
                    "like_count",
                    "collect_count",
                    "read_count",
                    "locator_hint",
                },
                path,
            )
            _validate_optional_string(
                item,
                "title_summary",
                path,
                TASK_RESULT_SUMMARY_MAX_LENGTH,
                required=True,
            )
            _validate_optional_string(item, "locator_hint", path, TASK_RESULT_LOCATOR_MAX_LENGTH)
            for count_key in ("like_count", "collect_count", "read_count"):
                _validate_optional_nonnegative_int(item, count_key, path)
    elif kind == "inbox":
        _validate_allowed_fields(payload, {"kind", "threads"}, "$")
        for index, thread in enumerate(
            _validate_object_list(payload, "threads", "$", TASK_RESULT_MAX_ITEMS)
        ):
            path = f"$.threads[{index}]"
            _validate_allowed_fields(
                thread,
                {
                    "title_summary",
                    "preview_summary",
                    "unread",
                    "locator_hint",
                    "messages",
                },
                path,
            )
            _validate_optional_string(
                thread,
                "title_summary",
                path,
                TASK_RESULT_SUMMARY_MAX_LENGTH,
                required=True,
            )
            _validate_optional_string(
                thread, "preview_summary", path, TASK_RESULT_SUMMARY_MAX_LENGTH
            )
            _validate_optional_string(thread, "locator_hint", path, TASK_RESULT_LOCATOR_MAX_LENGTH)
            if "unread" in thread and not isinstance(thread["unread"], bool):
                raise InvalidTaskResultPayloadError(f"{path}.unread must be a boolean")
            if "messages" in thread:
                _validate_messages(thread, "messages", path)
    elif kind == "thread":
        _validate_allowed_fields(payload, {"kind", "thread_id", "messages"}, "$")
        _validate_optional_string(payload, "thread_id", "$", TASK_RESULT_REFERENCE_MAX_LENGTH)
        messages = _validate_messages(payload, "messages", "$")
        if not messages:
            raise InvalidTaskResultPayloadError("$.messages must not be empty")
    elif kind == "publish":
        _validate_allowed_fields(
            payload,
            {
                "kind",
                "note_id",
                "note_ref",
                "title_summary",
                "content_id",
                "xhs_hint",
            },
            "$",
        )
        _validate_optional_string(
            payload,
            "title_summary",
            "$",
            TASK_RESULT_SUMMARY_MAX_LENGTH,
            required=True,
        )
        for key in ("note_id", "note_ref", "content_id", "xhs_hint"):
            _validate_optional_string(payload, key, "$", TASK_RESULT_REFERENCE_MAX_LENGTH)
    return payload


def _validate_allowed_fields(
    value: Mapping[str, Any],
    allowed: set[str],
    path: str,
) -> None:
    extras = sorted(set(value) - allowed)
    if extras:
        raise InvalidTaskResultPayloadError(
            f"{path} contains unsupported fields: {', '.join(extras)}"
        )


def _validate_object_list(
    value: Mapping[str, Any],
    key: str,
    path: str,
    maximum: int,
) -> list[Mapping[str, Any]]:
    items = value.get(key)
    if not isinstance(items, list):
        raise InvalidTaskResultPayloadError(f"{path}.{key} must be a list")
    if len(items) > maximum:
        raise InvalidTaskResultPayloadError(f"{path}.{key} must contain at most {maximum} items")
    for index, item in enumerate(items):
        if not isinstance(item, Mapping):
            raise InvalidTaskResultPayloadError(f"{path}.{key}[{index}] must be an object")
    return items


def _validate_optional_string(
    value: Mapping[str, Any],
    key: str,
    path: str,
    maximum: int,
    *,
    required: bool = False,
) -> None:
    field = value.get(key)
    if field is None:
        if required:
            raise InvalidTaskResultPayloadError(f"{path}.{key} is required")
        return
    if not isinstance(field, str):
        raise InvalidTaskResultPayloadError(f"{path}.{key} must be a string")
    if required and not field.strip():
        raise InvalidTaskResultPayloadError(f"{path}.{key} must not be empty")
    if len(field) > maximum:
        raise InvalidTaskResultPayloadError(f"{path}.{key} must not exceed {maximum} characters")


def _validate_optional_nonnegative_int(
    value: Mapping[str, Any],
    key: str,
    path: str,
) -> None:
    field = value.get(key)
    if field is None:
        return
    if isinstance(field, bool) or not isinstance(field, int) or not 0 <= field <= 2_147_483_647:
        raise InvalidTaskResultPayloadError(
            f"{path}.{key} must be an integer between 0 and 2147483647"
        )


def _validate_comment(
    comment: Mapping[str, Any],
    path: str,
    *,
    allow_replies: bool,
) -> None:
    allowed = {
        "author_summary",
        "body_summary",
        "locator_hint",
        "posted_at_text",
        "reply_to_author",
    }
    if allow_replies:
        allowed.add("replies")
    _validate_allowed_fields(comment, allowed, path)
    for key, maximum in (
        ("author_summary", TASK_RESULT_SUMMARY_MAX_LENGTH),
        ("body_summary", TASK_RESULT_SUMMARY_MAX_LENGTH),
        ("locator_hint", TASK_RESULT_LOCATOR_MAX_LENGTH),
        ("posted_at_text", TASK_RESULT_REFERENCE_MAX_LENGTH),
        ("reply_to_author", TASK_RESULT_SUMMARY_MAX_LENGTH),
    ):
        _validate_optional_string(comment, key, path, maximum)
    if not any(
        isinstance(comment.get(key), str) and comment[key].strip()
        for key in ("author_summary", "body_summary")
    ):
        raise InvalidTaskResultPayloadError(f"{path} requires author_summary or body_summary")
    if allow_replies and "replies" in comment:
        for index, reply in enumerate(
            _validate_object_list(comment, "replies", path, TASK_RESULT_MAX_REPLIES)
        ):
            _validate_comment(reply, f"{path}.replies[{index}]", allow_replies=False)


def _validate_messages(
    value: Mapping[str, Any],
    key: str,
    path: str,
) -> list[Mapping[str, Any]]:
    messages = _validate_object_list(value, key, path, TASK_RESULT_MAX_MESSAGES)
    for index, message in enumerate(messages):
        message_path = f"{path}.{key}[{index}]"
        _validate_allowed_fields(
            message,
            {"sender_summary", "body_summary", "posted_at_text"},
            message_path,
        )
        _validate_optional_string(
            message,
            "sender_summary",
            message_path,
            TASK_RESULT_SUMMARY_MAX_LENGTH,
        )
        _validate_optional_string(
            message,
            "body_summary",
            message_path,
            TASK_RESULT_SUMMARY_MAX_LENGTH,
            required=True,
        )
        _validate_optional_string(
            message,
            "posted_at_text",
            message_path,
            TASK_RESULT_REFERENCE_MAX_LENGTH,
        )
    return messages


class MqttBus(Protocol):
    def publish(self, topic: str, payload: dict[str, Any]) -> None:
        pass


@dataclass(frozen=True)
class TaskRecord:
    task_id: str
    operation_id: str
    device_id: str | None
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
    accepted_at: datetime | None
    executing_at: datetime | None
    effect_committed_at: datetime | None
    reported_at: datetime | None
    terminal_at: datetime | None
    timeout_phase: str | None


class DuplicateTaskError(Exception):
    pass


class TaskNotFoundError(Exception):
    pass


class TaskAlreadyTerminalError(Exception):
    pass


class TaskTransitionError(Exception):
    pass


class TaskAccountNotFoundError(Exception):
    pass


class TaskAccountUnavailableError(Exception):
    pass


class TaskDeviceUnavailableError(Exception):
    pass


class TaskAccessibilityDownError(Exception):
    pass


class TaskNetworkPolicyViolationError(Exception):
    pass


class TaskPublishQuotaExceededError(Exception):
    pass


class TaskDispatchError(Exception):
    def __init__(self, task_id: str) -> None:
        super().__init__(task_id)
        self.task_id = task_id


class TaskDispatchAmbiguousError(TaskDispatchError):
    def __init__(self, task: TaskRecord) -> None:
        super().__init__(task.task_id)
        self.status = task.status
        self.reconcile_required = task.reconcile_required
        self.error_code = task.error_code


def task_dispatch_ambiguous_detail(
    error: TaskDispatchAmbiguousError,
) -> dict[str, Any]:
    return {
        "code": "TASK_DISPATCH_AMBIGUOUS",
        "message": (
            "Dispatch outcome is unknown; blind retries are forbidden. "
            "Reconcile the original task before any further action."
        ),
        "task_id": error.task_id,
        "status": error.status,
        "reconcile_required": error.reconcile_required,
        "error_code": error.error_code or "EFFECT_UNKNOWN",
        "retry_permitted": False,
        "action": "RECONCILE_ORIGINAL_TASK",
    }


class PlaybookForbiddenError(Exception):
    pass


class TaskService:
    def __init__(
        self,
        database_url: str,
        mqtt_bus: MqttBus,
        engagement_service: Any | None = None,
        timeout_settings: TaskTimeoutSettings | None = None,
        result_payload_settings: TaskResultPayloadSettings | None = None,
    ) -> None:
        self.database_url = database_url
        self.mqtt_bus = mqtt_bus
        self.engagement_service = engagement_service
        self.timeout_settings = timeout_settings or TaskTimeoutSettings.from_env()
        self.result_payload_settings = (
            result_payload_settings or TaskResultPayloadSettings.from_env()
        )

    def create_and_dispatch(
        self,
        *,
        account_id: str,
        playbook: str,
        params: Mapping[str, Any] | None = None,
        operation_id: str | None = None,
        task_id: str | None = None,
        priority: int = DEFAULT_PRIORITY,
        source: str = DEFAULT_SOURCE,
        _before_publish: Callable[[TaskRecord], None] | None = None,
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
            timeout_sec=timeout_sec_for_playbook(
                playbook, self.timeout_settings.execution_timeout_sec
            ),
            source=source,
        )
        if _before_publish is not None:
            _before_publish(queued)
        return self._dispatch_queued_task(queued)

    def _dispatch_queued_task(self, queued: TaskRecord) -> TaskRecord:
        current, claimed = self._claim_dispatch_attempt(queued.task_id)
        if not claimed:
            if current.status == "queued":
                current, outcome_recorded = self._mark_dispatch_unknown(current)
                if outcome_recorded:
                    raise TaskDispatchAmbiguousError(current)
            return current
        topic = f"devices/{queued.device_id}/commands"
        try:
            self.mqtt_bus.publish(topic, task_command_envelope(queued))
        except Exception as error:
            current, outcome_recorded = self._mark_dispatch_unknown(queued)
            if outcome_recorded:
                raise TaskDispatchAmbiguousError(current) from error
            return current
        current, _ = self._update_status(
            queued.task_id,
            status="accepted",
            expected_status="queued",
        )
        return current

    def create_and_dispatch_idempotent(
        self,
        *,
        account_id: str,
        playbook: str,
        task_id: str,
        operation_id: str,
        params: Mapping[str, Any] | None = None,
        priority: int = DEFAULT_PRIORITY,
        source: str = DEFAULT_SOURCE,
        before_publish: Callable[[TaskRecord], None] | None = None,
    ) -> TaskRecord:
        with psycopg.connect(self.database_url, autocommit=True) as lock_connection:
            lock_connection.execute(
                "SELECT pg_advisory_lock(hashtext(%s), hashtext(%s))",
                ("loanagent-task-dispatch", task_id),
            )
            try:
                return self._create_and_dispatch_idempotent_locked(
                    account_id=account_id,
                    playbook=playbook,
                    task_id=task_id,
                    operation_id=operation_id,
                    params=params,
                    priority=priority,
                    source=source,
                    before_publish=before_publish,
                )
            finally:
                lock_connection.execute(
                    "SELECT pg_advisory_unlock(hashtext(%s), hashtext(%s))",
                    ("loanagent-task-dispatch", task_id),
                )

    def _create_and_dispatch_idempotent_locked(
        self,
        *,
        account_id: str,
        playbook: str,
        task_id: str,
        operation_id: str,
        params: Mapping[str, Any] | None,
        priority: int,
        source: str,
        before_publish: Callable[[TaskRecord], None] | None,
    ) -> TaskRecord:
        expected_params = dict(params or {})
        try:
            existing = self.get(task_id)
        except TaskNotFoundError:
            existing = None
        if existing is not None:
            self._validate_idempotent_task(
                existing,
                operation_id=operation_id,
                account_id=account_id,
                playbook=playbook,
                params=expected_params,
                priority=priority,
                source=source,
            )
            if existing.status == "queued":
                if before_publish is not None:
                    before_publish(existing)
                return self._dispatch_queued_task(existing)
            return existing

        try:
            return self.create_and_dispatch(
                account_id=account_id,
                playbook=playbook,
                params=expected_params,
                operation_id=operation_id,
                task_id=task_id,
                priority=priority,
                source=source,
                _before_publish=before_publish,
            )
        except DuplicateTaskError:
            existing = self.get(task_id)
            self._validate_idempotent_task(
                existing,
                operation_id=operation_id,
                account_id=account_id,
                playbook=playbook,
                params=expected_params,
                priority=priority,
                source=source,
            )
            if existing.status == "queued":
                if before_publish is not None:
                    before_publish(existing)
                return self._dispatch_queued_task(existing)
            return existing

    @staticmethod
    def _validate_idempotent_task(
        existing: TaskRecord,
        *,
        operation_id: str,
        account_id: str,
        playbook: str,
        params: dict[str, Any],
        priority: int,
        source: str,
    ) -> None:
        if (
            existing.operation_id != operation_id
            or existing.account_id != account_id
            or existing.playbook != playbook
            or existing.params != params
            or existing.priority != priority
            or existing.source != source
        ):
            raise DuplicateTaskError(existing.task_id)

    def scan_timeouts(
        self,
        *,
        now: datetime | None = None,
        _after_lock_acquired: Callable[[], None] | None = None,
    ) -> list[TaskRecord]:
        scan_time = now or datetime.now(timezone.utc)
        settings = self.timeout_settings
        with psycopg.connect(self.database_url) as connection:
            acquired = connection.execute(
                "SELECT pg_try_advisory_xact_lock(hashtext(%s))",
                ("loanagent-task-timeout-scan",),
            ).fetchone()[0]
            if not acquired:
                return []
            if _after_lock_acquired is not None:
                _after_lock_acquired()
            rows = connection.execute(
                _TIMEOUT_SCAN_SQL,
                _timeout_scan_values(scan_time, settings),
            ).fetchall()
        return [_task_from_row(row) for row in rows]

    async def scan_timeouts_async(
        self,
        *,
        now: datetime | None = None,
        _after_lock_acquired: Callable[[], None] | None = None,
    ) -> list[TaskRecord]:
        scan_time = now or datetime.now(timezone.utc)
        async with await psycopg.AsyncConnection.connect(self.database_url) as connection:
            cursor = await connection.execute(
                "SELECT pg_try_advisory_xact_lock(hashtext(%s))",
                ("loanagent-task-timeout-scan",),
            )
            acquired = (await cursor.fetchone())[0]
            if not acquired:
                return []
            if _after_lock_acquired is not None:
                _after_lock_acquired()
            cursor = await connection.execute(
                _TIMEOUT_SCAN_SQL,
                _timeout_scan_values(scan_time, self.timeout_settings),
            )
            rows = await cursor.fetchall()
        return [_task_from_row(row) for row in rows]

    def pending_commands_for_device(self, device_id: str) -> list[dict[str, Any]]:
        """HTTP fallback for devices that cannot reach the MQTT broker (port filters).

        Returns accepted commands without flipping them to executing. Only the device
        event `executing` may advance that checkpoint — poll delivery must not fake it,
        or ops shows executing while the phone never ran (and finals can get stuck).
        """
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                SELECT task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                FROM tasks
                WHERE device_id = %s AND status = 'accepted'
                ORDER BY created_at, task_id
                LIMIT 20
                """,
                (device_id,),
            ).fetchall()
        return [task_command_envelope(_task_from_row(row)) for row in rows]

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
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                FROM tasks
                {where}
                ORDER BY created_at DESC, task_id DESC
                """,
                values,
            ).fetchall()
        return [_task_from_row(row) for row in rows]

    def get(self, task_id: str) -> TaskRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                FROM tasks
                WHERE task_id = %s
                """,
                (task_id,),
            ).fetchone()
        if row is None:
            raise TaskNotFoundError(task_id)
        return _task_from_row(row)

    def cancel(
        self,
        task_id: str,
        *,
        error_code: str = "OPERATOR_CANCELLED",
    ) -> TaskRecord:
        validate_task_error_code(error_code)
        task = self.get(task_id)
        if task.status in {
            "succeeded",
            "failed",
            "cancelled",
            "unknown",
            "reconcile_required",
            "effect_committed",
            "reported",
        }:
            raise TaskAlreadyTerminalError(task_id)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                UPDATE tasks
                SET status = 'cancelled',
                    error_code = %s,
                    terminal_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = %s
                  AND status IN ('queued', 'accepted', 'executing')
                RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                """,
                (error_code, task_id),
            ).fetchone()
        if row is None:
            raise TaskAlreadyTerminalError(task_id)
        return _task_from_row(row)

    def cancel_open_for_device(
        self,
        device_id: str,
        *,
        error_code: str = "DEVICE_OFFLINE_CANCELLED",
    ) -> list[TaskRecord]:
        validate_task_error_code(error_code)
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(
                """
                UPDATE tasks
                SET status = 'cancelled',
                    error_code = %s,
                    terminal_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE device_id = %s
                  AND status IN ('queued', 'accepted', 'executing')
                RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                """,
                (error_code, device_id),
            ).fetchall()
        return [_task_from_row(row) for row in rows]

    def mark_from_event(
        self,
        *,
        device_id: str,
        task_id: str,
        status: str,
        error_code: str | None = None,
        result_payload: dict[str, Any] | None = None,
    ) -> TaskRecord:
        if status not in EVENT_TASK_STATUSES:
            raise ValueError(f"unsupported event status: {status}")
        validate_task_error_code(error_code)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                FROM tasks
                WHERE task_id = %s AND device_id = %s
                FOR UPDATE
                """,
                (task_id, device_id),
            ).fetchone()
            if row is None:
                raise TaskNotFoundError(task_id)
            current = _task_from_row(row)
            validated_result_payload = validate_task_result_payload(
                result_payload,
                playbook=current.playbook,
                max_bytes=self.result_payload_settings.max_bytes,
            )
            normalized_status, normalized_error = _normalize_event_result(
                current,
                status=status,
                error_code=error_code,
            )
            if current.status in TERMINAL_TASK_STATUSES:
                if status in _INTERMEDIATE_STATUS_RANK:
                    return current
                if current.status == normalized_status:
                    if normalized_status == "succeeded" and validated_result_payload is not None:
                        connection.execute(
                            """
                            UPDATE tasks
                            SET terminal_result_payload = %s
                            WHERE task_id = %s
                              AND result_processed_at IS NULL
                            """,
                            (Jsonb(validated_result_payload), task_id),
                        )
                    updated_task = current
                else:
                    raise TaskAlreadyTerminalError(task_id)
            else:
                if status in _INTERMEDIATE_STATUS_RANK:
                    if (
                        _INTERMEDIATE_STATUS_RANK[status]
                        <= _INTERMEDIATE_STATUS_RANK[current.status]
                    ):
                        return current

                effect_committed = current.effect_committed or status in {
                    "effect_committed",
                    "reported",
                    "succeeded",
                }
                reconcile_required = normalized_status == "reconcile_required"
                accepted_checkpoint = status == "accepted"
                executing_checkpoint = status == "executing"
                effect_checkpoint = status in {"effect_committed", "reported", "succeeded"}
                reported_checkpoint = status in {
                    "reported",
                    "succeeded",
                    "failed",
                    "unknown",
                }
                terminal_checkpoint = normalized_status in TERMINAL_TASK_STATUSES
                persist_result = (
                    normalized_status == "succeeded" and validated_result_payload is not None
                )
                updated = connection.execute(
                    """
                    UPDATE tasks
                    SET status = %s,
                        effect_committed = %s,
                        reconcile_required = %s,
                        error_code = %s,
                        accepted_at = CASE
                            WHEN %s THEN COALESCE(accepted_at, CURRENT_TIMESTAMP)
                            ELSE accepted_at
                        END,
                        executing_at = CASE
                            WHEN %s THEN COALESCE(executing_at, CURRENT_TIMESTAMP)
                            ELSE executing_at
                        END,
                        effect_committed_at = CASE
                            WHEN %s THEN COALESCE(effect_committed_at, CURRENT_TIMESTAMP)
                            ELSE effect_committed_at
                        END,
                        reported_at = CASE
                            WHEN %s THEN COALESCE(reported_at, CURRENT_TIMESTAMP)
                            ELSE reported_at
                        END,
                        terminal_at = CASE
                            WHEN %s THEN COALESCE(terminal_at, CURRENT_TIMESTAMP)
                            ELSE terminal_at
                        END,
                        terminal_result_payload = CASE
                            WHEN %s THEN %s
                            ELSE terminal_result_payload
                        END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE task_id = %s
                    RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                        effect_class, effect_committed, status, reconcile_required, priority,
                        timeout_sec, source, error_code, created_at, updated_at,
                        accepted_at, executing_at, effect_committed_at, reported_at,
                        terminal_at, timeout_phase
                    """,
                    (
                        normalized_status,
                        effect_committed,
                        reconcile_required,
                        normalized_error,
                        accepted_checkpoint,
                        executing_checkpoint,
                        effect_checkpoint,
                        reported_checkpoint,
                        terminal_checkpoint,
                        persist_result,
                        (
                            Jsonb(validated_result_payload)
                            if validated_result_payload is not None
                            else None
                        ),
                        task_id,
                    ),
                ).fetchone()
                updated_task = _task_from_row(updated)
        if updated_task.status in TERMINAL_TASK_STATUSES:
            self._process_terminal_postprocessing(updated_task)
        return updated_task

    def _process_terminal_postprocessing(self, task: TaskRecord) -> None:
        self._process_engagement_postprocessing(task)
        self._process_result_postprocessing(task)

    def _process_engagement_postprocessing(self, task: TaskRecord) -> None:
        with psycopg.connect(self.database_url) as connection:
            connection.execute(
                "SELECT pg_advisory_xact_lock(hashtext(%s), hashtext(%s))",
                ("loanagent-task-engagement", task.task_id),
            )
            state = connection.execute(
                """
                SELECT engagement_processed_at, status, error_code
                FROM tasks
                WHERE task_id = %s
                """,
                (task.task_id,),
            ).fetchone()
            if state is None:
                raise TaskNotFoundError(task.task_id)
            if state[0] is not None:
                return
            self._notify_engagement(
                task,
                status=state[1],
                error_code=state[2],
            )
            connection.execute(
                """
                UPDATE tasks
                SET engagement_processed_at = CURRENT_TIMESTAMP
                WHERE task_id = %s
                  AND engagement_processed_at IS NULL
                """,
                (task.task_id,),
            )

    def _process_result_postprocessing(self, task: TaskRecord) -> None:
        with psycopg.connect(self.database_url) as connection:
            connection.execute(
                "SELECT pg_advisory_xact_lock(hashtext(%s), hashtext(%s))",
                ("loanagent-task-result", task.task_id),
            )
            state = connection.execute(
                """
                SELECT result_processed_at, terminal_result_payload, status
                FROM tasks
                WHERE task_id = %s
                """,
                (task.task_id,),
            ).fetchone()
            if state is None:
                raise TaskNotFoundError(task.task_id)
            result_processed_at, persisted_payload, persisted_status = state
            if (
                result_processed_at is not None
                or persisted_payload is None
                or persisted_status != "succeeded"
            ):
                return
            validated_payload = validate_task_result_payload(
                persisted_payload,
                playbook=task.playbook,
                max_bytes=self.result_payload_settings.max_bytes,
            )
            if validated_payload is None:
                return
            self._ingest_result_payload(task, validated_payload)
            connection.execute(
                """
                UPDATE tasks
                SET result_processed_at = CURRENT_TIMESTAMP,
                    terminal_result_payload = NULL
                WHERE task_id = %s
                  AND result_processed_at IS NULL
                """,
                (task.task_id,),
            )

    def _notify_engagement(
        self,
        task: TaskRecord,
        *,
        status: str,
        error_code: str | None,
    ) -> None:
        engagement = self.engagement_service
        if engagement is None:
            # Lazy import avoids cycles when EngagementService is constructed later.
            from loanagent.engagement import EngagementService

            engagement = EngagementService(self.database_url, self)
        engagement.on_task_event(task, status=status, error_code=error_code)

    def _ingest_result_payload(
        self,
        task: TaskRecord,
        result_payload: Mapping[str, Any],
    ) -> None:
        kind = result_payload.get("kind")
        if kind == "comments":
            from loanagent.notes import NotesService

            NotesService(self.database_url, self).upsert_comments_from_payload(
                account_id=task.account_id,
                source_task_id=task.task_id,
                payload=result_payload,
            )
            return
        if kind == "notes":
            from loanagent.notes import NotesService

            NotesService(self.database_url, self).replace_notes_from_payload(
                account_id=task.account_id,
                payload=result_payload,
            )
            return
        if kind == "inbox":
            from loanagent.inbox import InboxService

            threads = list(result_payload.get("threads") or [])
            InboxService(self.database_url, self).ingest(task.account_id, threads)
            return
        if kind == "thread":
            from loanagent.inbox import InboxService, InboxThreadNotFoundError

            inbox = InboxService(self.database_url, self)
            params = task.params if isinstance(task.params, Mapping) else {}
            thread_id = result_payload.get("thread_id") or params.get("thread_id")
            messages = list(result_payload.get("messages") or result_payload.get("items") or [])
            if thread_id and messages:
                try:
                    inbox.add_messages(str(thread_id), messages)
                except InboxThreadNotFoundError:
                    # A missing thread must stay observable and retryable, never a silent
                    # drop of device-reported messages.
                    self._record_alert(
                        kind="inbox_thread_missing_on_ingest",
                        message=(
                            f"inbox result for task {task.task_id} referenced missing "
                            f"thread {thread_id}; messages were not ingested"
                        ),
                        ref_id=task.task_id,
                    )
                    raise
                return
            threads = list(result_payload.get("threads") or [])
            if threads:
                inbox.ingest(task.account_id, threads)
            return
        if kind == "publish":
            from loanagent.notes import NotesService

            NotesService(self.database_url, self).create_from_publish_event(
                task,
                result_payload,
            )

    def _record_alert(self, *, kind: str, message: str, ref_id: str | None) -> None:
        """Idempotent alert insert so retries of the same failure do not spam ops."""
        alert_id = str(uuid5(NAMESPACE_URL, f"loanagent:task-alert:{kind}:{ref_id}"))
        with psycopg.connect(self.database_url) as connection:
            connection.execute(
                """
                INSERT INTO alerts (alert_id, kind, message, ref_id)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (alert_id) DO NOTHING
                """,
                (alert_id, kind, message, ref_id),
            )

    def _resolve_dispatch_target(self, account_id: str, playbook: str) -> str:
        DeviceRepository(self.database_url).mark_stale_offline()
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT accounts.role, accounts.device_id, accounts.status,
                    accounts.network_policy, accounts.daily_publish_quota,
                    devices.online, devices.a11y_bound, devices.wifi_connected
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
            network_policy = row[3]
            daily_publish_quota = row[4]
            device_online = row[5]
            a11y_bound = row[6]
            wifi_connected = row[7]
            if not playbook_allowed_for_role(role, playbook):
                raise PlaybookForbiddenError(playbook)
            if account_status != "active":
                raise TaskAccountUnavailableError(account_id)
            if device_id is None or device_online is not True:
                raise TaskDeviceUnavailableError(account_id)
            if a11y_bound is not True:
                raise TaskAccessibilityDownError(device_id)
            # Matches design doc §9: wifi on a cellular_only account pauses side effects
            # (readonly/idempotent playbooks like ensure_app_ready stay on the troubleshooting
            # whitelist so operators can still recover the device).
            if (
                effect_class_for_playbook(playbook) == "non_idempotent"
                and network_policy == "cellular_only"
                and wifi_connected is True
            ):
                raise TaskNetworkPolicyViolationError(account_id)
            if playbook_base(playbook) == "publish_note":
                published_today = connection.execute(
                    """
                    SELECT count(*)
                    FROM tasks
                    WHERE account_id = %s
                      AND playbook LIKE %s
                      AND status = 'succeeded'
                      AND created_at >= date_trunc('day', now())
                    """,
                    (account_id, "publish_note@%"),
                ).fetchone()[0]
                if published_today >= daily_publish_quota:
                    raise TaskPublishQuotaExceededError(account_id)
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
                        timeout_sec, source, error_code, created_at, updated_at,
                        accepted_at, executing_at, effect_committed_at, reported_at,
                        terminal_at, timeout_phase
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

    def _claim_dispatch_attempt(
        self,
        task_id: str,
    ) -> tuple[TaskRecord, bool]:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                UPDATE tasks
                SET dispatch_started_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = %s
                  AND status = 'queued'
                  AND dispatch_started_at IS NULL
                RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                """,
                (task_id,),
            ).fetchone()
        if row is not None:
            return _task_from_row(row), True
        return self.get(task_id), False

    def _mark_dispatch_unknown(
        self,
        queued: TaskRecord,
    ) -> tuple[TaskRecord, bool]:
        status = "reconcile_required" if queued.effect_class == "non_idempotent" else "failed"
        reconcile_required = status == "reconcile_required"
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                UPDATE tasks
                SET status = %s,
                    reconcile_required = %s,
                    error_code = 'EFFECT_UNKNOWN',
                    terminal_at = COALESCE(terminal_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = %s
                  AND status = 'queued'
                RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                """,
                (status, reconcile_required, queued.task_id),
            ).fetchone()
        if row is not None:
            return _task_from_row(row), True
        return self.get(queued.task_id), False

    def _update_status(
        self,
        task_id: str,
        *,
        status: str,
        expected_status: str | None = None,
    ) -> tuple[TaskRecord, bool]:
        expected_clause = "AND status = %s" if expected_status is not None else ""
        values: tuple[str, ...] = (
            status,
            status,
            status,
            status,
            task_id,
        )
        if expected_status is not None:
            values += (expected_status,)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                f"""
                UPDATE tasks
                SET status = %s,
                    accepted_at = CASE
                        WHEN %s = 'accepted' THEN COALESCE(accepted_at, CURRENT_TIMESTAMP)
                        ELSE accepted_at
                    END,
                    executing_at = CASE
                        WHEN %s = 'executing' THEN COALESCE(executing_at, CURRENT_TIMESTAMP)
                        ELSE executing_at
                    END,
                    terminal_at = CASE
                        WHEN %s IN (
                            'succeeded', 'failed', 'cancelled', 'unknown',
                            'reconcile_required'
                        ) THEN COALESCE(terminal_at, CURRENT_TIMESTAMP)
                        ELSE terminal_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE task_id = %s
                  {expected_clause}
                RETURNING task_id, operation_id, device_id, account_id, playbook, params,
                    effect_class, effect_committed, status, reconcile_required, priority,
                    timeout_sec, source, error_code, created_at, updated_at,
                    accepted_at, executing_at, effect_committed_at, reported_at,
                    terminal_at, timeout_phase
                """,
                values,
            ).fetchone()
        if row is not None:
            return _task_from_row(row), True
        if expected_status is None:
            raise TaskNotFoundError(task_id)
        return self.get(task_id), False


def _timeout_scan_values(
    scan_time: datetime,
    settings: TaskTimeoutSettings,
) -> tuple[datetime | int | str, ...]:
    return (
        scan_time,
        settings.queue_timeout_sec,
        scan_time,
        settings.queue_timeout_sec,
        scan_time,
        settings.execution_timeout_sec,
        scan_time,
        settings.effect_report_grace_sec,
        scan_time,
        settings.effect_report_grace_sec,
        validate_task_error_code("TIMEOUT"),
        scan_time,
        scan_time,
    )


async def run_task_timeout_scanner(
    service: Any,
    *,
    interval_sec: float,
    stop_event: Any | None = None,
) -> None:
    while stop_event is None or not stop_event.is_set():
        try:
            from loanagent.devices import DeviceRepository

            stale_ids = DeviceRepository(service.database_url).mark_stale_offline()
            for device_id in stale_ids:
                service.cancel_open_for_device(device_id)
            await service.scan_timeouts_async()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("task timeout scan failed")

        if stop_event is None:
            await asyncio.sleep(interval_sec)
            continue
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=interval_sec)
        except TimeoutError:
            pass


def _normalize_event_result(
    task: TaskRecord,
    *,
    status: str,
    error_code: str | None,
) -> tuple[str, str | None]:
    if status == "unknown":
        if task.effect_class == "non_idempotent":
            return "reconcile_required", error_code or "EFFECT_UNKNOWN"
        return "failed", error_code or "EFFECT_UNKNOWN"
    if status == "failed" and task.effect_class == "non_idempotent":
        if task.effect_committed or error_code == "EFFECT_UNKNOWN":
            return "reconcile_required", error_code or "EFFECT_UNKNOWN"
    return status, error_code


def effect_class_for_playbook(playbook: str) -> str:
    base = playbook_base(playbook)
    if base in READONLY_PLAYBOOKS:
        return "readonly"
    if base in NON_IDEMPOTENT_PLAYBOOKS:
        return "non_idempotent"
    return "idempotent"


def timeout_sec_for_playbook(playbook: str, default: int) -> int:
    base = playbook_base(playbook)
    return PLAYBOOK_TIMEOUT_SEC.get(base, default)


def task_command_envelope(task: TaskRecord) -> dict[str, Any]:
    return {
        "schema_version": TASK_SCHEMA_VERSION,
        "task_id": task.task_id,
        "operation_id": task.operation_id,
        "account_id": task.account_id,
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


def serialize_task_record(task: TaskRecord) -> dict[str, Any]:
    return {
        "schema_id": TASK_RECORD_SCHEMA_ID,
        "schema_version": TASK_RECORD_SCHEMA_VERSION,
        **asdict(task),
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
        accepted_at=row[16],
        executing_at=row[17],
        effect_committed_at=row[18],
        reported_at=row[19],
        terminal_at=row[20],
        timeout_phase=row[21],
    )

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
from threading import Event
from uuid import uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient
from fastapi.encoders import jsonable_encoder
from jsonschema import Draft202012Validator
from psycopg import sql

from loanagent import db, tasks
from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.main import app
from loanagent.roles import AccountRole


DATABASE_URL = os.environ["DATABASE_URL"]
SCHEMA_ROOT = Path(__file__).resolve().parents[2] / "schemas"


def task_record_validator() -> Draft202012Validator:
    schema = json.loads((SCHEMA_ROOT / "task-record.schema.json").read_text())
    return Draft202012Validator(schema)


@pytest.fixture(autouse=True)
def clean_previous_timeout_test_tasks() -> None:
    with psycopg.connect(DATABASE_URL) as connection:
        tasks_exists = connection.execute("SELECT to_regclass('tasks')").fetchone()[0]
        if tasks_exists is not None:
            connection.execute("DELETE FROM tasks WHERE task_id LIKE 'hardening-timeout-%'")


class RecordingMqttBus:
    def __init__(self) -> None:
        self.published: list[tuple[str, dict]] = []

    def publish(self, topic: str, payload: dict) -> None:
        self.published.append((topic, payload))


def unique_id(prefix: str) -> str:
    return f"{prefix}-{uuid4()}"


def create_bound_account(
    *,
    role: AccountRole = AccountRole.PUBLISHER_MAIN,
) -> tuple[str, str]:
    device_id = unique_id("hardening-device")
    account_id = unique_id("hardening-account")
    devices = DeviceRepository(DATABASE_URL)
    devices.migrate()
    devices.heartbeat(
        device_id=device_id,
        agent_version="0.4.0",
        a11y_bound=True,
        cellular_ok=True,
    )
    AccountRepository(DATABASE_URL).create(
        account_id=account_id,
        role=role,
        device_id=device_id,
    )
    return device_id, account_id


def test_fleet_migration_21_adds_precise_task_timestamps() -> None:
    db.migrate_fleet_schema(DATABASE_URL)

    with psycopg.connect(DATABASE_URL) as connection:
        columns = {
            row[0]
            for row in connection.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'tasks'
                """
            ).fetchall()
        }
        version = connection.execute(
            "SELECT max(version) FROM loanagent_schema_migrations"
        ).fetchone()[0]

    assert db.FLEET_SCHEMA_VERSION == 21
    assert version >= 21
    assert {
        "accepted_at",
        "executing_at",
        "effect_committed_at",
        "reported_at",
        "terminal_at",
        "timeout_phase",
        "dispatch_started_at",
    } <= columns


def test_timeout_settings_use_approved_global_defaults() -> None:
    settings_type = getattr(tasks, "TaskTimeoutSettings", None)
    assert settings_type is not None

    settings = settings_type.from_env({})

    assert settings.queue_timeout_sec == 300
    assert settings.execution_timeout_sec == 900
    assert settings.effect_report_grace_sec == 60
    assert settings.scan_interval_sec == 30


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("TASK_QUEUE_TIMEOUT_SEC", "0"),
        ("TASK_EXECUTION_TIMEOUT_SEC", "0"),
        ("TASK_EFFECT_REPORT_GRACE_SEC", "-1"),
        ("TASK_TIMEOUT_SCAN_INTERVAL_SEC", "0"),
        ("TASK_QUEUE_TIMEOUT_SEC", "not-an-int"),
        ("TASK_EXECUTION_TIMEOUT_SEC", "604801"),
    ],
)
def test_timeout_settings_reject_invalid_global_values(name: str, value: str) -> None:
    settings_type = getattr(tasks, "TaskTimeoutSettings", None)
    assert settings_type is not None

    with pytest.raises(ValueError, match=name):
        settings_type.from_env({name: value})


def test_result_payload_settings_use_one_mib_default() -> None:
    settings_type = getattr(tasks, "TaskResultPayloadSettings", None)
    assert settings_type is not None

    settings = settings_type.from_env({})

    assert settings.max_bytes == 1024 * 1024


@pytest.mark.parametrize(
    "value",
    ["not-an-int", "1023", str(16 * 1024 * 1024 + 1)],
)
def test_result_payload_settings_reject_invalid_bounds(value: str) -> None:
    settings_type = getattr(tasks, "TaskResultPayloadSettings", None)
    assert settings_type is not None

    with pytest.raises(ValueError, match="TASK_RESULT_PAYLOAD_MAX_BYTES"):
        settings_type.from_env({"TASK_RESULT_PAYLOAD_MAX_BYTES": value})


def test_task_record_schema_accepts_precise_server_timestamps_and_timeout_phase() -> None:
    schema = json.loads((SCHEMA_ROOT / "task-record.schema.json").read_text())
    validator = Draft202012Validator(schema)
    task = {
        "schema_id": "task-record",
        "schema_version": "1.0",
        "task_id": "task-01",
        "operation_id": "operation-01",
        "device_id": "device-01",
        "account_id": "account-01",
        "playbook": "ensure_app_ready@1.0",
        "params": {},
        "effect_class": "readonly",
        "effect_committed": False,
        "status": "failed",
        "reconcile_required": False,
        "priority": 100,
        "timeout_sec": 900,
        "source": "manual",
        "error_code": "TIMEOUT",
        "created_at": "2026-07-14T00:00:00Z",
        "updated_at": "2026-07-14T00:05:01Z",
        "accepted_at": "2026-07-14T00:00:00Z",
        "executing_at": None,
        "effect_committed_at": None,
        "reported_at": None,
        "terminal_at": "2026-07-14T00:05:01Z",
        "timeout_phase": "queued",
    }

    validator.validate(task)


def test_task_api_response_conforms_to_persisted_record_schema() -> None:
    _, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")

    with TestClient(app) as client:
        client.app.state.task_service = service
        response = client.post(
            "/api/v1/tasks",
            headers={"Authorization": f"Bearer {ops_token}"},
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "task_id": unique_id("hardening-schema"),
            },
        )

    assert response.status_code == 200
    task_record_validator().validate(response.json())


def test_all_task_record_routes_use_the_canonical_serializer() -> None:
    _, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")
    headers = {"Authorization": f"Bearer {ops_token}"}
    task_id = unique_id("hardening-schema")

    class ReturningNotesService:
        task_service: tasks.TaskService

        def sync_notes(self, requested_account_id: str) -> tasks.TaskRecord:
            assert requested_account_id == account_id
            return service.get(task_id)

    class ReturningInboxService:
        task_service: tasks.TaskService

        def sync(self, requested_account_id: str) -> tasks.TaskRecord:
            assert requested_account_id == account_id
            return service.get(task_id)

    class ReturningScheduleRepository:
        task_service: tasks.TaskService

        def publish_immediate(self, **kwargs) -> tasks.TaskRecord:
            assert kwargs["account_id"] == account_id
            return service.get(task_id)

    with TestClient(app) as client:
        client.app.state.task_service = service
        client.app.state.notes_service = ReturningNotesService()
        client.app.state.inbox_service = ReturningInboxService()
        client.app.state.schedule_repository = ReturningScheduleRepository()
        created = client.post(
            "/api/v1/tasks",
            headers=headers,
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "task_id": task_id,
            },
        )
        listed = client.get(
            "/api/v1/tasks",
            headers=headers,
            params={"account_id": account_id},
        )
        fetched = client.get(f"/api/v1/tasks/{task_id}", headers=headers)
        cancelled = client.post(f"/api/v1/tasks/{task_id}/cancel", headers=headers)
        notes = client.post(
            "/api/v1/notes/sync",
            headers=headers,
            json={"account_id": account_id},
        )
        inbox = client.post(
            "/api/v1/inbox/sync",
            headers=headers,
            json={"account_id": account_id},
        )
        content = client.post(
            "/api/v1/publish/immediate",
            headers=headers,
            json={"account_id": account_id, "content_id": "content-01"},
        )

    payloads = [
        created.json(),
        listed.json()[0],
        fetched.json(),
        cancelled.json(),
        notes.json(),
        inbox.json(),
        content.json(),
    ]
    validator = task_record_validator()
    for payload in payloads:
        validator.validate(payload)
        assert payload["schema_id"] == "task-record"
        assert payload["schema_version"] == "1.0"
    assert cancelled.json()["error_code"] == "OPERATOR_CANCELLED"


def test_device_and_cancel_error_codes_serialize_as_task_records() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")
    nav_task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-error-code"),
    )

    with TestClient(app) as client:
        client.app.state.task_service = service
        nav_response = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers={"Authorization": f"Bearer {ops_token}"},
            json={
                "task_id": nav_task.task_id,
                "status": "failed",
                "error_code": "NAV_MISSING_HINT",
            },
        )

    offline_task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-error-code"),
    )
    cancelled = service.cancel_open_for_device(device_id)
    offline_record = next(task for task in cancelled if task.task_id == offline_task.task_id)
    offline_payload = jsonable_encoder(tasks.serialize_task_record(offline_record))

    assert nav_response.status_code == 200
    assert nav_response.json()["error_code"] == "NAV_MISSING_HINT"
    assert offline_payload["error_code"] == "DEVICE_OFFLINE_CANCELLED"
    task_record_validator().validate(nav_response.json())
    task_record_validator().validate(offline_payload)


INVALID_ERROR_CODES = [
    "NAV-MISSING",
    "../../X",
    "<SCRIPT>",
    "nav_missing_hint",
    "",
    "A",
    "A" * 65,
]


@pytest.mark.parametrize("error_code", INVALID_ERROR_CODES)
def test_device_event_api_rejects_invalid_error_code_without_update(
    error_code: str,
) -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-error-code"),
    )
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")

    with TestClient(app) as client:
        client.app.state.task_service = service
        response = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers={"Authorization": f"Bearer {ops_token}"},
            json={
                "task_id": task.task_id,
                "status": "failed",
                "error_code": error_code,
            },
        )

    assert response.status_code == 422
    assert service.get(task.task_id) == task


@pytest.mark.parametrize("error_code", INVALID_ERROR_CODES)
def test_service_rejects_invalid_event_error_code_without_update(
    error_code: str,
) -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-error-code"),
    )
    error_type = getattr(tasks, "InvalidTaskErrorCodeError", None)
    assert error_type is not None

    with pytest.raises(error_type):
        service.mark_from_event(
            device_id=device_id,
            task_id=task.task_id,
            status="failed",
            error_code=error_code,
        )

    assert service.get(task.task_id) == task


def test_custom_cancel_entry_points_reject_invalid_error_code_without_update() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-error-code"),
    )
    error_type = getattr(tasks, "InvalidTaskErrorCodeError", None)
    assert error_type is not None

    with pytest.raises(error_type):
        service.cancel(task.task_id, error_code="../../X")
    with pytest.raises(error_type):
        service.cancel_open_for_device(device_id, error_code="<SCRIPT>")

    assert service.get(task.task_id) == task


@pytest.mark.parametrize(
    "error_code",
    [
        "AB",
        "A" * 64,
        "NAV_MISSING_HINT",
        "OPERATOR_CANCELLED",
        "DEVICE_OFFLINE_CANCELLED",
        "TIMEOUT",
        "EFFECT_UNKNOWN",
    ],
)
def test_shared_error_code_constraint_accepts_boundaries_and_current_codes(
    error_code: str,
) -> None:
    validator = getattr(tasks, "validate_task_error_code", None)
    assert callable(validator)
    assert validator(error_code) == error_code


def test_runtime_error_code_constraint_matches_task_record_schema() -> None:
    schema = json.loads((SCHEMA_ROOT / "task-record.schema.json").read_text())
    error_code = schema["$defs"]["errorCode"]

    assert tasks.TASK_ERROR_CODE_MIN_LENGTH == error_code["minLength"]
    assert tasks.TASK_ERROR_CODE_MAX_LENGTH == error_code["maxLength"]
    assert tasks.TASK_ERROR_CODE_PATTERN == error_code["pattern"]


def test_historical_null_device_task_lists_gets_and_validates() -> None:
    _, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-null-device"),
    )
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            "UPDATE tasks SET device_id = NULL WHERE task_id = %s",
            (task.task_id,),
        )
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")

    with TestClient(app) as client:
        client.app.state.task_service = service
        listed = client.get(
            "/api/v1/tasks",
            headers={"Authorization": f"Bearer {ops_token}"},
            params={"account_id": account_id},
        )
        fetched = client.get(
            f"/api/v1/tasks/{task.task_id}",
            headers={"Authorization": f"Bearer {ops_token}"},
        )

    assert listed.status_code == 200
    assert fetched.status_code == 200
    listed_payload = next(row for row in listed.json() if row["task_id"] == task.task_id)
    assert listed_payload["device_id"] is None
    assert fetched.json()["device_id"] is None
    task_record_validator().validate(listed_payload)
    task_record_validator().validate(fetched.json())


def test_event_schema_accepts_reported_progress_state() -> None:
    schema = json.loads((SCHEMA_ROOT / "event.schema.json").read_text())
    validator = Draft202012Validator(schema)
    event = {
        "schema_version": "1.0",
        "event_id": "event-01",
        "device_id": "device-01",
        "direction": "upstream",
        "type": "task_progress",
        "occurred_at": "2026-07-14T00:00:00Z",
        "sequence": 1,
        "nonce": "nonce-01",
        "payload": {
            "task_id": "task-01",
            "status": "reported",
            "progress": 100,
        },
        "signature": {
            "algorithm": "Ed25519",
            "key_id": "device-key-01",
            "value": "signature",
        },
    }

    validator.validate(event)


def test_create_dispatch_and_http_poll_does_not_fake_executing() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(
        DATABASE_URL,
        RecordingMqttBus(),
        timeout_settings=tasks.TaskTimeoutSettings.from_env({}),
    )

    accepted = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-task"),
    )
    commands = service.pending_commands_for_device(device_id)
    after_poll = service.get(accepted.task_id)

    assert accepted.status == "accepted"
    assert accepted.accepted_at is not None
    assert accepted.executing_at is None
    assert accepted.timeout_sec == 60
    assert commands[0]["status"] == "accepted"
    assert after_poll.status == "accepted"
    assert after_poll.executing_at is None

    executing = service.mark_from_event(
        device_id=device_id,
        task_id=accepted.task_id,
        status="executing",
    )
    assert executing.status == "executing"
    assert executing.executing_at is not None
    assert executing.accepted_at <= executing.executing_at


@pytest.mark.parametrize("event_status", ["executing", "succeeded"])
def test_publish_success_does_not_overwrite_reentrant_device_event(event_status: str) -> None:
    device_id, account_id = create_bound_account()

    class ReentrantEventBus:
        service: tasks.TaskService

        def publish(self, topic: str, payload: dict) -> None:
            self.service.mark_from_event(
                device_id=device_id,
                task_id=payload["task_id"],
                status=event_status,
            )

    bus = ReentrantEventBus()
    service = tasks.TaskService(DATABASE_URL, bus)
    bus.service = service

    result = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-race"),
    )

    assert result.status == event_status
    assert service.get(result.task_id).status == event_status


@pytest.mark.parametrize("event_status", ["executing", "succeeded"])
def test_publish_failure_returns_reentrant_non_idempotent_progress(
    event_status: str,
) -> None:
    device_id, account_id = create_bound_account()

    class ReentrantFailingBus:
        service: tasks.TaskService

        def publish(self, topic: str, payload: dict) -> None:
            self.service.mark_from_event(
                device_id=device_id,
                task_id=payload["task_id"],
                status=event_status,
            )
            raise RuntimeError("late publish acknowledgement failure")

    bus = ReentrantFailingBus()
    service = tasks.TaskService(DATABASE_URL, bus)
    bus.service = service
    task_id = unique_id("hardening-race")

    result = service.create_and_dispatch(
        account_id=account_id,
        playbook="publish_note@1.0",
        task_id=task_id,
    )

    assert result.status == event_status
    assert result.effect_class == "non_idempotent"
    assert service.get(task_id) == result


@pytest.mark.parametrize("event_status", ["executing", "succeeded"])
def test_publish_failure_after_device_progress_returns_200_without_retry_signal(
    event_status: str,
) -> None:
    device_id, account_id = create_bound_account()
    task_id = unique_id("hardening-race")

    class ReentrantFailingBus:
        service: tasks.TaskService

        def publish(self, topic: str, payload: dict) -> None:
            self.service.mark_from_event(
                device_id=device_id,
                task_id=payload["task_id"],
                status=event_status,
            )
            raise RuntimeError("late publish acknowledgement failure")

    bus = ReentrantFailingBus()
    service = tasks.TaskService(DATABASE_URL, bus)
    bus.service = service
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")

    with TestClient(app) as client:
        client.app.state.task_service = service
        response = client.post(
            "/api/v1/tasks",
            headers={"Authorization": f"Bearer {ops_token}"},
            json={
                "account_id": account_id,
                "playbook": "publish_note@1.0",
                "task_id": task_id,
            },
        )

    assert response.status_code == 200
    assert response.json()["task_id"] == task_id
    assert response.json()["status"] == event_status
    assert [task.task_id for task in service.list(account_id=account_id)] == [task_id]


@pytest.mark.parametrize(
    ("playbook", "expected_status", "expected_reconcile"),
    [
        ("publish_note@1.0", "reconcile_required", True),
        ("ensure_app_ready@1.0", "failed", False),
    ],
)
def test_publish_exception_without_device_progress_is_effect_aware_unknown(
    playbook: str,
    expected_status: str,
    expected_reconcile: bool,
) -> None:
    _device_id, account_id = create_bound_account()

    class RecordThenFailBus(RecordingMqttBus):
        def publish(self, topic: str, payload: dict) -> None:
            super().publish(topic, payload)
            raise RuntimeError("broker acknowledgement lost")

    error_type = getattr(tasks, "TaskDispatchAmbiguousError", None)
    assert error_type is not None
    bus = RecordThenFailBus()
    service = tasks.TaskService(DATABASE_URL, bus)
    task_id = unique_id("hardening-dispatch-unknown")

    with pytest.raises(error_type, match=task_id):
        service.create_and_dispatch(
            account_id=account_id,
            playbook=playbook,
            task_id=task_id,
        )

    persisted = service.get(task_id)
    assert len(bus.published) == 1
    assert persisted.status == expected_status
    assert persisted.reconcile_required is expected_reconcile
    assert persisted.error_code == "EFFECT_UNKNOWN"


def test_dispatching_reservation_is_reconciled_without_republish() -> None:
    _device_id, account_id = create_bound_account()
    task_id = unique_id("hardening-dispatching")
    operation_id = unique_id("hardening-operation")
    initial = tasks.TaskService(DATABASE_URL, RecordingMqttBus())

    def crash_after_reservation(_task: tasks.TaskRecord) -> None:
        raise RuntimeError("crash after reservation")

    with pytest.raises(RuntimeError, match="crash after reservation"):
        initial.create_and_dispatch_idempotent(
            account_id=account_id,
            playbook="publish_note@1.0",
            task_id=task_id,
            operation_id=operation_id,
            before_publish=crash_after_reservation,
        )

    with psycopg.connect(DATABASE_URL) as connection:
        marker = connection.execute(
            """
            UPDATE tasks
            SET dispatch_started_at = CURRENT_TIMESTAMP
            WHERE task_id = %s
            RETURNING dispatch_started_at
            """,
            (task_id,),
        ).fetchone()[0]
    assert marker is not None

    bus = RecordingMqttBus()
    restarted = tasks.TaskService(DATABASE_URL, bus)
    with pytest.raises(tasks.TaskDispatchAmbiguousError, match=task_id):
        restarted.create_and_dispatch_idempotent(
            account_id=account_id,
            playbook="publish_note@1.0",
            task_id=task_id,
            operation_id=operation_id,
        )

    persisted = restarted.get(task_id)
    assert bus.published == []
    assert persisted.status == "reconcile_required"
    assert persisted.error_code == "EFFECT_UNKNOWN"


def test_event_progression_writes_each_checkpoint_time() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="publish_note@1.0",
        task_id=unique_id("hardening-task"),
    )

    executing = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="executing",
    )
    committed = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="effect_committed",
    )
    reported = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="reported",
    )
    succeeded = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="succeeded",
    )

    assert executing.executing_at is not None
    assert committed.effect_committed is True
    assert committed.effect_committed_at is not None
    assert reported.reported_at is not None
    assert succeeded.terminal_at is not None
    assert succeeded.reconcile_required is False


def test_same_terminal_event_is_idempotent_and_conflict_is_rejected() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-task"),
    )

    first = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="succeeded",
    )
    repeated = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="succeeded",
    )

    assert repeated == first
    with pytest.raises(tasks.TaskAlreadyTerminalError):
        service.mark_from_event(
            device_id=device_id,
            task_id=task.task_id,
            status="failed",
            error_code="APP_CRASH",
        )
    assert service.get(task.task_id) == first


def test_unknown_result_kind_returns_422_without_terminalizing_task() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-result-invalid"),
    )
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")

    with TestClient(app) as client:
        client.app.state.task_service = service
        response = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers={"Authorization": f"Bearer {ops_token}"},
            json={
                "task_id": task.task_id,
                "status": "succeeded",
                "result_payload": {"kind": "unsupported"},
            },
        )

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "TASK_RESULT_PAYLOAD_INVALID"
    assert service.get(task.task_id).status == "accepted"
    with psycopg.connect(DATABASE_URL) as connection:
        persisted = connection.execute(
            """
            SELECT terminal_result_payload, result_processed_at
            FROM tasks
            WHERE task_id = %s
            """,
            (task.task_id,),
        ).fetchone()
    assert persisted == (None, None)


@pytest.mark.parametrize(
    ("playbook", "payload"),
    [
        (
            "read_comments@1.0",
            {
                "kind": "comments",
                "note_ref": "note-1",
                "items": [
                    {
                        "author_summary": "用户甲",
                        "body_summary": "求同款",
                        "locator_hint": "index:0",
                        "posted_at_text": "1小时前",
                        "reply_to_author": None,
                        "replies": [
                            {
                                "author_summary": "作者",
                                "body_summary": "谢谢",
                                "locator_hint": None,
                                "posted_at_text": None,
                                "reply_to_author": "用户甲",
                            }
                        ],
                    }
                ],
            },
        ),
        (
            "sync_notes@1.0",
            {
                "kind": "notes",
                "items": [
                    {
                        "title_summary": "笔记",
                        "like_count": 1,
                        "collect_count": None,
                        "read_count": 10,
                        "locator_hint": None,
                    }
                ],
            },
        ),
        (
            "inbox_sync@1.0",
            {
                "kind": "inbox",
                "threads": [
                    {
                        "title_summary": "客户甲",
                        "preview_summary": "在吗",
                        "unread": True,
                        "locator_hint": None,
                        "messages": [
                            {
                                "sender_summary": "客户甲",
                                "body_summary": "在吗",
                                "posted_at_text": None,
                            }
                        ],
                    }
                ],
            },
        ),
        (
            "inbox_open_thread@1.0",
            {
                "kind": "thread",
                "thread_id": "thread-1",
                "messages": [
                    {
                        "sender_summary": "客户甲",
                        "body_summary": "想了解方案",
                    }
                ],
            },
        ),
        (
            "publish_note@1.0",
            {
                "kind": "publish",
                "title_summary": "春日茶会",
                "note_ref": "note-ref-1",
            },
        ),
    ],
)
def test_result_payload_accepts_real_android_shape_for_playbook(
    playbook: str,
    payload: dict,
) -> None:
    validated = tasks.validate_task_result_payload(
        payload,
        playbook=playbook,
        max_bytes=1024 * 1024,
    )

    assert validated == payload


@pytest.mark.parametrize(
    ("playbook", "payload"),
    [
        (
            "ensure_app_ready@1.0",
            {"kind": "notes", "items": []},
        ),
        (
            "sync_notes@1.0",
            {"kind": "comments", "items": []},
        ),
        (
            "sync_notes@1.0",
            {"kind": [], "items": []},
        ),
        (
            "sync_notes@1.0",
            {
                "kind": "notes",
                "items": [
                    {
                        "title_summary": "笔记",
                        "raw_phone": "13800138000",
                    }
                ],
            },
        ),
        (
            "sync_notes@1.0",
            {
                "kind": "notes",
                "items": [{"title_summary": f"笔记-{index}"} for index in range(101)],
            },
        ),
        (
            "sync_notes@1.0",
            {
                "kind": "notes",
                "items": [{"title_summary": "x" * 513}],
            },
        ),
        (
            "sync_notes@1.0",
            {
                "kind": "notes",
                "items": [
                    {
                        "title_summary": "笔记",
                        "like_count": 2_147_483_648,
                    }
                ],
            },
        ),
        (
            "inbox_sync@1.0",
            {
                "kind": "inbox",
                "threads": [
                    {
                        "title_summary": "客户甲",
                        "unread": "yes",
                    }
                ],
            },
        ),
        (
            "read_comments@1.0",
            {"kind": "comments", "items": [{}]},
        ),
        (
            "read_comments@1.0",
            {
                "kind": "comments",
                "items": [
                    {
                        "author_summary": "用户甲",
                        "body_summary": "求同款",
                        "replies": [
                            {
                                "author_summary": "作者",
                                "body_summary": "谢谢",
                                "replies": [],
                            }
                        ],
                    }
                ],
            },
        ),
    ],
)
def test_invalid_bound_result_payload_never_terminalizes_or_persists(
    playbook: str,
    payload: dict,
) -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook=playbook,
        task_id=unique_id("hardening-result-bound"),
    )

    with pytest.raises(tasks.InvalidTaskResultPayloadError):
        service.mark_from_event(
            device_id=device_id,
            task_id=task.task_id,
            status="succeeded",
            result_payload=payload,
        )

    assert service.get(task.task_id).status == "accepted"
    with psycopg.connect(DATABASE_URL) as connection:
        persisted = connection.execute(
            """
            SELECT terminal_result_payload, result_processed_at
            FROM tasks
            WHERE task_id = %s
            """,
            (task.task_id,),
        ).fetchone()
    assert persisted == (None, None)


def test_invalid_result_structure_can_retry_with_valid_payload_and_cleans_it() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="sync_notes@1.0",
        task_id=unique_id("hardening-result-invalid"),
    )
    error_type = getattr(tasks, "InvalidTaskResultPayloadError", None)
    assert error_type is not None

    with pytest.raises(error_type, match="items"):
        service.mark_from_event(
            device_id=device_id,
            task_id=task.task_id,
            status="succeeded",
            result_payload={"kind": "notes", "items": {}},
        )
    assert service.get(task.task_id).status == "accepted"

    succeeded = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="succeeded",
        result_payload={"kind": "notes", "items": []},
    )
    with psycopg.connect(DATABASE_URL) as connection:
        persisted = connection.execute(
            """
            SELECT terminal_result_payload, result_processed_at
            FROM tasks
            WHERE task_id = %s
            """,
            (task.task_id,),
        ).fetchone()

    assert succeeded.status == "succeeded"
    assert persisted[0] is None
    assert persisted[1] is not None


def test_oversized_result_payload_is_domain_error_and_api_422() -> None:
    device_id, account_id = create_bound_account()
    settings_type = getattr(tasks, "TaskResultPayloadSettings", None)
    error_type = getattr(tasks, "InvalidTaskResultPayloadError", None)
    assert settings_type is not None
    assert error_type is not None
    service = tasks.TaskService(
        DATABASE_URL,
        RecordingMqttBus(),
        result_payload_settings=settings_type(max_bytes=1024),
    )
    direct = service.create_and_dispatch(
        account_id=account_id,
        playbook="sync_notes@1.0",
        task_id=unique_id("hardening-result-limit"),
    )
    oversized = {"kind": "notes", "items": [], "padding": "x" * 2048}

    with pytest.raises(error_type, match="1024 bytes"):
        service.mark_from_event(
            device_id=device_id,
            task_id=direct.task_id,
            status="succeeded",
            result_payload=oversized,
        )
    assert service.get(direct.task_id).status == "accepted"

    api_task = service.create_and_dispatch(
        account_id=account_id,
        playbook="sync_notes@1.0",
        task_id=unique_id("hardening-result-limit"),
    )
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")
    with TestClient(app) as client:
        client.app.state.task_service = service
        response = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers={"Authorization": f"Bearer {ops_token}"},
            json={
                "task_id": api_task.task_id,
                "status": "succeeded",
                "result_payload": oversized,
            },
        )

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "TASK_RESULT_PAYLOAD_INVALID"
    assert service.get(api_task.task_id).status == "accepted"


def test_terminal_result_ingestion_failure_retries_persisted_payload_once() -> None:
    device_id, account_id = create_bound_account()
    calls: list[dict] = []

    class FlakyResultTaskService(tasks.TaskService):
        def _ingest_result_payload(
            self,
            task: tasks.TaskRecord,
            result_payload: dict,
        ) -> None:
            calls.append(dict(result_payload))
            if len(calls) == 1:
                raise RuntimeError("result ingestion failed")

    task_id = unique_id("hardening-result-retry")
    service = FlakyResultTaskService(DATABASE_URL, RecordingMqttBus())
    service.create_and_dispatch(
        account_id=account_id,
        playbook="sync_notes@1.0",
        task_id=task_id,
    )
    payload = {"kind": "notes", "items": [{"title_summary": "persisted"}]}

    with pytest.raises(RuntimeError, match="result ingestion failed"):
        service.mark_from_event(
            device_id=device_id,
            task_id=task_id,
            status="succeeded",
            result_payload=payload,
        )
    assert service.get(task_id).status == "succeeded"
    with psycopg.connect(DATABASE_URL) as connection:
        pending = connection.execute(
            """
            SELECT terminal_result_payload, result_processed_at
            FROM tasks
            WHERE task_id = %s
            """,
            (task_id,),
        ).fetchone()
    assert pending == (payload, None)

    restarted = FlakyResultTaskService(DATABASE_URL, RecordingMqttBus())
    retried = restarted.mark_from_event(
        device_id=device_id,
        task_id=task_id,
        status="succeeded",
    )
    duplicate = restarted.mark_from_event(
        device_id=device_id,
        task_id=task_id,
        status="succeeded",
    )

    assert retried.status == "succeeded"
    assert duplicate == retried
    assert calls == [payload, payload]
    with psycopg.connect(DATABASE_URL) as connection:
        completed = connection.execute(
            """
            SELECT terminal_result_payload, result_processed_at
            FROM tasks
            WHERE task_id = %s
            """,
            (task_id,),
        ).fetchone()
    assert completed[0] is None
    assert completed[1] is not None


def test_terminal_engagement_failure_retries_once_after_restart() -> None:
    device_id, account_id = create_bound_account()
    calls: list[tuple[str, str, str | None]] = []

    class FlakyEngagement:
        def on_task_event(
            self,
            task: tasks.TaskRecord,
            *,
            status: str,
            error_code: str | None,
        ) -> None:
            calls.append((task.task_id, status, error_code))
            if len(calls) == 1:
                raise RuntimeError("engagement failed")

    task_id = unique_id("hardening-engagement-retry")
    engagement = FlakyEngagement()
    service = tasks.TaskService(
        DATABASE_URL,
        RecordingMqttBus(),
        engagement_service=engagement,
    )
    service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=task_id,
    )

    with pytest.raises(RuntimeError, match="engagement failed"):
        service.mark_from_event(
            device_id=device_id,
            task_id=task_id,
            status="failed",
            error_code="APP_CRASH",
        )
    assert service.get(task_id).status == "failed"

    restarted = tasks.TaskService(
        DATABASE_URL,
        RecordingMqttBus(),
        engagement_service=engagement,
    )
    retried = restarted.mark_from_event(
        device_id=device_id,
        task_id=task_id,
        status="failed",
        error_code="APP_CRASH",
    )
    duplicate = restarted.mark_from_event(
        device_id=device_id,
        task_id=task_id,
        status="failed",
        error_code="APP_CRASH",
    )

    assert duplicate == retried
    assert calls == [
        (task_id, "failed", "APP_CRASH"),
        (task_id, "failed", "APP_CRASH"),
    ]


def test_delayed_intermediate_event_after_later_checkpoint_is_idempotent() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="publish_note@1.0",
        task_id=unique_id("hardening-task"),
    )
    service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="executing",
    )
    committed = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="effect_committed",
    )

    delayed = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="executing",
    )

    assert delayed == committed


def test_delayed_intermediate_event_after_terminal_is_idempotent() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-task"),
    )
    succeeded = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="succeeded",
    )

    delayed = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="executing",
    )

    assert delayed == succeeded


def test_non_idempotent_unknown_result_requires_reconciliation() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="publish_note@1.0",
        task_id=unique_id("hardening-task"),
    )

    result = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="unknown",
    )

    assert result.status == "reconcile_required"
    assert result.reconcile_required is True
    assert result.error_code == "EFFECT_UNKNOWN"
    assert result.terminal_at is not None


def test_non_idempotent_failure_after_effect_commit_requires_reconciliation() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="publish_note@1.0",
        task_id=unique_id("hardening-task"),
    )
    service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="effect_committed",
    )

    result = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="failed",
        error_code="EFFECT_UNKNOWN",
    )

    assert result.status == "reconcile_required"
    assert result.reconcile_required is True
    assert result.effect_committed is True


def test_readonly_unknown_result_is_failed_without_reconciliation() -> None:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="ensure_app_ready@1.0",
        task_id=unique_id("hardening-task"),
    )

    result = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="unknown",
    )

    assert result.status == "failed"
    assert result.reconcile_required is False
    assert result.error_code == "EFFECT_UNKNOWN"


def test_event_rejects_device_mismatch_and_accepts_monotonic_progress() -> None:
    device_id, account_id = create_bound_account()
    other_device_id, _ = create_bound_account(role=AccountRole.ENGAGER)
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook="publish_note@1.0",
        task_id=unique_id("hardening-task"),
    )

    with pytest.raises(tasks.TaskNotFoundError):
        service.mark_from_event(
            device_id=other_device_id,
            task_id=task.task_id,
            status="executing",
        )

    reported = service.mark_from_event(
        device_id=device_id,
        task_id=task.task_id,
        status="reported",
    )
    assert reported.status == "reported"


def test_event_route_accepts_intermediate_state_and_rejects_terminal_conflict() -> None:
    device_id, account_id = create_bound_account()
    task_id = unique_id("hardening-task")
    ops_token = os.environ.setdefault("OPS_TOKEN", "dev-only-ops-token")
    headers = {"Authorization": f"Bearer {ops_token}"}

    with TestClient(app) as client:
        client.app.state.task_service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
        created = client.post(
            "/api/v1/tasks",
            headers=headers,
            json={
                "account_id": account_id,
                "playbook": "ensure_app_ready@1.0",
                "task_id": task_id,
            },
        )
        executing = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers=headers,
            json={"task_id": task_id, "status": "executing"},
        )
        succeeded = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers=headers,
            json={"task_id": task_id, "status": "succeeded"},
        )
        conflict = client.post(
            f"/api/v1/devices/{device_id}/events",
            headers=headers,
            json={"task_id": task_id, "status": "failed"},
        )

    assert created.status_code == 200
    assert executing.status_code == 200
    assert executing.json()["executing_at"] is not None
    assert succeeded.status_code == 200
    assert conflict.status_code == 409
    assert conflict.json()["detail"]["code"] == "TASK_ALREADY_TERMINAL"


def age_task_checkpoint(
    task_id: str,
    *,
    status: str,
    checkpoint: datetime,
) -> None:
    checkpoint_column = {
        "queued": "created_at",
        "accepted": "accepted_at",
        "executing": "executing_at",
        "effect_committed": "effect_committed_at",
        "reported": "reported_at",
    }[status]
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            """
            UPDATE tasks
            SET status = %s,
                accepted_at = CASE
                    WHEN %s <> 'queued' THEN COALESCE(accepted_at, %s)
                    ELSE accepted_at
                END,
                executing_at = CASE
                    WHEN %s IN ('executing', 'effect_committed', 'reported')
                        THEN COALESCE(executing_at, %s)
                    ELSE executing_at
                END,
                effect_committed = %s IN ('effect_committed', 'reported'),
                effect_committed_at = CASE
                    WHEN %s IN ('effect_committed', 'reported')
                        THEN COALESCE(effect_committed_at, %s)
                    ELSE effect_committed_at
                END,
                reported_at = CASE
                    WHEN %s = 'reported' THEN COALESCE(reported_at, %s)
                    ELSE reported_at
                END,
                updated_at = %s
            WHERE task_id = %s
            """,
            (
                status,
                status,
                checkpoint,
                status,
                checkpoint,
                status,
                status,
                checkpoint,
                status,
                checkpoint,
                checkpoint,
                task_id,
            ),
        )
        connection.execute(
            sql.SQL("UPDATE tasks SET {} = %s WHERE task_id = %s").format(
                sql.Identifier(checkpoint_column)
            ),
            (checkpoint, task_id),
        )


def create_aged_task(
    *,
    playbook: str,
    status: str,
    checkpoint: datetime,
) -> tuple[tasks.TaskService, tasks.TaskRecord]:
    device_id, account_id = create_bound_account()
    service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    task = service.create_and_dispatch(
        account_id=account_id,
        playbook=playbook,
        task_id=unique_id("hardening-timeout"),
    )
    if status == "executing":
        service.mark_from_event(device_id=device_id, task_id=task.task_id, status="executing")
    elif status in {"effect_committed", "reported"}:
        service.mark_from_event(device_id=device_id, task_id=task.task_id, status="executing")
        service.mark_from_event(
            device_id=device_id,
            task_id=task.task_id,
            status="effect_committed",
        )
        if status == "reported":
            service.mark_from_event(device_id=device_id, task_id=task.task_id, status="reported")
    age_task_checkpoint(task.task_id, status=status, checkpoint=checkpoint)
    if status == "queued":
        with psycopg.connect(DATABASE_URL) as connection:
            connection.execute(
                "UPDATE tasks SET dispatch_started_at = NULL WHERE task_id = %s",
                (task.task_id,),
            )
    return service, task


def scan_at(service: tasks.TaskService, now: datetime) -> list[tasks.TaskRecord]:
    scan = getattr(service, "scan_timeouts", None)
    assert callable(scan)
    return scan(now=now)


def test_scan_fails_accepted_task_after_global_queue_timeout() -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    service, task = create_aged_task(
        playbook="ensure_app_ready@1.0",
        status="accepted",
        checkpoint=now - timedelta(seconds=301),
    )

    scanned = scan_at(service, now)
    result = service.get(task.task_id)

    assert task.task_id in {row.task_id for row in scanned}
    assert result.status == "failed"
    assert result.error_code == "TIMEOUT"
    assert result.timeout_phase == "queued"
    assert result.terminal_at == now


def test_scan_uses_queued_updated_anchor_and_times_out_after_queue_window() -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    service, task = create_aged_task(
        playbook="ensure_app_ready@1.0",
        status="queued",
        checkpoint=now - timedelta(seconds=10_000),
    )
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            "UPDATE tasks SET updated_at = %s WHERE task_id = %s",
            (now - timedelta(seconds=100), task.task_id),
        )

    first_scan_ids = {row.task_id for row in scan_at(service, now)}
    assert task.task_id not in first_scan_ids

    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            "UPDATE tasks SET updated_at = %s WHERE task_id = %s",
            (now - timedelta(seconds=301), task.task_id),
        )
    second_scan_ids = {row.task_id for row in scan_at(service, now)}
    result = service.get(task.task_id)

    assert task.task_id in second_scan_ids
    assert result.status == "failed"
    assert result.timeout_phase == "queued"


def test_scan_reconciles_abandoned_non_idempotent_dispatch_attempt() -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    service, task = create_aged_task(
        playbook="publish_note@1.0",
        status="queued",
        checkpoint=now - timedelta(seconds=301),
    )
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            """
            UPDATE tasks
            SET dispatch_started_at = %s,
                updated_at = %s
            WHERE task_id = %s
            """,
            (now - timedelta(seconds=301), now - timedelta(seconds=301), task.task_id),
        )

    scan_at(service, now)
    result = service.get(task.task_id)

    assert result.status == "reconcile_required"
    assert result.reconcile_required is True
    assert result.timeout_phase == "execution"
    assert result.error_code == "EFFECT_UNKNOWN"


@pytest.mark.parametrize(
    ("playbook", "expected_status", "reconcile_required"),
    [
        ("ensure_app_ready@1.0", "failed", False),
        ("dismiss_interruptions@1.0", "failed", False),
        ("publish_note@1.0", "reconcile_required", True),
    ],
)
def test_scan_classifies_executing_timeout_by_effect_class(
    playbook: str,
    expected_status: str,
    reconcile_required: bool,
) -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    service, task = create_aged_task(
        playbook=playbook,
        status="executing",
        checkpoint=now - timedelta(seconds=901),
    )

    scan_at(service, now)
    result = service.get(task.task_id)

    assert result.status == expected_status
    assert result.reconcile_required is reconcile_required
    assert result.timeout_phase == "execution"
    assert result.error_code == "TIMEOUT"


@pytest.mark.parametrize("status", ["effect_committed", "reported"])
@pytest.mark.parametrize(
    ("playbook", "expected_status"),
    [
        ("dismiss_interruptions@1.0", "failed"),
        ("publish_note@1.0", "reconcile_required"),
    ],
)
def test_scan_applies_effect_report_grace(
    status: str,
    playbook: str,
    expected_status: str,
) -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    service, task = create_aged_task(
        playbook=playbook,
        status=status,
        checkpoint=now - timedelta(seconds=61),
    )

    scan_at(service, now)
    result = service.get(task.task_id)

    assert result.status == expected_status
    assert result.timeout_phase == "report"
    assert result.effect_committed is True
    assert result.terminal_at == now


@pytest.mark.parametrize("status", ["effect_committed", "reported"])
@pytest.mark.parametrize(
    "playbook",
    ["ensure_app_ready@1.0", "dismiss_interruptions@1.0"],
)
def test_effect_timeout_task_record_conforms_to_task_record_schema(
    status: str,
    playbook: str,
) -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    service, task = create_aged_task(
        playbook=playbook,
        status=status,
        checkpoint=now - timedelta(seconds=61),
    )

    scan_at(service, now)
    result = service.get(task.task_id)
    payload = jsonable_encoder(tasks.serialize_task_record(result))
    schema = json.loads((SCHEMA_ROOT / "task-record.schema.json").read_text())
    errors = list(Draft202012Validator(schema).iter_errors(payload))

    assert result.status == "failed"
    assert result.effect_committed is True
    assert errors == []


def test_scan_ignores_task_level_legacy_timeout_and_terminal_rows() -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    service, open_task = create_aged_task(
        playbook="ensure_app_ready@1.0",
        status="accepted",
        checkpoint=now - timedelta(seconds=100),
    )
    terminal_service, terminal_source = create_aged_task(
        playbook="ensure_app_ready@1.0",
        status="accepted",
        checkpoint=now - timedelta(seconds=10_000),
    )
    terminal_task = service.mark_from_event(
        device_id=terminal_source.device_id or "",
        task_id=terminal_source.task_id,
        status="failed",
        error_code="APP_CRASH",
    )
    with psycopg.connect(DATABASE_URL) as connection:
        connection.execute(
            "UPDATE tasks SET timeout_sec = 1 WHERE task_id = %s",
            (open_task.task_id,),
        )

    scanned_ids = {row.task_id for row in scan_at(service, now)}
    assert open_task.task_id not in scanned_ids
    assert terminal_source.task_id not in scanned_ids
    assert service.get(open_task.task_id).status == "accepted"
    assert terminal_service.get(terminal_source.task_id) == terminal_task


def test_concurrent_scan_is_atomic_and_restart_scan_is_idempotent() -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    first_service, task = create_aged_task(
        playbook="ensure_app_ready@1.0",
        status="accepted",
        checkpoint=now - timedelta(seconds=301),
    )
    second_service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(
            executor.map(
                lambda service: scan_at(service, now),
                [first_service, second_service],
            )
        )

    assert sum(task.task_id in {row.task_id for row in result} for result in results) == 1
    assert first_service.get(task.task_id).status == "failed"
    restarted_service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    assert task.task_id not in {row.task_id for row in scan_at(restarted_service, now)}


def test_overlapping_scan_is_excluded_and_async_cancellation_is_bounded() -> None:
    now = datetime(2026, 7, 14, 12, 0, tzinfo=timezone.utc)
    setup_service, task = create_aged_task(
        playbook="ensure_app_ready@1.0",
        status="accepted",
        checkpoint=now - timedelta(seconds=301),
    )
    lock_acquired = Event()

    class SignalingTaskService(tasks.TaskService):
        async def scan_timeouts_async(
            self,
            *,
            now: datetime | None = None,
        ) -> list[tasks.TaskRecord]:
            return await super().scan_timeouts_async(
                now=now,
                _after_lock_acquired=lock_acquired.set,
            )

    service = SignalingTaskService(DATABASE_URL, RecordingMqttBus())
    blocker = psycopg.connect(DATABASE_URL)
    blocker.execute(
        "SELECT task_id FROM tasks WHERE task_id = %s FOR UPDATE",
        (task.task_id,),
    )

    async def exercise_overlap_and_cancel() -> None:
        scanner = asyncio.create_task(tasks.run_task_timeout_scanner(service, interval_sec=3600))
        try:
            assert await asyncio.to_thread(lock_acquired.wait, 2)
            overlapping = tasks.TaskService(
                DATABASE_URL,
                RecordingMqttBus(),
            ).scan_timeouts(now=now)
            assert overlapping == []

            scanner.cancel()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(scanner, timeout=2)
            assert scanner.done()
            assert setup_service.get(task.task_id).status == "accepted"
        finally:
            blocker.rollback()
            if not scanner.done():
                scanner.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await scanner

    try:
        asyncio.run(exercise_overlap_and_cancel())
    finally:
        blocker.close()

    restarted_service = tasks.TaskService(DATABASE_URL, RecordingMqttBus())
    rescanned_ids = {row.task_id for row in restarted_service.scan_timeouts(now=now)}
    assert task.task_id in rescanned_ids
    assert setup_service.get(task.task_id).status == "failed"


def test_scanner_loop_survives_exception_and_stops_without_sleep(caplog) -> None:
    runner = getattr(tasks, "run_task_timeout_scanner", None)
    assert callable(runner)

    class FlakyService:
        def __init__(self) -> None:
            self.calls = 0
            self.database_url = DATABASE_URL

        async def scan_timeouts_async(self) -> list:
            self.calls += 1
            if self.calls == 1:
                raise RuntimeError("scan boom")
            return []

        def cancel_open_for_device(self, device_id: str) -> list:
            return []

    class StopAfterTwoIterations:
        def __init__(self) -> None:
            self.waits = 0

        def is_set(self) -> bool:
            return self.waits >= 2

        async def wait(self) -> None:
            self.waits += 1

    service = FlakyService()
    asyncio.run(
        runner(
            service,
            interval_sec=0.001,
            stop_event=StopAfterTwoIterations(),
        )
    )

    assert service.calls == 2
    assert "task timeout scan failed" in caplog.text


def test_lifespan_starts_and_cancels_real_timeout_scanner() -> None:
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        scanner = client.app.state.task_timeout_scanner
        assert not scanner.done()

    assert scanner.done()
    assert scanner.cancelled()

import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import ValidationError


SCHEMA_ROOT = Path(__file__).resolve().parents[2] / "schemas"
FORMAT_CHECKER = FormatChecker()


def load_schema(name: str) -> dict:
    path = SCHEMA_ROOT / name
    assert path.exists(), f"{name} is not implemented"
    schema = json.loads(path.read_text())
    Draft202012Validator.check_schema(schema)
    return schema


def validator_for(name: str) -> Draft202012Validator:
    return Draft202012Validator(load_schema(name), format_checker=FORMAT_CHECKER)


def signature() -> dict:
    return {
        "algorithm": "Ed25519",
        "key_id": "device-key-01",
        "value": "base64-signature",
    }


def valid_task() -> dict:
    return {
        "schema_version": "1.0",
        "task_id": "task-01",
        "operation_id": "publish-01",
        "device_id": "device-01",
        "playbook": "publish_note@1.0",
        "params": {},
        "effect_class": "non_idempotent",
        "effect_committed": False,
        "status": "unknown",
        "reconcile_required": True,
        "error_code": "EFFECT_UNKNOWN",
        "priority": 10,
        "timeout_sec": 300,
        "source": "scheduler",
    }


EVENT_PAYLOADS = {
    "assign_task": {
        "task_id": "task-01",
        "playbook": "publish_note@1.0",
        "params": {},
    },
    "cancel_task": {"task_id": "task-01", "reason": "operator request"},
    "config_update": {
        "config_version": "1.2.3",
        "download_url": "https://objects.example/config.json",
    },
    "pause": {"reason": "maintenance"},
    "resume": {"reason": "maintenance complete"},
    "heartbeat": {"agent_version": "1.0.0", "sent_at": "2026-07-10T04:00:00Z"},
    "task_progress": {"task_id": "task-01", "status": "executing", "progress": 50},
    "task_result": {"task_id": "task-01", "result": "success"},
    "health_alert": {"error_code": "A11Y_DISABLED", "severity": "critical"},
}


def valid_event(direction: str, event_type: str) -> dict:
    return {
        "schema_version": "1.0",
        "event_id": "evt-01",
        "device_id": "device-01",
        "direction": direction,
        "type": event_type,
        "occurred_at": "2026-07-10T04:00:00Z",
        "sequence": 1,
        "nonce": "nonce-01",
        "payload": EVENT_PAYLOADS[event_type],
        "signature": signature(),
    }


def valid_selector_bundle() -> dict:
    return {
        "schema_version": "1.0",
        "bundle_version": "1.2.3",
        "target_package": "com.example.app",
        "target_app_versions": ["8.0.0"],
        "selectors": {
            "publish_button": {
                "strategy": "view_id",
                "value": "com.example.app:id/publish",
                "exact": True,
            }
        },
        "issued_at": "2026-07-10T04:00:00Z",
        "signature": signature(),
    }


def valid_update_manifest() -> dict:
    return {
        "schema_version": "1.0",
        "manifest_version": "1.2.3",
        "agent_version": "1.2.3",
        "minimum_agent_version": "1.0.0",
        "rollout_ring": "canary",
        "artifacts": [
            {
                "name": "agent.apk",
                "url": "https://objects.example/agent.apk",
                "sha256": "a" * 64,
                "size_bytes": 1024,
            }
        ],
        "issued_at": "2026-07-10T04:00:00Z",
        "signature": signature(),
    }


@pytest.mark.parametrize(
    "name",
    [
        "task.schema.json",
        "event.schema.json",
        "selector-bundle.schema.json",
        "update-manifest.schema.json",
    ],
)
def test_contract_is_a_valid_draft_2020_12_schema(name: str) -> None:
    schema = load_schema(name)
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"


def test_task_contract_models_effect_and_reconciliation_state() -> None:
    validator = validator_for("task.schema.json")
    task = valid_task()
    validator.validate(task)

    task["reconcile_required"] = False
    with pytest.raises(ValidationError):
        validator.validate(task)


def test_task_rejects_effect_committed_state_without_checkpoint() -> None:
    task = valid_task()
    task.update(
        status="effect_committed",
        effect_committed=False,
        reconcile_required=False,
    )
    task.pop("error_code")

    with pytest.raises(ValidationError):
        validator_for("task.schema.json").validate(task)


def test_task_rejects_invalid_schedule_format() -> None:
    task = valid_task()
    task["schedule_at"] = "tomorrow morning"

    with pytest.raises(ValidationError):
        validator_for("task.schema.json").validate(task)


def test_task_contract_exposes_stable_error_codes() -> None:
    schema = load_schema("task.schema.json")
    error_codes = set(schema["$defs"]["errorCode"]["enum"])
    assert {
        "DEVICE_OFFLINE",
        "DEVICE_LOCKED",
        "SCREEN_NOT_READY",
        "LAUNCH_BLOCKED",
        "UI_NOT_FOUND",
        "APP_CRASH",
        "BUSINESS_BLOCKED",
        "TIMEOUT",
        "PRECONDITION",
        "A11Y_DISABLED",
        "EFFECT_UNKNOWN",
    } <= error_codes


@pytest.mark.parametrize(
    ("direction", "event_type"),
    [
        ("downstream", "assign_task"),
        ("downstream", "cancel_task"),
        ("downstream", "config_update"),
        ("downstream", "pause"),
        ("downstream", "resume"),
        ("upstream", "heartbeat"),
        ("upstream", "task_progress"),
        ("upstream", "task_result"),
        ("upstream", "health_alert"),
    ],
)
def test_event_contract_supports_protocol_event(direction: str, event_type: str) -> None:
    validator_for("event.schema.json").validate(valid_event(direction, event_type))


def test_event_rejects_direction_type_mismatch() -> None:
    event = valid_event("upstream", "heartbeat")
    event["type"] = "assign_task"
    event["payload"] = EVENT_PAYLOADS["assign_task"]

    with pytest.raises(ValidationError):
        validator_for("event.schema.json").validate(event)


def test_event_rejects_payload_for_another_event_type() -> None:
    event = valid_event("downstream", "assign_task")
    event["payload"] = EVENT_PAYLOADS["heartbeat"]

    with pytest.raises(ValidationError):
        validator_for("event.schema.json").validate(event)


def test_event_rejects_invalid_timestamp_and_additional_fields() -> None:
    validator = validator_for("event.schema.json")
    event = valid_event("upstream", "heartbeat")
    event["occurred_at"] = "not-a-timestamp"
    with pytest.raises(ValidationError):
        validator.validate(event)

    event = valid_event("upstream", "heartbeat")
    event["unexpected"] = True
    with pytest.raises(ValidationError):
        validator.validate(event)


def test_selector_bundle_requires_non_empty_structured_selectors() -> None:
    validator = validator_for("selector-bundle.schema.json")
    validator.validate(valid_selector_bundle())

    bundle = valid_selector_bundle()
    bundle["selectors"] = {}
    with pytest.raises(ValidationError):
        validator.validate(bundle)

    bundle = valid_selector_bundle()
    bundle["selectors"]["publish_button"]["unexpected"] = True
    with pytest.raises(ValidationError):
        validator.validate(bundle)


def test_update_manifest_requires_https_and_valid_formats() -> None:
    validator = validator_for("update-manifest.schema.json")
    validator.validate(valid_update_manifest())

    manifest = valid_update_manifest()
    manifest["artifacts"][0]["url"] = "http://objects.example/agent.apk"
    with pytest.raises(ValidationError):
        validator.validate(manifest)

    manifest = valid_update_manifest()
    manifest["issued_at"] = "yesterday"
    with pytest.raises(ValidationError):
        validator.validate(manifest)


@pytest.mark.parametrize(
    ("name", "version_field"),
    [
        ("selector-bundle.schema.json", "bundle_version"),
        ("update-manifest.schema.json", "manifest_version"),
    ],
)
def test_signed_versioned_contracts_require_signature(name: str, version_field: str) -> None:
    schema = load_schema(name)
    assert version_field in schema["required"]
    assert "schema_version" in schema["required"]
    assert "signature" in schema["required"]

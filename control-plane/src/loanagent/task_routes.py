from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator

from loanagent.auth import require_device, require_device_or_ops, require_ops
from loanagent.tasks import (
    DuplicateTaskError,
    InvalidTaskResultPayloadError,
    PlaybookForbiddenError,
    TaskAccessibilityDownError,
    TaskAccountNotFoundError,
    TaskAccountUnavailableError,
    TaskAlreadyTerminalError,
    TaskDispatchAmbiguousError,
    TaskDispatchError,
    TaskDeviceUnavailableError,
    TaskNetworkPolicyViolationError,
    TaskNotFoundError,
    TaskPublishQuotaExceededError,
    TaskService,
    TaskTransitionError,
    serialize_task_record,
    task_dispatch_ambiguous_detail,
    validate_task_error_code,
)


router = APIRouter(prefix="/api/v1")


class TaskCreatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)
    playbook: str = Field(pattern=r"^[a-z][a-z0-9_]*@[0-9]+\.[0-9]+$")
    params: dict = Field(default_factory=dict)
    operation_id: str | None = Field(default=None, min_length=1, max_length=128)
    task_id: str | None = Field(default=None, min_length=1, max_length=128)


class DeviceTaskEventPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    task_id: str = Field(min_length=1, max_length=128)
    status: Literal[
        "accepted",
        "executing",
        "effect_committed",
        "reported",
        "succeeded",
        "failed",
        "unknown",
    ]
    error_code: str | None = None
    result_payload: dict | None = None

    @field_validator("error_code")
    @classmethod
    def validate_error_code(cls, value: str | None) -> str | None:
        return validate_task_error_code(value)


@router.post("/tasks", dependencies=[Depends(require_ops)])
def create_task(payload: TaskCreatePayload, request: Request) -> dict:
    service = _task_service(request)
    try:
        task = service.create_and_dispatch(**payload.model_dump())
    except PlaybookForbiddenError as error:
        raise _task_http_error(
            403,
            "PLAYBOOK_FORBIDDEN",
            "Playbook is not allowed for the account role.",
        ) from error
    except DuplicateTaskError as error:
        raise _task_http_error(
            409,
            "TASK_ALREADY_EXISTS",
            "Task already exists.",
        ) from error
    except TaskAccountUnavailableError as error:
        raise _task_http_error(
            409,
            "ACCOUNT_UNAVAILABLE",
            "Account is not active.",
        ) from error
    except TaskDeviceUnavailableError as error:
        raise _task_http_error(
            409,
            "DEVICE_UNAVAILABLE",
            "Account is not bound to an online device.",
        ) from error
    except TaskAccessibilityDownError as error:
        raise _task_http_error(
            409,
            "A11Y_DOWN",
            "Device accessibility service is not bound.",
        ) from error
    except TaskNetworkPolicyViolationError as error:
        raise _task_http_error(
            409,
            "NETWORK_POLICY_VIOLATION",
            "Device is on Wi-Fi while the account requires cellular_only.",
        ) from error
    except TaskPublishQuotaExceededError as error:
        raise _task_http_error(
            429,
            "PUBLISH_QUOTA_EXCEEDED",
            "Account has reached its daily publish quota.",
        ) from error
    except TaskAccountNotFoundError as error:
        raise _task_http_error(
            404,
            "ACCOUNT_NOT_FOUND",
            "Account does not exist.",
        ) from error
    except TaskDispatchAmbiguousError as error:
        raise HTTPException(
            status_code=409,
            detail=task_dispatch_ambiguous_detail(error),
        ) from error
    except TaskDispatchError as error:
        raise _task_http_error(
            502,
            "TASK_DISPATCH_FAILED",
            "Task dispatch failed after persistence; retry with a new task_id.",
        ) from error
    return serialize_task_record(task)


@router.get("/tasks", dependencies=[Depends(require_ops)])
def list_tasks(
    request: Request,
    account_id: str | None = Query(default=None, min_length=1, max_length=128),
    device_id: str | None = Query(default=None, min_length=1, max_length=128),
    status: str | None = Query(default=None, min_length=1, max_length=32),
) -> list[dict]:
    return [
        serialize_task_record(task)
        for task in _task_service(request).list(
            account_id=account_id,
            device_id=device_id,
            status=status,
        )
    ]


@router.get("/tasks/{task_id}", dependencies=[Depends(require_ops)])
def get_task(task_id: str, request: Request) -> dict:
    try:
        task = _task_service(request).get(task_id)
    except TaskNotFoundError as error:
        raise _task_http_error(
            404,
            "TASK_NOT_FOUND",
            "Task does not exist.",
        ) from error
    return serialize_task_record(task)


@router.post("/tasks/{task_id}/cancel", dependencies=[Depends(require_ops)])
def cancel_task(task_id: str, request: Request) -> dict:
    try:
        task = _task_service(request).cancel(task_id)
    except TaskNotFoundError as error:
        raise _task_http_error(
            404,
            "TASK_NOT_FOUND",
            "Task does not exist.",
        ) from error
    except TaskAlreadyTerminalError as error:
        raise _task_http_error(
            409,
            "TASK_ALREADY_TERMINAL",
            "Task is already finished and cannot be cancelled.",
        ) from error
    return serialize_task_record(task)


@router.get("/devices/{device_id}/commands", dependencies=[Depends(require_device)])
def list_device_commands(device_id: str, request: Request) -> list[dict]:
    """HTTP command poll for agents that cannot open outbound MQTT ports."""
    return _task_service(request).pending_commands_for_device(device_id)


# Device token preferred; ops bearer kept for debug Agent and console simulation.
@router.post("/devices/{device_id}/events", dependencies=[Depends(require_device_or_ops)])
def accept_device_event(device_id: str, payload: DeviceTaskEventPayload, request: Request) -> dict:
    service = _task_service(request)
    try:
        task = service.mark_from_event(
            device_id=device_id,
            task_id=payload.task_id,
            status=payload.status,
            error_code=payload.error_code,
            result_payload=payload.result_payload,
        )
    except TaskNotFoundError as error:
        raise _task_http_error(
            404,
            "TASK_NOT_FOUND",
            "Task does not exist for this device.",
        ) from error
    except TaskAlreadyTerminalError as error:
        raise _task_http_error(
            409,
            "TASK_ALREADY_TERMINAL",
            "Task already has a terminal result.",
        ) from error
    except TaskTransitionError as error:
        raise _task_http_error(
            409,
            "TASK_INVALID_TRANSITION",
            "Task event is not valid for the current state.",
        ) from error
    except InvalidTaskResultPayloadError as error:
        raise _task_http_error(
            422,
            "TASK_RESULT_PAYLOAD_INVALID",
            str(error),
        ) from error
    return serialize_task_record(task)


def _task_service(request: Request) -> TaskService:
    return request.app.state.task_service


def _task_http_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})

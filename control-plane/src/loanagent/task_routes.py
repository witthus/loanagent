from __future__ import annotations

from dataclasses import asdict
from typing import Literal

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from loanagent.tasks import (
    DuplicateTaskError,
    PlaybookForbiddenError,
    ReadonlyTaskRequiredError,
    TaskAccountNotFoundError,
    TaskAccountUnavailableError,
    TaskDispatchError,
    TaskDeviceUnavailableError,
    TaskNotFoundError,
    TaskService,
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
    status: Literal["succeeded"]


@router.post("/tasks")
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
    except TaskAccountNotFoundError as error:
        raise _task_http_error(
            404,
            "ACCOUNT_NOT_FOUND",
            "Account does not exist.",
        ) from error
    except TaskDispatchError as error:
        raise _task_http_error(
            502,
            "TASK_DISPATCH_FAILED",
            "Task dispatch failed after persistence; retry with a new task_id.",
        ) from error
    return asdict(task)


@router.get("/tasks")
def list_tasks(
    request: Request,
    account_id: str | None = Query(default=None, min_length=1, max_length=128),
    device_id: str | None = Query(default=None, min_length=1, max_length=128),
    status: str | None = Query(default=None, min_length=1, max_length=32),
) -> list[dict]:
    return [
        asdict(task)
        for task in _task_service(request).list(
            account_id=account_id,
            device_id=device_id,
            status=status,
        )
    ]


@router.post("/devices/{device_id}/events")
def accept_device_event(device_id: str, payload: DeviceTaskEventPayload, request: Request) -> dict:
    service = _task_service(request)
    try:
        task = service.mark_readonly_succeeded_from_event(
            device_id=device_id,
            task_id=payload.task_id,
        )
    except TaskNotFoundError as error:
        raise _task_http_error(
            404,
            "TASK_NOT_FOUND",
            "Task does not exist for this device.",
        ) from error
    except ReadonlyTaskRequiredError as error:
        raise _task_http_error(
            409,
            "READONLY_TASK_REQUIRED",
            "Only readonly tasks can be completed by this test hook.",
        ) from error
    return asdict(task)


def _task_service(request: Request) -> TaskService:
    return request.app.state.task_service


def _task_http_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})

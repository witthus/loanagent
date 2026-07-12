from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import asdict
from typing import Literal
import os

from fastapi import Depends, FastAPI, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from loanagent.accounts import (
    AccountAlreadyExistsError,
    AccountDeviceAlreadyBoundError,
    AccountDeviceNotFoundError,
    AccountNotFoundError,
    AccountRepository,
)
from loanagent.auth import require_device, require_ops
from loanagent.db import migrate_fleet_schema
from loanagent.devices import DeviceRepository
from loanagent.enrollment import (
    DeviceIdentity,
    EnrollmentConsumeStatus,
    EnrollmentRepository,
)
from loanagent.mqtt_bus import MqttCommandBus
from loanagent.roles import AccountRole
from loanagent.task_routes import router as task_router
from loanagent.tasks import TaskService


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    database_url = os.environ["DATABASE_URL"]
    enrollment_repository = EnrollmentRepository(database_url)
    enrollment_repository.migrate()
    migrate_fleet_schema(database_url)
    application.state.enrollment_repository = enrollment_repository
    application.state.device_repository = DeviceRepository(database_url)
    application.state.account_repository = AccountRepository(database_url)
    application.state.task_service = TaskService(
        database_url,
        MqttCommandBus(os.environ.get("MQTT_URL", "mqtt://localhost:1883")),
    )
    yield


app = FastAPI(
    title="Loanagent Control Plane",
    version="0.1.0",
    lifespan=lifespan,
)
app.include_router(task_router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "control-plane"}


class EnrollmentDevicePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    device_id: str = Field(min_length=1, max_length=128)
    manufacturer: str = Field(min_length=1, max_length=128)
    model: str = Field(min_length=1, max_length=128)
    android_version: str = Field(min_length=1, max_length=32)
    controller_version: str = Field(min_length=1, max_length=32)


class EnrollmentPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    token: str = Field(min_length=1, max_length=512)
    device: EnrollmentDevicePayload


class DeviceHeartbeatPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    agent_version: str | None = Field(default=None, min_length=1, max_length=64)
    wifi_connected: bool | None = None
    a11y_bound: bool | None = None
    cellular_ok: bool | None = None


class AccountCreatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)
    role: AccountRole
    device_id: str | None = Field(default=None, min_length=1, max_length=128)
    display_name: str | None = Field(default=None, min_length=1, max_length=256)


AccountStatusPayload = Literal["active", "paused", "blocked", "needs_login"]


class AccountPatchPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    device_id: str | None = Field(default=None, min_length=1, max_length=128)
    status: AccountStatusPayload | None = None
    network_policy: str | None = Field(default=None, min_length=1, max_length=64)
    daily_publish_quota: int | None = Field(default=None, ge=0)
    inbox_sync_enabled: bool | None = None
    display_name: str | None = Field(default=None, min_length=1, max_length=256)


@app.post("/enroll")
def enroll(payload: EnrollmentPayload, request: Request) -> dict[str, str]:
    repository: EnrollmentRepository = request.app.state.enrollment_repository
    result = repository.consume(
        payload.token,
        DeviceIdentity(**payload.device.model_dump()),
    )
    if result.status in {
        EnrollmentConsumeStatus.CONSUMED,
        EnrollmentConsumeStatus.IDEMPOTENT_RETRY,
    }:
        status = (
            "enrolled"
            if result.status is EnrollmentConsumeStatus.CONSUMED
            else "already_enrolled"
        )
        return {"status": status, "device_id": payload.device.device_id}

    errors = {
        EnrollmentConsumeStatus.DEVICE_CONFLICT: (
            409,
            "TOKEN_DEVICE_CONFLICT",
            "Enrollment token is bound to a different device.",
        ),
        EnrollmentConsumeStatus.EXPIRED: (
            410,
            "TOKEN_EXPIRED",
            "Enrollment token has expired.",
        ),
        EnrollmentConsumeStatus.INVALID: (
            401,
            "TOKEN_INVALID",
            "Enrollment token is invalid.",
        ),
    }
    status_code, code, message = errors[result.status]
    raise HTTPException(
        status_code=status_code,
        detail={"code": code, "message": message},
    )


@app.post("/api/v1/devices/{device_id}/heartbeat", dependencies=[Depends(require_device)])
def heartbeat_device(
    device_id: str,
    payload: DeviceHeartbeatPayload,
    request: Request,
) -> dict:
    repository: DeviceRepository = request.app.state.device_repository
    return asdict(repository.heartbeat(device_id=device_id, **payload.model_dump()))


@app.get("/api/v1/devices", dependencies=[Depends(require_ops)])
def list_devices(request: Request) -> list[dict]:
    repository: DeviceRepository = request.app.state.device_repository
    return [asdict(device) for device in repository.list()]


@app.post("/api/v1/accounts", dependencies=[Depends(require_ops)])
def create_account(payload: AccountCreatePayload, request: Request) -> dict:
    repository: AccountRepository = request.app.state.account_repository
    try:
        account = repository.create(**payload.model_dump())
    except AccountAlreadyExistsError as error:
        raise _account_http_error(
            409,
            "ACCOUNT_ALREADY_EXISTS",
            "Account already exists.",
        ) from error
    except AccountDeviceAlreadyBoundError as error:
        raise _account_http_error(
            409,
            "DEVICE_ALREADY_BOUND",
            "Device is already bound to an account.",
        ) from error
    except AccountDeviceNotFoundError as error:
        raise _account_http_error(
            404,
            "DEVICE_NOT_FOUND",
            "Device does not exist.",
        ) from error
    return asdict(account)


@app.get("/api/v1/accounts", dependencies=[Depends(require_ops)])
def list_accounts(request: Request) -> list[dict]:
    repository: AccountRepository = request.app.state.account_repository
    return [asdict(account) for account in repository.list()]


@app.patch("/api/v1/accounts/{account_id}", dependencies=[Depends(require_ops)])
def patch_account(
    account_id: str,
    payload: AccountPatchPayload,
    request: Request,
) -> dict:
    repository: AccountRepository = request.app.state.account_repository
    try:
        account = repository.update(account_id, **payload.model_dump(exclude_unset=True))
    except AccountDeviceAlreadyBoundError as error:
        raise _account_http_error(
            409,
            "DEVICE_ALREADY_BOUND",
            "Device is already bound to an account.",
        ) from error
    except AccountDeviceNotFoundError as error:
        raise _account_http_error(
            404,
            "DEVICE_NOT_FOUND",
            "Device does not exist.",
        ) from error
    except AccountNotFoundError as error:
        raise _account_http_error(
            404,
            "ACCOUNT_NOT_FOUND",
            "Account does not exist.",
        ) from error
    return asdict(account)


@app.post("/api/v1/accounts/{account_id}/pause", dependencies=[Depends(require_ops)])
def pause_account(account_id: str, request: Request) -> dict:
    repository: AccountRepository = request.app.state.account_repository
    try:
        account = repository.pause(account_id)
    except AccountNotFoundError as error:
        raise _account_http_error(
            404,
            "ACCOUNT_NOT_FOUND",
            "Account does not exist.",
        ) from error
    return asdict(account)


@app.post("/api/v1/accounts/{account_id}/resume", dependencies=[Depends(require_ops)])
def resume_account(account_id: str, request: Request) -> dict:
    repository: AccountRepository = request.app.state.account_repository
    try:
        account = repository.resume(account_id)
    except AccountNotFoundError as error:
        raise _account_http_error(
            404,
            "ACCOUNT_NOT_FOUND",
            "Account does not exist.",
        ) from error
    return asdict(account)


def _account_http_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})

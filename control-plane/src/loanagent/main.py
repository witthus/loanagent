from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path
from typing import Literal
import os

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field

from loanagent.accounts import (
    AccountAlreadyExistsError,
    AccountDeviceAlreadyBoundError,
    AccountDeviceNotFoundError,
    AccountNotFoundError,
    AccountRepository,
)
from loanagent.agent_release import (
    AgentReleaseNotFoundError,
    load_latest_release,
    resolve_apk_path,
    resolve_guide_pdf_path,
)
from loanagent.auth import require_device, require_ops
from loanagent.content import ContentRepository
from loanagent.content_routes import router as content_router
from loanagent.db import migrate_fleet_schema
from loanagent.devices import DeviceNotFoundError, DeviceRepository
from loanagent.engagement import EngagementService
from loanagent.geo_ip import (
    begin_geo_refresh,
    cached_geo_label,
    end_geo_refresh,
    extract_client_ip,
    lookup_geo_label,
    needs_geo_refresh,
)
from loanagent.engagement_routes import router as engagement_router
from loanagent.enrollment import (
    DeviceIdentity,
    EnrollmentConsumeStatus,
    EnrollmentRepository,
)
from loanagent.inbox import InboxService
from loanagent.inbox_routes import router as inbox_router
from loanagent.media import MediaRepository
from loanagent.mqtt_bus import MqttCommandBus
from loanagent.notes import NotesService
from loanagent.notes_routes import router as notes_router
from loanagent.roles import AccountRole
from loanagent.schedules import ScheduleRepository
from loanagent.task_routes import router as task_router
from loanagent.tasks import TaskService
from loanagent.web.routes import router as ops_web_router


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    database_url = os.environ["DATABASE_URL"]
    enrollment_repository = EnrollmentRepository(database_url)
    enrollment_repository.migrate()
    migrate_fleet_schema(database_url)
    application.state.database_url = database_url
    application.state.enrollment_repository = enrollment_repository
    application.state.device_repository = DeviceRepository(database_url)
    application.state.account_repository = AccountRepository(database_url)
    task_service = TaskService(
        database_url,
        MqttCommandBus(os.environ.get("MQTT_URL", "mqtt://localhost:1883")),
    )
    engagement_service = EngagementService(database_url, task_service)
    task_service.engagement_service = engagement_service
    inbox_service = InboxService(database_url, task_service)
    notes_service = NotesService(database_url, task_service)
    application.state.task_service = task_service
    application.state.engagement_service = engagement_service
    application.state.inbox_service = inbox_service
    application.state.notes_service = notes_service
    application.state.media_repository = MediaRepository(database_url)
    application.state.content_repository = ContentRepository(database_url)
    application.state.schedule_repository = ScheduleRepository(
        database_url,
        application.state.task_service,
        application.state.media_repository,
        application.state.content_repository,
    )
    dist = _ops_web_dist()
    if dist is not None:
        assets = dist / "assets"
        already = any(getattr(route, "name", None) == "ops-web-assets" for route in application.routes)
        if assets.is_dir() and not already:
            application.mount("/assets", StaticFiles(directory=assets), name="ops-web-assets")
    yield


app = FastAPI(
    title="Loanagent Control Plane",
    version="0.1.0",
    lifespan=lifespan,
)
app.include_router(task_router)
app.include_router(content_router)
app.include_router(engagement_router)
app.include_router(inbox_router)
app.include_router(notes_router)
app.include_router(ops_web_router)


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
    manufacturer: str | None = Field(default=None, min_length=1, max_length=128)
    model: str | None = Field(default=None, min_length=1, max_length=128)


class AccountCreatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)
    role: AccountRole
    device_id: str | None = Field(default=None, min_length=1, max_length=128)
    display_name: str | None = Field(default=None, min_length=1, max_length=256)
    platform: str | None = Field(default="xhs", min_length=1, max_length=32)


AccountStatusPayload = Literal["active", "paused", "blocked", "needs_login"]


class AccountPatchPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    device_id: str | None = Field(default=None, min_length=1, max_length=128)
    status: AccountStatusPayload | None = None
    network_policy: str | None = Field(default=None, min_length=1, max_length=64)
    daily_publish_quota: int | None = Field(default=None, ge=0)
    inbox_sync_enabled: bool | None = None
    display_name: str | None = Field(default=None, min_length=1, max_length=256)
    platform: str | None = Field(default=None, min_length=1, max_length=32)
    role: AccountRole | None = None


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
    background_tasks: BackgroundTasks,
) -> dict:
    repository: DeviceRepository = request.app.state.device_repository
    public_ip = extract_client_ip(request)
    # Never block the heartbeat path on upstream geo HTTP.
    geo_label = cached_geo_label(public_ip)
    record = repository.heartbeat(
        device_id=device_id,
        public_ip=public_ip,
        geo_label=geo_label,
        **payload.model_dump(),
    )
    if public_ip and needs_geo_refresh(public_ip) and begin_geo_refresh(public_ip):
        background_tasks.add_task(
            _refresh_device_geo_background,
            request.app.state.database_url,
            device_id,
            public_ip,
        )
    return asdict(record)


def _refresh_device_geo_background(database_url: str, device_id: str, public_ip: str) -> None:
    try:
        label = lookup_geo_label(public_ip, force_refresh=True)
        DeviceRepository(database_url).update_geo_if_ip_matches(
            device_id,
            public_ip=public_ip,
            geo_label=label,
        )
    finally:
        end_geo_refresh(public_ip)


@app.get("/api/v1/devices", dependencies=[Depends(require_ops)])
def list_devices(request: Request) -> list[dict]:
    repository: DeviceRepository = request.app.state.device_repository
    return [asdict(device) for device in repository.list()]


class DevicePatchPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    manufacturer: str | None = Field(default=None, min_length=1, max_length=128)
    model: str | None = Field(default=None, min_length=1, max_length=128)
    online: bool | None = None
    display_name: str | None = Field(default=None, min_length=1, max_length=256)


@app.get("/api/v1/devices/{device_id}", dependencies=[Depends(require_ops)])
def get_device(device_id: str, request: Request) -> dict:
    repository: DeviceRepository = request.app.state.device_repository
    try:
        return asdict(repository.get(device_id))
    except DeviceNotFoundError as error:
        raise HTTPException(
            status_code=404,
            detail={"code": "DEVICE_NOT_FOUND", "message": "Device does not exist."},
        ) from error


@app.patch("/api/v1/devices/{device_id}", dependencies=[Depends(require_ops)])
def patch_device(device_id: str, payload: DevicePatchPayload, request: Request) -> dict:
    repository: DeviceRepository = request.app.state.device_repository
    try:
        return asdict(repository.patch(device_id, **payload.model_dump(exclude_unset=True)))
    except DeviceNotFoundError as error:
        raise HTTPException(
            status_code=404,
            detail={"code": "DEVICE_NOT_FOUND", "message": "Device does not exist."},
        ) from error


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
def list_accounts(
    request: Request,
    platform: str | None = None,
) -> list[dict]:
    repository: AccountRepository = request.app.state.account_repository
    return [asdict(account) for account in repository.list(platform=platform)]


@app.patch("/api/v1/accounts/{account_id}", dependencies=[Depends(require_ops)])
def patch_account(
    account_id: str,
    payload: AccountPatchPayload,
    request: Request,
) -> dict:
    repository: AccountRepository = request.app.state.account_repository
    try:
        changes = payload.model_dump(exclude_unset=True)
        if "role" in changes and changes["role"] is not None:
            changes["role"] = AccountRole(changes["role"]).value
        account = repository.update(account_id, **changes)
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
    except ValueError as error:
        raise _account_http_error(400, "INVALID_ACCOUNT_PATCH", str(error)) from error
    return asdict(account)


@app.delete("/api/v1/accounts/{account_id}", dependencies=[Depends(require_ops)])
def delete_account(account_id: str, request: Request) -> dict:
    repository: AccountRepository = request.app.state.account_repository
    try:
        repository.delete(account_id)
    except AccountNotFoundError as error:
        raise _account_http_error(
            404,
            "ACCOUNT_NOT_FOUND",
            "Account does not exist.",
        ) from error
    return {"ok": True, "account_id": account_id}


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


@app.get("/api/v1/agent/latest", dependencies=[Depends(require_ops)])
def get_latest_agent_release() -> dict:
    return load_latest_release().as_public_dict()


@app.get("/downloads/agent-latest.apk")
def download_latest_agent_apk() -> FileResponse:
    """Public APK download so phones can install without ops login.

    Intentionally unauthenticated for onboarding. Debug builds embed DEVICE_TOKEN —
    treat published APKs as UNTRUSTED_DEBUG_TEST_ONLY.
    """
    try:
        path = resolve_apk_path()
    except AgentReleaseNotFoundError as error:
        raise HTTPException(
            status_code=404,
            detail={"code": "AGENT_APK_NOT_FOUND", "message": "Agent APK is not published."},
        ) from error
    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename="matrix-assistant-agent.apk",
        content_disposition_type="attachment",
        headers={"X-Loanagent-Classification": "UNTRUSTED_DEBUG_TEST_ONLY"},
    )


@app.get("/downloads/device-bind-guide.pdf")
def download_device_bind_guide_pdf() -> FileResponse:
    """Public install guide PDF for new-device binding."""
    try:
        path = resolve_guide_pdf_path()
    except AgentReleaseNotFoundError as error:
        raise HTTPException(
            status_code=404,
            detail={
                "code": "GUIDE_PDF_NOT_FOUND",
                "message": "Device bind guide PDF is not published.",
            },
        ) from error
    return FileResponse(
        path,
        media_type="application/pdf",
        filename="矩阵助手-新设备绑定安装指引.pdf",
        content_disposition_type="attachment",
    )


def _ops_web_dist() -> Path | None:
    candidates = [
        Path(os.environ.get("OPS_WEB_DIST", "")),
        Path("/app/static/ops-web"),
        Path(__file__).resolve().parents[2] / "static" / "ops-web",
        Path(__file__).resolve().parents[3] / "ops-web" / "dist",
    ]
    for path in candidates:
        if path and (path / "index.html").is_file():
            return path
    return None


@app.get("/")
def spa_root() -> FileResponse:
    dist = _ops_web_dist()
    if dist is None:
        raise HTTPException(status_code=404, detail="SPA not built")
    return FileResponse(dist / "index.html")


@app.get("/login")
def spa_login() -> FileResponse:
    dist = _ops_web_dist()
    if dist is None:
        raise HTTPException(status_code=404, detail="SPA not built")
    return FileResponse(dist / "index.html")


@app.get("/{full_path:path}")
def spa_fallback(full_path: str) -> FileResponse:
    # Never shadow API or infra routes if routing order changes.
    blocked_prefixes = ("api/", "ops/", "docs", "redoc", "openapi.json", "enroll", "downloads/")
    if full_path in {"health", "openapi.json", "redoc", "docs"} or full_path.startswith(
        blocked_prefixes
    ):
        raise HTTPException(status_code=404, detail="Not Found")
    dist = _ops_web_dist()
    if dist is None:
        raise HTTPException(status_code=404, detail="SPA not built")
    candidate = dist / full_path
    if full_path and candidate.is_file():
        return FileResponse(candidate)
    return FileResponse(dist / "index.html")

from __future__ import annotations

from dataclasses import asdict
from datetime import datetime
from email.parser import BytesParser
from email.policy import default as email_default_policy
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, ConfigDict, Field

from loanagent.auth import require_ops
from loanagent.content import ContentNotFoundError, ContentRepository, ContentSensitivityError
from loanagent.media import MediaNotFoundError, MediaRepository, MediaSignatureError
from loanagent.schedules import (
    ScheduleNotDispatchableError,
    ScheduleNotEditableError,
    ScheduleNotFoundError,
    ScheduleRepository,
)
from loanagent.tasks import (
    PlaybookForbiddenError,
    TaskAccessibilityDownError,
    TaskAccountNotFoundError,
    TaskAccountUnavailableError,
    TaskDeviceUnavailableError,
    TaskDispatchError,
)


router = APIRouter(prefix="/api/v1")


class ContentCreatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=512)
    body: str = Field(min_length=1, max_length=20000)
    media_ids: list[str] = Field(default_factory=list)
    geo_tags: list[Any] | dict[str, Any] | None = None
    platform: str | None = Field(default="xhs", min_length=1, max_length=32)


class ScheduleCreatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)
    content_id: str = Field(min_length=1, max_length=128)
    window_start: datetime | None = None
    window_end: datetime | None = None


class SchedulePatchPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str | None = Field(default=None, min_length=1, max_length=128)
    content_id: str | None = Field(default=None, min_length=1, max_length=128)
    window_start: datetime | None = None
    window_end: datetime | None = None


class ImmediatePublishPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)
    content_id: str = Field(min_length=1, max_length=128)
    engagement_mode: str | None = Field(default="auto", pattern="^(auto|manual)$")


@router.post("/media", dependencies=[Depends(require_ops)])
async def upload_media(request: Request) -> dict:
    filename, content_type, data = await _read_multipart_file(request)
    if not data:
        raise _http_error(400, "EMPTY_FILE", "Uploaded file is empty.")
    record = _media_repository(request).create_from_bytes(
        data=data,
        content_type=content_type or "application/octet-stream",
        filename=filename,
    )
    return {
        "media_id": record.media_id,
        "content_type": record.content_type,
        "byte_size": record.byte_size,
        "sha256": record.sha256,
    }


@router.get("/media/{media_id}/download")
def download_media(
    media_id: str,
    request: Request,
    exp: str | None = Query(default=None),
    sig: str | None = Query(default=None),
) -> Response:
    """Allow signed agent downloads, or ops session cookie for ops-web image preview."""
    media_repo = _media_repository(request)
    try:
        if exp and sig:
            media_repo.verify_signature(media_id, exp=exp, sig=sig)
        else:
            require_ops(request, creds=None)
        record = media_repo.get(media_id)
    except MediaSignatureError as error:
        raise _http_error(401, "INVALID_SIGNATURE", "Download signature is invalid.") from error
    except HTTPException:
        raise
    except MediaNotFoundError as error:
        raise _http_error(404, "MEDIA_NOT_FOUND", "Media does not exist.") from error
    path = media_repo.resolve_path(record)
    if not path.is_file():
        raise _http_error(404, "MEDIA_FILE_MISSING", "Media file is missing on disk.")
    return FileResponse(
        path=str(path),
        media_type=record.content_type,
        filename=path.name,
    )


@router.post("/content", dependencies=[Depends(require_ops)])
def create_content(payload: ContentCreatePayload, request: Request) -> dict:
    try:
        record = _content_repository(request).create(**payload.model_dump())
    except ContentSensitivityError as error:
        raise _http_error(
            400,
            "SENSITIVITY_REJECTED",
            f"Content rejected by sensitivity scan: {', '.join(error.hits)}",
        ) from error
    return asdict(record)


@router.get("/content", dependencies=[Depends(require_ops)])
def list_content(
    request: Request,
    platform: str | None = Query(default=None),
) -> list[dict]:
    return [asdict(item) for item in _content_repository(request).list(platform=platform)]


@router.get("/content/{content_id}", dependencies=[Depends(require_ops)])
def get_content(content_id: str, request: Request) -> dict:
    try:
        return asdict(_content_repository(request).get(content_id))
    except ContentNotFoundError as error:
        raise _http_error(404, "CONTENT_NOT_FOUND", "Content does not exist.") from error


@router.patch("/content/{content_id}", dependencies=[Depends(require_ops)])
def update_content(content_id: str, payload: ContentCreatePayload, request: Request) -> dict:
    try:
        record = _content_repository(request).update(content_id, **payload.model_dump())
    except ContentNotFoundError as error:
        raise _http_error(404, "CONTENT_NOT_FOUND", "Content does not exist.") from error
    except ContentSensitivityError as error:
        raise _http_error(
            400,
            "SENSITIVITY_REJECTED",
            f"Content rejected by sensitivity scan: {', '.join(error.hits)}",
        ) from error
    return asdict(record)


@router.delete("/content/{content_id}", dependencies=[Depends(require_ops)])
def delete_content(content_id: str, request: Request) -> Response:
    try:
        _content_repository(request).delete(content_id)
    except ContentNotFoundError as error:
        raise _http_error(404, "CONTENT_NOT_FOUND", "Content does not exist.") from error
    return Response(status_code=204)


@router.post("/schedules", dependencies=[Depends(require_ops)])
def create_schedule(payload: ScheduleCreatePayload, request: Request) -> dict:
    try:
        record = _schedule_repository(request).create(**payload.model_dump())
    except ContentNotFoundError as error:
        raise _http_error(404, "CONTENT_NOT_FOUND", "Content does not exist.") from error
    return asdict(record)


@router.get("/schedules", dependencies=[Depends(require_ops)])
def list_schedules(request: Request) -> list[dict]:
    return [asdict(item) for item in _schedule_repository(request).list()]


@router.patch("/schedules/{schedule_id}", dependencies=[Depends(require_ops)])
def patch_schedule(
    schedule_id: str,
    payload: SchedulePatchPayload,
    request: Request,
) -> dict:
    try:
        record = _schedule_repository(request).update(
            schedule_id,
            **payload.model_dump(exclude_unset=True),
        )
    except ScheduleNotFoundError as error:
        raise _http_error(404, "SCHEDULE_NOT_FOUND", "Schedule does not exist.") from error
    except ScheduleNotEditableError as error:
        raise _http_error(
            409,
            "SCHEDULE_NOT_EDITABLE",
            "Only ready or failed schedules can be edited.",
        ) from error
    except ContentNotFoundError as error:
        raise _http_error(404, "CONTENT_NOT_FOUND", "Content does not exist.") from error
    except ValueError as error:
        raise _http_error(400, "INVALID_SCHEDULE_PATCH", str(error)) from error
    return asdict(record)


@router.delete("/schedules/{schedule_id}", dependencies=[Depends(require_ops)])
def delete_schedule(schedule_id: str, request: Request) -> dict:
    try:
        _schedule_repository(request).delete(schedule_id)
    except ScheduleNotFoundError as error:
        raise _http_error(404, "SCHEDULE_NOT_FOUND", "Schedule does not exist.") from error
    return {"ok": True, "schedule_id": schedule_id}


@router.post("/schedules/{schedule_id}/dispatch", dependencies=[Depends(require_ops)])
def dispatch_schedule(schedule_id: str, request: Request) -> dict:
    try:
        record = _schedule_repository(request).dispatch(schedule_id)
    except ScheduleNotFoundError as error:
        raise _http_error(404, "SCHEDULE_NOT_FOUND", "Schedule does not exist.") from error
    except ScheduleNotDispatchableError as error:
        raise _http_error(
            409,
            "SCHEDULE_NOT_DISPATCHABLE",
            "Schedule is not in a dispatchable state.",
        ) from error
    except ContentNotFoundError as error:
        raise _http_error(404, "CONTENT_NOT_FOUND", "Content does not exist.") from error
    except MediaNotFoundError as error:
        raise _http_error(404, "MEDIA_NOT_FOUND", "Media does not exist.") from error
    return asdict(record)


@router.post("/publish/immediate", dependencies=[Depends(require_ops)])
def publish_immediate(payload: ImmediatePublishPayload, request: Request) -> dict:
    try:
        task = _schedule_repository(request).publish_immediate(**payload.model_dump())
    except ValueError as error:
        if "platform mismatch" in str(error):
            raise _http_error(
                400,
                "PLATFORM_MISMATCH",
                "Account and content must belong to the same platform.",
            ) from error
        raise
    except ContentNotFoundError as error:
        raise _http_error(404, "CONTENT_NOT_FOUND", "Content does not exist.") from error
    except MediaNotFoundError as error:
        raise _http_error(404, "MEDIA_NOT_FOUND", "Media does not exist.") from error
    except PlaybookForbiddenError as error:
        raise _http_error(
            403,
            "PLAYBOOK_FORBIDDEN",
            "Playbook is not allowed for the account role.",
        ) from error
    except TaskAccountUnavailableError as error:
        raise _http_error(409, "ACCOUNT_UNAVAILABLE", "Account is not active.") from error
    except TaskDeviceUnavailableError as error:
        raise _http_error(
            409,
            "DEVICE_UNAVAILABLE",
            "Account is not bound to an online device.",
        ) from error
    except TaskAccessibilityDownError as error:
        raise _http_error(
            409,
            "A11Y_DOWN",
            "Device accessibility service is not bound.",
        ) from error
    except TaskAccountNotFoundError as error:
        raise _http_error(404, "ACCOUNT_NOT_FOUND", "Account does not exist.") from error
    except TaskDispatchError as error:
        raise _http_error(
            502,
            "TASK_DISPATCH_FAILED",
            "Task dispatch failed after persistence; retry with a new task_id.",
        ) from error
    return asdict(task)


async def _read_multipart_file(request: Request) -> tuple[str | None, str | None, bytes]:
    content_type = request.headers.get("content-type", "")
    if "multipart/form-data" not in content_type.lower():
        raise _http_error(400, "EXPECTED_MULTIPART", "Expected multipart/form-data upload.")
    body = await request.body()
    # email.parser understands MIME multipart without python-multipart.
    message = BytesParser(policy=email_default_policy).parsebytes(
        f"Content-Type: {content_type}\r\n\r\n".encode("utf-8") + body
    )
    if not message.is_multipart():
        raise _http_error(400, "EXPECTED_MULTIPART", "Expected multipart/form-data upload.")
    for part in message.iter_parts():
        disposition = part.get_content_disposition()
        if disposition != "form-data":
            continue
        name = part.get_param("name", header="content-disposition")
        if name != "file":
            continue
        filename = part.get_filename()
        part_type = part.get_content_type()
        payload = part.get_payload(decode=True)
        if payload is None:
            raw = part.get_payload(decode=False)
            payload = raw.encode("utf-8") if isinstance(raw, str) else b""
        return filename, part_type, payload
    raise _http_error(400, "MISSING_FILE", 'Multipart field "file" is required.')


def _media_repository(request: Request) -> MediaRepository:
    return request.app.state.media_repository


def _content_repository(request: Request) -> ContentRepository:
    return request.app.state.content_repository


def _schedule_repository(request: Request) -> ScheduleRepository:
    repository: ScheduleRepository = request.app.state.schedule_repository
    # Tests (and ops) may replace task_service after lifespan; keep dispatch wired.
    repository.task_service = request.app.state.task_service
    return repository


def _http_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})

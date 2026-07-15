from __future__ import annotations

from dataclasses import asdict
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from loanagent.auth import require_ops
from loanagent.inbox import (
    ContactForbiddenError,
    InboxService,
    InboxSyncDisabledError,
    InboxThreadNotFoundError,
)
from loanagent.tasks import (
    PlaybookForbiddenError,
    TaskAccessibilityDownError,
    TaskAccountNotFoundError,
    TaskAccountUnavailableError,
    TaskDeviceUnavailableError,
    TaskDispatchAmbiguousError,
    TaskDispatchError,
    serialize_task_record,
    task_dispatch_ambiguous_detail,
)


router = APIRouter(prefix="/api/v1")


class InboxMessageIngestPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sender_summary: str | None = Field(default=None, max_length=512)
    body_summary: str | None = Field(default=None, max_length=4000)


class InboxThreadIngestPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title_summary: str = Field(min_length=1, max_length=512)
    preview_summary: str | None = Field(default=None, max_length=2000)
    unread: bool = False
    messages: list[InboxMessageIngestPayload] = Field(default_factory=list)


class InboxIngestPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)
    threads: list[InboxThreadIngestPayload] = Field(min_length=1)


class InboxSyncPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)


class InboxReplyPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1, max_length=4000)
    thread_id: str | None = Field(default=None, min_length=1, max_length=128)


LeadStatus = Literal["new", "warm", "hot", "closed"]


class InboxLeadPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    thread_id: str = Field(min_length=1, max_length=128)
    status: LeadStatus
    note: str | None = Field(default=None, max_length=2000)


class InboxThreadReplyPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1, max_length=4000)


@router.get("/inbox/threads", dependencies=[Depends(require_ops)])
def list_inbox_threads(
    request: Request,
    account_id: str | None = Query(default=None),
) -> list[dict]:
    return [
        asdict(thread) for thread in _inbox_service(request).list_threads(account_id=account_id)
    ]


@router.get("/inbox/threads/{thread_id}", dependencies=[Depends(require_ops)])
def get_inbox_thread(thread_id: str, request: Request) -> dict:
    try:
        thread = _inbox_service(request).get_thread(thread_id)
    except InboxThreadNotFoundError as error:
        raise _http_error(
            404,
            "THREAD_NOT_FOUND",
            "Inbox thread does not exist.",
        ) from error
    return asdict(thread)


@router.get("/inbox/threads/{thread_id}/messages", dependencies=[Depends(require_ops)])
def list_thread_messages(thread_id: str, request: Request) -> list[dict]:
    try:
        messages = _inbox_service(request).list_messages(thread_id)
    except InboxThreadNotFoundError as error:
        raise _http_error(
            404,
            "THREAD_NOT_FOUND",
            "Inbox thread does not exist.",
        ) from error
    return [asdict(message) for message in messages]


@router.post("/inbox/threads/{thread_id}/reply", dependencies=[Depends(require_ops)])
def reply_thread(thread_id: str, payload: InboxThreadReplyPayload, request: Request) -> dict:
    try:
        task = _inbox_service(request).reply_thread(
            thread_id,
            account_id=payload.account_id,
            text=payload.text,
        )
    except ContactForbiddenError as error:
        raise _http_error(
            400,
            "CONTACT_FORBIDDEN",
            "Plaintext WeChat ID or phone number is not allowed.",
        ) from error
    except InboxThreadNotFoundError as error:
        raise _http_error(
            404,
            "THREAD_NOT_FOUND",
            "Inbox thread does not exist.",
        ) from error
    except Exception as error:
        raise _map_task_error(error) from error
    return serialize_task_record(task)


@router.post("/inbox/ingest", dependencies=[Depends(require_ops)])
def ingest_inbox(payload: InboxIngestPayload, request: Request) -> list[dict]:
    threads = _inbox_service(request).ingest(
        payload.account_id,
        [item.model_dump() for item in payload.threads],
    )
    return [asdict(thread) for thread in threads]


@router.post("/inbox/sync", dependencies=[Depends(require_ops)])
def sync_inbox(payload: InboxSyncPayload, request: Request) -> dict:
    try:
        task = _inbox_service(request).sync(payload.account_id)
    except Exception as error:
        raise _map_task_error(error) from error
    return serialize_task_record(task)


@router.post("/inbox/threads/{thread_id}/open", dependencies=[Depends(require_ops)])
def open_inbox_thread(thread_id: str, request: Request) -> dict:
    try:
        task = _inbox_service(request).open_thread(thread_id)
    except InboxThreadNotFoundError as error:
        raise _http_error(
            404,
            "THREAD_NOT_FOUND",
            "Inbox thread does not exist.",
        ) from error
    except Exception as error:
        raise _map_task_error(error) from error
    return serialize_task_record(task)


@router.post("/inbox/reply", dependencies=[Depends(require_ops)])
def reply_inbox(payload: InboxReplyPayload, request: Request) -> dict:
    try:
        task = _inbox_service(request).reply(
            account_id=payload.account_id,
            text=payload.text,
            thread_id=payload.thread_id,
        )
    except ContactForbiddenError as error:
        raise _http_error(
            400,
            "CONTACT_FORBIDDEN",
            "Plaintext WeChat ID or phone number is not allowed.",
        ) from error
    except Exception as error:
        raise _map_task_error(error) from error
    return serialize_task_record(task)


@router.post("/inbox/leads", dependencies=[Depends(require_ops)])
def mark_lead(payload: InboxLeadPayload, request: Request) -> dict:
    try:
        lead = _inbox_service(request).mark_lead(
            thread_id=payload.thread_id,
            status=payload.status,
            note=payload.note,
        )
    except InboxThreadNotFoundError as error:
        raise _http_error(
            404,
            "THREAD_NOT_FOUND",
            "Inbox thread does not exist.",
        ) from error
    return asdict(lead)


@router.get("/inbox/leads", dependencies=[Depends(require_ops)])
def list_leads(request: Request) -> list[dict]:
    return [asdict(lead) for lead in _inbox_service(request).list_leads()]


def _inbox_service(request: Request) -> InboxService:
    service: InboxService = request.app.state.inbox_service
    service.task_service = request.app.state.task_service
    return service


def _map_task_error(error: Exception) -> HTTPException:
    if isinstance(error, PlaybookForbiddenError):
        return _http_error(
            403,
            "PLAYBOOK_FORBIDDEN",
            "Playbook is not allowed for the account role.",
        )
    if isinstance(error, TaskAccountUnavailableError):
        return _http_error(409, "ACCOUNT_UNAVAILABLE", "Account is not active.")
    if isinstance(error, TaskDeviceUnavailableError):
        return _http_error(
            409,
            "DEVICE_UNAVAILABLE",
            "Account is not bound to an online device.",
        )
    if isinstance(error, TaskAccessibilityDownError):
        return _http_error(
            409,
            "A11Y_DOWN",
            "Device accessibility service is not bound.",
        )
    if isinstance(error, TaskAccountNotFoundError):
        return _http_error(404, "ACCOUNT_NOT_FOUND", "Account does not exist.")
    if isinstance(error, InboxSyncDisabledError):
        return _http_error(
            409,
            "INBOX_SYNC_DISABLED",
            "Inbox sync is disabled for this account.",
        )
    if isinstance(error, TaskDispatchAmbiguousError):
        return HTTPException(
            status_code=409,
            detail=task_dispatch_ambiguous_detail(error),
        )
    if isinstance(error, TaskDispatchError):
        return _http_error(
            502,
            "TASK_DISPATCH_FAILED",
            "Task dispatch failed after persistence; retry with a new task_id.",
        )
    raise error


def _http_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})

from __future__ import annotations

from dataclasses import asdict

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from loanagent.auth import require_ops
from loanagent.notes import (
    CommentNotFoundError,
    NoteNotFoundError,
    NotesComplianceError,
    NotesService,
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


class CommentReplyPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=4000)


class NotesSyncPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    account_id: str = Field(min_length=1, max_length=128)


@router.get("/notes", dependencies=[Depends(require_ops)])
def list_notes(
    request: Request,
    account_id: str | None = Query(default=None, min_length=1, max_length=128),
) -> list[dict]:
    return [
        asdict(note)
        for note in _notes_service(request).list_notes(account_id=account_id)
    ]


@router.post("/notes/sync", dependencies=[Depends(require_ops)])
def sync_notes(payload: NotesSyncPayload, request: Request) -> dict:
    try:
        task = _notes_service(request).sync_notes(payload.account_id)
    except Exception as error:
        raise _map_task_error(error) from error
    return asdict(task)


@router.post("/notes/{note_id}/sync-comments", dependencies=[Depends(require_ops)])
def sync_note_comments(note_id: str, request: Request) -> dict:
    try:
        task = _notes_service(request).sync_comments(note_id)
    except NoteNotFoundError as error:
        raise _http_error(404, "NOTE_NOT_FOUND", "Published note does not exist.") from error
    except Exception as error:
        raise _map_task_error(error) from error
    return asdict(task)


@router.get("/notes/{note_id}/comments", dependencies=[Depends(require_ops)])
def list_note_comments(note_id: str, request: Request) -> list[dict]:
    try:
        comments = _notes_service(request).list_comments(note_id)
    except NoteNotFoundError as error:
        raise _http_error(404, "NOTE_NOT_FOUND", "Published note does not exist.") from error
    return [asdict(comment) for comment in comments]


@router.post("/notes/{note_id}/comments", dependencies=[Depends(require_ops)])
def post_note_comment(note_id: str, payload: CommentReplyPayload, request: Request) -> dict:
    try:
        task = _notes_service(request).post_comment(note_id, payload.text)
    except NoteNotFoundError as error:
        raise _http_error(404, "NOTE_NOT_FOUND", "Published note does not exist.") from error
    except NotesComplianceError as error:
        raise _http_error(400, "COMPLIANCE_REJECTED", str(error)) from error
    except Exception as error:
        raise _map_task_error(error) from error
    return asdict(task)


@router.post("/comments/{comment_id}/reply", dependencies=[Depends(require_ops)])
def reply_comment(comment_id: str, payload: CommentReplyPayload, request: Request) -> dict:
    try:
        task = _notes_service(request).reply_comment(comment_id, payload.text)
    except CommentNotFoundError as error:
        raise _http_error(404, "COMMENT_NOT_FOUND", "Comment does not exist.") from error
    except NotesComplianceError as error:
        raise _http_error(400, "COMPLIANCE_REJECTED", str(error)) from error
    except Exception as error:
        raise _map_task_error(error) from error
    return asdict(task)


def _notes_service(request: Request) -> NotesService:
    service: NotesService = request.app.state.notes_service
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
    if isinstance(error, TaskDispatchError):
        return _http_error(
            502,
            "TASK_DISPATCH_FAILED",
            "Task dispatch failed after persistence; retry with a new task_id.",
        )
    raise error


def _http_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})

from __future__ import annotations

from dataclasses import asdict

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from loanagent.auth import require_ops
from loanagent.engagement import EngagementChainNotFoundError, EngagementService
from loanagent.tasks import TaskNotFoundError


router = APIRouter(prefix="/api/v1")


class ManualChainPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    publish_task_id: str = Field(min_length=1, max_length=128)
    engager_account_ids: list[str] = Field(min_length=1)
    platform: str | None = Field(default="xhs", min_length=1, max_length=32)
    note_ref: str | None = Field(default=None, max_length=512)


@router.get("/engagement/chains", dependencies=[Depends(require_ops)])
def list_engagement_chains(request: Request) -> list[dict]:
    return [asdict(chain) for chain in _engagement_service(request).list_chains()]


@router.post("/engagement/chains", dependencies=[Depends(require_ops)])
def create_engagement_chain(payload: ManualChainPayload, request: Request) -> dict:
    service = _engagement_service(request)
    try:
        chain = service.create_manual(
            publish_task_id=payload.publish_task_id,
            engager_account_ids=payload.engager_account_ids,
            platform=payload.platform,
            note_ref=payload.note_ref,
        )
    except TaskNotFoundError as error:
        raise HTTPException(
            status_code=404,
            detail={"code": "TASK_NOT_FOUND", "message": "Publish task does not exist."},
        ) from error
    except ValueError as error:
        raise HTTPException(
            status_code=400,
            detail={"code": "INVALID_CHAIN", "message": str(error)},
        ) from error
    return asdict(chain)


@router.post("/engagement/chains/{chain_id}/stop", dependencies=[Depends(require_ops)])
def stop_engagement_chain(chain_id: str, request: Request) -> dict:
    service = _engagement_service(request)
    try:
        return asdict(service.stop(chain_id))
    except EngagementChainNotFoundError as error:
        raise HTTPException(
            status_code=404,
            detail={"code": "CHAIN_NOT_FOUND", "message": "Engagement chain does not exist."},
        ) from error


@router.post("/engagement/chains/{chain_id}/advance", dependencies=[Depends(require_ops)])
def advance_engagement_chain(chain_id: str, request: Request) -> dict:
    service = _engagement_service(request)
    try:
        return asdict(service.advance(chain_id))
    except EngagementChainNotFoundError as error:
        raise HTTPException(
            status_code=404,
            detail={"code": "CHAIN_NOT_FOUND", "message": "Engagement chain does not exist."},
        ) from error


@router.get("/alerts", dependencies=[Depends(require_ops)])
def list_alerts(request: Request) -> list[dict]:
    return [asdict(alert) for alert in _engagement_service(request).list_alerts()]


def _engagement_service(request: Request) -> EngagementService:
    return request.app.state.engagement_service

from __future__ import annotations

import os
from pathlib import Path
from urllib.parse import parse_qs

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from fastapi.templating import Jinja2Templates

from loanagent.accounts import AccountRepository
from loanagent.devices import DeviceRepository
from loanagent.tasks import TaskService


router = APIRouter(prefix="/ops")
templates = Jinja2Templates(directory=Path(__file__).with_name("templates"))


@router.get("/login", response_class=HTMLResponse)
def login_page(request: Request) -> Response:
    return templates.TemplateResponse(
        request,
        "login.html",
        {"error": None},
    )


@router.post("/login", response_class=HTMLResponse)
async def login_submit(request: Request) -> Response:
    token = _form_value(await request.body(), "token")
    if not _valid_ops_token(token):
        return templates.TemplateResponse(
            request,
            "login.html",
            {"error": "Invalid ops token"},
            status_code=401,
        )

    response = RedirectResponse("/ops/", status_code=303)
    # Insecure by design for localhost-only M1 ops: the raw OPS_TOKEN is stored in the cookie.
    response.set_cookie(
        "ops_session",
        token,
        httponly=True,
        samesite="lax",
    )
    return response


@router.get("/", response_class=HTMLResponse)
def dashboard(request: Request) -> Response:
    redirect = _redirect_if_unauthenticated(request)
    if redirect is not None:
        return redirect

    devices = _device_repository(request).list()
    accounts = _account_repository(request).list()
    tasks = _task_service(request).list()
    return templates.TemplateResponse(
        request,
        "dashboard.html",
        {"devices": devices, "accounts": accounts, "tasks": tasks},
    )


@router.get("/devices", response_class=HTMLResponse)
def devices_page(request: Request) -> Response:
    redirect = _redirect_if_unauthenticated(request)
    if redirect is not None:
        return redirect

    return templates.TemplateResponse(
        request,
        "devices.html",
        {"devices": _device_repository(request).list()},
    )


@router.get("/accounts", response_class=HTMLResponse)
def accounts_page(request: Request) -> Response:
    redirect = _redirect_if_unauthenticated(request)
    if redirect is not None:
        return redirect

    return templates.TemplateResponse(
        request,
        "accounts.html",
        {"accounts": _account_repository(request).list()},
    )


@router.get("/tasks", response_class=HTMLResponse)
def tasks_page(request: Request) -> Response:
    redirect = _redirect_if_unauthenticated(request)
    if redirect is not None:
        return redirect

    return templates.TemplateResponse(
        request,
        "tasks.html",
        {"tasks": _task_service(request).list()},
    )


def _redirect_if_unauthenticated(request: Request) -> RedirectResponse | None:
    if _valid_ops_token(request.cookies.get("ops_session")):
        return None
    return RedirectResponse("/ops/login", status_code=303)


def _valid_ops_token(token: str | None) -> bool:
    expected = os.environ.get("OPS_TOKEN", "")
    return bool(expected) and token == expected


def _form_value(body: bytes, key: str) -> str | None:
    values = parse_qs(body.decode("utf-8"), keep_blank_values=True)
    first = values.get(key, [None])[0]
    return first if first else None


def _device_repository(request: Request) -> DeviceRepository:
    return request.app.state.device_repository


def _account_repository(request: Request) -> AccountRepository:
    return request.app.state.account_repository


def _task_service(request: Request) -> TaskService:
    return request.app.state.task_service

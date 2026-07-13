from __future__ import annotations

import os
from urllib.parse import parse_qs

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response


router = APIRouter(prefix="/ops")


@router.get("/login")
def login_page() -> Response:
    return RedirectResponse("/login", status_code=303)


@router.post("/login")
async def login_submit(request: Request) -> Response:
    token = _form_value(await request.body(), "token")
    if not _valid_ops_token(token):
        return JSONResponse({"ok": False, "error": "Invalid ops token"}, status_code=401)

    response = RedirectResponse("/", status_code=303)
    response.set_cookie(
        "ops_session",
        token or "",
        httponly=True,
        samesite="lax",
    )
    return response


@router.post("/api/login")
async def api_login(request: Request) -> Response:
    try:
        body = await request.json()
    except Exception:
        return JSONResponse({"ok": False, "error": "令牌不正确"}, status_code=400)
    token = str(body.get("token") or "")
    if not _valid_ops_token(token):
        return JSONResponse({"ok": False, "error": "令牌不正确"}, status_code=401)
    response = JSONResponse({"ok": True})
    response.set_cookie(
        "ops_session",
        token,
        httponly=True,
        samesite="lax",
    )
    return response


@router.post("/api/logout")
async def api_logout() -> Response:
    response = JSONResponse({"ok": True})
    response.delete_cookie("ops_session")
    return response


@router.get("/api/session")
def api_session(request: Request) -> Response:
    if _valid_ops_token(request.cookies.get("ops_session")):
        return JSONResponse({"ok": True})
    return JSONResponse({"ok": False}, status_code=401)


@router.get("/")
def dashboard(request: Request) -> Response:
    redirect = _redirect_if_unauthenticated(request)
    if redirect is not None:
        return redirect
    return RedirectResponse("/", status_code=303)


@router.api_route(
    "/{path:path}",
    methods=["GET", "POST"],
    include_in_schema=False,
)
def legacy_ops_pages(path: str, request: Request) -> Response:
    """Send legacy Jinja URLs into the Vue SPA."""
    redirect = _redirect_if_unauthenticated(request)
    if redirect is not None:
        return redirect
    mapping = {
        "devices": "/devices",
        "accounts": "/accounts",
        "tasks": "/tasks",
        "content": "/contents",
        "publish": "/publish",
        "comments": "/comments",
        "inbox": "/inbox",
    }
    if path.startswith("inbox/"):
        return RedirectResponse(f"/{path}", status_code=303)
    target = mapping.get(path.split("/", 1)[0], "/")
    if path.startswith("comments") or path.startswith("inbox"):
        # form posts under comments/* or inbox/*
        root = path.split("/", 1)[0]
        target = mapping.get(root, "/")
    return RedirectResponse(target, status_code=303)


def _redirect_if_unauthenticated(request: Request) -> RedirectResponse | None:
    if _valid_ops_token(request.cookies.get("ops_session")):
        return None
    return RedirectResponse("/login", status_code=303)


def _valid_ops_token(token: str | None) -> bool:
    expected = os.environ.get("OPS_TOKEN", "")
    return bool(expected) and token == expected


def _form_value(body: bytes, key: str) -> str | None:
    values = parse_qs(body.decode("utf-8"), keep_blank_values=True)
    first = values.get(key, [None])[0]
    return first if first else None

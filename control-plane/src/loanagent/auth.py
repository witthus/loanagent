import os

from fastapi import Depends, Header, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

_security = HTTPBearer(auto_error=False)


def require_ops(
    request: Request,
    creds: HTTPAuthorizationCredentials | None = Depends(_security),
) -> str:
    expected = os.environ.get("OPS_TOKEN", "")
    if not expected:
        raise HTTPException(status_code=401, detail="unauthorized")
    if creds is not None and creds.credentials == expected:
        return "ops"
    if request.cookies.get("ops_session") == expected:
        return "ops"
    raise HTTPException(status_code=401, detail="unauthorized")


def require_device(
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> str:
    expected = os.environ.get("DEVICE_TOKEN", "")
    if not expected or x_device_token != expected:
        raise HTTPException(status_code=401, detail="unauthorized")
    return "device"


def require_device_or_ops(
    request: Request,
    creds: HTTPAuthorizationCredentials | None = Depends(_security),
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> str:
    """Accept device token (preferred) or ops bearer/cookie (debug / ops simulation)."""
    device_expected = os.environ.get("DEVICE_TOKEN", "")
    if device_expected and x_device_token == device_expected:
        return "device"
    ops_expected = os.environ.get("OPS_TOKEN", "")
    if ops_expected and creds is not None and creds.credentials == ops_expected:
        return "ops"
    if ops_expected and request.cookies.get("ops_session") == ops_expected:
        return "ops"
    raise HTTPException(status_code=401, detail="unauthorized")

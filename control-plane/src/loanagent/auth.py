import os

from fastapi import Depends, Header, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

_security = HTTPBearer(auto_error=False)


def require_ops(
    creds: HTTPAuthorizationCredentials | None = Depends(_security),
) -> str:
    expected = os.environ.get("OPS_TOKEN", "")
    if not expected or creds is None or creds.credentials != expected:
        raise HTTPException(status_code=401, detail="unauthorized")
    return "ops"


def require_device(
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> str:
    expected = os.environ.get("DEVICE_TOKEN", "")
    if not expected or x_device_token != expected:
        raise HTTPException(status_code=401, detail="unauthorized")
    return "device"

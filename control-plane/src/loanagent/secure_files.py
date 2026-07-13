from __future__ import annotations

import os
import stat
import tempfile
from pathlib import Path


def read_mode_0600_secret(path: Path) -> str:
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"{path} must be a regular file")
        if stat.S_IMODE(metadata.st_mode) != 0o600:
            raise ValueError(f"{path} must have mode 0600")
        with os.fdopen(descriptor, encoding="utf-8", closefd=False) as file:
            value = file.read().strip()
    finally:
        os.close(descriptor)
    if not value:
        raise ValueError(f"{path} must not be empty")
    return value


def atomic_write_mode_0600(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    temporary_path = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as file:
            file.write(content)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary_path, path)
        os.chmod(path, 0o600)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise

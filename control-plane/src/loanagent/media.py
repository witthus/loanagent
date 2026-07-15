from __future__ import annotations

import hashlib
import hmac
import os
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from urllib.parse import urlencode
from uuid import uuid4

import psycopg

from loanagent.db import migrate_fleet_schema
from loanagent.secure_files import atomic_write_mode_0600


DEFAULT_MEDIA_ROOT = "/tmp/loanagent-media"
DEFAULT_SIGNED_TTL_SEC = 900


@dataclass(frozen=True)
class MediaRecord:
    media_id: str
    content_type: str
    sha256: str
    byte_size: int
    storage_path: str
    created_at: datetime


class MediaNotFoundError(Exception):
    pass


class MediaSignatureError(Exception):
    pass


class MediaRepository:
    def __init__(self, database_url: str, *, media_root: str | None = None) -> None:
        self.database_url = database_url
        self.media_root = Path(media_root or os.environ.get("MEDIA_ROOT", DEFAULT_MEDIA_ROOT))

    def migrate(self) -> None:
        migrate_fleet_schema(self.database_url)

    def create_from_bytes(
        self,
        *,
        data: bytes,
        content_type: str,
        filename: str | None = None,
    ) -> MediaRecord:
        media_id = str(uuid4())
        digest = hashlib.sha256(data).hexdigest()
        extension = Path(filename or "").suffix
        relative = f"{media_id}{extension}" if extension else media_id
        storage_path = str(self.media_root / relative)
        self.save_file(storage_path, data)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                INSERT INTO media_objects (
                    media_id, content_type, sha256, byte_size, storage_path
                )
                VALUES (%s, %s, %s, %s, %s)
                RETURNING media_id, content_type, sha256, byte_size, storage_path, created_at
                """,
                (media_id, content_type, digest, len(data), storage_path),
            ).fetchone()
        return _media_from_row(row)

    def get(self, media_id: str) -> MediaRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT media_id, content_type, sha256, byte_size, storage_path, created_at
                FROM media_objects
                WHERE media_id = %s
                """,
                (media_id,),
            ).fetchone()
        if row is None:
            raise MediaNotFoundError(media_id)
        return _media_from_row(row)

    def save_file(self, storage_path: str, data: bytes) -> None:
        atomic_write_mode_0600(Path(storage_path), data)

    def resolve_path(self, record: "MediaRecord") -> Path:
        primary = Path(record.storage_path)
        if primary.is_file():
            return primary
        # Recover when MEDIA_ROOT moved but DB still has an older absolute path.
        fallback = self.media_root / primary.name
        if fallback.is_file():
            return fallback
        return primary

    def read_bytes(self, media_id: str) -> bytes:
        record = self.get(media_id)
        return self.resolve_path(record).read_bytes()

    def signed_download_path(self, media_id: str) -> str:
        return f"/api/v1/media/{media_id}/download"

    def signed_download_query(
        self,
        media_id: str,
        *,
        ttl_sec: int = DEFAULT_SIGNED_TTL_SEC,
        now: int | None = None,
    ) -> dict[str, str]:
        exp = str((now if now is not None else int(time.time())) + ttl_sec)
        sig = self._sign(media_id, exp)
        return {"exp": exp, "sig": sig}

    def signed_download_url(
        self,
        media_id: str,
        *,
        ttl_sec: int = DEFAULT_SIGNED_TTL_SEC,
        now: int | None = None,
    ) -> str:
        base = os.environ.get("PUBLIC_BASE_URL", "http://127.0.0.1:8000").rstrip("/")
        path = self.signed_download_path(media_id)
        query = urlencode(self.signed_download_query(media_id, ttl_sec=ttl_sec, now=now))
        return f"{base}{path}?{query}"

    def verify_signature(self, media_id: str, *, exp: str, sig: str) -> None:
        try:
            expires_at = int(exp)
        except (TypeError, ValueError) as error:
            raise MediaSignatureError("invalid exp") from error
        if expires_at < int(time.time()):
            raise MediaSignatureError("expired")
        expected = self._sign(media_id, exp)
        if not hmac.compare_digest(expected, sig or ""):
            raise MediaSignatureError("bad signature")

    def _sign(self, media_id: str, exp: str) -> str:
        secret = _signing_secret().encode("utf-8")
        message = f"{media_id}:{exp}".encode("utf-8")
        return hmac.new(secret, message, hashlib.sha256).hexdigest()


def _signing_secret() -> str:
    secret = (os.environ.get("MEDIA_SIGNING_SECRET") or "").strip()
    if not secret:
        raise MediaSignatureError("MEDIA_SIGNING_SECRET is required")
    return secret


def _media_from_row(row: tuple) -> MediaRecord:
    return MediaRecord(
        media_id=row[0],
        content_type=row[1],
        sha256=row[2],
        byte_size=row[3],
        storage_path=row[4],
        created_at=row[5],
    )

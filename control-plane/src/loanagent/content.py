from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb

from loanagent.db import migrate_fleet_schema
from loanagent.platforms import DEFAULT_PLATFORM, normalize_platform
from loanagent.sensitivity import scan_text


@dataclass(frozen=True)
class ContentRecord:
    content_id: str
    title: str
    body: str
    media_ids: list[str]
    geo_tags: list[Any] | dict[str, Any] | None
    sensitivity_status: str
    sensitivity_hits: list[str]
    platform: str
    created_at: datetime
    updated_at: datetime


class ContentNotFoundError(Exception):
    pass


class ContentSensitivityError(Exception):
    def __init__(self, hits: list[str]) -> None:
        super().__init__(", ".join(hits))
        self.hits = hits


class ContentRepository:
    def __init__(self, database_url: str) -> None:
        self.database_url = database_url

    def migrate(self) -> None:
        migrate_fleet_schema(self.database_url)

    def create(
        self,
        *,
        title: str,
        body: str,
        media_ids: list[str] | None = None,
        geo_tags: list[Any] | dict[str, Any] | None = None,
        platform: str | None = None,
    ) -> ContentRecord:
        media_ids = list(media_ids or [])
        platform_value = normalize_platform(platform)
        hits = scan_text(title, body)
        if hits:
            raise ContentSensitivityError(hits)
        content_id = str(uuid4())
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                INSERT INTO content_assets (
                    content_id, title, body, media_ids, geo_tags,
                    sensitivity_status, sensitivity_hits, platform
                )
                VALUES (%s, %s, %s, %s, %s, 'clean', %s, %s)
                RETURNING content_id, title, body, media_ids, geo_tags,
                    sensitivity_status, sensitivity_hits, platform, created_at, updated_at
                """,
                (
                    content_id,
                    title,
                    body,
                    Jsonb(media_ids),
                    Jsonb(geo_tags) if geo_tags is not None else None,
                    Jsonb([]),
                    platform_value,
                ),
            ).fetchone()
        return _content_from_row(row)

    def list(self, *, platform: str | None = None) -> list[ContentRecord]:
        query = """
            SELECT content_id, title, body, media_ids, geo_tags,
                sensitivity_status, sensitivity_hits, platform, created_at, updated_at
            FROM content_assets
        """
        params: list[Any] = []
        if platform is not None:
            query += " WHERE platform = %s"
            params.append(normalize_platform(platform))
        query += " ORDER BY created_at DESC, content_id"
        with psycopg.connect(self.database_url) as connection:
            rows = connection.execute(query, params).fetchall()
        return [_content_from_row(row) for row in rows]

    def get(self, content_id: str) -> ContentRecord:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                SELECT content_id, title, body, media_ids, geo_tags,
                    sensitivity_status, sensitivity_hits, platform, created_at, updated_at
                FROM content_assets
                WHERE content_id = %s
                """,
                (content_id,),
            ).fetchone()
        if row is None:
            raise ContentNotFoundError(content_id)
        return _content_from_row(row)

    def update(
        self,
        content_id: str,
        *,
        title: str,
        body: str,
        media_ids: list[str] | None = None,
        geo_tags: list[Any] | dict[str, Any] | None = None,
        platform: str | None = None,
    ) -> ContentRecord:
        self.get(content_id)
        media_ids = list(media_ids or [])
        platform_value = normalize_platform(platform)
        hits = scan_text(title, body)
        if hits:
            raise ContentSensitivityError(hits)
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                UPDATE content_assets
                SET title = %s,
                    body = %s,
                    media_ids = %s,
                    geo_tags = %s,
                    sensitivity_status = 'clean',
                    sensitivity_hits = %s,
                    platform = %s,
                    updated_at = CURRENT_TIMESTAMP
                WHERE content_id = %s
                RETURNING content_id, title, body, media_ids, geo_tags,
                    sensitivity_status, sensitivity_hits, platform, created_at, updated_at
                """,
                (
                    title,
                    body,
                    Jsonb(media_ids),
                    Jsonb(geo_tags) if geo_tags is not None else None,
                    Jsonb([]),
                    platform_value,
                    content_id,
                ),
            ).fetchone()
        if row is None:
            raise ContentNotFoundError(content_id)
        return _content_from_row(row)

    def delete(self, content_id: str) -> None:
        with psycopg.connect(self.database_url) as connection:
            row = connection.execute(
                """
                DELETE FROM content_assets
                WHERE content_id = %s
                RETURNING content_id
                """,
                (content_id,),
            ).fetchone()
        if row is None:
            raise ContentNotFoundError(content_id)


def _content_from_row(row: tuple) -> ContentRecord:
    return ContentRecord(
        content_id=row[0],
        title=row[1],
        body=row[2],
        media_ids=list(row[3] or []),
        geo_tags=row[4],
        sensitivity_status=row[5],
        sensitivity_hits=list(row[6] or []),
        platform=row[7],
        created_at=row[8],
        updated_at=row[9],
    )

from __future__ import annotations

from typing import Literal

Platform = Literal["xhs", "douyin"]
VALID_PLATFORMS: set[str] = {"xhs", "douyin"}
DEFAULT_PLATFORM: Platform = "xhs"


def normalize_platform(value: str | None) -> Platform:
    platform = (value or DEFAULT_PLATFORM).strip().lower()
    if platform not in VALID_PLATFORMS:
        raise ValueError(f"unsupported platform: {value}")
    return platform  # type: ignore[return-value]

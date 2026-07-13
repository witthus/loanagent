from __future__ import annotations

import os


_DEFAULT_SENSITIVE_WORDS = (
    "无抵押",
    "秒贷",
    "低息",
    "放款",
    "套现",
    "高利贷",
    "网贷",
    "信用卡套现",
    "借壳",
    "洗钱",
    "保本高收益",
    "稳赚不赔",
    "日息",
    "黑户可办",
    "无视征信",
)


def _sensitive_words() -> list[str]:
    override = os.environ.get("SENSITIVE_WORDS", "").strip()
    if override:
        return [part.strip() for part in override.split(",") if part.strip()]
    return list(_DEFAULT_SENSITIVE_WORDS)


def scan_text(*parts: str | None) -> list[str]:
    combined = "\n".join(part for part in parts if part)
    if not combined:
        return []
    hits: list[str] = []
    for word in _sensitive_words():
        if word and word in combined:
            hits.append(word)
    return hits


def assert_clean(*parts: str | None) -> None:
    hits = scan_text(*parts)
    if hits:
        raise ValueError(f"sensitive words detected: {', '.join(hits)}")

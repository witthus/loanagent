"""Unit tests for client IP extraction and geo label normalization."""

from __future__ import annotations

from starlette.requests import Request

from loanagent.geo_ip import (
    _normalize_geo_label,
    cached_geo_label,
    clear_geo_cache,
    extract_client_ip,
    format_location_cell,
    lookup_geo_label,
    needs_geo_refresh,
)


def _request_with_headers(headers: dict[str, str], client_host: str = "127.0.0.1") -> Request:
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": "/",
        "raw_path": b"/",
        "query_string": b"",
        "headers": [(k.lower().encode(), v.encode()) for k, v in headers.items()],
        "client": (client_host, 12345),
        "server": ("test", 80),
    }
    return Request(scope)


def test_extract_client_ip_ignores_spoofed_forwarded_by_default() -> None:
    request = _request_with_headers(
        {"x-forwarded-for": "1.2.3.4, 10.0.0.1"},
        client_host="8.8.8.8",
    )
    assert extract_client_ip(request) == "8.8.8.8"


def test_extract_client_ip_trusts_forwarded_only_from_trusted_proxy(monkeypatch) -> None:
    monkeypatch.setenv("TRUST_PROXY_HEADERS", "1")
    monkeypatch.setenv("TRUSTED_PROXIES", "10.0.0.0/8")
    request = _request_with_headers(
        {"x-forwarded-for": "1.2.3.4"},
        client_host="10.0.0.2",
    )
    assert extract_client_ip(request) == "1.2.3.4"

    spoofed = _request_with_headers(
        {"x-forwarded-for": "9.9.9.9"},
        client_host="8.8.8.8",
    )
    assert extract_client_ip(spoofed) == "8.8.8.8"


def test_extract_client_ip_falls_back_to_peer() -> None:
    request = _request_with_headers({}, client_host="8.8.8.8")
    assert extract_client_ip(request) == "8.8.8.8"


def test_lookup_geo_skips_private_ip() -> None:
    assert lookup_geo_label("192.168.1.1") is None
    assert lookup_geo_label("127.0.0.1") is None


def test_cached_geo_label_never_hits_network(monkeypatch) -> None:
    clear_geo_cache()

    def boom(_ip: str) -> str | None:
        raise AssertionError("network should not be called")

    monkeypatch.setattr("loanagent.geo_ip._lookup_pconline", boom)
    assert cached_geo_label("1.2.3.4") is None
    assert needs_geo_refresh("1.2.3.4") is True


def test_normalize_geo_label_province_city() -> None:
    assert _normalize_geo_label("湖北省", "黄冈市", "", "") == "湖北黄冈"
    assert _normalize_geo_label("北京市", "北京市", "", "") == "北京"
    assert _normalize_geo_label("广东省", "深圳市", "", "") == "广东深圳"
    assert _normalize_geo_label("广西壮族自治区", "南宁市", "", "") == "广西南宁"


def test_format_location_cell() -> None:
    assert format_location_cell("湖北黄冈", "1.2.3.4") == "湖北黄冈 · 1.2.3.4"
    assert format_location_cell(None, "1.2.3.4") == "1.2.3.4"
    assert format_location_cell(None, None) == "—"

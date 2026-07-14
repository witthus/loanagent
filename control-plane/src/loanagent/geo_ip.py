"""Extract client IP and resolve a short China geo label (e.g. 湖北黄冈).

By default proxy headers are ignored because control-plane is often exposed
directly on :80. Set TRUST_PROXY_HEADERS=1 and TRUSTED_PROXIES=<cidrs> only
when a real reverse proxy terminates TLS in front of the app.
"""

from __future__ import annotations

import ipaddress
import json
import logging
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import OrderedDict
from typing import Any

from starlette.requests import Request

logger = logging.getLogger(__name__)

_GEO_CACHE: OrderedDict[str, tuple[float, str | None]] = OrderedDict()
_GEO_CACHE_LOCK = threading.Lock()
_GEO_CACHE_TTL_SEC = 6 * 60 * 60
_GEO_CACHE_MAX_ENTRIES = 2048
_GEO_LOOKUP_TIMEOUT_SEC = 1.5
_GEO_MAX_RESPONSE_BYTES = 8 * 1024
_PCONLINE_URL = "https://whois.pconline.com.cn/ipJson.jsp"
_IN_FLIGHT: set[str] = set()
_IN_FLIGHT_LOCK = threading.Lock()


def trust_proxy_headers_enabled() -> bool:
    return os.environ.get("TRUST_PROXY_HEADERS", "").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


def trusted_proxy_networks() -> list[ipaddress.IPv4Network | ipaddress.IPv6Network]:
    raw = os.environ.get("TRUSTED_PROXIES", "").strip()
    if not raw:
        return []
    networks: list[ipaddress.IPv4Network | ipaddress.IPv6Network] = []
    for part in raw.split(","):
        token = part.strip()
        if not token:
            continue
        try:
            networks.append(ipaddress.ip_network(token, strict=False))
        except ValueError:
            logger.warning("Ignoring invalid TRUSTED_PROXIES entry: %s", token)
    return networks


def extract_client_ip(request: Request) -> str | None:
    """Return the connecting client IP.

    Proxy headers are used only when TRUST_PROXY_HEADERS is enabled and the
    direct peer belongs to TRUSTED_PROXIES.
    """
    peer = ""
    if request.client and request.client.host:
        peer = request.client.host.strip()

    if (
        trust_proxy_headers_enabled()
        and peer
        and _peer_is_trusted_proxy(peer)
    ):
        forwarded = (request.headers.get("x-forwarded-for") or "").strip()
        if forwarded:
            candidate = forwarded.split(",")[0].strip()
            if _is_usable_ip(candidate):
                return candidate
        real_ip = (request.headers.get("x-real-ip") or "").strip()
        if _is_usable_ip(real_ip):
            return real_ip

    if _is_usable_ip(peer):
        return peer
    return None


def cached_geo_label(ip: str | None) -> str | None:
    """Return a cached geo label only — never performs network I/O."""
    if not ip or not _is_public_ip(ip):
        return None
    now = time.monotonic()
    with _GEO_CACHE_LOCK:
        cached = _GEO_CACHE.get(ip)
        if cached is None:
            return None
        expires_at, label = cached
        if now >= expires_at:
            _GEO_CACHE.pop(ip, None)
            return None
        _GEO_CACHE.move_to_end(ip)
        return label


def lookup_geo_label(ip: str | None, *, force_refresh: bool = False) -> str | None:
    """Resolve IP → short region label (may hit the network). Prefer cached_geo_label on request path."""
    if not ip or not _is_public_ip(ip):
        return None

    if not force_refresh:
        with _GEO_CACHE_LOCK:
            cached = _GEO_CACHE.get(ip)
            if cached is not None and time.monotonic() < cached[0]:
                _GEO_CACHE.move_to_end(ip)
                return cached[1]

    label = _lookup_pconline(ip)
    _store_cache(ip, label)
    return label


def needs_geo_refresh(ip: str | None) -> bool:
    if not ip or not _is_public_ip(ip):
        return False
    with _GEO_CACHE_LOCK:
        cached = _GEO_CACHE.get(ip)
        if cached is None:
            return True
        return time.monotonic() >= cached[0]


def format_location_cell(geo_label: str | None, public_ip: str | None) -> str:
    """Human display helper (tests). Prefer geo, fall back to IP."""
    geo = (geo_label or "").strip()
    ip = (public_ip or "").strip()
    if geo and ip:
        return f"{geo} · {ip}"
    return geo or ip or "—"


def clear_geo_cache() -> None:
    with _GEO_CACHE_LOCK:
        _GEO_CACHE.clear()


def _store_cache(ip: str, label: str | None) -> None:
    now = time.monotonic()
    with _GEO_CACHE_LOCK:
        _GEO_CACHE[ip] = (now + _GEO_CACHE_TTL_SEC, label)
        _GEO_CACHE.move_to_end(ip)
        while len(_GEO_CACHE) > _GEO_CACHE_MAX_ENTRIES:
            _GEO_CACHE.popitem(last=False)


def _peer_is_trusted_proxy(peer: str) -> bool:
    try:
        addr = ipaddress.ip_address(peer)
    except ValueError:
        return False
    networks = trusted_proxy_networks()
    if not networks:
        return False
    return any(addr in network for network in networks)


def _is_usable_ip(value: str) -> bool:
    if not value:
        return False
    try:
        ipaddress.ip_address(value)
    except ValueError:
        return False
    return True


def _is_public_ip(value: str) -> bool:
    try:
        addr = ipaddress.ip_address(value)
    except ValueError:
        return False
    return not (
        addr.is_private
        or addr.is_loopback
        or addr.is_link_local
        or addr.is_multicast
        or addr.is_reserved
        or addr.is_unspecified
    )


def _lookup_pconline(ip: str) -> str | None:
    query = urllib.parse.urlencode({"ip": ip, "json": "true"})
    url = f"{_PCONLINE_URL}?{query}"
    try:
        with urllib.request.urlopen(url, timeout=_GEO_LOOKUP_TIMEOUT_SEC) as response:
            raw = response.read(_GEO_MAX_RESPONSE_BYTES + 1)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        logger.debug("geo lookup failed for %s: %s", ip, exc)
        return None

    if len(raw) > _GEO_MAX_RESPONSE_BYTES:
        logger.debug("geo lookup response too large for %s", ip)
        return None

    text = raw.decode("gbk", errors="ignore").strip()
    if not text:
        return None
    try:
        payload: dict[str, Any] = json.loads(text)
    except json.JSONDecodeError:
        logger.debug("geo lookup returned non-JSON for %s: %s", ip, text[:120])
        return None
    return _normalize_geo_label(
        str(payload.get("pro") or ""),
        str(payload.get("city") or ""),
        str(payload.get("region") or ""),
        str(payload.get("addr") or ""),
    )


def _normalize_geo_label(pro: str, city: str, region: str, addr: str) -> str | None:
    province = _strip_admin_suffix(pro)
    city_name = _strip_admin_suffix(city)
    if city_name in {"", "内网", "本机地址", "局域网"}:
        city_name = ""
    if province and city_name:
        if city_name.startswith(province):
            return city_name
        return f"{province}{city_name}"
    if province:
        return province
    if city_name:
        return city_name
    region_name = _strip_admin_suffix(region)
    if region_name:
        return region_name
    addr = addr.strip()
    if addr and ("省" in addr or "市" in addr or "自治区" in addr):
        return addr.split()[0][:16] if addr else None
    return None


def _strip_admin_suffix(value: str) -> str:
    text = value.strip()
    # Longest / most specific first so 广西壮族自治区 → 广西
    for suffix in (
        "特别行政区",
        "维吾尔自治区",
        "壮族自治区",
        "回族自治区",
        "自治区",
        "省",
        "市",
        "地区",
        "盟",
    ):
        if text.endswith(suffix) and len(text) > len(suffix):
            text = text[: -len(suffix)]
            break
    return text.strip()


def begin_geo_refresh(ip: str) -> bool:
    """Claim an in-flight refresh slot. Returns False if already refreshing."""
    with _IN_FLIGHT_LOCK:
        if ip in _IN_FLIGHT:
            return False
        _IN_FLIGHT.add(ip)
        return True


def end_geo_refresh(ip: str) -> None:
    with _IN_FLIGHT_LOCK:
        _IN_FLIGHT.discard(ip)

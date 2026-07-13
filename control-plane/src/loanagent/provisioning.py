from __future__ import annotations

import argparse
import base64
import binascii
import io
import ipaddress
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from urllib.parse import urlparse

from loanagent.secure_files import atomic_write_mode_0600, read_mode_0600_secret


DEVICE_ADMIN_COMPONENT = (
    "com.loanagent.devicecontroller/.LoanAgentDeviceAdminReceiver"
)
COMPONENT_KEY = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
DOWNLOAD_KEY = (
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"
)
CHECKSUM_KEY = (
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"
)
EXTRAS_KEY = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"


@dataclass(frozen=True)
class ProvisioningConfig:
    apk_url: str
    signature_checksum: str
    enrollment_token: str
    control_plane_url: str
    update_manifest_url: str
    update_key_id: str
    update_public_key: str
    trusted_update_host: str
    trusted_control_plane_host: str


def build_provisioning_payload(config: ProvisioningConfig) -> dict[str, object]:
    _require_https(config.apk_url, "apk_url")
    _require_https(config.control_plane_url, "control_plane_url")
    _require_https(config.update_manifest_url, "update_manifest_url")
    _require_sha256_checksum(config.signature_checksum)
    _require_non_empty(config.enrollment_token, "enrollment_token")
    _require_non_empty(config.update_key_id, "update_key_id")
    _require_standard_base64(config.update_public_key, "update_public_key")
    _require_hostname(config.trusted_update_host, "trusted_update_host")
    _require_hostname(
        config.trusted_control_plane_host,
        "trusted_control_plane_host",
    )
    _require_url_host(
        config.control_plane_url,
        config.trusted_control_plane_host,
        "control_plane_url",
    )
    _require_url_host(
        config.update_manifest_url,
        config.trusted_update_host,
        "update_manifest_url",
    )
    _require_url_host(config.apk_url, config.trusted_update_host, "apk_url")

    return {
        COMPONENT_KEY: DEVICE_ADMIN_COMPONENT,
        DOWNLOAD_KEY: config.apk_url,
        CHECKSUM_KEY: config.signature_checksum,
        EXTRAS_KEY: {
            "enrollment_token": config.enrollment_token,
            "control_plane_url": config.control_plane_url,
            "update_manifest_url": config.update_manifest_url,
            "update_key_id": config.update_key_id,
            "update_public_key": config.update_public_key,
            "trusted_update_host": config.trusted_update_host.lower(),
            "trusted_control_plane_host": config.trusted_control_plane_host.lower(),
        },
    }


def serialize_payload(payload: dict[str, object]) -> str:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def read_enrollment_token(path: Path) -> str:
    return read_mode_0600_secret(path)


def _render_qr_png(payload: dict[str, object]) -> bytes:
    import qrcode

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=8,
        border=4,
    )
    qr.add_data(serialize_payload(payload))
    qr.make(fit=True)
    image = qr.make_image(fill_color="black", back_color="white")
    output = io.BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def write_provisioning_outputs(
    payload: dict[str, object],
    json_output: Path,
    png_output: Path,
) -> None:
    atomic_write_mode_0600(
        json_output,
        (serialize_payload(payload) + "\n").encode("utf-8"),
    )
    atomic_write_mode_0600(png_output, _render_qr_png(payload))


def _require_https(value: str, name: str) -> None:
    parsed = urlparse(value)
    if (
        parsed.scheme.lower() != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port not in (None, 443)
    ):
        raise ValueError(f"{name} must be an HTTPS URL without user info")


def _require_sha256_checksum(value: str) -> None:
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, binascii.Error) as error:
        raise ValueError("signature_checksum must be URL-safe base64") from error
    if len(decoded) != 32:
        raise ValueError("signature_checksum must encode exactly 32 bytes")
    canonical = base64.urlsafe_b64encode(decoded).decode().rstrip("=")
    if canonical != value:
        raise ValueError("signature_checksum must be unpadded URL-safe base64")


def _require_standard_base64(value: str, name: str) -> None:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError(f"{name} must be standard base64") from error
    if not decoded:
        raise ValueError(f"{name} must decode to non-empty bytes")


def _require_non_empty(value: str, name: str) -> None:
    if not value.strip():
        raise ValueError(f"{name} must not be empty")


def _require_hostname(value: str, name: str) -> None:
    parsed = urlparse(f"//{value}")
    if (
        not parsed.hostname
        or parsed.hostname != value.lower()
        or parsed.port is not None
        or parsed.path
        or parsed.username is not None
        or parsed.password is not None
    ):
        raise ValueError(f"{name} must be a hostname without path or port")
    if value.lower() == "localhost" or value.lower().endswith(".localhost"):
        raise ValueError(f"{name} must not be localhost")
    try:
        ipaddress.ip_address(value)
    except ValueError:
        return
    raise ValueError(f"{name} must not be an IP literal")


def _require_url_host(value: str, trusted_host: str, name: str) -> None:
    if urlparse(value).hostname != trusted_host.lower():
        raise ValueError(f"{name} host must exactly match its trusted host")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate Android Device Owner provisioning JSON and QR PNG.",
    )
    fields = tuple(
        field
        for field in asdict(
            ProvisioningConfig(
                apk_url="",
                signature_checksum="",
                enrollment_token="",
                control_plane_url="",
                update_manifest_url="",
                update_key_id="",
                update_public_key="",
                trusted_update_host="",
                trusted_control_plane_host="",
            )
        )
        if field != "enrollment_token"
    )
    for field in fields:
        parser.add_argument(f"--{field.replace('_', '-')}", required=True)
    parser.add_argument("--enrollment-token-file", type=Path, required=True)
    parser.add_argument("--json-output", type=Path, required=True)
    parser.add_argument("--png-output", type=Path, required=True)
    args = parser.parse_args(argv)

    values = {field: getattr(args, field) for field in fields}
    values["enrollment_token"] = read_enrollment_token(args.enrollment_token_file)
    config = ProvisioningConfig(**values)
    payload = build_provisioning_payload(config)
    write_provisioning_outputs(payload, args.json_output, args.png_output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

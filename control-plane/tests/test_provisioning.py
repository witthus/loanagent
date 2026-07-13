import base64
import stat

import pytest

from loanagent.provisioning import (
    ProvisioningConfig,
    build_provisioning_payload,
    read_enrollment_token,
    write_provisioning_outputs,
)


def valid_config() -> ProvisioningConfig:
    return ProvisioningConfig(
        apk_url="https://updates.loanagent.example/device-controller.apk",
        signature_checksum=base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("="),
        enrollment_token="single-use-token",
        control_plane_url="https://control.loanagent.example/enroll",
        update_manifest_url="https://updates.loanagent.example/agent-manifest.json",
        update_key_id="m0-key",
        update_public_key=base64.b64encode(b"DER public key fixture").decode(),
        trusted_update_host="updates.loanagent.example",
        trusted_control_plane_host="control.loanagent.example",
    )


def test_builds_android_device_owner_qr_payload_with_enrollment_config() -> None:
    payload = build_provisioning_payload(valid_config())

    assert payload[
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
    ] == "com.loanagent.devicecontroller/.LoanAgentDeviceAdminReceiver"
    assert payload[
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"
    ] == "https://updates.loanagent.example/device-controller.apk"
    assert payload[
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"
    ] == valid_config().signature_checksum
    assert payload["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"] == {
        "enrollment_token": "single-use-token",
        "control_plane_url": "https://control.loanagent.example/enroll",
        "update_manifest_url": "https://updates.loanagent.example/agent-manifest.json",
        "update_key_id": "m0-key",
        "update_public_key": base64.b64encode(b"DER public key fixture").decode(),
        "trusted_update_host": "updates.loanagent.example",
        "trusted_control_plane_host": "control.loanagent.example",
    }


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("apk_url", "http://updates.loanagent.example/device-controller.apk"),
        ("control_plane_url", "http://control.loanagent.example/enroll"),
        ("update_manifest_url", "file:///tmp/manifest.json"),
        ("signature_checksum", "not base64!"),
        ("enrollment_token", ""),
        ("update_public_key", "not base64!"),
        ("trusted_update_host", "updates.loanagent.example/path"),
        ("trusted_control_plane_host", "127.0.0.1"),
        ("trusted_control_plane_host", "localhost"),
        ("control_plane_url", "https://other.loanagent.example/enroll"),
    ],
)
def test_rejects_invalid_or_insecure_provisioning_input(field: str, value: str) -> None:
    values = vars(valid_config()) | {field: value}

    with pytest.raises(ValueError):
        build_provisioning_payload(ProvisioningConfig(**values))


def test_reads_enrollment_token_only_from_mode_0600_file(tmp_path) -> None:
    token_file = tmp_path / "token"
    token_file.write_text("single-use-token\n", encoding="utf-8")
    token_file.chmod(0o600)

    assert read_enrollment_token(token_file) == "single-use-token"

    token_file.chmod(0o644)
    with pytest.raises(ValueError, match="0600"):
        read_enrollment_token(token_file)


def test_atomically_writes_json_and_png_with_mode_0600(tmp_path) -> None:
    json_output = tmp_path / "device-owner.json"
    png_output = tmp_path / "device-owner.png"
    payload = build_provisioning_payload(valid_config())

    write_provisioning_outputs(payload, json_output, png_output)

    assert png_output.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
    assert png_output.stat().st_size > 100
    assert stat.S_IMODE(json_output.stat().st_mode) == 0o600
    assert stat.S_IMODE(png_output.stat().st_mode) == 0o600
    assert list(tmp_path.glob("*.tmp")) == []

from pathlib import Path
from xml.etree import ElementTree


ANDROID = "{http://schemas.android.com/apk/res/android}"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RECOVERY_PERMISSION = "com.loanagent.permission.RECOVER_AGENT"


def parse_manifest(module: str) -> ElementTree.Element:
    return ElementTree.parse(
        REPOSITORY_ROOT / "android" / module / "src" / "main" / "AndroidManifest.xml"
    ).getroot()


def test_dpc_is_the_only_definition_site_for_recovery_signature_permission() -> None:
    dpc = parse_manifest("device-controller")
    agent = parse_manifest("agent")

    definitions = [
        (module, permission.get(f"{ANDROID}protectionLevel"))
        for module, manifest in (("device-controller", dpc), ("agent", agent))
        for permission in manifest.findall("permission")
        if permission.get(f"{ANDROID}name") == RECOVERY_PERMISSION
    ]

    assert definitions == [("device-controller", "signature")]


def test_both_apps_use_permission_and_agent_receiver_requires_it() -> None:
    dpc = parse_manifest("device-controller")
    agent = parse_manifest("agent")

    for manifest in (dpc, agent):
        uses = {
            item.get(f"{ANDROID}name")
            for item in manifest.findall("uses-permission")
        }
        assert RECOVERY_PERMISSION in uses

    receiver = next(
        item
        for item in agent.findall("application/receiver")
        if item.get(f"{ANDROID}name") == ".AgentRecoveryReceiver"
    )
    assert receiver.get(f"{ANDROID}exported") == "true"
    assert receiver.get(f"{ANDROID}permission") == RECOVERY_PERMISSION


def test_accessibility_service_observes_all_packages_while_code_enforces_action_scope() -> None:
    main_service = ElementTree.parse(
        REPOSITORY_ROOT
        / "android"
        / "agent"
        / "src"
        / "main"
        / "res"
        / "xml"
        / "m0_accessibility_service.xml"
    ).getroot()
    debug_service = ElementTree.parse(
        REPOSITORY_ROOT
        / "android"
        / "agent"
        / "src"
        / "debug"
        / "res"
        / "xml"
        / "m0_accessibility_service.xml"
    ).getroot()
    release_service = ElementTree.parse(
        REPOSITORY_ROOT
        / "android"
        / "agent"
        / "src"
        / "release"
        / "res"
        / "xml"
        / "m0_accessibility_service.xml"
    ).getroot()
    assert main_service.get(f"{ANDROID}packageNames") is None
    assert debug_service.get(f"{ANDROID}packageNames") is None
    assert release_service.get(f"{ANDROID}packageNames") is None
    assert main_service.get(f"{ANDROID}canPerformGestures") == "false"
    assert release_service.get(f"{ANDROID}canPerformGestures") == "false"
    assert debug_service.get(f"{ANDROID}canPerformGestures") == "true"

    main_manifest = parse_manifest("agent")
    debug_manifest = ElementTree.parse(
        REPOSITORY_ROOT
        / "android"
        / "agent"
        / "src"
        / "debug"
        / "AndroidManifest.xml"
    ).getroot()
    main_queries = {
        item.get(f"{ANDROID}name") for item in main_manifest.findall("queries/package")
    }
    debug_queries = {
        item.get(f"{ANDROID}name") for item in debug_manifest.findall("queries/package")
    }
    assert main_queries == {"com.xingin.xhs"}
    assert debug_queries == {"com.loanagent.fixture", "com.xingin.xhs"}

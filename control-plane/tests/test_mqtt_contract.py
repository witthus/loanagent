import json
from pathlib import Path


CONTRACT_PATH = Path(__file__).resolve().parents[2] / "schemas" / "mqtt-contract.json"


def test_mqtt_contract_defines_delivery_and_replay_guards() -> None:
    assert CONTRACT_PATH.exists(), "MQTT contract is not implemented"
    contract = json.loads(CONTRACT_PATH.read_text())

    assert contract["version"] == "1.0"
    assert contract["topics"] == {
        "commands": "devices/{deviceId}/commands",
        "events": "devices/{deviceId}/events",
    }
    assert contract["qos"] == 1
    assert contract["delivery_semantics"] == "at_least_once"
    assert contract["deduplication_key"] == "task_id"
    assert set(contract["replay_protection"]) == {"sequence", "nonce"}

from __future__ import annotations

import json
from typing import Any
from urllib.parse import urlparse


class MqttCommandBus:
    def __init__(self, mqtt_url: str) -> None:
        parsed = urlparse(mqtt_url)
        if parsed.scheme != "mqtt":
            raise ValueError("MQTT_URL must use mqtt://")
        self.host = parsed.hostname or "localhost"
        self.port = parsed.port or 1883
        self.username = parsed.username
        self.password = parsed.password

    def publish(self, topic: str, payload: dict[str, Any]) -> None:
        import paho.mqtt.client as mqtt

        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        if self.username is not None:
            client.username_pw_set(self.username, self.password)
        client.connect(self.host, self.port, keepalive=30)
        result = client.publish(topic, json.dumps(payload, separators=(",", ":")), qos=1)
        result.wait_for_publish()
        client.disconnect()

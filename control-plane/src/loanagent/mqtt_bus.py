from __future__ import annotations

import json
from typing import Any
from urllib.parse import urlparse


class MqttPublishError(RuntimeError):
    pass


class MqttCommandBus:
    def __init__(self, mqtt_url: str, *, publish_timeout_sec: float = 5.0) -> None:
        parsed = urlparse(mqtt_url)
        if parsed.scheme != "mqtt":
            raise ValueError("MQTT_URL must use mqtt://")
        self.host = parsed.hostname or "localhost"
        self.port = parsed.port or 1883
        self.username = parsed.username
        self.password = parsed.password
        self.publish_timeout_sec = publish_timeout_sec

    def publish(self, topic: str, payload: dict[str, Any]) -> None:
        import paho.mqtt.client as mqtt

        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        if self.username is not None:
            client.username_pw_set(self.username, self.password)
        connected = False
        loop_started = False
        try:
            connect_rc = client.connect(self.host, self.port, keepalive=30)
            _raise_if_mqtt_error(mqtt, connect_rc, "connect")
            connected = True
            loop_rc = client.loop_start()
            _raise_if_mqtt_error(mqtt, loop_rc, "loop_start")
            loop_started = True
            result = client.publish(topic, json.dumps(payload, separators=(",", ":")), qos=1)
            _raise_if_mqtt_error(mqtt, result.rc, "publish")
            result.wait_for_publish(timeout=self.publish_timeout_sec)
            if not result.is_published():
                raise MqttPublishError(
                    f"Timed out waiting for MQTT PUBACK on {topic} after "
                    f"{self.publish_timeout_sec:g}s"
                )
        finally:
            if connected:
                client.disconnect()
            if loop_started:
                client.loop_stop()


def _raise_if_mqtt_error(mqtt: Any, result_code: int | None, operation: str) -> None:
    if result_code in (None, mqtt.MQTT_ERR_SUCCESS):
        return
    raise MqttPublishError(f"MQTT {operation} failed: {mqtt.error_string(result_code)}")

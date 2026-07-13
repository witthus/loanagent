package com.loanagent.agent

/**
 * Fixed M1 debug endpoints for the current CN server deploy.
 * Debug-only; do not ship release builds with these secrets.
 */
object CloudBridgeConfig {
    const val CONTROL_PLANE_BASE_URL = "http://119.45.36.208"
    const val MQTT_HOST = "119.45.36.208"
    // Public remap: residential networks often block outbound 1883.
    const val MQTT_PORT = 11883
    const val DEVICE_ID = "redmi-note-12"
    const val DEVICE_TOKEN = "cb571ab15f2f873f0fbbb533b16a70a5"
    const val OPS_TOKEN = "admin123"
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val COMMAND_POLL_INTERVAL_MS = 5_000L
    const val ENSURE_APP_READY_TIMEOUT_MS = 20_000L

    fun agentVersion(): String = "${BuildConfig.VERSION_NAME}-debug"

    fun heartbeatUrl(): String =
        "$CONTROL_PLANE_BASE_URL/api/v1/devices/$DEVICE_ID/heartbeat"

    fun eventsUrl(): String =
        "$CONTROL_PLANE_BASE_URL/api/v1/devices/$DEVICE_ID/events"

    fun commandsPollUrl(): String =
        "$CONTROL_PLANE_BASE_URL/api/v1/devices/$DEVICE_ID/commands"

    fun commandsTopic(): String = MqttTopics().commands(DEVICE_ID)
}

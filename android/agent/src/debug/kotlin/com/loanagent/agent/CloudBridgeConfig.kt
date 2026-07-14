package com.loanagent.agent

/**
 * Fixed M1 debug endpoints for the current CN server deploy.
 * Debug-only; do not ship release builds with these secrets.
 *
 * device_id is per-phone (ANDROID_ID), resolved via [init].
 */
object CloudBridgeConfig {
    const val CONTROL_PLANE_BASE_URL = "http://119.45.36.208"
    const val MQTT_HOST = "119.45.36.208"
    // Public remap: residential networks often block outbound 1883.
    const val MQTT_PORT = 11883
    const val DEVICE_TOKEN = "cb571ab15f2f873f0fbbb533b16a70a5"
    const val OPS_TOKEN = "admin123"
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val COMMAND_POLL_INTERVAL_MS = 5_000L
    const val ENSURE_APP_READY_TIMEOUT_MS = 20_000L

    @Volatile
    private var resolvedDeviceId: String? = null

    /** Must be called once with app context before heartbeat/MQTT. */
    fun init(context: android.content.Context) {
        if (resolvedDeviceId != null) return
        resolvedDeviceId = DeviceIdentityStore.deviceId(context)
    }

    val DEVICE_ID: String
        get() = resolvedDeviceId
            ?: error("CloudBridgeConfig.init(context) must be called before using DEVICE_ID")

    fun agentVersion(): String = "${BuildConfig.VERSION_NAME}-debug"

    fun heartbeatUrl(): String =
        "$CONTROL_PLANE_BASE_URL/api/v1/devices/$DEVICE_ID/heartbeat"

    fun eventsUrl(): String =
        "$CONTROL_PLANE_BASE_URL/api/v1/devices/$DEVICE_ID/events"

    fun commandsPollUrl(): String =
        "$CONTROL_PLANE_BASE_URL/api/v1/devices/$DEVICE_ID/commands"

    fun commandsTopic(): String = MqttTopics().commands(DEVICE_ID)
}

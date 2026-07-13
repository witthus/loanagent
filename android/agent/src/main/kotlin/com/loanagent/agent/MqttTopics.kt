package com.loanagent.agent

class MqttTopics {
    fun commands(deviceId: String): String = "devices/${validated(deviceId)}/commands"

    fun events(deviceId: String): String = "devices/${validated(deviceId)}/events"

    private fun validated(deviceId: String): String {
        require(DEVICE_ID.matches(deviceId)) {
            "deviceId must be one non-empty MQTT topic level"
        }
        return deviceId
    }

    private companion object {
        val DEVICE_ID = Regex("^[A-Za-z0-9._-]+$")
    }
}

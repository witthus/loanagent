package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MqttTopicsTest {
    @Test
    fun formatsDeviceCommandAndEventTopics() {
        val topics = MqttTopics()
        assertEquals("devices/device-01/commands", topics.commands("device-01"))
        assertEquals("devices/device-01/events", topics.events("device-01"))
    }

    @Test
    fun rejectsDeviceIdsThatCannotFormOneTopicLevel() {
        val topics = MqttTopics()

        assertThrows(IllegalArgumentException::class.java) { topics.commands("") }
        assertThrows(IllegalArgumentException::class.java) { topics.events("device/01") }
    }
}

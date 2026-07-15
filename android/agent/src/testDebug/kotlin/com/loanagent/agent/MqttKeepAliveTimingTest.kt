package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttKeepAliveTimingTest {
    @Test
    fun socketTimeoutIsStrictlyBelowBrokerKillWindow() {
        val keepAlive = 60
        val so = MqttCommandClient.mqttSocketTimeoutMs(keepAlive)
        assertTrue(so < keepAlive * 1500)
        assertTrue(so >= 5_000)
    }

    @Test
    fun defaultKeepAliveUsesTwentySecondPingWindow() {
        assertEquals(60, MqttCommandClient.KEEP_ALIVE_SEC)
        assertEquals(20_000, MqttCommandClient.mqttSocketTimeoutMs(MqttCommandClient.KEEP_ALIVE_SEC))
    }
}

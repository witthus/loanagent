package com.loanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedDeviceGateTest {
    @Test
    fun acceptsNote12TurboModelCode() {
        val snap = SupportedDeviceGate.Snapshot(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "23049RAD8C",
            device = "marble",
            product = "marble",
            marketName = "Redmi Note 12 Turbo",
        )
        assertTrue(SupportedDeviceGate.isSupported(snap))
    }

    @Test
    fun rejectsBareMarbleCodename() {
        val snap = SupportedDeviceGate.Snapshot(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "marble",
            device = "marble",
            product = "marble",
            marketName = null,
        )
        assertFalse(SupportedDeviceGate.isSupported(snap))
    }

    @Test
    fun rejectsPocoF5Marble() {
        val snap = SupportedDeviceGate.Snapshot(
            manufacturer = "Xiaomi",
            brand = "POCO",
            model = "23049PCD8G",
            device = "marble",
            product = "marble",
            marketName = "POCO F5",
        )
        assertFalse(SupportedDeviceGate.isSupported(snap))
    }

    @Test
    fun rejectsOtherRedmiPhones() {
        val snap = SupportedDeviceGate.Snapshot(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "2312DRA50C",
            device = "garnet",
            product = "garnet",
            marketName = "Redmi Note 13 Pro",
        )
        assertFalse(SupportedDeviceGate.isSupported(snap))
    }

    @Test
    fun rejectsGenericNote12WithoutTurbo() {
        val snap = SupportedDeviceGate.Snapshot(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "22101316C",
            device = "sunstone",
            product = "sunstone",
            marketName = "Redmi Note 12",
        )
        assertFalse(SupportedDeviceGate.isSupported(snap))
    }
}

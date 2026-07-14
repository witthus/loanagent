package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityStoreTest {
    @Test
    fun buildsStableDevPrefixedIdFromAndroidId() {
        assertEquals("dev-a1b2c3d4e5f67890", DeviceIdentityStore.buildDeviceId("A1B2C3D4E5F67890"))
    }

    @Test
    fun sanitizesInvalidCharacters() {
        val id = DeviceIdentityStore.buildDeviceId("ab/cd:ef gh")
        assertTrue(id.startsWith("dev-"))
        assertTrue(id.matches(Regex("^dev-[a-z0-9._-]+$")))
    }

    @Test
    fun fallsBackWhenAndroidIdMissing() {
        val id = DeviceIdentityStore.buildDeviceId(null)
        assertTrue(id.startsWith("dev-"))
        assertTrue(id.length > 4)
    }
}

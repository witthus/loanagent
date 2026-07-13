package com.loanagent.devicecontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleIdentityTest {
    @Test
    fun reportsDeviceControllerModuleName() {
        assertEquals("device-controller", ModuleIdentity().moduleName())
    }
}

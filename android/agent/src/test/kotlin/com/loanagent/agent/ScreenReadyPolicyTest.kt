package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenReadyPolicyTest {
    @Test
    fun alreadyReadyWhenInteractiveAndUnlocked() {
        assertNull(ScreenReadyPolicy.gate(interactive = true, keyguardLocked = false, keyguardSecure = false))
        assertNull(ScreenReadyPolicy.gate(interactive = true, keyguardLocked = false, keyguardSecure = true))
    }

    @Test
    fun secureLockedBlocksAutomation() {
        assertEquals(
            ScreenReadyPolicy.ERROR_SECURE_OR_FAILED,
            ScreenReadyPolicy.gate(interactive = false, keyguardLocked = true, keyguardSecure = true),
        )
        assertEquals(
            ScreenReadyPolicy.ERROR_SECURE_OR_FAILED,
            ScreenReadyPolicy.gate(interactive = true, keyguardLocked = true, keyguardSecure = true),
        )
    }

    @Test
    fun swipeOrScreenOffNeedsWakeAttempt() {
        assertEquals(
            "",
            ScreenReadyPolicy.gate(interactive = false, keyguardLocked = false, keyguardSecure = false),
        )
        assertEquals(
            "",
            ScreenReadyPolicy.gate(interactive = false, keyguardLocked = true, keyguardSecure = false),
        )
        assertEquals(
            "",
            ScreenReadyPolicy.gate(interactive = true, keyguardLocked = true, keyguardSecure = false),
        )
    }
}

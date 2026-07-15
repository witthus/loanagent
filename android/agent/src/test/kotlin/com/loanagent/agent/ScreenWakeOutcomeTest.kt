package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenWakeOutcomeTest {
    @Test
    fun wakeLockAloneSucceedsWhenAlreadyReady() {
        assertEquals(
            ScreenWakeResult.Ok,
            ScreenWakeOutcome.decide(
                readyAfterWakeLock = true,
                activityResult = null,
                readyAfterActivityOrTimeout = false,
            ),
        )
    }

    @Test
    fun miuiDeniedActivityStillOkIfReadyAfterTimeout() {
        assertEquals(
            ScreenWakeResult.Ok,
            ScreenWakeOutcome.decide(
                readyAfterWakeLock = false,
                activityResult = ScreenWakeResult.Timeout,
                readyAfterActivityOrTimeout = true,
            ),
        )
        assertEquals(
            ScreenWakeResult.Ok,
            ScreenWakeOutcome.decide(
                readyAfterWakeLock = false,
                activityResult = ScreenWakeResult.StartFailed,
                readyAfterActivityOrTimeout = true,
            ),
        )
    }

    @Test
    fun secureKeyguardWinsOverReadyFlags() {
        assertEquals(
            ScreenWakeResult.SecureKeyguard,
            ScreenWakeOutcome.decide(
                readyAfterWakeLock = false,
                activityResult = ScreenWakeResult.SecureKeyguard,
                readyAfterActivityOrTimeout = true,
            ),
        )
    }

    @Test
    fun timeoutWhenNeverReady() {
        assertEquals(
            ScreenWakeResult.Timeout,
            ScreenWakeOutcome.decide(
                readyAfterWakeLock = false,
                activityResult = null,
                readyAfterActivityOrTimeout = false,
            ),
        )
    }
}

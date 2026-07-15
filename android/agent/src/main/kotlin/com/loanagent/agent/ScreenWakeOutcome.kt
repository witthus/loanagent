package com.loanagent.agent

/**
 * Pure resolution for screen-wake attempts. HyperOS may deny starting
 * [ScreenWakeActivity] while still allowing a PowerManager wake lock to light
 * the display — treat "already interactive + unlocked" as success without the Activity.
 */
object ScreenWakeOutcome {
    fun decide(
        readyAfterWakeLock: Boolean,
        activityResult: ScreenWakeResult?,
        readyAfterActivityOrTimeout: Boolean,
    ): ScreenWakeResult {
        if (readyAfterWakeLock) return ScreenWakeResult.Ok
        when (activityResult) {
            ScreenWakeResult.SecureKeyguard -> return ScreenWakeResult.SecureKeyguard
            ScreenWakeResult.Ok -> return ScreenWakeResult.Ok
            else -> Unit
        }
        if (readyAfterActivityOrTimeout) return ScreenWakeResult.Ok
        return activityResult
            ?: ScreenWakeResult.Timeout
    }
}

package com.loanagent.agent

/**
 * Pure policy for screen / keyguard readiness (no Android framework calls).
 * Secure (PIN/pattern/password) locks that are currently locked are never
 * auto-dismissed without Device Owner keyguard disable.
 * After DO setKeyguardDisabled, runtime typically reports unlocked → wake only.
 */
object ScreenReadyPolicy {
    const val ERROR_SECURE_OR_FAILED = "SCREEN_NOT_READY"

    /**
     * @return null when already ready; [ERROR_SECURE_OR_FAILED] when blocked;
     *         empty string "" when a wake/dismiss attempt is required.
     */
    fun gate(interactive: Boolean, keyguardLocked: Boolean, keyguardSecure: Boolean): String? {
        if (keyguardLocked && keyguardSecure) return ERROR_SECURE_OR_FAILED
        if (interactive && !keyguardLocked) return null
        return "" // needs wake / swipe dismiss
    }
}

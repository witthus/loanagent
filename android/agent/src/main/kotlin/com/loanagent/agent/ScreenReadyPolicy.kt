package com.loanagent.agent

/**
 * Pure policy for screen / keyguard readiness (no Android framework calls).
 * Secure (PIN/pattern/password) locks are never auto-dismissed without MDM.
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

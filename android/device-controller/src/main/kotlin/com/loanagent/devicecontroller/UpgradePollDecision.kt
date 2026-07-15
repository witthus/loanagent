package com.loanagent.devicecontroller

/**
 * Pure decision for whether a remote upgrade poll should start an install.
 */
enum class UpgradePollAction {
    SKIP_NOT_DEVICE_OWNER,
    SKIP_MISSING_CONFIG,
    SKIP_INSTALL_IN_PROGRESS,
    SKIP_NO_PENDING,
    INSTALL_PENDING,
}

data class PendingUpgradeInfo(
    val manifestUrl: String,
    val ring: String?,
    val requestId: String?,
)

object UpgradePollDecision {
    fun decide(
        isDeviceOwner: Boolean,
        hasTrustedUpdateConfig: Boolean,
        hasEnrolledDeviceId: Boolean,
        hasControlPlane: Boolean,
        installInProgress: Boolean,
        pending: PendingUpgradeInfo?,
    ): UpgradePollAction = when {
        !isDeviceOwner -> UpgradePollAction.SKIP_NOT_DEVICE_OWNER
        !hasTrustedUpdateConfig || !hasEnrolledDeviceId || !hasControlPlane ->
            UpgradePollAction.SKIP_MISSING_CONFIG
        installInProgress -> UpgradePollAction.SKIP_INSTALL_IN_PROGRESS
        pending == null || pending.manifestUrl.isBlank() -> UpgradePollAction.SKIP_NO_PENDING
        else -> UpgradePollAction.INSTALL_PENDING
    }
}

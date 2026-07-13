package com.loanagent.devicecontroller

enum class BootRecoveryAction {
    SKIP_NOT_DEVICE_OWNER,
    RECOVER_AGENT,
    RECOVERY_ENTRY_MISSING,
    RECOVERY_PERMISSION_MISSING,
    WAIT_FOR_INSTALL,
    AGENT_MISSING,
}

class BootRecoveryDecisionEngine {
    fun decide(
        isDeviceOwner: Boolean,
        packageInstalled: Boolean,
        recoveryEntryAvailable: Boolean,
        permissionGranted: Boolean,
        installInProgress: Boolean,
    ): BootRecoveryAction = when {
        !isDeviceOwner -> BootRecoveryAction.SKIP_NOT_DEVICE_OWNER
        installInProgress -> BootRecoveryAction.WAIT_FOR_INSTALL
        !packageInstalled -> BootRecoveryAction.AGENT_MISSING
        !recoveryEntryAvailable -> BootRecoveryAction.RECOVERY_ENTRY_MISSING
        !permissionGranted -> BootRecoveryAction.RECOVERY_PERMISSION_MISSING
        else -> BootRecoveryAction.RECOVER_AGENT
    }
}

enum class RecoveryDispatchOutcome {
    SENT,
    FAILED,
}

class BootRecoveryRecordFormatter {
    fun format(
        action: BootRecoveryAction,
        dispatchOutcome: RecoveryDispatchOutcome? = null,
    ): String = when {
        action != BootRecoveryAction.RECOVER_AGENT -> action.name
        dispatchOutcome == RecoveryDispatchOutcome.SENT ->
            "${action.name}:RECOVERY_BROADCAST_SENT"

        else -> "${action.name}:RECOVERY_FAILED"
    }
}

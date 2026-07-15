package com.loanagent.devicecontroller

data class DeviceOwnerState(
    val isThisAppDeviceOwner: Boolean,
    val ownerPackage: String?,
) {
    companion object {
        fun evaluate(applicationPackage: String, ownerPackage: String?): DeviceOwnerState =
            DeviceOwnerState(
                isThisAppDeviceOwner = ownerPackage == applicationPackage,
                ownerPackage = ownerPackage,
            )
    }
}

enum class PolicyCapability {
    LOCK_TASK,
    MAXIMUM_TIME_TO_LOCK,
    KEEP_SCREEN_ON,
    KEYGUARD_DISABLED,
}

data class PolicyCapabilities(
    val lockTask: Boolean,
    val maximumTimeToLock: Boolean,
    val keepScreenOn: Boolean,
    val keyguardDisabled: Boolean = true,
)

enum class PolicyStatus {
    APPLIED,
    NOT_DEVICE_OWNER,
    FAILED,
}

enum class PolicyError {
    REQUIRED_CAPABILITY_UNAVAILABLE,
    SET_LOCK_TASK_FAILED,
    VERIFY_LOCK_TASK_FAILED,
    LOCK_TASK_POSTCONDITION_FAILED,
    SET_MAXIMUM_TIME_TO_LOCK_FAILED,
    SET_KEYGUARD_DISABLED_FAILED,
}

data class PolicyApplicationResult(
    val status: PolicyStatus,
    val error: PolicyError? = null,
    val applied: Set<PolicyCapability> = emptySet(),
    val unsupported: Set<PolicyCapability> = emptySet(),
    val availableToActivity: Set<PolicyCapability> = emptySet(),
)

interface DevicePolicyGateway {
    fun setLockTaskPackage(packageName: String)

    fun setMaximumTimeToLock(milliseconds: Long)

    fun isLockTaskPermitted(packageName: String): Boolean

    fun setKeyguardDisabled(disabled: Boolean)
}

class PolicyCoordinator(private val gateway: DevicePolicyGateway) {
    fun applyMinimumPolicy(
        ownerState: DeviceOwnerState,
        capabilities: PolicyCapabilities,
        agentPackage: String,
    ): PolicyApplicationResult {
        if (!ownerState.isThisAppDeviceOwner) {
            return PolicyApplicationResult(status = PolicyStatus.NOT_DEVICE_OWNER)
        }

        val applied = mutableSetOf<PolicyCapability>()
        val unsupported = mutableSetOf<PolicyCapability>()
        val activityCapabilities = mutableSetOf<PolicyCapability>()

        if (!capabilities.lockTask) {
            unsupported += PolicyCapability.LOCK_TASK
        }
        if (!capabilities.maximumTimeToLock) {
            unsupported += PolicyCapability.MAXIMUM_TIME_TO_LOCK
        }
        if (!capabilities.keyguardDisabled) {
            unsupported += PolicyCapability.KEYGUARD_DISABLED
        }
        if (capabilities.keepScreenOn) {
            activityCapabilities += PolicyCapability.KEEP_SCREEN_ON
        } else {
            unsupported += PolicyCapability.KEEP_SCREEN_ON
        }
        if (!capabilities.lockTask || !capabilities.keyguardDisabled) {
            return PolicyApplicationResult(
                status = PolicyStatus.FAILED,
                error = PolicyError.REQUIRED_CAPABILITY_UNAVAILABLE,
                unsupported = unsupported,
                availableToActivity = activityCapabilities,
            )
        }

        runCatching { gateway.setLockTaskPackage(agentPackage) }
            .getOrElse {
                return PolicyApplicationResult(
                    status = PolicyStatus.FAILED,
                    error = PolicyError.SET_LOCK_TASK_FAILED,
                )
            }
        val lockTaskPermitted = runCatching {
            gateway.isLockTaskPermitted(agentPackage)
        }.getOrElse {
            return PolicyApplicationResult(
                status = PolicyStatus.FAILED,
                error = PolicyError.VERIFY_LOCK_TASK_FAILED,
            )
        }
        if (!lockTaskPermitted) {
            return PolicyApplicationResult(
                status = PolicyStatus.FAILED,
                error = PolicyError.LOCK_TASK_POSTCONDITION_FAILED,
            )
        }
        applied += PolicyCapability.LOCK_TASK

        if (capabilities.maximumTimeToLock) {
            runCatching { gateway.setMaximumTimeToLock(0L) }
                .getOrElse {
                    return PolicyApplicationResult(
                        status = PolicyStatus.FAILED,
                        error = PolicyError.SET_MAXIMUM_TIME_TO_LOCK_FAILED,
                    )
                }
            applied += PolicyCapability.MAXIMUM_TIME_TO_LOCK
        }

        runCatching { gateway.setKeyguardDisabled(true) }
            .getOrElse {
                return PolicyApplicationResult(
                    status = PolicyStatus.FAILED,
                    error = PolicyError.SET_KEYGUARD_DISABLED_FAILED,
                    applied = applied,
                    unsupported = unsupported,
                    availableToActivity = activityCapabilities,
                )
            }
        applied += PolicyCapability.KEYGUARD_DISABLED

        return PolicyApplicationResult(
            status = PolicyStatus.APPLIED,
            applied = applied,
            unsupported = unsupported,
            availableToActivity = activityCapabilities,
        )
    }
}

package com.loanagent.devicecontroller

enum class ProvisioningModeDecision {
    SELECT_FULLY_MANAGED,
    REJECT_NOT_ALLOWED,
}

class ProvisioningModeSelector {
    fun select(allowedModes: Set<Int>): ProvisioningModeDecision =
        if (FULLY_MANAGED_MODE in allowedModes) {
            ProvisioningModeDecision.SELECT_FULLY_MANAGED
        } else {
            ProvisioningModeDecision.REJECT_NOT_ALLOWED
        }

    companion object {
        const val FULLY_MANAGED_MODE = 1
        const val MANAGED_PROFILE_MODE = 2
    }
}

enum class ProvisioningRunState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLIANT,
    FAILED,
}

enum class ProvisioningRunAction {
    START,
    WAIT,
    RESUME,
    RETURN_SUCCESS,
    RETURN_FAILURE,
}

class ProvisioningRunDecisionEngine {
    fun decide(
        state: ProvisioningRunState,
        workerActive: Boolean,
    ): ProvisioningRunAction = when {
        state == ProvisioningRunState.COMPLIANT -> ProvisioningRunAction.RETURN_SUCCESS
        state == ProvisioningRunState.FAILED -> ProvisioningRunAction.RETURN_FAILURE
        workerActive -> ProvisioningRunAction.WAIT
        state == ProvisioningRunState.NOT_STARTED -> ProvisioningRunAction.START
        state == ProvisioningRunState.IN_PROGRESS -> ProvisioningRunAction.RESUME
        else -> error("unreachable provisioning state")
    }
}

class ProvisioningSingleFlightGate {
    private val decisionEngine = ProvisioningRunDecisionEngine()
    private var workerActive = false

    @Synchronized
    fun begin(state: ProvisioningRunState): ProvisioningRunAction {
        val action = decisionEngine.decide(state, workerActive)
        if (action in setOf(ProvisioningRunAction.START, ProvisioningRunAction.RESUME)) {
            workerActive = true
        }
        return action
    }

    @Synchronized
    fun complete() {
        workerActive = false
    }
}

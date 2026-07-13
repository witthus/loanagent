package com.loanagent.devicecontroller

import android.content.Context
import java.util.concurrent.Executors

object ProvisioningApplicationCoordinator {
    private val executor = Executors.newSingleThreadExecutor()
    private val gate = ProvisioningSingleFlightGate()

    fun ensureRunning(context: Context): ProvisioningRunAction {
        val applicationContext = context.applicationContext
        val store = ControllerStore(applicationContext)
        val action = gate.begin(store.provisioningRunState())
        if (action !in setOf(ProvisioningRunAction.START, ProvisioningRunAction.RESUME)) {
            return action
        }
        store.recordProvisioningRun(ProvisioningRunState.IN_PROGRESS, "IN_PROGRESS")
        executor.execute {
            try {
                complete(applicationContext)
            } finally {
                gate.complete()
            }
        }
        return action
    }

    private fun complete(context: Context) {
        val store = ControllerStore(context)
        val enrollment = store.pendingEnrollmentConfig()
        if (enrollment == null) {
            finish(store, ComplianceResult(ComplianceStatus.SERVER_FAILED), "MISSING_ENROLLMENT_CONFIG")
            return
        }
        val result = runCatching {
            val ownerState = AndroidDeviceOwnerState(context).read()
            val request = EnrollmentRequest(
                token = enrollment.token,
                endpoint = enrollment.endpoint,
                trustedHost = enrollment.trustedHost,
                identity = AndroidEnrollmentIdentity(context).read(),
            )
            ProvisioningComplianceCoordinator(
                enrollmentGateway = HttpsEnrollmentClient(),
                policyApplier = {
                    val detected = AndroidPolicyCapabilities(context).read()
                    PolicyCoordinator(AndroidDevicePolicyGateway(context)).applyMinimumPolicy(
                        ownerState = ownerState,
                        capabilities = detected.copy(
                            maximumTimeToLock = false,
                            keepScreenOn = true,
                        ),
                        agentPackage = ManagementActivity.AGENT_PACKAGE,
                    )
                },
            ).complete(ownerState, request)
        }.getOrElse {
            ComplianceResult(ComplianceStatus.SERVER_FAILED)
        }
        finish(store, result, result.diagnostic)
    }

    private fun finish(
        store: ControllerStore,
        result: ComplianceResult,
        diagnostic: String,
    ) {
        store.recordEnrollment(diagnostic)
        if (result.shouldClearEnrollmentToken) {
            store.clearEnrollmentToken()
        }
        store.recordProvisioningRun(
            if (result.isCompliant) {
                ProvisioningRunState.COMPLIANT
            } else {
                ProvisioningRunState.FAILED
            },
            diagnostic,
        )
    }
}

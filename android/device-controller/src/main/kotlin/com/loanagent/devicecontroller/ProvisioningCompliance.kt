package com.loanagent.devicecontroller

data class EnrollmentDeviceIdentity(
    val deviceId: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val controllerVersion: String,
)

data class EnrollmentRequest(
    val token: String,
    val endpoint: String,
    val trustedHost: String,
    val identity: EnrollmentDeviceIdentity,
)

enum class EnrollmentOutcome {
    SUCCESS,
    TOKEN_ALREADY_CONSUMED,
    TOKEN_DEVICE_CONFLICT,
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    NETWORK_ERROR,
    SERVER_ERROR,
}

fun interface EnrollmentGateway {
    fun enroll(request: EnrollmentRequest): EnrollmentOutcome
}

fun interface MinimumPolicyApplier {
    fun apply(): PolicyApplicationResult
}

enum class ComplianceStatus {
    COMPLIANT,
    NOT_DEVICE_OWNER,
    TOKEN_ALREADY_CONSUMED,
    TOKEN_DEVICE_CONFLICT,
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    NETWORK_FAILED,
    SERVER_FAILED,
    POLICY_FAILED,
}

data class ComplianceResult(
    val status: ComplianceStatus,
    val shouldClearEnrollmentToken: Boolean = false,
    val policyError: PolicyError? = null,
) {
    val isCompliant: Boolean
        get() = status == ComplianceStatus.COMPLIANT

    val diagnostic: String
        get() = if (policyError == null) status.name else "${status.name}:$policyError"
}

class ProvisioningComplianceCoordinator(
    private val enrollmentGateway: EnrollmentGateway,
    private val policyApplier: MinimumPolicyApplier,
) {
    fun complete(
        ownerState: DeviceOwnerState,
        request: EnrollmentRequest,
    ): ComplianceResult {
        if (!ownerState.isThisAppDeviceOwner) {
            return ComplianceResult(ComplianceStatus.NOT_DEVICE_OWNER)
        }

        return when (enrollmentGateway.enroll(request)) {
            EnrollmentOutcome.SUCCESS -> {
                val policy = policyApplier.apply()
                if (policy.status == PolicyStatus.APPLIED) {
                    ComplianceResult(
                        status = ComplianceStatus.COMPLIANT,
                        shouldClearEnrollmentToken = true,
                    )
                } else {
                    ComplianceResult(
                        ComplianceStatus.POLICY_FAILED,
                        policyError = policy.error,
                    )
                }
            }

            EnrollmentOutcome.TOKEN_ALREADY_CONSUMED ->
                ComplianceResult(ComplianceStatus.TOKEN_ALREADY_CONSUMED)

            EnrollmentOutcome.TOKEN_DEVICE_CONFLICT ->
                ComplianceResult(ComplianceStatus.TOKEN_DEVICE_CONFLICT)

            EnrollmentOutcome.TOKEN_EXPIRED ->
                ComplianceResult(ComplianceStatus.TOKEN_EXPIRED)

            EnrollmentOutcome.TOKEN_INVALID ->
                ComplianceResult(ComplianceStatus.TOKEN_INVALID)

            EnrollmentOutcome.NETWORK_ERROR ->
                ComplianceResult(ComplianceStatus.NETWORK_FAILED)

            EnrollmentOutcome.SERVER_ERROR ->
                ComplianceResult(ComplianceStatus.SERVER_FAILED)
        }
    }
}

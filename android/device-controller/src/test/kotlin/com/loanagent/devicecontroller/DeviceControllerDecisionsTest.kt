package com.loanagent.devicecontroller

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControllerDecisionsTest {
    @Test
    fun upgradePollInstallsOnlyWhenPendingAndConfigured() {
        assertEquals(
            UpgradePollAction.INSTALL_PENDING,
            UpgradePollDecision.decide(
                isDeviceOwner = true,
                hasTrustedUpdateConfig = true,
                hasEnrolledDeviceId = true,
                hasControlPlane = true,
                installInProgress = false,
                pending = PendingUpgradeInfo(
                    manifestUrl = "https://updates.example/canary.json",
                    ring = "canary",
                    requestId = "req-1",
                ),
            ),
        )
        assertEquals(
            UpgradePollAction.SKIP_NO_PENDING,
            UpgradePollDecision.decide(
                isDeviceOwner = true,
                hasTrustedUpdateConfig = true,
                hasEnrolledDeviceId = true,
                hasControlPlane = true,
                installInProgress = false,
                pending = null,
            ),
        )
        assertEquals(
            UpgradePollAction.SKIP_NOT_DEVICE_OWNER,
            UpgradePollDecision.decide(
                isDeviceOwner = false,
                hasTrustedUpdateConfig = true,
                hasEnrolledDeviceId = true,
                hasControlPlane = true,
                installInProgress = false,
                pending = PendingUpgradeInfo("https://x", null, null),
            ),
        )
    }

    @Test
    fun deviceOwnerStateOnlyMatchesThisApplication() {
        val state = DeviceOwnerState.evaluate(
            applicationPackage = "com.loanagent.devicecontroller",
            ownerPackage = "com.other.dpc",
        )

        assertFalse(state.isThisAppDeviceOwner)
        assertEquals("com.other.dpc", state.ownerPackage)
    }

    @Test
    fun policyCoordinatorRefusesAllChangesWhenNotDeviceOwner() {
        val gateway = RecordingPolicyGateway()
        val result = PolicyCoordinator(gateway).applyMinimumPolicy(
            ownerState = DeviceOwnerState(false, "com.other.dpc"),
            capabilities = PolicyCapabilities(
                lockTask = true,
                maximumTimeToLock = true,
                keepScreenOn = true,
            ),
            agentPackage = "com.loanagent.agent",
        )

        assertEquals(PolicyStatus.NOT_DEVICE_OWNER, result.status)
        assertTrue(result.applied.isEmpty())
        assertTrue(gateway.operations.isEmpty())
    }

    @Test
    fun policyCoordinatorAppliesOnlySupportedMinimumPolicy() {
        val gateway = RecordingPolicyGateway()
        val result = PolicyCoordinator(gateway).applyMinimumPolicy(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            capabilities = PolicyCapabilities(
                lockTask = true,
                maximumTimeToLock = false,
                keepScreenOn = true,
            ),
            agentPackage = "com.loanagent.agent",
        )

        assertEquals(PolicyStatus.APPLIED, result.status)
        assertEquals(
            setOf(PolicyCapability.LOCK_TASK, PolicyCapability.KEYGUARD_DISABLED),
            result.applied,
        )
        assertEquals(
            setOf(PolicyCapability.MAXIMUM_TIME_TO_LOCK),
            result.unsupported,
        )
        assertEquals(
            setOf(PolicyCapability.KEEP_SCREEN_ON),
            result.availableToActivity,
        )
        assertEquals(
            listOf("lockTask:com.loanagent.agent", "keyguardDisabled:true"),
            gateway.operations,
        )
    }

    @Test
    fun policyCoordinatorAppliesKeyguardDisabledWhenDeviceOwner() {
        val gateway = RecordingPolicyGateway()
        val result = PolicyCoordinator(gateway).applyMinimumPolicy(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            capabilities = PolicyCapabilities(
                lockTask = true,
                maximumTimeToLock = true,
                keepScreenOn = true,
                keyguardDisabled = true,
            ),
            agentPackage = "com.loanagent.agent",
        )

        assertEquals(PolicyStatus.APPLIED, result.status)
        assertTrue(PolicyCapability.KEYGUARD_DISABLED in result.applied)
        assertTrue("keyguardDisabled:true" in gateway.operations)
    }

    @Test
    fun policyCoordinatorFailsWhenSetKeyguardDisabledThrows() {
        val result = PolicyCoordinator(
            RecordingPolicyGateway(throwOnKeyguardDisabled = true),
        ).applyMinimumPolicy(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            capabilities = PolicyCapabilities(
                lockTask = true,
                maximumTimeToLock = false,
                keepScreenOn = true,
                keyguardDisabled = true,
            ),
            agentPackage = "com.loanagent.agent",
        )

        assertEquals(PolicyStatus.FAILED, result.status)
        assertEquals(PolicyError.SET_KEYGUARD_DISABLED_FAILED, result.error)
        assertEquals(setOf(PolicyCapability.LOCK_TASK), result.applied)
    }

    @Test
    fun policyCoordinatorFailsWhenRequiredLockTaskCapabilityIsUnavailable() {
        val gateway = RecordingPolicyGateway()

        val result = PolicyCoordinator(gateway).applyMinimumPolicy(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            capabilities = PolicyCapabilities(
                lockTask = false,
                maximumTimeToLock = false,
                keepScreenOn = true,
            ),
            agentPackage = "com.loanagent.agent",
        )

        assertEquals(PolicyStatus.FAILED, result.status)
        assertTrue(result.applied.isEmpty())
        assertEquals(
            setOf(PolicyCapability.LOCK_TASK, PolicyCapability.MAXIMUM_TIME_TO_LOCK),
            result.unsupported,
        )
        assertTrue(gateway.operations.isEmpty())
    }

    @Test
    fun policyCoordinatorFailsWhenLockTaskPostconditionIsNotMet() {
        val gateway = RecordingPolicyGateway(lockTaskPostcondition = false)

        val result = PolicyCoordinator(gateway).applyMinimumPolicy(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            capabilities = PolicyCapabilities(
                lockTask = true,
                maximumTimeToLock = false,
                keepScreenOn = true,
            ),
            agentPackage = "com.loanagent.agent",
        )

        assertEquals(PolicyStatus.FAILED, result.status)
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun policyCoordinatorClassifiesLockTaskVerificationExceptionAsPolicyFailure() {
        val result = PolicyCoordinator(
            RecordingPolicyGateway(throwOnLockTaskVerification = true),
        ).applyMinimumPolicy(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            capabilities = PolicyCapabilities(
                lockTask = true,
                maximumTimeToLock = false,
                keepScreenOn = true,
            ),
            agentPackage = "com.loanagent.agent",
        )

        assertEquals(PolicyStatus.FAILED, result.status)
        assertEquals(PolicyError.VERIFY_LOCK_TASK_FAILED, result.error)

        val compliance = ProvisioningComplianceCoordinator(
            enrollmentGateway = { EnrollmentOutcome.SUCCESS },
            policyApplier = { result },
        ).complete(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            request = enrollmentRequest(),
        )
        assertEquals(ComplianceStatus.POLICY_FAILED, compliance.status)
    }

    @Test
    fun updateVerifierRejectsUntrustedOrInsecureArtifactUrl() {
        val verifier = UpdateManifestVerifier(
            trustedHosts = setOf("updates.loanagent.example"),
            signatureVerifier = { _, _, _ -> true },
        )

        val result = verifier.verify(
            manifest = validManifest(url = "http://updates.loanagent.example/agent.apk"),
            apkBytes = "apk".toByteArray(),
        )

        assertEquals(UpdateValidationCode.UNTRUSTED_URL, result.code)
    }

    @Test
    fun updateVerifierChecksManifestSignatureAndApkSha256() {
        val apk = "trusted-apk".toByteArray()
        val verifier = UpdateManifestVerifier(
            trustedHosts = setOf("updates.loanagent.example"),
            signatureVerifier = { keyId, payload, signature ->
                keyId == "m0-key" &&
                    payload.decodeToString().contains("\"agent_version\":\"1.2.0\"") &&
                    signature.contentEquals(byteArrayOf(1, 2, 3))
            },
        )

        val result = verifier.verify(
            manifest = validManifest(
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(apk)
                    .joinToString("") { "%02x".format(it) },
                sizeBytes = apk.size.toLong(),
            ),
            apkBytes = apk,
        )

        assertEquals(UpdateValidationCode.VALID, result.code)
    }

    @Test
    fun updateVerifierRejectsBadSignatureBeforeHashAcceptance() {
        val verifier = UpdateManifestVerifier(
            trustedHosts = setOf("updates.loanagent.example"),
            signatureVerifier = { _, _, _ -> false },
        )

        val result = verifier.verify(validManifest(), "apk".toByteArray())

        assertEquals(UpdateValidationCode.INVALID_SIGNATURE, result.code)
    }

    @Test
    fun updateVerifierRejectsMalformedManifestBeforeArtifactDownload() {
        val verifier = UpdateManifestVerifier(
            trustedHosts = setOf("updates.loanagent.example"),
            signatureVerifier = { _, _, _ -> true },
        )

        val result = verifier.validateManifest(
            validManifest(sha256 = "not-a-sha256", sizeBytes = -1),
        )

        assertEquals(UpdateValidationCode.INVALID_MANIFEST, result.code)
    }

    @Test
    fun installDecisionAllowsUpgradeButRequiresExplicitRollbackAuthorization() {
        val engine = InstallDecisionEngine()

        assertEquals(
            InstallAction.INSTALL_UPGRADE,
            engine.decide(
                installedVersion = "1.1.0",
                targetVersion = "1.2.0",
                validationCode = UpdateValidationCode.VALID,
                rollbackAuthorized = false,
            ).action,
        )
        assertEquals(
            InstallAction.REJECT_ROLLBACK,
            engine.decide(
                installedVersion = "1.2.0",
                targetVersion = "1.1.0",
                validationCode = UpdateValidationCode.VALID,
                rollbackAuthorized = false,
            ).action,
        )
        assertEquals(
            InstallAction.ATTEMPT_ROLLBACK,
            engine.decide(
                installedVersion = "1.2.0",
                targetVersion = "1.1.0",
                validationCode = UpdateValidationCode.VALID,
                rollbackAuthorized = true,
            ).action,
        )
        assertEquals(
            InstallAction.REJECT_BELOW_MINIMUM_VERSION,
            engine.decide(
                installedVersion = "0.9.0",
                targetVersion = "1.2.0",
                minimumInstalledVersion = "1.0.0",
                validationCode = UpdateValidationCode.VALID,
                rollbackAuthorized = false,
            ).action,
        )
    }

    @Test
    fun bootRecoverySeparatesPackageInstallationFromRecoveryEntry() {
        val engine = BootRecoveryDecisionEngine()

        assertEquals(
            BootRecoveryAction.SKIP_NOT_DEVICE_OWNER,
            engine.decide(
                isDeviceOwner = false,
                packageInstalled = true,
                recoveryEntryAvailable = true,
                permissionGranted = true,
                installInProgress = false,
            ),
        )
        assertEquals(
            BootRecoveryAction.AGENT_MISSING,
            engine.decide(
                isDeviceOwner = true,
                packageInstalled = false,
                recoveryEntryAvailable = false,
                permissionGranted = true,
                installInProgress = false,
            ),
        )
        assertEquals(
            BootRecoveryAction.RECOVERY_ENTRY_MISSING,
            engine.decide(
                isDeviceOwner = true,
                packageInstalled = true,
                recoveryEntryAvailable = false,
                permissionGranted = true,
                installInProgress = false,
            ),
        )
        assertEquals(
            BootRecoveryAction.RECOVERY_PERMISSION_MISSING,
            engine.decide(
                isDeviceOwner = true,
                packageInstalled = true,
                recoveryEntryAvailable = true,
                permissionGranted = false,
                installInProgress = false,
            ),
        )
        assertEquals(
            BootRecoveryAction.RECOVER_AGENT,
            engine.decide(
                isDeviceOwner = true,
                packageInstalled = true,
                recoveryEntryAvailable = true,
                permissionGranted = true,
                installInProgress = false,
            ),
        )
        assertEquals(
            BootRecoveryAction.WAIT_FOR_INSTALL,
            engine.decide(
                isDeviceOwner = true,
                packageInstalled = false,
                recoveryEntryAvailable = false,
                permissionGranted = true,
                installInProgress = true,
            ),
        )
    }

    @Test
    fun bootRecoveryRecordNeverReportsSuccessForMissingPermissionOrDispatchFailure() {
        val formatter = BootRecoveryRecordFormatter()

        assertEquals(
            "RECOVERY_PERMISSION_MISSING",
            formatter.format(BootRecoveryAction.RECOVERY_PERMISSION_MISSING),
        )
        assertEquals(
            "RECOVER_AGENT:RECOVERY_FAILED",
            formatter.format(
                BootRecoveryAction.RECOVER_AGENT,
                RecoveryDispatchOutcome.FAILED,
            ),
        )
        assertEquals(
            "RECOVER_AGENT:RECOVERY_BROADCAST_SENT",
            formatter.format(
                BootRecoveryAction.RECOVER_AGENT,
                RecoveryDispatchOutcome.SENT,
            ),
        )
    }

    @Test
    fun provisioningComplianceEnrollsBeforeApplyingPolicy() {
        val calls = mutableListOf<String>()
        val coordinator = ProvisioningComplianceCoordinator(
            enrollmentGateway = {
                calls += "enroll"
                EnrollmentOutcome.SUCCESS
            },
            policyApplier = {
                calls += "policy"
                PolicyApplicationResult(
                    status = PolicyStatus.APPLIED,
                    applied = setOf(PolicyCapability.LOCK_TASK),
                )
            },
        )

        val result = coordinator.complete(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            request = enrollmentRequest(),
        )

        assertEquals(ComplianceStatus.COMPLIANT, result.status)
        assertTrue(result.shouldClearEnrollmentToken)
        assertEquals(listOf("enroll", "policy"), calls)
    }

    @Test
    fun provisioningComplianceNeverAppliesPolicyOrSucceedsAfterEnrollmentFailure() {
        val failures = mapOf(
            EnrollmentOutcome.TOKEN_ALREADY_CONSUMED to ComplianceStatus.TOKEN_ALREADY_CONSUMED,
            EnrollmentOutcome.TOKEN_EXPIRED to ComplianceStatus.TOKEN_EXPIRED,
            EnrollmentOutcome.NETWORK_ERROR to ComplianceStatus.NETWORK_FAILED,
        )

        failures.forEach { (outcome, expectedStatus) ->
            var policyCalls = 0
            val coordinator = ProvisioningComplianceCoordinator(
                enrollmentGateway = { outcome },
                policyApplier = {
                    policyCalls += 1
                    PolicyApplicationResult(status = PolicyStatus.APPLIED)
                },
            )

            val result = coordinator.complete(
                ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
                request = enrollmentRequest(),
            )

            assertEquals(expectedStatus, result.status)
            assertFalse(result.isCompliant)
            assertFalse(result.shouldClearEnrollmentToken)
            assertEquals(0, policyCalls)
        }
    }

    @Test
    fun provisioningComplianceRefusesEnrollmentWhenAppIsNotDeviceOwner() {
        var enrollmentCalls = 0
        val coordinator = ProvisioningComplianceCoordinator(
            enrollmentGateway = {
                enrollmentCalls += 1
                EnrollmentOutcome.SUCCESS
            },
            policyApplier = {
                PolicyApplicationResult(status = PolicyStatus.APPLIED)
            },
        )

        val result = coordinator.complete(
            ownerState = DeviceOwnerState(false, "com.other.dpc"),
            request = enrollmentRequest(),
        )

        assertEquals(ComplianceStatus.NOT_DEVICE_OWNER, result.status)
        assertFalse(result.isCompliant)
        assertEquals(0, enrollmentCalls)
    }

    @Test
    fun provisioningComplianceDoesNotSucceedWhenMinimumPolicyFails() {
        val coordinator = ProvisioningComplianceCoordinator(
            enrollmentGateway = { EnrollmentOutcome.SUCCESS },
            policyApplier = {
                PolicyApplicationResult(status = PolicyStatus.FAILED)
            },
        )

        val result = coordinator.complete(
            ownerState = DeviceOwnerState(true, "com.loanagent.devicecontroller"),
            request = enrollmentRequest(),
        )

        assertEquals(ComplianceStatus.POLICY_FAILED, result.status)
        assertFalse(result.isCompliant)
        assertFalse(result.shouldClearEnrollmentToken)
    }

    private fun enrollmentRequest() = EnrollmentRequest(
        token = "single-use-token",
        endpoint = "https://control.loanagent.example/enroll",
        trustedHost = "control.loanagent.example",
        identity = EnrollmentDeviceIdentity(
            deviceId = "device-a",
            manufacturer = "Xiaomi",
            model = "Redmi",
            androidVersion = "15",
            controllerVersion = "0.1.0",
        ),
    )

    private fun validManifest(
        url: String = "https://updates.loanagent.example/agent.apk",
        sha256: String = MessageDigest.getInstance("SHA-256")
            .digest("apk".toByteArray())
            .joinToString("") { "%02x".format(it) },
        sizeBytes: Long = 3,
    ) = AgentUpdateManifest(
        manifestVersion = "1.0.0",
        agentVersion = "1.2.0",
        minimumAgentVersion = "1.0.0",
        artifact = UpdateArtifact(url = url, sha256 = sha256, sizeBytes = sizeBytes),
        keyId = "m0-key",
        signature = byteArrayOf(1, 2, 3),
        issuedAt = "2026-07-15T12:00:00Z",
    )
}

private class RecordingPolicyGateway(
    private val lockTaskPostcondition: Boolean = true,
    private val throwOnLockTaskVerification: Boolean = false,
    private val throwOnKeyguardDisabled: Boolean = false,
) : DevicePolicyGateway {
    val operations = mutableListOf<String>()

    override fun setLockTaskPackage(packageName: String) {
        operations += "lockTask:$packageName"
    }

    override fun setMaximumTimeToLock(milliseconds: Long) {
        operations += "maximumTimeToLock:$milliseconds"
    }

    override fun isLockTaskPermitted(packageName: String): Boolean {
        if (throwOnLockTaskVerification) {
            error("fixture policy verification failure")
        }
        return lockTaskPostcondition
    }

    override fun setKeyguardDisabled(disabled: Boolean) {
        if (throwOnKeyguardDisabled) {
            error("fixture keyguard disable failure")
        }
        operations += "keyguardDisabled:$disabled"
    }
}

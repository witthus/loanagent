package com.loanagent.devicecontroller

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityDecisionsTest {
    @Test
    fun provisioningModeRequiresFullyManagedInSystemAllowedModes() {
        val selector = ProvisioningModeSelector()

        assertEquals(
            ProvisioningModeDecision.SELECT_FULLY_MANAGED,
            selector.select(setOf(ProvisioningModeSelector.FULLY_MANAGED_MODE)),
        )
        assertEquals(
            ProvisioningModeDecision.REJECT_NOT_ALLOWED,
            selector.select(setOf(ProvisioningModeSelector.MANAGED_PROFILE_MODE)),
        )
        assertEquals(
            ProvisioningModeDecision.REJECT_NOT_ALLOWED,
            selector.select(emptySet()),
        )
    }

    @Test
    fun provisioningLifecycleIsSingleFlightAndResumesOnlyAfterProcessLoss() {
        val engine = ProvisioningRunDecisionEngine()

        assertEquals(
            ProvisioningRunAction.START,
            engine.decide(ProvisioningRunState.NOT_STARTED, workerActive = false),
        )
        assertEquals(
            ProvisioningRunAction.WAIT,
            engine.decide(ProvisioningRunState.IN_PROGRESS, workerActive = true),
        )
        assertEquals(
            ProvisioningRunAction.RESUME,
            engine.decide(ProvisioningRunState.IN_PROGRESS, workerActive = false),
        )
        assertEquals(
            ProvisioningRunAction.RETURN_SUCCESS,
            engine.decide(ProvisioningRunState.COMPLIANT, workerActive = false),
        )
        assertEquals(
            ProvisioningRunAction.RETURN_FAILURE,
            engine.decide(ProvisioningRunState.FAILED, workerActive = false),
        )

        val gate = ProvisioningSingleFlightGate()
        assertEquals(
            ProvisioningRunAction.START,
            gate.begin(ProvisioningRunState.NOT_STARTED),
        )
        assertEquals(
            ProvisioningRunAction.WAIT,
            gate.begin(ProvisioningRunState.NOT_STARTED),
        )
        gate.complete()
        assertEquals(
            ProvisioningRunAction.RESUME,
            gate.begin(ProvisioningRunState.IN_PROGRESS),
        )
    }

    @Test
    fun artifactSizeBoundaryAcceptsExactly256MiBAndRejectsOneMoreByte() {
        assertTrue(ArtifactSizePolicy.allows(ArtifactSizePolicy.MAX_APK_BYTES))
        assertFalse(ArtifactSizePolicy.allows(ArtifactSizePolicy.MAX_APK_BYTES + 1))
    }

    @Test
    fun streamingVerifierRejectsTruncationAndOverLimitWithoutBufferingArtifact() {
        val bytes = "streamed-apk".toByteArray()
        val sha256 = bytes.sha256Hex()
        val output = ByteArrayOutputStream()

        val valid = ArtifactStreamVerifier.copyAndVerify(
            input = ByteArrayInputStream(bytes),
            output = output,
            expectedSize = bytes.size.toLong(),
            expectedSha256 = sha256,
            maximumBytes = bytes.size.toLong(),
        )
        val truncated = ArtifactStreamVerifier.copyAndVerify(
            input = ByteArrayInputStream(bytes.copyOf(bytes.size - 1)),
            output = ByteArrayOutputStream(),
            expectedSize = bytes.size.toLong(),
            expectedSha256 = sha256,
            maximumBytes = bytes.size.toLong(),
        )
        val overLimit = ArtifactStreamVerifier.copyAndVerify(
            input = ByteArrayInputStream(bytes),
            output = ByteArrayOutputStream(),
            expectedSize = bytes.size.toLong(),
            expectedSha256 = sha256,
            maximumBytes = (bytes.size - 1).toLong(),
        )

        assertEquals(ArtifactStreamCode.VALID, valid.code)
        assertTrue(output.toByteArray().contentEquals(bytes))
        assertEquals(ArtifactStreamCode.INVALID_SIZE, truncated.code)
        assertEquals(ArtifactStreamCode.LIMIT_EXCEEDED, overLimit.code)
    }

    @Test
    fun sessionStagerAbandonsSessionAfterAnyWriteFailure() {
        val session = RecordingInstallSession(failWrite = true)

        val result = runCatching {
            InstallSessionStager().stageAndCommit(
                input = ByteArrayInputStream("apk".toByteArray()),
                sizeBytes = 3,
                session = session,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(session.abandoned)
        assertFalse(session.committed)
    }

    @Test
    fun createdPackageSessionIsAbandonedWhenFailurePrecedesStagerCreation() {
        var abandonedSession: Int? = null

        val result = runCatching {
            CreatedSessionGuard.run(
                sessionId = 73,
                abandon = { abandonedSession = it },
            ) {
                error("openSession failed before stager existed")
            }
        }

        assertTrue(result.isFailure)
        assertEquals(73, abandonedSession)
    }

    @Test
    fun apkArchiveMustMatchAgentPackageVersionCodeAndTrustedSigner() {
        val trusted = "trusted-certificate-sha256"
        val policy = ApkArchivePolicy()
        val valid = ApkArchiveMetadata(
            packageName = "com.loanagent.agent",
            versionName = "1.2.0",
            versionCode = 12,
            signerSha256 = setOf(trusted),
        )

        assertEquals(
            ApkArchiveValidationCode.VALID,
            policy.validate(valid, "1.2.0", installedVersionCode = 11, trustedSigner = trusted),
        )
        assertEquals(
            ApkArchiveValidationCode.WRONG_PACKAGE,
            policy.validate(
                valid.copy(packageName = "com.attacker.agent"),
                "1.2.0",
                installedVersionCode = 11,
                trustedSigner = trusted,
            ),
        )
        assertEquals(
            ApkArchiveValidationCode.VERSION_CODE_NOT_INCREASING,
            policy.validate(valid.copy(versionCode = 11), "1.2.0", 11, trusted),
        )
        assertEquals(
            ApkArchiveValidationCode.INVALID_VERSION_CODE,
            policy.validate(valid.copy(versionCode = 0), "1.2.0", null, trusted),
        )
        assertEquals(
            ApkArchiveValidationCode.UNTRUSTED_SIGNER,
            policy.validate(valid.copy(signerSha256 = setOf("other")), "1.2.0", 11, trusted),
        )
    }

    @Test
    fun manifestUsesCanonicalUtf8JsonWithStrictEscapingAndFields() {
        val manifest = validManifest(agentVersion = "1.2.\"quoted\"\n")
        val canonical = manifest.canonicalPayload().decodeToString()

        assertTrue(canonical.startsWith("{"))
        assertTrue(canonical.contains("\"agent_version\":\"1.2.\\\"quoted\\\"\\n\""))
        assertFalse(canonical.contains("agent_version="))
        assertEquals(
            StrictJsonFields.Result.VALID,
            StrictJsonFields.check(
                actual = UpdateManifestJsonParser.ROOT_FIELDS,
                expected = UpdateManifestJsonParser.ROOT_FIELDS,
            ),
        )
        assertEquals(
            StrictJsonFields.Result.UNKNOWN_FIELDS,
            StrictJsonFields.check(
                actual = UpdateManifestJsonParser.ROOT_FIELDS + "unexpected",
                expected = UpdateManifestJsonParser.ROOT_FIELDS,
            ),
        )
    }

    @Test
    fun manifestRejectsExpiredFutureAndReplayedVersions() {
        val now = Instant.parse("2026-07-10T08:00:00Z")
        val verifier = verifierAt(now, highestManifestVersion = "1.1.0")

        assertEquals(
            UpdateValidationCode.MANIFEST_EXPIRED,
            verifier.validateManifest(
                validManifest(
                    manifestVersion = "1.2.0",
                    issuedAt = "2026-07-09T07:59:59Z",
                ),
            ).code,
        )
        assertEquals(
            UpdateValidationCode.MANIFEST_FROM_FUTURE,
            verifier.validateManifest(
                validManifest(
                    manifestVersion = "1.2.0",
                    issuedAt = "2026-07-10T08:05:01Z",
                ),
            ).code,
        )
        assertEquals(
            UpdateValidationCode.MANIFEST_REPLAYED,
            verifier.validateManifest(
                validManifest(
                    manifestVersion = "1.1.0",
                    issuedAt = "2026-07-10T08:00:00Z",
                ),
            ).code,
        )
    }

    @Test
    fun realEcdsaP256VectorVerifiesAndWrongCurveIsRejected() {
        val payload = "canonical manifest payload".toByteArray()
        val p256 = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(p256.private)
            update(payload)
            sign()
        }
        val verifier = EcdsaP256SignatureVerifier(
            keyId = "m0-key",
            publicKeyDerBase64 = Base64.getEncoder().encodeToString(p256.public.encoded),
        )

        assertTrue(verifier.verify("m0-key", payload, signature))

        val p384 = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp384r1"))
            generateKeyPair()
        }
        val wrongCurveVerifier = EcdsaP256SignatureVerifier(
            keyId = "m0-key",
            publicKeyDerBase64 = Base64.getEncoder().encodeToString(p384.public.encoded),
        )
        val p384Signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(p384.private)
            update(payload)
            sign()
        }
        assertFalse(wrongCurveVerifier.verify("m0-key", payload, p384Signature))
    }

    @Test
    fun p256PolicyRejectsForgedParametersWithSameBitLengths() {
        val p256 = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }.public as java.security.interfaces.ECPublicKey
        val parameters = p256.params
        val forged = ECParameterSpec(
            parameters.curve,
            ECPoint(
                parameters.generator.affineX.add(java.math.BigInteger.ONE),
                parameters.generator.affineY,
            ),
            parameters.order,
            parameters.cofactor,
        )

        assertTrue(P256CurvePolicy.matches(parameters))
        assertFalse(P256CurvePolicy.matches(forged))
    }

    @Test
    fun downloadPolicyRejectsIpLiteralsUnsafeDnsAndNonDefaultPorts() {
        val publicAddress = InetAddress.getByName("93.184.216.34")
        val privateAddress = InetAddress.getByName("192.168.1.10")
        val publicPolicy = TrustedDownloadPolicy(
            trustedHosts = setOf("updates.loanagent.example"),
            resolver = { arrayOf(publicAddress) },
        )
        val privatePolicy = TrustedDownloadPolicy(
            trustedHosts = setOf("updates.loanagent.example"),
            resolver = { arrayOf(privateAddress) },
        )

        assertEquals(
            DownloadUrlValidationCode.VALID,
            publicPolicy.validateAndResolve("https://updates.loanagent.example/agent.apk").code,
        )
        assertEquals(
            DownloadUrlValidationCode.IP_LITERAL,
            publicPolicy.validateAndResolve("https://127.0.0.1/agent.apk").code,
        )
        assertEquals(
            DownloadUrlValidationCode.UNSAFE_ADDRESS,
            privatePolicy.validateAndResolve("https://updates.loanagent.example/agent.apk").code,
        )
        assertEquals(
            DownloadUrlValidationCode.UNTRUSTED_PORT,
            publicPolicy.validateAndResolve(
                "https://updates.loanagent.example:8443/agent.apk",
            ).code,
        )
    }

    @Test
    fun pinnedDnsCannotRebindAfterValidation() {
        val publicAddress = InetAddress.getByName("93.184.216.34")
        val privateAddress = InetAddress.getByName("192.168.1.10")
        var lookups = 0
        val policy = TrustedDownloadPolicy(
            trustedHosts = setOf("updates.loanagent.example"),
            resolver = {
                lookups += 1
                if (lookups == 1) arrayOf(publicAddress) else arrayOf(privateAddress)
            },
        )

        val validated = policy.validateAndResolve(
            "https://updates.loanagent.example/agent.apk",
        )
        val pinned = PinnedHostDns(validated.host!!, validated.addresses)

        assertEquals(listOf(publicAddress), pinned.lookup("updates.loanagent.example"))
        assertEquals(listOf(publicAddress), pinned.lookup("updates.loanagent.example"))
        assertEquals(1, lookups)
    }

    @Test
    fun enrollmentEndpointRequiresExactTrustedPublicHostOnHttps443() {
        val publicAddress = InetAddress.getByName("93.184.216.34")
        val policy = TrustedDownloadPolicy(
            trustedHosts = setOf("control.loanagent.example"),
            resolver = { arrayOf(publicAddress) },
        )

        assertEquals(
            DownloadUrlValidationCode.VALID,
            policy.validateAndResolve("https://control.loanagent.example/enroll").code,
        )
        assertEquals(
            DownloadUrlValidationCode.UNTRUSTED_HOST,
            policy.validateAndResolve("https://updates.loanagent.example/enroll").code,
        )
        assertEquals(
            DownloadUrlValidationCode.UNTRUSTED_PORT,
            policy.validateAndResolve("https://control.loanagent.example:8443/enroll").code,
        )
    }

    @Test
    fun installReconciliationDoesNotWaitForeverForMissingSession() {
        val engine = InstallReconciliationEngine()

        assertEquals(
            InstallReconciliationAction.WAIT_FOR_SESSION,
            engine.decide(
                installInProgress = true,
                sessionExists = true,
                installedVersionCode = 11,
                targetVersionCode = 12,
            ),
        )
        assertEquals(
            InstallReconciliationAction.MARK_SUCCESS,
            engine.decide(true, false, installedVersionCode = 12, targetVersionCode = 12),
        )
        assertEquals(
            InstallReconciliationAction.WAIT_FOR_CALLBACK,
            engine.decide(
                true,
                false,
                installedVersionCode = 11,
                targetVersionCode = 12,
                startedAtEpochMillis = 1_000,
                nowEpochMillis = 2_000,
            ),
        )
        assertEquals(
            InstallReconciliationAction.MARK_FAILED_STALE,
            engine.decide(
                true,
                false,
                installedVersionCode = 11,
                targetVersionCode = 12,
                startedAtEpochMillis = 1_000,
                nowEpochMillis = 1_000 + InstallReconciliationEngine.DEFAULT_STALE_AFTER_MILLIS + 1,
            ),
        )
    }

    @Test
    fun installCallbackIgnoresStaleSessionAndClearsTerminalSession() {
        val engine = InstallCallbackDecisionEngine()

        assertEquals(
            InstallCallbackAction.IGNORE_STALE_SESSION,
            engine.decide(
                expectedSessionId = 42,
                callbackSessionId = 41,
                status = InstallCallbackStatus.SUCCESS,
            ),
        )
        assertEquals(
            InstallCallbackAction.KEEP_IN_PROGRESS,
            engine.decide(42, 42, InstallCallbackStatus.PENDING),
        )
        assertEquals(
            InstallCallbackAction.CLEAR_SESSION,
            engine.decide(42, 42, InstallCallbackStatus.SUCCESS),
        )
        assertEquals(
            InstallCallbackAction.CLEAR_SESSION,
            engine.decide(42, 42, InstallCallbackStatus.FAILURE),
        )
    }

    private fun verifierAt(
        now: Instant,
        highestManifestVersion: String?,
    ) = UpdateManifestVerifier(
        trustedHosts = setOf("updates.loanagent.example"),
        signatureVerifier = { _, _, _ -> true },
        clock = Clock.fixed(now, ZoneOffset.UTC),
        highestManifestVersion = highestManifestVersion,
    )

    private fun validManifest(
        manifestVersion: String = "1.2.0",
        agentVersion: String = "1.2.0",
        issuedAt: String = "2026-07-10T08:00:00Z",
    ) = AgentUpdateManifest(
        manifestVersion = manifestVersion,
        agentVersion = agentVersion,
        minimumAgentVersion = "1.0.0",
        artifact = UpdateArtifact(
            url = "https://updates.loanagent.example/agent.apk",
            sha256 = ByteArray(32).sha256Hex(),
            sizeBytes = 3,
        ),
        keyId = "m0-key",
        signature = byteArrayOf(1),
        issuedAt = issuedAt,
    )
}

private class RecordingInstallSession(
    private val failWrite: Boolean,
) : StagedInstallSession {
    var abandoned = false
    var committed = false

    override fun openWrite(sizeBytes: Long): OutputStream =
        if (failWrite) {
            object : OutputStream() {
                override fun write(value: Int) {
                    throw IOException("fixture write failure")
                }
            }
        } else {
            ByteArrayOutputStream()
        }

    override fun fsync(output: OutputStream) = Unit

    override fun commit() {
        committed = true
    }

    override fun abandon() {
        abandoned = true
    }
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

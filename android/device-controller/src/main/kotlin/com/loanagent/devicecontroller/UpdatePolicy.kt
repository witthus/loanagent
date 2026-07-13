package com.loanagent.devicecontroller

import java.security.KeyFactory
import java.security.AlgorithmParameters
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

data class UpdateArtifact(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val name: String = "agent.apk",
)

data class AgentUpdateManifest(
    val manifestVersion: String,
    val agentVersion: String,
    val minimumAgentVersion: String,
    val artifact: UpdateArtifact,
    val keyId: String,
    val signature: ByteArray,
    val signatureAlgorithm: String = "ECDSA-P256-SHA256",
    val schemaVersion: String = "1.0",
    val rolloutRing: String = "canary",
    val issuedAt: String = "",
) {
    fun canonicalPayload(): ByteArray = (
        "{" +
            "\"agent_version\":${agentVersion.canonicalJsonString()}," +
            "\"artifact\":{" +
            "\"name\":${artifact.name.canonicalJsonString()}," +
            "\"sha256\":${artifact.sha256.canonicalJsonString()}," +
            "\"size_bytes\":${artifact.sizeBytes}," +
            "\"url\":${artifact.url.canonicalJsonString()}" +
            "}," +
            "\"issued_at\":${issuedAt.canonicalJsonString()}," +
            "\"manifest_version\":${manifestVersion.canonicalJsonString()}," +
            "\"minimum_agent_version\":${minimumAgentVersion.canonicalJsonString()}," +
            "\"rollout_ring\":${rolloutRing.canonicalJsonString()}," +
            "\"schema_version\":${schemaVersion.canonicalJsonString()}," +
            "\"signature\":{" +
            "\"algorithm\":${signatureAlgorithm.canonicalJsonString()}," +
            "\"key_id\":${keyId.canonicalJsonString()}" +
            "}" +
            "}"
        ).toByteArray(Charsets.UTF_8)
}

fun interface ManifestSignatureVerifier {
    fun verify(keyId: String, payload: ByteArray, signature: ByteArray): Boolean
}

class EcdsaP256SignatureVerifier(
    private val keyId: String,
    private val publicKeyDerBase64: String,
) : ManifestSignatureVerifier {
    override fun verify(
        keyId: String,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean = runCatching {
        if (keyId != this.keyId) return false
        val key = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyDerBase64)),
        ) as? ECPublicKey ?: return false
        if (!P256CurvePolicy.matches(key.params)) {
            return false
        }
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(signature)
        }
    }.getOrDefault(false)

}

object P256CurvePolicy {
    private val expected: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun matches(actual: ECParameterSpec): Boolean =
        actual.curve.field == expected.curve.field &&
            actual.curve.a == expected.curve.a &&
            actual.curve.b == expected.curve.b &&
            seedsEqual(actual.curve.seed, expected.curve.seed) &&
            actual.generator == expected.generator &&
            actual.order == expected.order &&
            actual.cofactor == expected.cofactor

    private fun seedsEqual(first: ByteArray?, second: ByteArray?): Boolean =
        first == null && second == null ||
            first != null && second != null && first.contentEquals(second)
}

enum class UpdateValidationCode {
    VALID,
    INVALID_MANIFEST,
    UNTRUSTED_URL,
    UNSUPPORTED_SIGNATURE_ALGORITHM,
    INVALID_SIGNATURE,
    INVALID_SIZE,
    INVALID_SHA256,
    MANIFEST_EXPIRED,
    MANIFEST_FROM_FUTURE,
    MANIFEST_REPLAYED,
}

data class UpdateValidationResult(
    val code: UpdateValidationCode,
)

object StrictJsonFields {
    enum class Result {
        VALID,
        MISSING_FIELDS,
        UNKNOWN_FIELDS,
    }

    fun check(actual: Set<String>, expected: Set<String>): Result = when {
        !actual.containsAll(expected) -> Result.MISSING_FIELDS
        actual != expected -> Result.UNKNOWN_FIELDS
        else -> Result.VALID
    }
}

class UpdateManifestVerifier(
    trustedHosts: Set<String>,
    private val signatureVerifier: ManifestSignatureVerifier,
    private val supportedSignatureAlgorithms: Set<String> = setOf("ECDSA-P256-SHA256"),
    private val clock: Clock = Clock.systemUTC(),
    private val maximumManifestAge: Duration = Duration.ofHours(24),
    private val maximumFutureSkew: Duration = Duration.ofMinutes(5),
    private val highestManifestVersion: String? = null,
) {
    private val urlPolicy = TrustedHttpsUrlPolicy(trustedHosts)

    fun verify(manifest: AgentUpdateManifest, apkBytes: ByteArray): UpdateValidationResult {
        val manifestResult = validateManifest(manifest)
        if (manifestResult.code != UpdateValidationCode.VALID) {
            return manifestResult
        }
        return verifyArtifact(manifest.artifact, apkBytes)
    }

    fun validateManifest(manifest: AgentUpdateManifest): UpdateValidationResult {
        if (!manifest.hasValidStructure()) {
            return UpdateValidationResult(UpdateValidationCode.INVALID_MANIFEST)
        }
        if (!urlPolicy.allows(manifest.artifact.url)) {
            return UpdateValidationResult(UpdateValidationCode.UNTRUSTED_URL)
        }
        val issuedAt = Instant.parse(manifest.issuedAt)
        val now = clock.instant()
        if (issuedAt.isBefore(now.minus(maximumManifestAge))) {
            return UpdateValidationResult(UpdateValidationCode.MANIFEST_EXPIRED)
        }
        if (issuedAt.isAfter(now.plus(maximumFutureSkew))) {
            return UpdateValidationResult(UpdateValidationCode.MANIFEST_FROM_FUTURE)
        }
        val manifestVersion = SemanticVersion.parse(manifest.manifestVersion)!!
        val highest = highestManifestVersion?.let(SemanticVersion::parse)
        if (highest != null && manifestVersion <= highest) {
            return UpdateValidationResult(UpdateValidationCode.MANIFEST_REPLAYED)
        }
        if (manifest.signatureAlgorithm !in supportedSignatureAlgorithms) {
            return UpdateValidationResult(
                UpdateValidationCode.UNSUPPORTED_SIGNATURE_ALGORITHM,
            )
        }
        if (!signatureVerifier.verify(
                manifest.keyId,
                manifest.canonicalPayload(),
                manifest.signature,
            )
        ) {
            return UpdateValidationResult(UpdateValidationCode.INVALID_SIGNATURE)
        }
        return UpdateValidationResult(UpdateValidationCode.VALID)
    }

    fun verifyArtifact(
        artifact: UpdateArtifact,
        apkBytes: ByteArray,
    ): UpdateValidationResult {
        if (artifact.sizeBytes != apkBytes.size.toLong()) {
            return UpdateValidationResult(UpdateValidationCode.INVALID_SIZE)
        }

        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(apkBytes)
            .joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(
                actualSha256.toByteArray(Charsets.US_ASCII),
                artifact.sha256.lowercase().toByteArray(Charsets.US_ASCII),
            )
        ) {
            return UpdateValidationResult(UpdateValidationCode.INVALID_SHA256)
        }
        return UpdateValidationResult(UpdateValidationCode.VALID)
    }

}

private fun AgentUpdateManifest.hasValidStructure(): Boolean =
    schemaVersion.matches(Regex("^[1-9][0-9]*\\.[0-9]+$")) &&
        SemanticVersion.parse(manifestVersion) != null &&
        SemanticVersion.parse(agentVersion) != null &&
        SemanticVersion.parse(minimumAgentVersion) != null &&
        rolloutRing in setOf("canary", "staged", "stable") &&
        artifact.name.isNotBlank() &&
        artifact.sha256.matches(Regex("^[a-f0-9]{64}$")) &&
        artifact.sizeBytes in 1..MAX_M0_APK_BYTES &&
        keyId.isNotBlank() &&
        signature.isNotEmpty() &&
        runCatching { Instant.parse(issuedAt) }.isSuccess

private const val MAX_M0_APK_BYTES = ArtifactSizePolicy.MAX_APK_BYTES

class TrustedHttpsUrlPolicy(trustedHosts: Set<String>) {
    private val policy = TrustedDownloadPolicy(
        trustedHosts = trustedHosts,
        resolver = { arrayOf(java.net.InetAddress.getByName("93.184.216.34")) },
    )

    fun allows(value: String): Boolean =
        policy.validateAndResolve(value).code == DownloadUrlValidationCode.VALID
}

enum class InstallAction {
    INSTALL_NEW,
    INSTALL_UPGRADE,
    ATTEMPT_ROLLBACK,
    NO_OP_CURRENT,
    REJECT_INVALID_UPDATE,
    REJECT_ROLLBACK,
    REJECT_BELOW_MINIMUM_VERSION,
    REJECT_INVALID_VERSION,
}

data class InstallDecision(
    val action: InstallAction,
)

class InstallDecisionEngine {
    fun decide(
        installedVersion: String?,
        targetVersion: String,
        minimumInstalledVersion: String? = null,
        validationCode: UpdateValidationCode,
        rollbackAuthorized: Boolean,
    ): InstallDecision {
        if (validationCode != UpdateValidationCode.VALID) {
            return InstallDecision(InstallAction.REJECT_INVALID_UPDATE)
        }

        val target = SemanticVersion.parse(targetVersion)
            ?: return InstallDecision(InstallAction.REJECT_INVALID_VERSION)
        val minimum = minimumInstalledVersion?.let(SemanticVersion::parse)
        if (minimumInstalledVersion != null && minimum == null) {
            return InstallDecision(InstallAction.REJECT_INVALID_VERSION)
        }
        val installed = installedVersion?.let(SemanticVersion::parse)
            ?: return if (installedVersion == null) {
                InstallDecision(InstallAction.INSTALL_NEW)
            } else {
                InstallDecision(InstallAction.REJECT_INVALID_VERSION)
            }

        if (minimum != null && installed < minimum) {
            return InstallDecision(InstallAction.REJECT_BELOW_MINIMUM_VERSION)
        }

        return when {
            target > installed -> InstallDecision(InstallAction.INSTALL_UPGRADE)
            target == installed -> InstallDecision(InstallAction.NO_OP_CURRENT)
            rollbackAuthorized -> InstallDecision(InstallAction.ATTEMPT_ROLLBACK)
            else -> InstallDecision(InstallAction.REJECT_ROLLBACK)
        }
    }
}

private data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    companion object {
        fun parse(value: String): SemanticVersion? {
            val parts = value.split(".")
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers.any { it < 0 }) return null
            return SemanticVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

private fun String.canonicalJsonString(): String = buildString {
    append('"')
    this@canonicalJsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

package com.loanagent.devicecontroller

import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

object ArtifactSizePolicy {
    const val MAX_APK_BYTES = 256L * 1024 * 1024

    fun allows(sizeBytes: Long): Boolean = sizeBytes in 1..MAX_APK_BYTES
}

enum class ArtifactStreamCode {
    VALID,
    INVALID_SIZE,
    INVALID_SHA256,
    LIMIT_EXCEEDED,
}

data class ArtifactStreamResult(
    val code: ArtifactStreamCode,
    val bytesCopied: Long,
)

object ArtifactStreamVerifier {
    fun copyAndVerify(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        expectedSha256: String,
        maximumBytes: Long = ArtifactSizePolicy.MAX_APK_BYTES,
    ): ArtifactStreamResult {
        require(ArtifactSizePolicy.allows(expectedSize))
        require(maximumBytes in 1..ArtifactSizePolicy.MAX_APK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximumBytes || total > expectedSize) {
                return ArtifactStreamResult(ArtifactStreamCode.LIMIT_EXCEEDED, total)
            }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        if (total != expectedSize) {
            return ArtifactStreamResult(ArtifactStreamCode.INVALID_SIZE, total)
        }
        val actual = digest.digest().toHex()
        if (!MessageDigest.isEqual(
                actual.toByteArray(Charsets.US_ASCII),
                expectedSha256.lowercase().toByteArray(Charsets.US_ASCII),
            )
        ) {
            return ArtifactStreamResult(ArtifactStreamCode.INVALID_SHA256, total)
        }
        return ArtifactStreamResult(ArtifactStreamCode.VALID, total)
    }
}

interface StagedInstallSession {
    fun openWrite(sizeBytes: Long): OutputStream

    fun fsync(output: OutputStream)

    fun commit()

    fun abandon()
}

class InstallSessionStager {
    fun stageAndCommit(
        input: InputStream,
        sizeBytes: Long,
        session: StagedInstallSession,
    ) {
        try {
            session.openWrite(sizeBytes).use { output ->
                val copied = input.copyTo(output)
                require(copied == sizeBytes) { "staged APK size changed before commit" }
                session.fsync(output)
            }
            session.commit()
        } catch (error: Throwable) {
            runCatching { session.abandon() }
            throw error
        }
    }
}

object CreatedSessionGuard {
    fun <T> run(
        sessionId: Int,
        abandon: (Int) -> Unit,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: Throwable) {
        runCatching { abandon(sessionId) }
        throw error
    }
}

data class ApkArchiveMetadata(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

enum class ApkArchiveValidationCode {
    VALID,
    WRONG_PACKAGE,
    VERSION_NAME_MISMATCH,
    INVALID_VERSION_CODE,
    VERSION_CODE_NOT_INCREASING,
    UNTRUSTED_SIGNER,
}

class ApkArchivePolicy {
    fun validate(
        metadata: ApkArchiveMetadata,
        expectedVersionName: String,
        installedVersionCode: Long?,
        trustedSigner: String,
    ): ApkArchiveValidationCode = when {
        metadata.packageName != AGENT_PACKAGE -> ApkArchiveValidationCode.WRONG_PACKAGE
        metadata.versionName != expectedVersionName ->
            ApkArchiveValidationCode.VERSION_NAME_MISMATCH
        metadata.versionCode <= 0 -> ApkArchiveValidationCode.INVALID_VERSION_CODE
        installedVersionCode != null && metadata.versionCode <= installedVersionCode ->
            ApkArchiveValidationCode.VERSION_CODE_NOT_INCREASING
        metadata.signerSha256.size != 1 ||
            !metadata.signerSha256.single().equals(trustedSigner, ignoreCase = true) ->
            ApkArchiveValidationCode.UNTRUSTED_SIGNER
        else -> ApkArchiveValidationCode.VALID
    }

    private companion object {
        const val AGENT_PACKAGE = "com.loanagent.agent"
    }
}

fun interface HostAddressResolver {
    fun resolve(host: String): Array<InetAddress>
}

enum class DownloadUrlValidationCode {
    VALID,
    INVALID_URL,
    UNTRUSTED_HOST,
    UNTRUSTED_PORT,
    IP_LITERAL,
    DNS_FAILED,
    UNSAFE_ADDRESS,
}

data class DownloadUrlValidationResult(
    val code: DownloadUrlValidationCode,
    val host: String? = null,
    val addresses: List<InetAddress> = emptyList(),
)

class TrustedDownloadPolicy(
    trustedHosts: Set<String>,
    private val resolver: HostAddressResolver = HostAddressResolver(InetAddress::getAllByName),
) {
    private val trustedHosts = trustedHosts.map(String::lowercase).toSet()

    fun validateAndResolve(value: String): DownloadUrlValidationResult {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return DownloadUrlValidationResult(DownloadUrlValidationCode.INVALID_URL)
        val host = uri.host?.lowercase()
            ?: return DownloadUrlValidationResult(DownloadUrlValidationCode.INVALID_URL)
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.userInfo != null ||
            uri.fragment != null
        ) {
            return DownloadUrlValidationResult(DownloadUrlValidationCode.INVALID_URL)
        }
        if (isIpLiteral(host) || host == "localhost" || host.endsWith(".localhost")) {
            return DownloadUrlValidationResult(DownloadUrlValidationCode.IP_LITERAL)
        }
        if (host !in trustedHosts) {
            return DownloadUrlValidationResult(DownloadUrlValidationCode.UNTRUSTED_HOST)
        }
        if (uri.port !in setOf(-1, 443)) {
            return DownloadUrlValidationResult(DownloadUrlValidationCode.UNTRUSTED_PORT)
        }
        val addresses = runCatching { resolver.resolve(host).toList() }.getOrNull()
            ?: return DownloadUrlValidationResult(DownloadUrlValidationCode.DNS_FAILED)
        if (addresses.isEmpty()) {
            return DownloadUrlValidationResult(DownloadUrlValidationCode.DNS_FAILED)
        }
        if (addresses.any(::isUnsafeAddress)) {
            return DownloadUrlValidationResult(DownloadUrlValidationCode.UNSAFE_ADDRESS)
        }
        return DownloadUrlValidationResult(
            DownloadUrlValidationCode.VALID,
            host,
            addresses,
        )
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(":") || host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))

    private fun isUnsafeAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                first == 0 ||
                    first == 10 ||
                    first == 127 ||
                    first >= 224 ||
                    (first == 100 && second in 64..127) ||
                    (first == 169 && second == 254) ||
                    (first == 172 && second in 16..31) ||
                    (first == 192 && second == 168)
            }
            is Inet6Address -> (bytes[0].toInt() and 0xfe) == 0xfc
            else -> true
        }
    }
}

class PinnedHostDns(
    expectedHost: String,
    addresses: List<InetAddress>,
) : Dns {
    private val expectedHost = expectedHost.lowercase()
    private val addresses = addresses.toList()

    init {
        require(this.expectedHost.isNotBlank())
        require(this.addresses.isNotEmpty())
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(expectedHost, ignoreCase = true)) {
            throw UnknownHostException("unpinned host: $hostname")
        }
        return addresses
    }
}

object PinnedHttpsClientFactory {
    fun create(
        validation: DownloadUrlValidationResult,
        connectTimeoutMillis: Long,
        readTimeoutMillis: Long,
    ): OkHttpClient {
        require(validation.code == DownloadUrlValidationCode.VALID)
        val host = requireNotNull(validation.host)
        return OkHttpClient.Builder()
            .dns(PinnedHostDns(host, validation.addresses))
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    }
}

enum class InstallReconciliationAction {
    NOT_IN_PROGRESS,
    WAIT_FOR_SESSION,
    WAIT_FOR_CALLBACK,
    MARK_SUCCESS,
    MARK_FAILED_STALE,
}

class InstallReconciliationEngine {
    fun decide(
        installInProgress: Boolean,
        sessionExists: Boolean,
        installedVersionCode: Long?,
        targetVersionCode: Long?,
        startedAtEpochMillis: Long? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
        staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
    ): InstallReconciliationAction = when {
        !installInProgress -> InstallReconciliationAction.NOT_IN_PROGRESS
        sessionExists -> InstallReconciliationAction.WAIT_FOR_SESSION
        targetVersionCode != null &&
            installedVersionCode != null &&
            installedVersionCode >= targetVersionCode ->
            InstallReconciliationAction.MARK_SUCCESS
        startedAtEpochMillis != null &&
            nowEpochMillis - startedAtEpochMillis in 0..staleAfterMillis ->
            InstallReconciliationAction.WAIT_FOR_CALLBACK
        else -> InstallReconciliationAction.MARK_FAILED_STALE
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MILLIS = 10L * 60 * 1000
    }
}

enum class InstallCallbackStatus {
    SUCCESS,
    PENDING,
    FAILURE,
}

enum class InstallCallbackAction {
    IGNORE_STALE_SESSION,
    KEEP_IN_PROGRESS,
    CLEAR_SESSION,
}

class InstallCallbackDecisionEngine {
    fun decide(
        expectedSessionId: Int?,
        callbackSessionId: Int,
        status: InstallCallbackStatus,
    ): InstallCallbackAction = when {
        expectedSessionId == null || expectedSessionId != callbackSessionId ->
            InstallCallbackAction.IGNORE_STALE_SESSION
        status == InstallCallbackStatus.PENDING -> InstallCallbackAction.KEEP_IN_PROGRESS
        else -> InstallCallbackAction.CLEAR_SESSION
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

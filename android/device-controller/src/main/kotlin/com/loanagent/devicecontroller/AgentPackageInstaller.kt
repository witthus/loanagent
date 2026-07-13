package com.loanagent.devicecontroller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.OutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import okhttp3.Request

class AgentPackageInstaller(context: Context) {
    private val context = context.applicationContext
    private val store = ControllerStore(this.context)

    fun installFromManifest(
        config: TrustedUpdateConfig,
        rollbackAuthorized: Boolean = false,
        onFinished: () -> Unit = {},
    ) {
        if (store.installInProgress()) {
            store.recordInstall("REFUSED_INSTALL_ALREADY_IN_PROGRESS", true)
            onFinished()
            return
        }
        store.recordInstall("FETCHING_MANIFEST", true)
        executor.execute {
            try {
                installBlocking(config, rollbackAuthorized)
            } catch (error: Exception) {
                store.clearInstallSession()
                store.recordInstall(
                    "FAILED:${error.javaClass.simpleName}:${error.message.orEmpty()}",
                    false,
                )
            } finally {
                onFinished()
            }
        }
    }

    private fun installBlocking(
        config: TrustedUpdateConfig,
        rollbackAuthorized: Boolean,
    ) {
        val downloadPolicy = TrustedDownloadPolicy(setOf(config.trustedHost))
        if (
            downloadPolicy.validateAndResolve(config.manifestUrl).code !=
            DownloadUrlValidationCode.VALID
        ) {
            store.recordInstall("REFUSED_UNTRUSTED_MANIFEST_URL", false)
            return
        }

        val manifestBytes = downloadManifest(
            config.manifestUrl,
            MAX_MANIFEST_BYTES,
            downloadPolicy,
        )
        val manifest = UpdateManifestJsonParser.parse(manifestBytes.decodeToString())
        val verifier = UpdateManifestVerifier(
            trustedHosts = setOf(config.trustedHost),
            signatureVerifier = EcdsaP256SignatureVerifier(
                config.keyId,
                config.publicKeyDerBase64,
            ),
            highestManifestVersion = store.highestManifestVersion(),
        )
        val manifestValidation = verifier.validateManifest(manifest)
        if (manifestValidation.code != UpdateValidationCode.VALID) {
            store.recordInstall("REFUSED_${manifestValidation.code}", false)
            return
        }

        val decision = InstallDecisionEngine().decide(
            installedVersion = installedAgentVersion(),
            targetVersion = manifest.agentVersion,
            minimumInstalledVersion = manifest.minimumAgentVersion,
            validationCode = manifestValidation.code,
            rollbackAuthorized = rollbackAuthorized,
        )
        if (decision.action !in INSTALL_ACTIONS) {
            store.recordInstall("REFUSED_${decision.action}", false)
            return
        }

        store.recordInstall("DOWNLOADING_APK:${decision.action}", true)
        if (
            downloadPolicy.validateAndResolve(manifest.artifact.url).code !=
            DownloadUrlValidationCode.VALID
        ) {
            store.recordInstall("REFUSED_UNSAFE_ARTIFACT_ADDRESS", false)
            return
        }
        val temporaryApk = File.createTempFile("agent-", ".apk", context.cacheDir)
        try {
            val artifactResult = downloadArtifact(
                manifest.artifact,
                temporaryApk,
                downloadPolicy,
            )
            if (artifactResult.code != ArtifactStreamCode.VALID) {
                store.recordInstall("REFUSED_${artifactResult.code}", false)
                return
            }
            val archive = inspectArchive(temporaryApk)
            val archiveValidation = ApkArchivePolicy().validate(
                metadata = archive,
                expectedVersionName = manifest.agentVersion,
                installedVersionCode = installedAgentPackage()?.let(::packageVersionCode),
                trustedSigner = ownSigningCertificateSha256(),
            )
            if (archiveValidation != ApkArchiveValidationCode.VALID) {
                store.recordInstall("REFUSED_$archiveValidation", false)
                return
            }
            commitPackageInstall(
                temporaryApk,
                archive,
                manifest.manifestVersion,
                decision.action,
            )
        } finally {
            temporaryApk.delete()
        }
    }

    private fun downloadManifest(
        url: String,
        maximumBytes: Long,
        policy: TrustedDownloadPolicy,
    ): ByteArray {
        require(maximumBytes in 1..MAX_MANIFEST_BYTES) { "invalid download size limit" }
        val validation = policy.validateAndResolve(url)
        require(validation.code == DownloadUrlValidationCode.VALID) {
            "download URL failed DNS safety validation"
        }
        val client = PinnedHttpsClientFactory.create(
            validation,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
        )
        return client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            require(response.code == 200) {
                "download returned HTTP ${response.code}"
            }
            val body = requireNotNull(response.body) { "download returned no body" }
            val declaredLength = body.contentLength()
            require(declaredLength < 0 || declaredLength <= maximumBytes) {
                "download exceeds declared size"
            }
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maximumBytes) { "download exceeds size limit" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }

    private fun downloadArtifact(
        artifact: UpdateArtifact,
        destination: File,
        policy: TrustedDownloadPolicy,
    ): ArtifactStreamResult {
        require(ArtifactSizePolicy.allows(artifact.sizeBytes)) { "invalid artifact size" }
        val validation = policy.validateAndResolve(artifact.url)
        require(validation.code == DownloadUrlValidationCode.VALID) {
            "artifact URL failed DNS safety validation"
        }
        val client = PinnedHttpsClientFactory.create(
            validation,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
        )
        return client.newCall(
            Request.Builder().url(artifact.url).get().build(),
        ).execute().use { response ->
            require(response.code == 200) {
                "download returned HTTP ${response.code}"
            }
            val body = requireNotNull(response.body) { "artifact download returned no body" }
            val declaredLength = body.contentLength()
            require(declaredLength < 0 || declaredLength == artifact.sizeBytes) {
                "artifact Content-Length does not match manifest"
            }
            body.byteStream().use { input ->
                destination.outputStream().buffered().use { output ->
                    ArtifactStreamVerifier.copyAndVerify(
                        input = input,
                        output = output,
                        expectedSize = artifact.sizeBytes,
                        expectedSha256 = artifact.sha256,
                    )
                }
            }
        }
    }

    private fun commitPackageInstall(
        apkFile: File,
        archive: ApkArchiveMetadata,
        manifestVersion: String,
        action: InstallAction,
    ) {
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(ManagementActivity.AGENT_PACKAGE)
            setInstallReason(PackageManager.INSTALL_REASON_POLICY)
            setSize(apkFile.length())
        }
        val sessionId = installer.createSession(parameters)
        CreatedSessionGuard.run(sessionId, installer::abandonSession) {
            store.recordInstallSession(sessionId, archive.versionCode, manifestVersion)
            val session = installer.openSession(sessionId)
            val callbackIntent = Intent(context, InstallResultReceiver::class.java).apply {
                this.action = InstallResultReceiver.ACTION_INSTALL_RESULT
                putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(
                    InstallResultReceiver.EXTRA_TARGET_VERSION,
                    archive.versionName.orEmpty(),
                )
                putExtra(InstallResultReceiver.EXTRA_TARGET_VERSION_CODE, archive.versionCode)
                putExtra(InstallResultReceiver.EXTRA_MANIFEST_VERSION, manifestVersion)
                putExtra(InstallResultReceiver.EXTRA_INSTALL_ACTION, action.name)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            try {
                val adapter = AndroidStagedInstallSession(
                    session = session,
                    callback = pendingIntent,
                )
                apkFile.inputStream().buffered().use { input ->
                    InstallSessionStager().stageAndCommit(
                        input = input,
                        sizeBytes = apkFile.length(),
                        session = adapter,
                    )
                }
                store.recordInstall(
                    "COMMITTING:$action:${archive.versionName}:${archive.versionCode}",
                    true,
                )
            } finally {
                session.close()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun installedAgentPackage(): android.content.pm.PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                ManagementActivity.AGENT_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            context.packageManager.getPackageInfo(ManagementActivity.AGENT_PACKAGE, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun installedAgentVersion(): String? = installedAgentPackage()?.versionName

    @Suppress("DEPRECATION")
    private fun inspectArchive(apkFile: File): ApkArchiveMetadata {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(apkFile.path, flags)
            ?: error("PackageManager could not parse downloaded APK")
        return ApkArchiveMetadata(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = packageVersionCode(info),
            signerSha256 = signatures(info).map(Signature::toByteArray)
                .map(ByteArray::certificateSha256)
                .toSet(),
        )
    }

    @Suppress("DEPRECATION")
    private fun ownSigningCertificateSha256(): String {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(context.packageName, flags)
        }
        return signatures(info).singleOrNull()?.toByteArray()?.certificateSha256()
            ?: error("DPC must have exactly one current signing certificate")
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: android.content.pm.PackageInfo): Array<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            info.signatures ?: emptyArray()
        }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
        val INSTALL_ACTIONS = setOf(
            InstallAction.INSTALL_NEW,
            InstallAction.INSTALL_UPGRADE,
            InstallAction.ATTEMPT_ROLLBACK,
        )
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 60_000L
    }
}

internal object UpdateManifestJsonParser {
    val ROOT_FIELDS = setOf(
        "schema_version",
        "manifest_version",
        "agent_version",
        "minimum_agent_version",
        "rollout_ring",
        "artifacts",
        "issued_at",
        "signature",
    )
    private val ARTIFACT_FIELDS = setOf("name", "url", "sha256", "size_bytes")
    private val SIGNATURE_FIELDS = setOf("algorithm", "key_id", "value")

    fun parse(value: String): AgentUpdateManifest {
        val reader = JsonReader(StringReader(value)).apply { isLenient = false }
        var schemaVersion: String? = null
        var manifestVersion: String? = null
        var agentVersion: String? = null
        var minimumAgentVersion: String? = null
        var rolloutRing: String? = null
        var artifact: UpdateArtifact? = null
        var issuedAt: String? = null
        var parsedSignature: ParsedSignature? = null
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextUniqueName(seen, ROOT_FIELDS)
            when (name) {
                "schema_version" -> schemaVersion = reader.nextStrictString(name)
                "manifest_version" -> manifestVersion = reader.nextStrictString(name)
                "agent_version" -> agentVersion = reader.nextStrictString(name)
                "minimum_agent_version" ->
                    minimumAgentVersion = reader.nextStrictString(name)
                "rollout_ring" -> rolloutRing = reader.nextStrictString(name)
                "artifacts" -> artifact = reader.nextSingleArtifact()
                "issued_at" -> issuedAt = reader.nextStrictString(name)
                "signature" -> parsedSignature = reader.nextSignature()
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT) { "trailing JSON content" }
        require(seen == ROOT_FIELDS) { "missing manifest fields: ${ROOT_FIELDS - seen}" }
        val signature = requireNotNull(parsedSignature)
        return AgentUpdateManifest(
            schemaVersion = requireNotNull(schemaVersion),
            manifestVersion = requireNotNull(manifestVersion),
            agentVersion = requireNotNull(agentVersion),
            minimumAgentVersion = requireNotNull(minimumAgentVersion),
            rolloutRing = requireNotNull(rolloutRing),
            artifact = requireNotNull(artifact),
            issuedAt = requireNotNull(issuedAt),
            signatureAlgorithm = signature.algorithm,
            keyId = signature.keyId,
            signature = Base64.getDecoder().decode(signature.value),
        )
    }

    private fun JsonReader.nextSingleArtifact(): UpdateArtifact {
        beginArray()
        require(hasNext()) { "M0 requires exactly one APK artifact" }
        val artifact = nextArtifact()
        require(!hasNext()) { "M0 requires exactly one APK artifact" }
        endArray()
        return artifact
    }

    private fun JsonReader.nextArtifact(): UpdateArtifact {
        var name: String? = null
        var url: String? = null
        var sha256: String? = null
        var sizeBytes: Long? = null
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            when (val field = nextUniqueName(seen, ARTIFACT_FIELDS)) {
                "name" -> name = nextStrictString("artifact.$field")
                "url" -> url = nextStrictString("artifact.$field")
                "sha256" -> sha256 = nextStrictString("artifact.$field")
                "size_bytes" -> sizeBytes = nextStrictSize()
            }
        }
        endObject()
        require(seen == ARTIFACT_FIELDS) {
            "missing artifact fields: ${ARTIFACT_FIELDS - seen}"
        }
        return UpdateArtifact(
            name = requireNotNull(name),
            url = requireNotNull(url),
            sha256 = requireNotNull(sha256),
            sizeBytes = requireNotNull(sizeBytes),
        )
    }

    private fun JsonReader.nextSignature(): ParsedSignature {
        var algorithm: String? = null
        var keyId: String? = null
        var value: String? = null
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            when (val field = nextUniqueName(seen, SIGNATURE_FIELDS)) {
                "algorithm" -> algorithm = nextStrictString("signature.$field")
                "key_id" -> keyId = nextStrictString("signature.$field")
                "value" -> value = nextStrictString("signature.$field")
            }
        }
        endObject()
        require(seen == SIGNATURE_FIELDS) {
            "missing signature fields: ${SIGNATURE_FIELDS - seen}"
        }
        return ParsedSignature(
            requireNotNull(algorithm),
            requireNotNull(keyId),
            requireNotNull(value),
        )
    }

    private fun JsonReader.nextUniqueName(
        seen: MutableSet<String>,
        allowed: Set<String>,
    ): String {
        val name = nextName()
        require(name in allowed) { "unknown JSON field: $name" }
        require(seen.add(name)) { "duplicate JSON field: $name" }
        return name
    }

    private fun JsonReader.nextStrictString(name: String): String {
        require(peek() == JsonToken.STRING) { "$name must be a JSON string" }
        return nextString()
    }

    private fun JsonReader.nextStrictSize(): Long {
        require(peek() == JsonToken.NUMBER) {
            "artifact.size_bytes must be an integral JSON number"
        }
        val literal = nextString()
        require(literal.matches(Regex("^(0|[1-9][0-9]*)$"))) {
            "artifact.size_bytes must use canonical integer syntax"
        }
        val value = literal.toLongOrNull()
            ?: throw IllegalArgumentException("artifact.size_bytes is out of range")
        require(ArtifactSizePolicy.allows(value)) {
            "artifact.size_bytes is outside the M0 range"
        }
        return value
    }

    private data class ParsedSignature(
        val algorithm: String,
        val keyId: String,
        val value: String,
    )
}

private class AndroidStagedInstallSession(
    private val session: PackageInstaller.Session,
    private val callback: PendingIntent,
) : StagedInstallSession {
    override fun openWrite(sizeBytes: Long): OutputStream =
        session.openWrite("agent.apk", 0, sizeBytes)

    override fun fsync(output: OutputStream) {
        session.fsync(output)
    }

    override fun commit() {
        session.commit(callback.intentSender)
    }

    override fun abandon() {
        session.abandon()
    }
}

private fun ByteArray.certificateSha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

package com.loanagent.devicecontroller

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Polls control-plane for a pending signed update-manifest and installs via
 * [AgentPackageInstaller] when Device Owner + provisioning config are ready.
 */
class RemoteUpgradeCoordinator(private val context: Context) {
    private val store = ControllerStore(context)

    fun pollAndMaybeInstall(): UpgradePollAction {
        val owner = AndroidDeviceOwnerState(context).read()
        val config = store.trustedUpdateConfig()
        val deviceId = store.enrolledDeviceId()
        val controlPlane = store.controlPlaneBaseUrl()
        val pending = if (
            owner.isThisAppDeviceOwner &&
            config != null &&
            !deviceId.isNullOrBlank() &&
            !controlPlane.isNullOrBlank()
        ) {
            fetchPending(controlPlane, deviceId, store.trustedControlPlaneHost())
        } else {
            null
        }
        val action = UpgradePollDecision.decide(
            isDeviceOwner = owner.isThisAppDeviceOwner,
            hasTrustedUpdateConfig = config != null,
            hasEnrolledDeviceId = !deviceId.isNullOrBlank(),
            hasControlPlane = !controlPlane.isNullOrBlank(),
            installInProgress = store.installInProgress(),
            pending = pending,
        )
        store.recordUpgradePoll(action.name + (pending?.let { ":${it.manifestUrl}" } ?: ""))
        if (action != UpgradePollAction.INSTALL_PENDING || pending == null || config == null) {
            return action
        }
        val installConfig = config.copy(manifestUrl = pending.manifestUrl)
        reportStatus(controlPlane!!, deviceId!!, "in_progress", null)
        AgentPackageInstaller(context).installFromManifest(installConfig, rollbackAuthorized = false) {
            val last = ControllerStore(context).lastInstall()
            val terminal = when {
                last.startsWith("SUCCESS") -> "succeeded"
                last.contains("PENDING") -> "in_progress"
                else -> "failed"
            }
            reportStatus(controlPlane, deviceId, terminal, last)
        }
        return action
    }

    private fun fetchPending(
        controlPlaneUrl: String,
        deviceId: String,
        trustedHost: String?,
    ): PendingUpgradeInfo? {
        val base = controlPlaneUrl.trimEnd('/')
        // control_plane_url may be .../enroll — strip trailing path for API base
        val apiRoot = base.removeSuffix("/enroll")
        val url = "$apiRoot/api/v1/devices/$deviceId/upgrade"
        return runCatching {
            val host = trustedHost ?: return@runCatching null
            val validation = TrustedDownloadPolicy(
                trustedHosts = setOf(host),
                resolver = HostAddressResolver(java.net.InetAddress::getAllByName),
            ).validateAndResolve(url)
            if (validation.code != DownloadUrlValidationCode.VALID) return@runCatching null
            val client = PinnedHttpsClientFactory.create(validation, 15_000L, 30_000L)
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Token", DpcCloudConfig.DEVICE_TOKEN)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 204 || response.code == 404) return@runCatching null
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@runCatching null
                val json = JSONObject(body)
                if (!json.optBoolean("pending", false)) return@runCatching null
                val manifestUrl = json.optString("manifest_url").orEmpty()
                if (manifestUrl.isBlank()) return@runCatching null
                PendingUpgradeInfo(
                    manifestUrl = manifestUrl,
                    ring = json.optString("ring").takeIf { it.isNotBlank() },
                    requestId = json.optString("request_id").takeIf { it.isNotBlank() },
                )
            }
        }.onFailure { Log.w(TAG, "fetchPending failed", it) }.getOrNull()
    }

    private fun reportStatus(
        controlPlaneUrl: String,
        deviceId: String,
        status: String,
        detail: String?,
    ) {
        val apiRoot = controlPlaneUrl.trimEnd('/').removeSuffix("/enroll")
        val url = "$apiRoot/api/v1/devices/$deviceId/upgrade/result"
        runCatching {
            val host = store.trustedControlPlaneHost() ?: return@runCatching
            val validation = TrustedDownloadPolicy(
                trustedHosts = setOf(host),
                resolver = HostAddressResolver(java.net.InetAddress::getAllByName),
            ).validateAndResolve(url)
            if (validation.code != DownloadUrlValidationCode.VALID) return@runCatching
            val client = PinnedHttpsClientFactory.create(validation, 15_000L, 30_000L)
            val payload = JSONObject()
                .put("status", status)
                .put("detail", detail ?: JSONObject.NULL)
                .toString()
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Token", DpcCloudConfig.DEVICE_TOKEN)
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().close()
        }.onFailure { Log.w(TAG, "reportStatus failed", it) }
    }

    companion object {
        private const val TAG = "RemoteUpgrade"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

object DpcCloudConfig {
    /** Shared fleet device token; must match control-plane DEVICE_TOKEN. */
    const val DEVICE_TOKEN = "cb571ab15f2f873f0fbbb533b16a70a5"
}

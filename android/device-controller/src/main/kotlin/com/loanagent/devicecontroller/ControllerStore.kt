package com.loanagent.devicecontroller

import android.content.Context
import android.os.PersistableBundle
import java.security.MessageDigest

data class TrustedUpdateConfig(
    val manifestUrl: String,
    val keyId: String,
    val publicKeyDerBase64: String,
    val trustedHost: String,
)

data class PendingEnrollmentConfig(
    val token: String,
    val endpoint: String,
    val trustedHost: String,
)

class ControllerStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "device_controller_state",
        Context.MODE_PRIVATE,
    )

    fun saveProvisioningExtras(extras: PersistableBundle) {
        val enrollmentToken = extras.getString("enrollment_token")
        if (
            enrollmentToken.isNullOrBlank() ||
            preferences.contains(KEY_ENROLLMENT_TOKEN) ||
            provisioningRunState() != ProvisioningRunState.NOT_STARTED
        ) {
            return
        }
        val enrollmentTokenHash = enrollmentToken?.takeIf(String::isNotBlank)?.sha256()
        val editor = preferences.edit()
        if (
            enrollmentTokenHash != null &&
            enrollmentTokenHash != preferences.getString(KEY_ENROLLMENT_TOKEN_HASH, null)
        ) {
            editor
                .putString(KEY_PROVISIONING_RUN_STATE, ProvisioningRunState.NOT_STARTED.name)
                .remove(KEY_PROVISIONING_DIAGNOSTIC)
        }
        editor
            .putString(KEY_ENROLLMENT_TOKEN, enrollmentToken)
            .putString(KEY_ENROLLMENT_TOKEN_HASH, enrollmentTokenHash)
            .putString(KEY_CONTROL_PLANE_URL, extras.getString("control_plane_url"))
            .putString(
                KEY_TRUSTED_CONTROL_PLANE_HOST,
                extras.getString("trusted_control_plane_host"),
            )
            .putString(KEY_MANIFEST_URL, extras.getString("update_manifest_url"))
            .putString(KEY_UPDATE_KEY_ID, extras.getString("update_key_id"))
            .putString(KEY_UPDATE_PUBLIC_KEY, extras.getString("update_public_key"))
            .putString(KEY_TRUSTED_HOST, extras.getString("trusted_update_host"))
            .apply()
    }

    fun trustedUpdateConfig(): TrustedUpdateConfig? {
        val manifestUrl = preferences.getString(KEY_MANIFEST_URL, null) ?: return null
        val keyId = preferences.getString(KEY_UPDATE_KEY_ID, null) ?: return null
        val publicKey = preferences.getString(KEY_UPDATE_PUBLIC_KEY, null) ?: return null
        val trustedHost = preferences.getString(KEY_TRUSTED_HOST, null) ?: return null
        return TrustedUpdateConfig(manifestUrl, keyId, publicKey, trustedHost)
    }

    fun pendingEnrollmentConfig(): PendingEnrollmentConfig? {
        val token = preferences.getString(KEY_ENROLLMENT_TOKEN, null) ?: return null
        val endpoint = preferences.getString(KEY_CONTROL_PLANE_URL, null) ?: return null
        val trustedHost =
            preferences.getString(KEY_TRUSTED_CONTROL_PLANE_HOST, null) ?: return null
        if (token.isBlank() || endpoint.isBlank() || trustedHost.isBlank()) return null
        return PendingEnrollmentConfig(token, endpoint, trustedHost)
    }

    fun clearEnrollmentToken() {
        preferences.edit().remove(KEY_ENROLLMENT_TOKEN).apply()
    }

    fun recordEnrollment(status: String) {
        preferences.edit().putString(KEY_LAST_ENROLLMENT, status).apply()
    }

    fun provisioningRunState(): ProvisioningRunState = runCatching {
        ProvisioningRunState.valueOf(
            preferences.getString(
                KEY_PROVISIONING_RUN_STATE,
                ProvisioningRunState.NOT_STARTED.name,
            )!!,
        )
    }.getOrDefault(ProvisioningRunState.NOT_STARTED)

    fun provisioningDiagnostic(): String =
        preferences.getString(KEY_PROVISIONING_DIAGNOSTIC, "NOT_STARTED") ?: "NOT_STARTED"

    fun recordProvisioningRun(state: ProvisioningRunState, diagnostic: String) {
        preferences.edit()
            .putString(KEY_PROVISIONING_RUN_STATE, state.name)
            .putString(KEY_PROVISIONING_DIAGNOSTIC, diagnostic)
            .commit()
    }

    fun lastEnrollment(): String =
        preferences.getString(KEY_LAST_ENROLLMENT, "NOT_RUN") ?: "NOT_RUN"

    fun saveEnrolledDeviceId(deviceId: String) {
        if (deviceId.isBlank()) return
        preferences.edit().putString(KEY_ENROLLED_DEVICE_ID, deviceId).apply()
    }

    fun enrolledDeviceId(): String? =
        preferences.getString(KEY_ENROLLED_DEVICE_ID, null)?.takeIf { it.isNotBlank() }

    fun controlPlaneBaseUrl(): String? =
        preferences.getString(KEY_CONTROL_PLANE_URL, null)?.takeIf { it.isNotBlank() }

    fun trustedControlPlaneHost(): String? =
        preferences.getString(KEY_TRUSTED_CONTROL_PLANE_HOST, null)?.takeIf { it.isNotBlank() }

    fun recordUpgradePoll(status: String) {
        preferences.edit().putString(KEY_LAST_UPGRADE_POLL, status).apply()
    }

    fun lastUpgradePoll(): String =
        preferences.getString(KEY_LAST_UPGRADE_POLL, "NOT_RUN") ?: "NOT_RUN"

    fun recordRecovery(status: String) {
        preferences.edit().putString(KEY_LAST_RECOVERY, status).apply()
    }

    fun lastRecovery(): String =
        preferences.getString(KEY_LAST_RECOVERY, "NOT_RUN") ?: "NOT_RUN"

    fun recordInstall(status: String, inProgress: Boolean) {
        preferences.edit()
            .putString(KEY_LAST_INSTALL, status)
            .putBoolean(KEY_INSTALL_IN_PROGRESS, inProgress)
            .apply()
    }

    fun lastInstall(): String =
        preferences.getString(KEY_LAST_INSTALL, "NOT_RUN") ?: "NOT_RUN"

    fun installInProgress(): Boolean =
        preferences.getBoolean(KEY_INSTALL_IN_PROGRESS, false)

    fun recordInstallSession(
        sessionId: Int,
        targetVersionCode: Long,
        manifestVersion: String,
    ) {
        preferences.edit()
            .putInt(KEY_INSTALL_SESSION_ID, sessionId)
            .putLong(KEY_INSTALL_TARGET_VERSION_CODE, targetVersionCode)
            .putString(KEY_INSTALL_MANIFEST_VERSION, manifestVersion)
            .putLong(KEY_INSTALL_STARTED_AT, System.currentTimeMillis())
            .putBoolean(KEY_INSTALL_IN_PROGRESS, true)
            .commit()
    }

    fun installSessionId(): Int? =
        if (preferences.contains(KEY_INSTALL_SESSION_ID)) {
            preferences.getInt(KEY_INSTALL_SESSION_ID, -1).takeIf { it >= 0 }
        } else {
            null
        }

    fun installTargetVersionCode(): Long? =
        if (preferences.contains(KEY_INSTALL_TARGET_VERSION_CODE)) {
            preferences.getLong(KEY_INSTALL_TARGET_VERSION_CODE, -1L).takeIf { it >= 0 }
        } else {
            null
        }

    fun installManifestVersion(): String? =
        preferences.getString(KEY_INSTALL_MANIFEST_VERSION, null)

    fun installStartedAtEpochMillis(): Long? =
        if (preferences.contains(KEY_INSTALL_STARTED_AT)) {
            preferences.getLong(KEY_INSTALL_STARTED_AT, -1L).takeIf { it >= 0 }
        } else {
            null
        }

    fun clearInstallSession() {
        preferences.edit()
            .remove(KEY_INSTALL_SESSION_ID)
            .remove(KEY_INSTALL_TARGET_VERSION_CODE)
            .remove(KEY_INSTALL_MANIFEST_VERSION)
            .remove(KEY_INSTALL_STARTED_AT)
            .putBoolean(KEY_INSTALL_IN_PROGRESS, false)
            .commit()
    }

    fun highestManifestVersion(): String? =
        preferences.getString(KEY_HIGHEST_MANIFEST_VERSION, null)

    fun recordHighestManifestVersion(version: String) {
        preferences.edit().putString(KEY_HIGHEST_MANIFEST_VERSION, version).commit()
    }

    private companion object {
        const val KEY_ENROLLMENT_TOKEN = "enrollment_token"
        const val KEY_ENROLLMENT_TOKEN_HASH = "enrollment_token_hash"
        const val KEY_CONTROL_PLANE_URL = "control_plane_url"
        const val KEY_TRUSTED_CONTROL_PLANE_HOST = "trusted_control_plane_host"
        const val KEY_MANIFEST_URL = "update_manifest_url"
        const val KEY_UPDATE_KEY_ID = "update_key_id"
        const val KEY_UPDATE_PUBLIC_KEY = "update_public_key"
        const val KEY_TRUSTED_HOST = "trusted_update_host"
        const val KEY_LAST_RECOVERY = "last_recovery"
        const val KEY_LAST_INSTALL = "last_install"
        const val KEY_INSTALL_IN_PROGRESS = "install_in_progress"
        const val KEY_LAST_ENROLLMENT = "last_enrollment"
        const val KEY_ENROLLED_DEVICE_ID = "enrolled_device_id"
        const val KEY_LAST_UPGRADE_POLL = "last_upgrade_poll"
        const val KEY_PROVISIONING_RUN_STATE = "provisioning_run_state"
        const val KEY_PROVISIONING_DIAGNOSTIC = "provisioning_diagnostic"
        const val KEY_INSTALL_SESSION_ID = "install_session_id"
        const val KEY_INSTALL_TARGET_VERSION_CODE = "install_target_version_code"
        const val KEY_INSTALL_MANIFEST_VERSION = "install_manifest_version"
        const val KEY_INSTALL_STARTED_AT = "install_started_at"
        const val KEY_HIGHEST_MANIFEST_VERSION = "highest_manifest_version"
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

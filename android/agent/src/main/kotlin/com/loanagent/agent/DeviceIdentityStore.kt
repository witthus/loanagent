package com.loanagent.agent

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Stable per-install device_id for cloud bridge.
 * Derived from ANDROID_ID and persisted so reinstalls on the same app sandbox keep the id
 * until app data is cleared.
 */
object DeviceIdentityStore {
    private const val PREFS = "loanagent_device_identity"
    private const val KEY_DEVICE_ID = "device_id"

    @JvmStatic
    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = buildDeviceId(readAndroidId(context))
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    /** Pure helper for unit tests. */
    @JvmStatic
    fun buildDeviceId(androidId: String?): String {
        val raw = androidId?.trim().orEmpty().ifEmpty {
            UUID.randomUUID().toString().replace("-", "")
        }
        val sanitized = raw.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "")
            .take(24)
            .ifEmpty { "unknown" }
        return "dev-$sanitized"
    }

    private fun readAndroidId(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}

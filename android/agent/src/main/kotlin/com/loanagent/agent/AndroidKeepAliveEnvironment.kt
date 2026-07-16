package com.loanagent.agent

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.Process

class AndroidKeepAliveEnvironment(
    private val context: Context,
    private val accessibilityBound: () -> Boolean,
) : KeepAliveEnvironment {
    private val prefs =
        context.getSharedPreferences("keepalive_health", Context.MODE_PRIVATE)

    override fun accessibilityBound(): Boolean = accessibilityBound.invoke()

    override fun imeEnabled(): Boolean = M0InputMethodService.status(context).enabled

    override fun imeSelected(): Boolean = M0InputMethodService.status(context).selected

    override fun ignoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    override fun oemBatteryUnrestrictedAcked(): Boolean =
        prefs.getBoolean(KEY_OEM_BATTERY_ACK, false)

    fun ackOemBatteryUnrestricted() {
        prefs.edit().putBoolean(KEY_OEM_BATTERY_ACK, true).apply()
    }

    /** Clear OEM ack once Android Doze whitelist is actually granted. */
    fun syncOemBatteryAckWithSystem() {
        if (ignoringBatteryOptimizations() && oemBatteryUnrestrictedAcked()) {
            prefs.edit().putBoolean(KEY_OEM_BATTERY_ACK, false).apply()
        }
    }

    override fun keyguardSecure(): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return keyguard.isKeyguardSecure
    }

    override fun cloudBridgeRunning(): Boolean {
        if (CloudBridgeStatusHub.get().bridgeRunning) return true
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return am.getRunningServices(64).any {
            it.service.className == "com.loanagent.agent.M0DebugKeepAliveService" &&
                it.pid == Process.myPid()
        }
    }

    override fun xhsInstalled(): Boolean = XhsPhotoAccess.isInstalled(context)

    override fun xhsPhotoAccessGranted(): Boolean =
        PublishMediaSelfCheck.xhsPhotoGranted(context)

    override fun screenInteractive(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isInteractive
    }

    override fun keyguardLocked(): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return keyguard.isKeyguardLocked
    }

    override fun hasCloudBridgeBuild(): Boolean = BuildConfig.DEBUG

    companion object {
        private const val KEY_OEM_BATTERY_ACK = "oem_battery_unrestricted_ack"
    }
}

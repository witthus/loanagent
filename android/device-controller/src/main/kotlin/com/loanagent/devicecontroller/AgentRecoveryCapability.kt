package com.loanagent.devicecontroller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class AgentRecoveryCapability(private val context: Context) {
    fun packageInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                AGENT_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(
                    PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(
                AGENT_PACKAGE,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun recoveryEntryAvailable(): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getReceiverInfo(
                RECOVERY_COMPONENT,
                PackageManager.ComponentInfoFlags.of(
                    PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getReceiverInfo(
                RECOVERY_COMPONENT,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        }
        info.enabled &&
            info.exported &&
            info.permission == RECOVERY_PERMISSION
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun permissionGranted(): Boolean =
        context.checkSelfPermission(RECOVERY_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun requestRecovery(reason: String?): Result<Unit> = runCatching {
        check(permissionGranted()) { "DPC recovery signature permission is not granted" }
        context.sendBroadcast(
            Intent(RECOVERY_ACTION).apply {
                component = RECOVERY_COMPONENT
                putExtra(EXTRA_RECOVERY_REASON, reason.orEmpty())
            },
        )
    }

    companion object {
        const val AGENT_PACKAGE = "com.loanagent.agent"
        const val RECOVERY_ACTION = "com.loanagent.agent.action.RECOVER"
        const val RECOVERY_PERMISSION = "com.loanagent.permission.RECOVER_AGENT"
        const val EXTRA_RECOVERY_REASON = "recovery_reason"
        val RECOVERY_COMPONENT = ComponentName(
            AGENT_PACKAGE,
            "com.loanagent.agent.AgentRecoveryReceiver",
        )
    }
}

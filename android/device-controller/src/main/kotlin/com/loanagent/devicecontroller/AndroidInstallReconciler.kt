package com.loanagent.devicecontroller

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

class AndroidInstallReconciler(context: Context) {
    private val context = context.applicationContext

    @Suppress("DEPRECATION")
    fun reconcile(store: ControllerStore): InstallReconciliationAction {
        val sessionId = store.installSessionId()
        val sessionExists = sessionId != null &&
            context.packageManager.packageInstaller.mySessions.any { it.sessionId == sessionId }
        val installedVersionCode = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    ManagementActivity.AGENT_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                context.packageManager.getPackageInfo(ManagementActivity.AGENT_PACKAGE, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val action = InstallReconciliationEngine().decide(
            installInProgress = store.installInProgress(),
            sessionExists = sessionExists,
            installedVersionCode = installedVersionCode,
            targetVersionCode = store.installTargetVersionCode(),
            startedAtEpochMillis = store.installStartedAtEpochMillis(),
        )
        when (action) {
            InstallReconciliationAction.MARK_SUCCESS -> {
                store.installManifestVersion()?.let(store::recordHighestManifestVersion)
                store.clearInstallSession()
                store.recordInstall("RECONCILED_SUCCESS:$installedVersionCode", false)
            }
            InstallReconciliationAction.MARK_FAILED_STALE -> {
                store.clearInstallSession()
                store.recordInstall("FAILED_STALE_SESSION:$sessionId", false)
            }
            InstallReconciliationAction.NOT_IN_PROGRESS,
            InstallReconciliationAction.WAIT_FOR_CALLBACK,
            InstallReconciliationAction.WAIT_FOR_SESSION,
            -> Unit
        }
        return action
    }
}

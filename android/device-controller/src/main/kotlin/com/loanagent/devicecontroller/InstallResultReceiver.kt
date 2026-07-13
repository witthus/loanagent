package com.loanagent.devicecontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val version = intent.getStringExtra(EXTRA_TARGET_VERSION).orEmpty()
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val manifestVersion = intent.getStringExtra(EXTRA_MANIFEST_VERSION).orEmpty()
        val action = intent.getStringExtra(EXTRA_INSTALL_ACTION).orEmpty()
        val callbackStatus = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallCallbackStatus.SUCCESS
            PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallCallbackStatus.PENDING
            else -> InstallCallbackStatus.FAILURE
        }
        val store = ControllerStore(context)
        when (
            InstallCallbackDecisionEngine().decide(
                expectedSessionId = store.installSessionId(),
                callbackSessionId = sessionId,
                status = callbackStatus,
            )
        ) {
            InstallCallbackAction.IGNORE_STALE_SESSION -> {
                store.recordRecovery("IGNORED_STALE_INSTALL_CALLBACK:$sessionId")
                return
            }
            InstallCallbackAction.KEEP_IN_PROGRESS -> Unit
            InstallCallbackAction.CLEAR_SESSION -> store.clearInstallSession()
        }
        val statusName = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
            PackageInstaller.STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
            else -> "FAILURE_$status"
        }
        if (status == PackageInstaller.STATUS_SUCCESS && manifestVersion.isNotBlank()) {
            store.recordHighestManifestVersion(manifestVersion)
        }
        store.recordInstall(
            "$statusName:$action:$version:$message",
            inProgress = status == PackageInstaller.STATUS_PENDING_USER_ACTION,
        )
    }

    companion object {
        const val ACTION_INSTALL_RESULT =
            "com.loanagent.devicecontroller.action.INSTALL_RESULT"
        const val EXTRA_TARGET_VERSION = "target_version"
        const val EXTRA_TARGET_VERSION_CODE = "target_version_code"
        const val EXTRA_MANIFEST_VERSION = "manifest_version"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_INSTALL_ACTION = "install_action"
    }
}

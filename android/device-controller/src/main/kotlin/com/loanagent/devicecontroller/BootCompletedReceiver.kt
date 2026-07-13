package com.loanagent.devicecontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = ControllerStore(context)
        val installAction = AndroidInstallReconciler(context).reconcile(store)
        val ownerState = AndroidDeviceOwnerState(context).read()
        val recovery = AgentRecoveryCapability(context)
        val action = BootRecoveryDecisionEngine().decide(
            isDeviceOwner = ownerState.isThisAppDeviceOwner,
            packageInstalled = recovery.packageInstalled(),
            recoveryEntryAvailable = recovery.recoveryEntryAvailable(),
            permissionGranted = recovery.permissionGranted(),
            installInProgress = installAction in setOf(
                InstallReconciliationAction.WAIT_FOR_SESSION,
                InstallReconciliationAction.WAIT_FOR_CALLBACK,
            ),
        )
        val formatter = BootRecoveryRecordFormatter()

        if (action != BootRecoveryAction.RECOVER_AGENT) {
            store.recordRecovery("${intent.action}:${formatter.format(action)}")
            return
        }

        val dispatchOutcome = recovery.requestRecovery(intent.action).fold(
            onSuccess = { RecoveryDispatchOutcome.SENT },
            onFailure = { RecoveryDispatchOutcome.FAILED },
        )
        store.recordRecovery(
            "${intent.action}:${formatter.format(action, dispatchOutcome)}",
        )
    }
}

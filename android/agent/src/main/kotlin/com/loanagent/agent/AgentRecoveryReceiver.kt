package com.loanagent.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

class AgentRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentRecoveryStore(context).record(
            recoveredAt = Instant.now().toString(),
            reason = intent.getStringExtra(EXTRA_RECOVERY_REASON).orEmpty(),
        )
    }

    companion object {
        const val EXTRA_RECOVERY_REASON = "recovery_reason"
    }
}

class AgentRecoveryStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "agent_recovery",
        Context.MODE_PRIVATE,
    )

    fun record(recoveredAt: String, reason: String) {
        preferences.edit()
            .putString(KEY_RECOVERED_AT, recoveredAt)
            .putString(KEY_REASON, reason)
            .apply()
    }

    fun status(): String {
        val recoveredAt = preferences.getString(KEY_RECOVERED_AT, null) ?: return "NOT_RUN"
        val reason = preferences.getString(KEY_REASON, "").orEmpty()
        return "$recoveredAt ($reason)"
    }

    private companion object {
        const val KEY_RECOVERED_AT = "recovered_at"
        const val KEY_REASON = "reason"
    }
}

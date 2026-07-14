package com.loanagent.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Brief visible Activity so HyperOS/Android background-start rules allow bringing XHS
 * to the foreground from an automation task. Finishes immediately after launching XHS.
 */
class XhsLaunchTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val launch = packageManager.getLaunchIntentForPackage(XHS_PACKAGE)
            if (launch != null) {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
                startActivity(launch)
            } else {
                Log.w(TAG, "XHS launch intent missing")
            }
        } catch (error: Exception) {
            Log.w(TAG, "trampoline failed to start XHS", error)
        } finally {
            finish()
        }
    }

    companion object {
        private const val TAG = "XhsLaunchTrampoline"
        private const val XHS_PACKAGE = "com.xingin.xhs"
    }
}

package com.loanagent.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Exact/idle-allowed alarms that re-enter the process when HyperOS freezes the
 * in-process ScheduledExecutor heartbeat thread.
 *
 * Note: while in deep Doze without battery whitelist, the platform may still
 * coalesce these; whitelist + FGS remain mandatory for ≤30s cadence.
 */
object HeartbeatAlarmScheduler {
    const val ACTION = "com.loanagent.agent.ACTION_HEARTBEAT_ALARM"
    const val INTERVAL_MS = 30_000L
    private const val TAG = "HeartbeatAlarm"
    private const val REQUEST_CODE = 7102

    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(app, HeartbeatAlarmReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, REQUEST_CODE, intent, flags)
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                else ->
                    @Suppress("DEPRECATION")
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (error: Exception) {
            Log.w(TAG, "scheduleNext failed; falling back to inexact", error)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(app, HeartbeatAlarmReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, REQUEST_CODE, intent, flags)
        am.cancel(pi)
    }
}

class HeartbeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != HeartbeatAlarmScheduler.ACTION &&
            intent?.action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }
        Log.i(TAG, "alarm fired action=${intent.action}")
        M0DebugKeepAliveService.start(context, forceHeartbeat = true)
        HeartbeatAlarmScheduler.scheduleNext(context)
    }

    companion object {
        private const val TAG = "HeartbeatAlarm"
    }
}

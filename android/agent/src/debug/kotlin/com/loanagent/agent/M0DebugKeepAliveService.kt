package com.loanagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Debug-only keep-alive so HyperOS Greezer does not skip M0 debug broadcasts while the
 * accessibility service process is otherwise cached.
 */
class M0DebugKeepAliveService : Service() {
    private var bridge: CloudBridgeCoordinator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Loanagent M0 debug")
            .setContentText("Cloud bridge + debug keep-alive")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        if (bridge == null) {
            bridge = CloudBridgeCoordinator(applicationContext).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bridge?.stop()
        bridge = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "M0 debug keep-alive",
            NotificationManager.IMPORTANCE_MIN,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "m0-debug-keepalive"
        private const val NOTIFICATION_ID = 7101

        // Called reflectively from main sources (AgentStatusActivity / M0AccessibilityService).
        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, M0DebugKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

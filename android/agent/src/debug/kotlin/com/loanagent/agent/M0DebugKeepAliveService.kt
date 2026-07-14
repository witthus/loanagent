package com.loanagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Debug-only keep-alive so HyperOS Greezer does not skip M0 debug broadcasts while the
 * accessibility service process is otherwise cached.
 */
class M0DebugKeepAliveService : Service() {
    private var bridge: CloudBridgeCoordinator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (!SupportedDeviceGate.isSupported()) {
            val snap = SupportedDeviceGate.snapshotFromBuild()
            Log.w(TAG, "unsupported device, skip cloud bridge: ${snap.summaryLine()}")
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("矩阵助手（不支持本机）")
                .setContentText("v${BuildConfig.VERSION_NAME} · 仅支持 ${SupportedDeviceGate.REQUIRED_LABEL}")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOngoing(false)
                .build()
            startForeground(NOTIFICATION_ID, notification)
            stopSelf()
            return START_NOT_STICKY
        }
        CloudBridgeConfig.init(applicationContext)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("矩阵助手云桥")
            .setContentText("v${BuildConfig.VERSION_NAME} · 设备 ${CloudBridgeConfig.DEVICE_ID}")
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
        private const val TAG = "M0DebugKeepAlive"
        private const val CHANNEL_ID = "m0-debug-keepalive"
        private const val NOTIFICATION_ID = 7101

        // Called reflectively from main sources (AgentStatusActivity / M0AccessibilityService).
        @JvmStatic
        fun start(context: Context) {
            if (!SupportedDeviceGate.isSupported()) {
                Log.w(TAG, "skip keep-alive start on unsupported device")
                return
            }
            val intent = Intent(context, M0DebugKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

package com.loanagent.devicecontroller

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class LoanAgentDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, LoanAgentDeviceAdminReceiver::class.java)
    }
}

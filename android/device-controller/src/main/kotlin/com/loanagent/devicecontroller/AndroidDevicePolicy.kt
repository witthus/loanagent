package com.loanagent.devicecontroller

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager

class AndroidDeviceOwnerState(private val context: Context) {
    private val manager = context.getSystemService(DevicePolicyManager::class.java)

    fun read(): DeviceOwnerState {
        val isOwner = manager.isDeviceOwnerApp(context.packageName)
        return DeviceOwnerState(
            isThisAppDeviceOwner = isOwner,
            ownerPackage = if (isOwner) context.packageName else null,
        )
    }
}

class AndroidPolicyCapabilities(private val context: Context) {
    fun read(): PolicyCapabilities = PolicyCapabilities(
        lockTask = context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN),
        maximumTimeToLock = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_DEVICE_ADMIN,
        ),
        keepScreenOn = true,
    )
}

class AndroidDevicePolicyGateway(context: Context) : DevicePolicyGateway {
    private val manager = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = LoanAgentDeviceAdminReceiver.componentName(context)

    override fun setLockTaskPackage(packageName: String) {
        manager.setLockTaskPackages(admin, arrayOf(packageName))
    }

    override fun setMaximumTimeToLock(milliseconds: Long) {
        manager.setMaximumTimeToLock(admin, milliseconds)
    }

    override fun isLockTaskPermitted(packageName: String): Boolean =
        manager.isLockTaskPermitted(packageName)
}

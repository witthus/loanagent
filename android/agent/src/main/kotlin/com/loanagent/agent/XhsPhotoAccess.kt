package com.loanagent.agent

import android.content.Context
import android.content.pm.PackageManager

/**
 * Decides whether Xiaohongshu has enough media-read access for album pick.
 * Requires images (or legacy storage) grant — video-only / partial-only is not enough
 * to reliably see newly written DCIM/Camera rows.
 */
object XhsPhotoAccess {
    const val XHS_PACKAGE = "com.xingin.xhs"

    private const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    private const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"

    /** Permissions that alone are sufficient for publish album pick. */
    val SUFFICIENT_PERMISSIONS: Set<String> = setOf(
        READ_MEDIA_IMAGES,
        READ_EXTERNAL_STORAGE,
    )

    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(XHS_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun isGranted(grantedPermissionNames: Collection<String>): Boolean {
        if (grantedPermissionNames.isEmpty()) return false
        val granted = grantedPermissionNames.toHashSet()
        return SUFFICIENT_PERMISSIONS.any { it in granted }
    }

    /** Prefer [android.content.pm.PackageManager.checkPermission] over GET_PERMISSIONS flags. */
    fun isGrantedByCheck(checkPermission: (String) -> Boolean): Boolean =
        SUFFICIENT_PERMISSIONS.any(checkPermission)
}

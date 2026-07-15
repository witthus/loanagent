package com.loanagent.agent

/**
 * Decides whether Xiaohongshu has enough media-read access for album pick.
 * Accepts full images grant, partial visual selection, video, or legacy storage read.
 */
object XhsPhotoAccess {
    const val XHS_PACKAGE = "com.xingin.xhs"

    val ACCEPTED_PERMISSIONS: Set<String> = setOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_EXTERNAL_STORAGE",
    )

    fun isGranted(grantedPermissionNames: Collection<String>): Boolean {
        if (grantedPermissionNames.isEmpty()) return false
        val granted = grantedPermissionNames.toHashSet()
        return ACCEPTED_PERMISSIONS.any { it in granted }
    }

    /** Prefer [android.content.pm.PackageManager.checkPermission] over GET_PERMISSIONS flags. */
    fun isGrantedByCheck(checkPermission: (String) -> Boolean): Boolean =
        ACCEPTED_PERMISSIONS.any(checkPermission)
}

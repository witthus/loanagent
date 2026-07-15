package com.loanagent.agent

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

enum class PublishMediaSelfCheckCode {
    OK,
    WRITE_FAILED,
    VERIFY_FAILED,
    XHS_NOT_INSTALLED,
    XHS_PHOTO_DENIED,
}

data class PublishMediaSelfCheckResult(
    val code: PublishMediaSelfCheckCode,
    val message: String,
)

/**
 * Offline publish-media readiness: write bundled image to DCIM/Camera and check XHS photo access.
 */
object PublishMediaSelfCheck {
    private const val TAG = "PublishMediaSelfCheck"
    private const val ASSET_NAME = "selfcheck_media.png"

    fun run(context: Context): PublishMediaSelfCheckResult {
        val displayName = "la_selfcheck_${System.currentTimeMillis()}.png"
        val bytes = try {
            context.assets.open(ASSET_NAME).use { it.readBytes() }
        } catch (error: Exception) {
            Log.w(TAG, "asset missing", error)
            return PublishMediaSelfCheckResult(
                PublishMediaSelfCheckCode.WRITE_FAILED,
                "内置自检图片缺失，无法验证相册写入",
            )
        }
        if (bytes.isEmpty()) {
            return PublishMediaSelfCheckResult(
                PublishMediaSelfCheckCode.WRITE_FAILED,
                "内置自检图片为空",
            )
        }
        val uri = GalleryMediaWriter.insertImageBytes(context, bytes, displayName)
            ?: return PublishMediaSelfCheckResult(
                PublishMediaSelfCheckCode.WRITE_FAILED,
                "矩阵助手写入系统相册 DCIM/Camera 失败",
            )
        val found = GalleryMediaWriter.findByDisplayName(context, displayName)
        if (found == null) {
            GalleryMediaWriter.deleteUri(context, uri)
            return PublishMediaSelfCheckResult(
                PublishMediaSelfCheckCode.VERIFY_FAILED,
                "写入后未能在 MediaStore 查到测试图",
            )
        }
        GalleryMediaWriter.deleteUri(context, found)

        if (!xhsInstalled(context)) {
            return PublishMediaSelfCheckResult(
                PublishMediaSelfCheckCode.XHS_NOT_INSTALLED,
                "相册写入成功，但未安装小红书",
            )
        }
        if (!xhsPhotoGranted(context)) {
            return PublishMediaSelfCheckResult(
                PublishMediaSelfCheckCode.XHS_PHOTO_DENIED,
                "相册写入成功，但小红书未授权照片/相册（发布选图会失败）",
            )
        }
        return PublishMediaSelfCheckResult(
            PublishMediaSelfCheckCode.OK,
            "通过：矩阵助手可写入 DCIM/Camera，小红书已授照片/相册权限",
        )
    }

    private fun xhsInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(XhsPhotoAccess.XHS_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun xhsPhotoGranted(context: Context): Boolean {
        val pm = context.packageManager
        // Cross-package GET_PERMISSIONS flags are unreliable on HyperOS/Android 14+;
        // checkPermission reflects the real runtime grant (matches dumpsys).
        val byPm = XhsPhotoAccess.isGrantedByCheck { perm ->
            pm.checkPermission(perm, XhsPhotoAccess.XHS_PACKAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (byPm) return true
        // AppOps fallback when PackageManager lags OEM settings UI.
        return xhsPhotoGrantedViaAppOps(context)
    }

    private fun xhsPhotoGrantedViaAppOps(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
                ?: return false
            val uid = context.packageManager.getPackageUid(XhsPhotoAccess.XHS_PACKAGE, 0)
            val ops = buildList {
                for (perm in XhsPhotoAccess.ACCEPTED_PERMISSIONS) {
                    android.app.AppOpsManager.permissionToOp(perm)?.let { add(it) }
                }
            }.distinct()
            ops.any { op ->
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(op, uid, XhsPhotoAccess.XHS_PACKAGE) ==
                    android.app.AppOpsManager.MODE_ALLOWED
            }
        } catch (error: Exception) {
            Log.w(TAG, "appops photo probe failed", error)
            false
        }
    }

    fun grantedPermissionsFor(context: Context, packageName: String): Set<String>? {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            buildSet {
                for (perm in XhsPhotoAccess.ACCEPTED_PERMISSIONS) {
                    if (
                        context.packageManager.checkPermission(perm, packageName) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        add(perm)
                    }
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}

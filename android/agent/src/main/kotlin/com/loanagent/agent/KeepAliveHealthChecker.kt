package com.loanagent.agent

/**
 * Pure keep-alive / readiness checks for the status home screen.
 * Android framework calls stay behind [KeepAliveEnvironment].
 */
data class KeepAliveIssue(
    val code: String,
    val message: String,
    val settingsAction: SettingsAction,
    val secondaryAction: SettingsAction = SettingsAction.NONE,
)

enum class SettingsAction {
    ACCESSIBILITY,
    INPUT_METHOD,
    BATTERY_OPTIMIZATION,
    APP_BATTERY_DETAILS,
    ACK_OEM_BATTERY_UNRESTRICTED,
    LOCK_SCREEN_SECURITY,
    START_CLOUD_BRIDGE,
    XHS_APP_DETAILS,
    NONE,
}

interface KeepAliveEnvironment {
    fun accessibilityBound(): Boolean
    fun imeEnabled(): Boolean
    fun imeSelected(): Boolean
    fun ignoringBatteryOptimizations(): Boolean
    /** User confirmed HyperOS/OEM「无限制」when Android Doze API still reports optimized. */
    fun oemBatteryUnrestrictedAcked(): Boolean
    fun keyguardSecure(): Boolean
    fun cloudBridgeRunning(): Boolean
    fun xhsInstalled(): Boolean
    /** False when XHS missing or photo/media read not granted. */
    fun xhsPhotoAccessGranted(): Boolean
    fun screenInteractive(): Boolean
    fun keyguardLocked(): Boolean
    fun hasCloudBridgeBuild(): Boolean
}

class KeepAliveHealthChecker(
    private val env: KeepAliveEnvironment,
) {
    fun issues(): List<KeepAliveIssue> {
        val out = mutableListOf<KeepAliveIssue>()
        if (!env.accessibilityBound()) {
            out += KeepAliveIssue(
                code = "A11Y_DOWN",
                message = "无障碍未开启或未绑定，无法执行任务与唤醒手势",
                settingsAction = SettingsAction.ACCESSIBILITY,
            )
        }
        if (!env.imeEnabled() || !env.imeSelected()) {
            out += KeepAliveIssue(
                code = "IME_NOT_READY",
                message = "Loanagent 输入法未启用或未选为当前输入法（评论/私信需要）",
                settingsAction = SettingsAction.INPUT_METHOD,
            )
        }
        val batteryOk =
            env.ignoringBatteryOptimizations() || env.oemBatteryUnrestrictedAcked()
        if (!batteryOk) {
            out += KeepAliveIssue(
                code = "BATTERY_OPTIMIZED",
                message = "电池优化未放行：请先允许「忽略电池优化」；小米还需应用信息→耗电管理→无限制",
                settingsAction = SettingsAction.BATTERY_OPTIMIZATION,
                secondaryAction = SettingsAction.APP_BATTERY_DETAILS,
            )
        }
        if (env.keyguardSecure() && env.keyguardLocked()) {
            out += KeepAliveIssue(
                code = "SECURE_KEYGUARD",
                message = "锁屏有密码/图案且当前已锁定，自动化无法解锁；矩阵机请用 DO 禁锁屏或改为无密码",
                settingsAction = SettingsAction.LOCK_SCREEN_SECURITY,
            )
        }
        if (env.hasCloudBridgeBuild() && !env.cloudBridgeRunning()) {
            out += KeepAliveIssue(
                code = "BRIDGE_DOWN",
                message = "云桥保活服务未运行，心跳与拉令会中断",
                settingsAction = SettingsAction.START_CLOUD_BRIDGE,
            )
        }
        if (env.xhsInstalled() && !env.xhsPhotoAccessGranted()) {
            out += KeepAliveIssue(
                code = "XHS_PHOTO_DENIED",
                message = "小红书未授权照片/相册，发布选图会失败（云端图由矩阵助手写入 DCIM/Camera）",
                settingsAction = SettingsAction.XHS_APP_DETAILS,
            )
        }
        return out
    }

    fun screenLine(): String {
        val lit = if (env.screenInteractive()) "亮屏" else "熄屏"
        val lock = when {
            env.keyguardSecure() && env.keyguardLocked() -> "有密码锁屏中"
            env.keyguardSecure() -> "有凭据·当前未锁"
            env.keyguardLocked() -> "上滑/无密码锁屏中"
            else -> "未锁屏"
        }
        val xhs = if (env.xhsInstalled()) "小红书已安装" else "未安装小红书"
        val album = when {
            !env.xhsInstalled() -> "相册权限: 未装小红书"
            env.xhsPhotoAccessGranted() -> "相册权限: 已开"
            else -> "相册权限: 未开"
        }
        return "$lit · $lock · $xhs · $album"
    }
}

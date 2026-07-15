package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveHealthCheckerTest {
    @Test
    fun allClearWhenEnvironmentHealthy() {
        val issues = KeepAliveHealthChecker(FakeEnv()).issues()
        assertTrue(issues.isEmpty())
    }

    @Test
    fun reportsSecureKeyguardAndBatteryAndBridge() {
        val issues = KeepAliveHealthChecker(
            FakeEnv(
                keyguardSecure = true,
                keyguardLocked = true,
                ignoringBattery = false,
                bridgeRunning = false,
            ),
        ).issues()
        assertEquals(
            listOf("BATTERY_OPTIMIZED", "SECURE_KEYGUARD", "BRIDGE_DOWN"),
            issues.map { it.code },
        )
        val battery = issues.first { it.code == "BATTERY_OPTIMIZED" }
        assertEquals(SettingsAction.BATTERY_OPTIMIZATION, battery.settingsAction)
        assertEquals(SettingsAction.APP_BATTERY_DETAILS, battery.secondaryAction)
    }

    @Test
    fun secureCredentialWithoutLockDoesNotWarnWhenDoDisabledKeyguard() {
        val issues = KeepAliveHealthChecker(
            FakeEnv(keyguardSecure = true, keyguardLocked = false),
        ).issues()
        assertTrue(issues.none { it.code == "SECURE_KEYGUARD" })
    }

    @Test
    fun oemBatteryAckClearsBatteryIssue() {
        val issues = KeepAliveHealthChecker(
            FakeEnv(ignoringBattery = false, oemAck = true),
        ).issues()
        assertTrue(issues.none { it.code == "BATTERY_OPTIMIZED" })
    }

    @Test
    fun a11yAndImeIssuesHaveNavigationActions() {
        val issues = KeepAliveHealthChecker(
            FakeEnv(a11y = false, imeEnabled = false, imeSelected = false),
        ).issues()
        assertEquals(SettingsAction.ACCESSIBILITY, issues.first { it.code == "A11Y_DOWN" }.settingsAction)
        assertEquals(SettingsAction.INPUT_METHOD, issues.first { it.code == "IME_NOT_READY" }.settingsAction)
    }

    @Test
    fun screenLineSummarizesLitLockAndXhs() {
        val line = KeepAliveHealthChecker(
            FakeEnv(interactive = false, keyguardLocked = true, xhsInstalled = false),
        ).screenLine()
        assertTrue(line.contains("熄屏"))
        assertTrue(line.contains("上滑"))
        assertTrue(line.contains("未安装小红书"))
        assertTrue(line.contains("相册权限: 未装小红书"))
    }

    @Test
    fun xhsPhotoDeniedWhenInstalledWithoutGrant() {
        val issues = KeepAliveHealthChecker(
            FakeEnv(xhsInstalled = true, xhsPhoto = false),
        ).issues()
        val photo = issues.first { it.code == "XHS_PHOTO_DENIED" }
        assertEquals(SettingsAction.XHS_APP_DETAILS, photo.settingsAction)
    }

    @Test
    fun noPhotoIssueWhenXhsMissing() {
        val issues = KeepAliveHealthChecker(
            FakeEnv(xhsInstalled = false, xhsPhoto = false),
        ).issues()
        assertTrue(issues.none { it.code == "XHS_PHOTO_DENIED" })
    }

    private class FakeEnv(
        private val a11y: Boolean = true,
        private val imeEnabled: Boolean = true,
        private val imeSelected: Boolean = true,
        private val ignoringBattery: Boolean = true,
        private val oemAck: Boolean = false,
        private val keyguardSecure: Boolean = false,
        private val bridgeRunning: Boolean = true,
        private val xhsInstalled: Boolean = true,
        private val xhsPhoto: Boolean = true,
        private val interactive: Boolean = true,
        private val keyguardLocked: Boolean = false,
        private val hasBridge: Boolean = true,
    ) : KeepAliveEnvironment {
        override fun accessibilityBound() = a11y
        override fun imeEnabled() = imeEnabled
        override fun imeSelected() = imeSelected
        override fun ignoringBatteryOptimizations() = ignoringBattery
        override fun oemBatteryUnrestrictedAcked() = oemAck
        override fun keyguardSecure() = keyguardSecure
        override fun cloudBridgeRunning() = bridgeRunning
        override fun xhsInstalled() = xhsInstalled
        override fun xhsPhotoAccessGranted() = xhsPhoto
        override fun screenInteractive() = interactive
        override fun keyguardLocked() = keyguardLocked
        override fun hasCloudBridgeBuild() = hasBridge
    }
}

package com.loanagent.devicecontroller

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ManagementActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusView = TextView(this).apply {
            textSize = 17f
            setPadding(0, 0, 0, 32)
        }
        val applyPolicyButton = Button(this).apply {
            text = "Apply minimum Device Owner policy"
            setOnClickListener { applyMinimumPolicy() }
        }
        val installButton = Button(this).apply {
            text = "Install or upgrade Agent"
            setOnClickListener { installAgent(rollbackAuthorized = false) }
        }
        val rollbackButton = Button(this).apply {
            text = "Attempt rollback (higher versionCode artifact required)"
            setOnClickListener { installAgent(rollbackAuthorized = true) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(applyPolicyButton)
            addView(installButton)
            addView(rollbackButton)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        AndroidInstallReconciler(this).reconcile(ControllerStore(this))
        refreshStatus()
    }

    private fun applyMinimumPolicy() {
        val result = runCatching {
            val ownerState = AndroidDeviceOwnerState(this).read()
            val detected = AndroidPolicyCapabilities(this).read()
            PolicyCoordinator(AndroidDevicePolicyGateway(this)).applyMinimumPolicy(
                ownerState = ownerState,
                capabilities = detected.copy(
                    maximumTimeToLock = false,
                    keepScreenOn = true,
                ),
                agentPackage = AGENT_PACKAGE,
            )
        }.getOrElse {
            PolicyApplicationResult(status = PolicyStatus.FAILED)
        }
        ControllerStore(this).recordRecovery(
            "POLICY_${result.status}:error=${result.error}:" +
                "applied=${result.applied}:unsupported=${result.unsupported}",
        )
        refreshStatus()
    }

    private fun installAgent(rollbackAuthorized: Boolean) {
        val ownerState = AndroidDeviceOwnerState(this).read()
        if (!ownerState.isThisAppDeviceOwner) {
            ControllerStore(this).recordInstall("REFUSED_NOT_DEVICE_OWNER", false)
            refreshStatus()
            return
        }
        val config = ControllerStore(this).trustedUpdateConfig()
        if (config == null) {
            ControllerStore(this).recordInstall("REFUSED_MISSING_PROVISIONING_CONFIG", false)
            refreshStatus()
            return
        }
        AgentPackageInstaller(this).installFromManifest(config, rollbackAuthorized) {
            runOnUiThread { refreshStatus() }
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val owner = AndroidDeviceOwnerState(this).read()
        val version = installedAgentVersion()
        val store = ControllerStore(this)
        statusView.text = buildString {
            appendLine("Device Owner: ${owner.isThisAppDeviceOwner}")
            appendLine("Owner package: ${owner.ownerPackage ?: "unknown / none"}")
            appendLine("Agent installed: ${version != null}")
            appendLine("Agent version: ${version ?: "NOT_INSTALLED"}")
            appendLine("Last enrollment: ${store.lastEnrollment()}")
            appendLine("Last recovery: ${store.lastRecovery()}")
            append("Last install: ${store.lastInstall()}")
        }
    }

    @Suppress("DEPRECATION")
    private fun installedAgentVersion(): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                AGENT_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            packageManager.getPackageInfo(AGENT_PACKAGE, 0)
        }
        info.versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        const val AGENT_PACKAGE = "com.loanagent.agent"
    }
}

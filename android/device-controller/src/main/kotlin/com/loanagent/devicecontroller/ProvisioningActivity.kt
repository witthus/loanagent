package com.loanagent.devicecontroller

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.TextView

class ProvisioningActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var observingCompliance = false
    private val complianceObserver = object : Runnable {
        override fun run() {
            if (!observingCompliance || isFinishing || isDestroyed) return
            val store = ControllerStore(this@ProvisioningActivity)
            when (store.provisioningRunState()) {
                ProvisioningRunState.COMPLIANT -> {
                    finishCompliance(true, store.provisioningDiagnostic())
                }
                ProvisioningRunState.FAILED -> {
                    finishCompliance(false, store.provisioningDiagnostic())
                }
                ProvisioningRunState.NOT_STARTED,
                ProvisioningRunState.IN_PROGRESS,
                -> handler.postDelayed(this, OBSERVER_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saveAdminExtras()

        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                selectProvisioningMode()
            }

            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                completeEnrollmentAndPolicy()
            }

            else -> {
                showStatus("Provisioning entry is reserved for Android managed provisioning.")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (intent.action == DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE) {
            observingCompliance = true
            handler.removeCallbacks(complianceObserver)
            handler.post(complianceObserver)
        }
    }

    override fun onStop() {
        observingCompliance = false
        handler.removeCallbacks(complianceObserver)
        super.onStop()
    }

    private fun selectProvisioningMode() {
        val key = DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
        val allowedModes = (
            intent.getIntegerArrayListExtra(key)?.toSet()
                ?: intent.getIntArrayExtra(key)?.toSet()
                ?: emptySet()
            )
        if (
            ProvisioningModeSelector().select(allowedModes) !=
            ProvisioningModeDecision.SELECT_FULLY_MANAGED
        ) {
            setResult(
                RESULT_CANCELED,
                Intent().putExtra(EXTRA_COMPLIANCE_STATUS, "FULLY_MANAGED_MODE_NOT_ALLOWED"),
            )
            finish()
            return
        }
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
                )
                putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS,
                    true,
                )
            },
        )
        finish()
    }

    private fun completeEnrollmentAndPolicy() {
        showStatus("Consuming one-time enrollment token…")
        ProvisioningApplicationCoordinator.ensureRunning(applicationContext)
    }

    private fun finishCompliance(compliant: Boolean, diagnostic: String) {
        observingCompliance = false
        handler.removeCallbacks(complianceObserver)
        val response = Intent().putExtra(EXTRA_COMPLIANCE_STATUS, diagnostic)
        if (compliant) {
            setResult(RESULT_OK, response)
        } else {
            setResult(RESULT_CANCELED, response)
        }
        finish()
    }

    private fun showStatus(message: String) {
        setContentView(
            TextView(this).apply {
                text = message
                textSize = 18f
                setPadding(32, 64, 32, 32)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun saveAdminExtras() {
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )
        if (extras != null) {
            ControllerStore(this).saveProvisioningExtras(extras)
        }
    }

    companion object {
        const val EXTRA_COMPLIANCE_STATUS = "compliance_status"
        private const val OBSERVER_INTERVAL_MS = 200L
    }
}

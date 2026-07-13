package com.loanagent.devicecontroller

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import android.os.PersistableBundle
import java.util.ArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProvisioningActivityLifecycleTest {
    @Before
    fun clearControllerState() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("device_controller_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun getProvisioningModeReturnsOnlySystemAllowedFullyManagedMode() {
        val allowed = Intent(DevicePolicyManager.ACTION_GET_PROVISIONING_MODE).apply {
            putIntegerArrayListExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES,
                ArrayList(listOf(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)),
            )
        }
        val accepted = Robolectric.buildActivity(ProvisioningActivity::class.java, allowed)
            .create()
            .get()
        val rejected = Robolectric.buildActivity(
            ProvisioningActivity::class.java,
            Intent(DevicePolicyManager.ACTION_GET_PROVISIONING_MODE),
        ).create().get()

        assertTrue(accepted.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(accepted).resultCode)
        assertTrue(rejected.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(rejected).resultCode)
    }

    @Test
    fun recreatedActivityReturnsPersistedResultWithoutStartingEnrollmentAgain() {
        val application = RuntimeEnvironment.getApplication()
        val store = ControllerStore(application)
        val adminExtras = PersistableBundle().apply {
            putString("enrollment_token", "single-use-token")
            putString("control_plane_url", "https://control.loanagent.example/enroll")
            putString("trusted_control_plane_host", "control.loanagent.example")
        }
        store.saveProvisioningExtras(adminExtras)
        store.recordProvisioningRun(
            ProvisioningRunState.COMPLIANT,
            ComplianceStatus.COMPLIANT.name,
        )
        store.clearEnrollmentToken()
        val intent = Intent(DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE).apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                adminExtras,
            )
        }

        val first = Robolectric.buildActivity(ProvisioningActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .get()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(store.pendingEnrollmentConfig())
        val recreated = Robolectric.buildActivity(ProvisioningActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .get()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Activity.RESULT_OK, shadowOf(first).resultCode)
        assertEquals(Activity.RESULT_OK, shadowOf(recreated).resultCode)
        assertNull(store.pendingEnrollmentConfig())
        assertEquals(
            ProvisioningRunState.COMPLIANT,
            store.provisioningRunState(),
        )
    }

    @Test
    fun manifestParserRejectsUnknownFieldsAndWrongFieldTypes() {
        val valid = """
            {
              "schema_version":"1.0",
              "manifest_version":"1.0.0",
              "agent_version":"1.0.0",
              "minimum_agent_version":"1.0.0",
              "rollout_ring":"canary",
              "artifacts":[{
                "name":"agent.apk",
                "url":"https://updates.loanagent.example/agent.apk",
                "sha256":"${"0".repeat(64)}",
                "size_bytes":3
              }],
              "issued_at":"2026-07-10T08:00:00Z",
              "signature":{"algorithm":"ECDSA-P256-SHA256","key_id":"m0","value":"AQ=="}
            }
        """.trimIndent()
        val unknownField = valid.replace(
            "\"schema_version\":\"1.0\",",
            "\"schema_version\":\"1.0\",\"unexpected\":true,",
        )
        val wrongType = valid.replace("\"size_bytes\":3", "\"size_bytes\":\"3\"")
        val duplicateKey = valid.replace(
            "\"schema_version\":\"1.0\",",
            "\"schema_version\":\"1.0\",\"schema_version\":\"2.0\",",
        )
        val fractional = valid.replace("\"size_bytes\":3", "\"size_bytes\":3.0")
        val exponent = valid.replace("\"size_bytes\":3", "\"size_bytes\":3e0")
        val tooLarge = valid.replace(
            "\"size_bytes\":3",
            "\"size_bytes\":${ArtifactSizePolicy.MAX_APK_BYTES + 1}",
        )

        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestJsonParser.parse(unknownField)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestJsonParser.parse(wrongType)
        }
        listOf(duplicateKey, fractional, exponent, tooLarge).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                UpdateManifestJsonParser.parse(invalid)
            }
        }
    }

    @Test
    fun installReceiverPersistsOnlyMatchingTerminalSessionResult() {
        val application = RuntimeEnvironment.getApplication()
        val store = ControllerStore(application)
        store.recordInstallSession(42, 12, "1.2.0")
        val stale = Intent(InstallResultReceiver.ACTION_INSTALL_RESULT).apply {
            putExtra(InstallResultReceiver.EXTRA_SESSION_ID, 41)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
            putExtra(InstallResultReceiver.EXTRA_MANIFEST_VERSION, "9.9.9")
        }
        InstallResultReceiver().onReceive(application, stale)

        assertEquals(42, store.installSessionId())
        assertNull(store.highestManifestVersion())

        val matching = Intent(InstallResultReceiver.ACTION_INSTALL_RESULT).apply {
            putExtra(InstallResultReceiver.EXTRA_SESSION_ID, 42)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
            putExtra(InstallResultReceiver.EXTRA_TARGET_VERSION, "1.2.0")
            putExtra(InstallResultReceiver.EXTRA_INSTALL_ACTION, InstallAction.INSTALL_UPGRADE.name)
            putExtra(InstallResultReceiver.EXTRA_MANIFEST_VERSION, "1.2.0")
        }
        InstallResultReceiver().onReceive(application, matching)

        assertNull(store.installSessionId())
        assertEquals("1.2.0", store.highestManifestVersion())
        assertTrue(store.lastInstall().startsWith("SUCCESS:INSTALL_UPGRADE:1.2.0"))
    }
}

package com.loanagent.agent

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M0DebugBridgeReleaseTest {
    @Test
    @Suppress("DEPRECATION")
    fun releaseManifestDoesNotContainDebugCommandReceiver() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(
            context.packageName,
            "com.loanagent.agent.M0DebugCommandReceiver",
        )

        assertThrows(PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
        }
    }
}

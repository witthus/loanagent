package com.loanagent.agent

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M0AndroidBoundaryTest {
    @Test
    @Suppress("DEPRECATION")
    fun manifestProtectsAccessibilityAndImeServicesWithSystemPermissions() {
        val packageManager = RuntimeEnvironment.getApplication().packageManager
        val accessibility = packageManager.getServiceInfo(
            ComponentName(RuntimeEnvironment.getApplication(), M0AccessibilityService::class.java),
            PackageManager.GET_META_DATA,
        )
        val ime = packageManager.getServiceInfo(
            ComponentName(RuntimeEnvironment.getApplication(), M0InputMethodService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, accessibility.permission)
        assertNotEquals(0, accessibility.metaData.getInt("android.accessibilityservice"))
        assertEquals(Manifest.permission.BIND_INPUT_METHOD, ime.permission)
        assertTrue(accessibility.exported)
        assertTrue(ime.exported)
    }

    @Test
    fun accessibilityTreeExcludesViewsAppsMarkAsUnimportant() {
        val context = RuntimeEnvironment.getApplication()
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, M0AccessibilityService::class.java),
            PackageManager.GET_META_DATA,
        )
        val parser = context.resources.getXml(
            service.metaData.getInt("android.accessibilityservice"),
        )
        while (
            parser.eventType != XmlPullParser.START_TAG &&
            parser.eventType != XmlPullParser.END_DOCUMENT
        ) {
            parser.next()
        }

        val flags = parser.getAttributeIntValue(
            "http://schemas.android.com/apk/res/android",
            "accessibilityFlags",
            0,
        )

        assertNotEquals(
            0,
            flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS,
        )
        assertEquals(
            0,
            flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS,
        )
    }

    @Test
    fun accessibilityScopeAllowsOnlyXhsAndFixture() {
        assertTrue(M0AccessibilityService.isPackageAllowed("com.xingin.xhs"))
        assertEquals(
            BuildConfig.DEBUG,
            M0AccessibilityService.isPackageAllowed("com.loanagent.fixture"),
        )
        assertFalse(M0AccessibilityService.isPackageAllowed("com.example.bank"))
    }

    @Test
    @Config(sdk = [34])
    fun android14ImeStatusDoesNotTrustLegacyEnabledSetting() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, M0InputMethodService::class.java)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS,
            component.flattenToString(),
        )
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            "malformed/component/with/extra",
        )

        val status = M0InputMethodService.status(context)

        assertFalse(status.enabled)
        assertFalse(status.selected)
    }

    @Test
    @Config(sdk = [35])
    fun android15ImeStatusSafelyParsesSelectedComponent() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, M0InputMethodService::class.java)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            component.flattenToString(),
        )

        assertTrue(M0InputMethodService.status(context).selected)
    }

    @Test
    @Suppress("DEPRECATION")
    fun serviceWindowEventsAdvanceGenerationAcrossUnknownAppForAbaProtection() {
        val service = Robolectric.buildService(M0AccessibilityService::class.java).create().get()
        val first = service.leaseGeneration.current()
        val xhs = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.xingin.xhs"
        }
        val unknown = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.example.bank"
        }

        service.onAccessibilityEvent(xhs)
        service.onAccessibilityEvent(unknown)
        service.onAccessibilityEvent(xhs)

        assertEquals(first + 3, service.leaseGeneration.current())
        xhs.recycle()
        unknown.recycle()
    }

    @Test
    @Suppress("DEPRECATION")
    fun repeatedWindowEventsFromSamePackageKeepLeaseGenerationStable() {
        val service = Robolectric.buildService(M0AccessibilityService::class.java).create().get()
        val first = service.leaseGeneration.current()
        val initial = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.xingin.xhs"
        }
        val dynamicRefresh = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED).apply {
            packageName = "com.xingin.xhs"
        }

        service.onAccessibilityEvent(initial)
        service.onAccessibilityEvent(dynamicRefresh)

        assertEquals(first + 1, service.leaseGeneration.current())
        initial.recycle()
        dynamicRefresh.recycle()
    }

    @Test
    fun diagnosticCacheRejectsPathsOutsidePrivateCache() {
        val cache = DiagnosticCache(RuntimeEnvironment.getApplication())

        assertFalse(cache.isManagedFile("/sdcard/leak.png"))
        assertTrue(cache.isManagedFile(cache.directory.absolutePath + "/snapshot.json"))
    }

    @Test
    fun diagnosticCacheDeletesOnlyExpiredFiles() {
        val cache = DiagnosticCache(RuntimeEnvironment.getApplication())
        cache.clear()
        val old = cache.newFile("old", "json").apply {
            writeText("old")
            setLastModified(1_000)
        }
        val fresh = cache.newFile("fresh", "json").apply {
            writeText("fresh")
            setLastModified(99_000)
        }

        cache.deleteExpired(nowMillis = 100_000, expiryMillis = 10_000)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun diagnosticActivityExposesWaitTimeoutPostconditionAndManualImeControls() {
        val activity = Robolectric.buildActivity(AgentStatusActivity::class.java).setup().get()

        assertNotNull(activity.findViewById<EditText>(DiagnosticIds.WAIT_CONDITION))
        assertNotNull(activity.findViewById<EditText>(DiagnosticIds.WAIT_TIMEOUT))
        assertNotNull(activity.findViewById<EditText>(DiagnosticIds.POSTCONDITION))
        assertNotNull(activity.findViewById<Button>(DiagnosticIds.WAIT_BUTTON))
        assertNotNull(activity.findViewById<Button>(DiagnosticIds.IME_SETTINGS_BUTTON))
    }

    @Test
    fun dangerousActionButtonShowsConfirmationBeforeExecution() {
        val fake = FakeDiagnosticController()
        AgentStatusActivity.controllerProvider = { fake }
        val activity = Robolectric.buildActivity(AgentStatusActivity::class.java).setup().get()

        try {
            activity.findViewById<Button>(DiagnosticIds.CLICK_BUTTON).performClick()

            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            assertTrue(dialog.isShowing)
        } finally {
            AgentStatusActivity.resetControllerProvider()
        }
    }

    @Test
    fun accessibilityServiceUsesAutomationAndVisualPorts() {
        assertTrue(AutomationPort::class.java.isAssignableFrom(M0AccessibilityService::class.java))
        assertTrue(VisualDiagnosticPort::class.java.isAssignableFrom(M0AccessibilityService::class.java))
        assertTrue(M0DiagnosticController::class.java.isAssignableFrom(M0AccessibilityService::class.java))
    }

    @Test
    fun directServicePortsEnforceGestureAndGlobalActionSafety() {
        val service = Robolectric.buildService(M0AccessibilityService::class.java).create().get()
        val lease = TargetLease("com.xingin.xhs", service.leaseGeneration.current())
        var gestureCallbackCalled = false

        val dispatched = service.dispatchGesture(
            lease,
            SwipeSpec(10, 20, 10, 5, 100),
        ) { gestureCallbackCalled = true }
        assertFalse(dispatched)
        assertFalse(gestureCallbackCalled)
        if (BuildConfig.DEBUG) {
            // Debug allows XHS gestures at the policy boundary; Robolectric has no active window.
            assertEquals(null, service.lastGestureBoundaryStatus)
        } else {
            assertEquals("UNSAFE_GESTURE_BLOCKED", service.lastGestureBoundaryStatus)
        }

        assertFalse(service.globalBack(lease))
        assertEquals("UNSAFE_GLOBAL_ACTION_BLOCKED", service.lastGlobalActionBoundaryStatus)
    }

    @Test
    fun diagnosticActivityInvokesInjectedControllerInterfaceForWait() {
        val fake = FakeDiagnosticController()
        AgentStatusActivity.controllerProvider = { fake }
        try {
            val activity = Robolectric.buildActivity(AgentStatusActivity::class.java).setup().get()
            activity.findViewById<EditText>(DiagnosticIds.WAIT_CONDITION)
                .setText("pageChange:HOME")

            activity.findViewById<Button>(DiagnosticIds.WAIT_BUTTON).performClick()

            assertEquals(1, fake.waitCalls)
        } finally {
            AgentStatusActivity.resetControllerProvider()
        }
    }

    @Test
    fun activityRejectsRepeatedDangerousRequestAndCancelsOnDestroy() {
        val fake = FakeDiagnosticController(completeActionsImmediately = false)
        AgentStatusActivity.controllerProvider = { fake }
        try {
            val controller = Robolectric.buildActivity(AgentStatusActivity::class.java).setup()
            val activity = controller.get()
            activity.findViewById<EditText>(DiagnosticIds.SELECTOR).setText("text=发布")
            activity.findViewById<Button>(DiagnosticIds.CLICK_BUTTON).performClick()
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            activity.findViewById<Button>(DiagnosticIds.CLICK_BUTTON).performClick()

            assertEquals(1, fake.actionCalls)
            assertEquals(
                "REQUEST_IN_FLIGHT",
                activity.findViewById<TextView>(DiagnosticIds.OUTPUT).text.toString(),
            )
            controller.destroy()
            assertEquals(1, fake.cancelCalls)
        } finally {
            AgentStatusActivity.resetControllerProvider()
        }
    }

    @Test
    fun destroyedActivityIgnoresOldActionCallback() {
        val fake = FakeDiagnosticController(completeActionsImmediately = false)
        AgentStatusActivity.controllerProvider = { fake }
        try {
            val controller = Robolectric.buildActivity(AgentStatusActivity::class.java).setup()
            val activity = controller.get()
            activity.findViewById<EditText>(DiagnosticIds.SELECTOR).setText("text=发布")
            activity.findViewById<Button>(DiagnosticIds.CLICK_BUTTON).performClick()
            ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.destroy()

            fake.completePendingAction()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            assertEquals(
                "ACTION_RUNNING",
                activity.findViewById<TextView>(DiagnosticIds.OUTPUT).text.toString(),
            )
        } finally {
            AgentStatusActivity.resetControllerProvider()
        }
    }

    @Test
    fun memoryOcrButtonRequestsNoScreenshotPersistence() {
        val fake = FakeDiagnosticController()
        AgentStatusActivity.controllerProvider = { fake }
        try {
            val activity = Robolectric.buildActivity(AgentStatusActivity::class.java).setup().get()

            activity.findViewById<Button>(DiagnosticIds.SCREENSHOT_MEMORY_BUTTON).performClick()

            assertFalse(requireNotNull(fake.lastVisualRequest).saveOriginal)
        } finally {
            AgentStatusActivity.resetControllerProvider()
        }
    }

    @Test
    fun activityUsesOneGlobalFlightAcrossWaitAndOcrWithoutReplacingHandle() {
        val fake = FakeDiagnosticController(completeWaitsImmediately = false)
        AgentStatusActivity.controllerProvider = { fake }
        try {
            val controller = Robolectric.buildActivity(AgentStatusActivity::class.java).setup()
            val activity = controller.get()
            activity.findViewById<EditText>(DiagnosticIds.WAIT_CONDITION)
                .setText("pageChange:HOME")
            activity.findViewById<Button>(DiagnosticIds.WAIT_BUTTON).performClick()

            activity.findViewById<Button>(DiagnosticIds.SCREENSHOT_MEMORY_BUTTON).performClick()

            assertEquals(1, fake.waitCalls)
            assertEquals(0, fake.visualCalls)
            assertEquals(
                "REQUEST_IN_FLIGHT",
                activity.findViewById<TextView>(DiagnosticIds.OUTPUT).text.toString(),
            )
            controller.destroy()
            assertEquals(1, fake.cancelCalls)
            fake.completePendingWait()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(
                "REQUEST_IN_FLIGHT",
                activity.findViewById<TextView>(DiagnosticIds.OUTPUT).text.toString(),
            )
        } finally {
            AgentStatusActivity.resetControllerProvider()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun diagnosticProviderIsPrivateGrantOnlyAndReadOnly() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getProviderInfo(
            ComponentName(context, DiagnosticExportProvider::class.java),
            PackageManager.GET_META_DATA,
        )
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        val provider = Robolectric.setupContentProvider(DiagnosticExportProvider::class.java)
        val cache = DiagnosticCache(context)
        cache.clear()
        val file = cache.newFile("provider", "json").apply { writeText("{}") }
        val valid = Uri.parse("content://${context.packageName}.diagnostics/${file.name}")

        provider.openFile(valid, "r").close()
        assertThrows(FileNotFoundException::class.java) { provider.openFile(valid, "w") }
        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(
                Uri.parse("content://${context.packageName}.diagnostics/unknown.json"),
                "r",
            )
        }
    }
}

private class FakeDiagnosticController(
    private val completeActionsImmediately: Boolean = true,
    private val completeWaitsImmediately: Boolean = true,
) : M0DiagnosticController {
    var waitCalls = 0
    var actionCalls = 0
    var visualCalls = 0
    var cancelCalls = 0
    var lastVisualRequest: VisualDiagnosticRequest? = null
    private val lease = TargetLease("com.xingin.xhs", 1)
    private var pendingAction: ((ActionResult) -> Unit)? = null
    private var pendingWait: ((WaitResult) -> Unit)? = null

    override fun currentLease(): TargetLease = lease

    override fun observe(expectedLease: TargetLease?): UiSnapshot? = null

    override fun executeAction(
        request: ActionRequest,
        callback: (ActionResult) -> Unit,
    ): RequestHandle {
        actionCalls += 1
        pendingAction = callback
        if (completeActionsImmediately) completePendingAction()
        return object : RequestHandle {
            override fun cancel() {
                cancelCalls += 1
                pendingAction = null
            }
        }
    }

    override fun waitForCondition(
        expectedLease: TargetLease,
        condition: WaitCondition,
        timeoutMs: Long,
        callback: (WaitResult) -> Unit,
    ): RequestHandle {
        waitCalls += 1
        pendingWait = callback
        if (completeWaitsImmediately) completePendingWait()
        return object : RequestHandle {
            override fun cancel() {
                cancelCalls += 1
                pendingWait = null
            }
        }
    }

    override fun runVisualDiagnostic(
        request: VisualDiagnosticRequest,
        callback: (VisualDiagnosticResult) -> Unit,
    ): RequestHandle {
        visualCalls += 1
        lastVisualRequest = request
        callback(VisualDiagnosticResult(null, "OCR", "SUCCESS"))
        return CompletedRequestHandle
    }

    fun completePendingAction() {
        pendingAction?.invoke(
            ActionResult(
                ActionStatus.SUCCESS,
                M0Action.CLICK,
                ActionPath.NODE_ACTION,
                false,
                "done",
                ExecutionStage.ACTION_ACCEPTED,
            ),
        )
        pendingAction = null
    }

    fun completePendingWait() {
        pendingWait?.invoke(WaitResult(WaitStatus.MET, 1, 0))
        pendingWait = null
    }
}

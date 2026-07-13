package com.loanagent.fixture

import android.widget.Button
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FixtureActivityTest {
    @Test
    fun exposesStableButtonInputAndScrollableListNodes() {
        val activity = Robolectric.buildActivity(FixtureActivity::class.java).setup().get()

        assertNotNull(activity.findViewById<Button>(FixtureIds.NORMAL_BUTTON))
        assertNotNull(activity.findViewById<EditText>(FixtureIds.TEXT_INPUT))
        assertNotNull(activity.findViewById<android.widget.ListView>(FixtureIds.SCROLL_LIST))
    }

    @Test
    fun scenarioButtonsExposeBlockedAndLoginRequiredText() {
        val activity = Robolectric.buildActivity(FixtureActivity::class.java).setup().get()
        activity.findViewById<Button>(FixtureIds.BUSINESS_BLOCKED).performClick()
        assertTrue(activity.fixtureStatus().contains("业务升级维护中"))

        activity.findViewById<Button>(FixtureIds.LOGIN_REQUIRED).performClick()
        assertTrue(activity.fixtureStatus().contains("登录"))
        assertTrue(activity.fixtureStatus().contains("验证码"))
    }

    @Test
    fun customDrawnRegionIntentionallyHasNoAccessibilityDescription() {
        val activity = Robolectric.buildActivity(FixtureActivity::class.java).setup().get()
        val region = activity.findViewById<android.view.View>(FixtureIds.CUSTOM_DRAWN)

        assertEquals(null, region.contentDescription)
        assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO, region.importantForAccessibility)
    }

    @Test
    fun dialogButtonShowsControllableFixtureDialog() {
        val activity = Robolectric.buildActivity(FixtureActivity::class.java).setup().get()

        activity.findViewById<Button>(FixtureIds.DIALOG_BUTTON).performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
    }
}

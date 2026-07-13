package com.loanagent.fixture

import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureScenarioTest {
    @Test
    fun createsStableAccessibilityScenarioLabels() {
        assertEquals("fixture:ready", FixtureScenario().label("ready"))
    }
}

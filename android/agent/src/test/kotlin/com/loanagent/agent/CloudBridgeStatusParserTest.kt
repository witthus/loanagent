package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudBridgeStatusParserTest {
    @Test
    fun parsesDisplayNameGeoAndBoundAccount() {
        val body =
            """
            {
              "display_name": "客厅红米",
              "geo_label": "湖北武汉",
              "bound_account": {
                "account_id": "phone-publisher-1",
                "display_name": "主号",
                "role": "PUBLISHER_MAIN",
                "status": "active"
              }
            }
            """.trimIndent()
        val (name, geo, bound) = CloudBridgeStatusParser.parseHeartbeatBody(body)
        assertEquals("客厅红米", name)
        assertEquals("湖北武汉", geo)
        assertEquals("phone-publisher-1", bound!!.accountId)
        assertEquals("主号", bound.displayName)
        assertEquals("PUBLISHER_MAIN", bound.role)
        assertEquals("active", bound.status)
    }

    @Test
    fun nullBoundAccountWhenMissing() {
        val (name, geo, bound) = CloudBridgeStatusParser.parseHeartbeatBody(
            """{"display_name":"x","bound_account":null}""",
        )
        assertEquals("x", name)
        assertNull(geo)
        assertNull(bound)
    }

    @Test
    fun jsonNullDisplayNameIsAbsentNotLiteralNull() {
        val (name, _, _) = CloudBridgeStatusParser.parseHeartbeatBody(
            """{"display_name":null,"geo_label":null,"bound_account":null}""",
        )
        assertNull(name)
    }
}

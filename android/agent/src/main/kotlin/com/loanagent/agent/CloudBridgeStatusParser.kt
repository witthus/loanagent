package com.loanagent.agent

import org.json.JSONObject

/** Pure JSON helpers for heartbeat / bridge status parsing (unit-testable). */
object CloudBridgeStatusParser {
    fun parseHeartbeatBody(body: String): Triple<String?, String?, BoundAccountSnapshot?> {
        val json = JSONObject(body)
        val displayName = optionalString(json, "display_name")
        val geo = optionalString(json, "geo_label")
        val bound = when {
            !json.has("bound_account") || json.isNull("bound_account") -> null
            else -> {
                val node = json.getJSONObject("bound_account")
                BoundAccountSnapshot(
                    accountId = node.getString("account_id"),
                    displayName = optionalString(node, "display_name"),
                    role = node.getString("role"),
                    status = node.getString("status"),
                )
            }
        }
        return Triple(displayName, geo, bound)
    }

    /** Treats JSON null / blank / literal "null" as absent. */
    fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val raw = json.opt(key) ?: return null
        if (raw === JSONObject.NULL) return null
        val value = raw.toString().trim()
        if (value.isEmpty() || value.equals("null", ignoreCase = true)) return null
        return value
    }
}

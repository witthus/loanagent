package com.loanagent.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

class HeartbeatClient(
    private val openConnection: (String) -> HttpURLConnection = { url ->
        (URL(url).openConnection() as HttpURLConnection)
    },
) {
    fun send(
        a11yBound: Boolean,
        wifiConnected: Boolean? = null,
        cellularOk: Boolean? = null,
        manufacturer: String? = null,
        model: String? = null,
    ): Boolean {
        val body = JSONObject()
            .put("agent_version", CloudBridgeConfig.agentVersion())
            .put("a11y_bound", a11yBound)
        if (wifiConnected != null) body.put("wifi_connected", wifiConnected)
        if (cellularOk != null) body.put("cellular_ok", cellularOk)
        if (!manufacturer.isNullOrBlank()) body.put("manufacturer", manufacturer)
        if (!model.isNullOrBlank()) body.put("model", model)
        return postJson(
            url = CloudBridgeConfig.heartbeatUrl(),
            body = body.toString(),
            headers = mapOf("X-Device-Token" to CloudBridgeConfig.DEVICE_TOKEN),
        )
    }

    private fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): Boolean {
        val connection = openConnection(url)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            BufferedOutputStream(connection.outputStream).use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

fun interface TaskEventSink {
    fun report(
        taskId: String,
        status: String,
        errorCode: String?,
        resultPayload: Map<String, Any?>?,
    ): Boolean
}

class TaskEventReporter(
    private val openConnection: (String) -> HttpURLConnection = { url ->
        (URL(url).openConnection() as HttpURLConnection)
    },
) : TaskEventSink {
    override fun report(
        taskId: String,
        status: String,
        errorCode: String?,
        resultPayload: Map<String, Any?>?,
    ): Boolean {
        val body = JSONObject()
            .put("task_id", taskId)
            .put("status", status)
        if (errorCode != null) body.put("error_code", errorCode)
        if (resultPayload != null) {
            body.put("result_payload", toJsonValue(resultPayload))
        }
        val connection = openConnection(CloudBridgeConfig.eventsUrl())
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty(
                "Authorization",
                "Bearer ${CloudBridgeConfig.OPS_TOKEN}",
            )
            BufferedOutputStream(connection.outputStream).use { out ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = connection.responseCode
            val ok = code in 200..299
            if (!ok) {
                Log.w(TAG, "event report HTTP $code for task=$taskId status=$status")
            }
            ok
        } catch (error: Exception) {
            Log.w(TAG, "event report failed for task=$taskId", error)
            false
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "CloudEvents"

        internal fun toJsonValue(value: Any?): Any {
            return when (value) {
                null -> JSONObject.NULL
                is JSONObject, is JSONArray -> value
                is Map<*, *> -> {
                    val obj = JSONObject()
                    value.forEach { (key, nested) ->
                        if (key != null) {
                            obj.put(key.toString(), toJsonValue(nested))
                        }
                    }
                    obj
                }
                is Iterable<*> -> {
                    val arr = JSONArray()
                    value.forEach { arr.put(toJsonValue(it)) }
                    arr
                }
                is Array<*> -> {
                    val arr = JSONArray()
                    value.forEach { arr.put(toJsonValue(it)) }
                    arr
                }
                is Boolean, is Number, is String -> value
                else -> value.toString()
            }
        }
    }
}

class CommandPollClient(
    private val openConnection: (String) -> HttpURLConnection = { url ->
        (URL(url).openConnection() as HttpURLConnection)
    },
) {
    fun poll(): List<String> {
        val connection = openConnection(CloudBridgeConfig.commandsPollUrl())
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("X-Device-Token", CloudBridgeConfig.DEVICE_TOKEN)
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "command poll HTTP $code")
                return emptyList()
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = org.json.JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    add(array.getJSONObject(i).toString())
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "command poll failed", error)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "CloudPoll"
    }
}

package com.loanagent.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface CloudNetworkClient {
    fun cancelActive()
}

class HeartbeatClient(
    private val openConnection: (String) -> HttpURLConnection = { url ->
        (URL(url).openConnection() as HttpURLConnection)
    },
) : CloudNetworkClient {
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val closed = AtomicBoolean(false)

    override fun cancelActive() {
        closed.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }
    fun send(
        a11yBound: Boolean,
        wifiConnected: Boolean? = null,
        cellularOk: Boolean? = null,
        manufacturer: String? = null,
        model: String? = null,
    ): Boolean {
        if (closed.get()) return false
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
        activeConnection.set(connection)
        if (closed.get()) {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            return false
        }
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
            activeConnection.compareAndSet(connection, null)
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

    fun report(
        taskId: String,
        status: String,
        errorCode: String?,
        resultPayload: Map<String, Any?>?,
        execution: TaskExecutionContext,
    ): Boolean = report(taskId, status, errorCode, resultPayload)
}

class TaskEventReporter(
    private val openConnection: (String) -> HttpURLConnection = { url ->
        (URL(url).openConnection() as HttpURLConnection)
    },
) : TaskEventSink, CloudNetworkClient {
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val closed = AtomicBoolean(false)

    override fun cancelActive() {
        closed.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }
    override fun report(
        taskId: String,
        status: String,
        errorCode: String?,
        resultPayload: Map<String, Any?>?,
    ): Boolean = reportInternal(taskId, status, errorCode, resultPayload, null)

    override fun report(
        taskId: String,
        status: String,
        errorCode: String?,
        resultPayload: Map<String, Any?>?,
        execution: TaskExecutionContext,
    ): Boolean = reportInternal(taskId, status, errorCode, resultPayload, execution)

    private fun reportInternal(
        taskId: String,
        status: String,
        errorCode: String?,
        resultPayload: Map<String, Any?>?,
        execution: TaskExecutionContext?,
    ): Boolean {
        if (closed.get()) return false
        execution?.check()
        val body = JSONObject()
            .put("task_id", taskId)
            .put("status", status)
        if (errorCode != null) body.put("error_code", errorCode)
        if (status == "succeeded" && resultPayload != null) {
            body.put("result_payload", toJsonValue(resultPayload))
        }
        val connection = openConnection(CloudBridgeConfig.eventsUrl())
        activeConnection.set(connection)
        if (closed.get()) {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            return false
        }
        return try {
            connection.requestMethod = "POST"
            // After complete() CAS, remainingMillis() is 0. Terminal reports still need a
            // real network budget — otherwise connectTimeout collapses to 1ms and finals
            // never leave the device ledger (cloud stuck in executing).
            val remaining = execution?.remainingMillis()
            val timeout = when {
                remaining == null -> 8_000
                remaining <= 0L -> 8_000
                else -> remaining.coerceAtMost(8_000L).toInt().coerceAtLeast(1_000)
            }
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
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
            execution?.check()
            val code = connection.responseCode
            execution?.check()
            val ok = code in 200..299
            if (!ok) {
                Log.w(TAG, "event report HTTP $code for task=$taskId status=$status")
            }
            ok
        } catch (cancelled: TaskExecutionCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "event report failed for task=$taskId", error)
            false
        } finally {
            activeConnection.compareAndSet(connection, null)
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
) : CloudNetworkClient {
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val closed = AtomicBoolean(false)

    override fun cancelActive() {
        closed.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    fun poll(): List<String> {
        if (closed.get()) return emptyList()
        val connection = openConnection(CloudBridgeConfig.commandsPollUrl())
        activeConnection.set(connection)
        if (closed.get()) {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            return emptyList()
        }
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
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "CloudPoll"
    }
}

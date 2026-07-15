package com.loanagent.agent

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal MQTT 3.1.1 subscriber (CONNECT + SUBSCRIBE + PUBLISH + PING).
 * Debug-only; avoids adding Paho to the locked dependency set.
 */
class MqttCommandClient(
    private val onCommand: (String) -> Unit,
    private val socketFactory: () -> Socket = ::Socket,
    private val joinTimeoutMs: Long = 2_000,
) {
    private val started = AtomicBoolean(false)
    private val lifecycle = Any()
    private val socketLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        synchronized(lifecycle) {
            if (worker?.isAlive == true) return
            if (!started.compareAndSet(false, true)) return
            val thread = Thread(
                {
                    try {
                        while (started.get()) {
                            try {
                                runSession()
                            } catch (error: Exception) {
                                if (started.get()) Log.w(TAG, "mqtt session ended", error)
                            }
                            if (started.get()) {
                                try {
                                    Thread.sleep(3_000)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }
                    } finally {
                        started.set(false)
                    }
                },
                "cloud-mqtt",
            ).apply { isDaemon = true }
            // Publish worker before Thread.start so stop() cannot miss it.
            worker = thread
            thread.start()
        }
    }

    fun stop(): Boolean {
        synchronized(lifecycle) {
            started.set(false)
            val activeSocket = synchronized(socketLock) { socket }
            try {
                activeSocket?.close()
            } catch (_: Exception) {
            }
            val thread = worker
            thread?.interrupt()
            // Thread.join(0) waits forever; non-positive means do not block.
            if (thread != null && thread !== Thread.currentThread() && joinTimeoutMs > 0) {
                try {
                    thread.join(joinTimeoutMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val terminated = thread?.isAlive != true
            if (terminated) {
                worker = null
                synchronized(socketLock) {
                    socket = null
                }
                out = null
            }
            return terminated
        }
    }

    internal fun workerAlive(): Boolean = worker?.isAlive == true

    private fun runSession() {
        val sock = socketFactory()
        val accepted = synchronized(socketLock) {
            if (started.get()) {
                socket = sock
                true
            } else {
                false
            }
        }
        if (!accepted) {
            try {
                sock.close()
            } catch (_: Exception) {
            }
            return
        }
        try {
            sock.soTimeout = 45_000
            sock.connect(
                InetSocketAddress(CloudBridgeConfig.MQTT_HOST, CloudBridgeConfig.MQTT_PORT),
                10_000,
            )
            if (!started.get()) return
            val input = DataInputStream(sock.getInputStream())
            val output = DataOutputStream(sock.getOutputStream())
            out = output
            val clientId = "la-${CloudBridgeConfig.DEVICE_ID}-${System.currentTimeMillis() % 100000}"
            writePacket(output, 0x10, connectPayload(clientId))
            val connack = readPacket(input)
            if (connack.type != 0x20 || connack.payload.size < 2 || connack.payload[1] != 0.toByte()) {
                throw IllegalStateException("MQTT CONNACK failed")
            }
            val topic = CloudBridgeConfig.commandsTopic()
            writePacket(output, 0x82, subscribePayload(1, topic))
            val suback = readPacket(input)
            if (suback.type != 0x90) {
                throw IllegalStateException("MQTT SUBACK missing")
            }
            Log.i(TAG, "mqtt subscribed $topic")
            CloudBridgeStatusHub.update { it.copy(mqttConnected = true) }
            while (started.get() && !sock.isClosed) {
                val packet = try {
                    readPacket(input)
                } catch (_: java.net.SocketTimeoutException) {
                    writePacket(output, 0xC0, ByteArray(0))
                    continue
                }
                when (packet.type and 0xF0) {
                    0x30 -> {
                        val (msgTopic, payload, packetId, qos) = parsePublish(packet.type, packet.payload)
                        if (msgTopic == topic) {
                            onCommand(payload)
                        }
                        if (qos == 1 && packetId != null) {
                            writePacket(
                                output,
                                0x40,
                                byteArrayOf((packetId shr 8).toByte(), (packetId and 0xff).toByte()),
                            )
                        }
                    }
                    0xD0 -> Unit // PINGRESP
                    else -> Unit
                }
            }
        } finally {
            CloudBridgeStatusHub.update { it.copy(mqttConnected = false) }
            try {
                sock.close()
            } catch (_: Exception) {
            }
            synchronized(socketLock) {
                if (socket === sock) {
                    socket = null
                    out = null
                }
            }
        }
    }

    private data class MqttPacket(val type: Int, val payload: ByteArray)

    private data class PublishParts(
        val topic: String,
        val payload: String,
        val packetId: Int?,
        val qos: Int,
    )

    private fun connectPayload(clientId: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val data = DataOutputStream(buf)
        writeMqttString(data, "MQTT")
        data.writeByte(4) // protocol level 3.1.1
        data.writeByte(0x02) // clean session
        data.writeShort(30) // keep alive
        writeMqttString(data, clientId)
        return buf.toByteArray()
    }

    private fun subscribePayload(packetId: Int, topic: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val data = DataOutputStream(buf)
        data.writeShort(packetId)
        writeMqttString(data, topic)
        data.writeByte(1) // QoS 1
        return buf.toByteArray()
    }

    private fun writeMqttString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun writePacket(out: DataOutputStream, type: Int, payload: ByteArray) {
        synchronized(out) {
            out.writeByte(type)
            writeRemainingLength(out, payload.size)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    private fun writeRemainingLength(out: DataOutputStream, length: Int) {
        var x = length
        do {
            var encoded = x % 128
            x /= 128
            if (x > 0) encoded = encoded or 0x80
            out.writeByte(encoded)
        } while (x > 0)
    }

    private fun readPacket(input: DataInputStream): MqttPacket {
        val type = input.readUnsignedByte()
        var multiplier = 1
        var length = 0
        while (true) {
            val encoded = input.readUnsignedByte()
            length += (encoded and 0x7f) * multiplier
            if ((encoded and 0x80) == 0) break
            multiplier *= 128
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return MqttPacket(type, payload)
    }

    private fun parsePublish(type: Int, payload: ByteArray): PublishParts {
        val qos = (type shr 1) and 0x03
        val input = DataInputStream(payload.inputStream())
        val topicLen = input.readUnsignedShort()
        val topicBytes = ByteArray(topicLen)
        input.readFully(topicBytes)
        val topic = String(topicBytes, StandardCharsets.UTF_8)
        val packetId = if (qos > 0) input.readUnsignedShort() else null
        val remaining = payload.size - topicLen - 2 - (if (qos > 0) 2 else 0)
        val body = ByteArray(remaining)
        input.readFully(body)
        return PublishParts(topic, String(body, StandardCharsets.UTF_8), packetId, qos)
    }

    companion object {
        private const val TAG = "CloudMqtt"
    }
}

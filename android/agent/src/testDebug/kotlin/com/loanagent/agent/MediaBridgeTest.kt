package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaBridgeTest {
    @Test
    fun downloadRetriesThenSucceedsAndClearsLastError() {
        val context = RuntimeEnvironment.getApplication()
        MediaBridge.clearLastError()
        val attempts = AtomicInteger()
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(),
        )
        val result = MediaBridge.preparePublishParams(
            context,
            mapOf(
                "title" to "T",
                "body" to "B",
                "media_urls" to listOf(mapOf("url" to "http://example.test/a.jpg", "filename" to "a.jpg")),
            ),
        ) {
            val n = attempts.incrementAndGet()
            object : HttpURLConnection(java.net.URL(it)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = if (n == 1) 503 else 200
                override fun getInputStream() = ByteArrayInputStream(jpeg)
            }
        }
        assertNotNull(result)
        assertEquals(true, result!!["media_prepared"])
        assertTrue(attempts.get() >= 2)
        assertNull(MediaBridge.lastErrorCode())
    }

    @Test
    fun downloadHttpFailureSetsMediaDownloadFailed() {
        val context = RuntimeEnvironment.getApplication()
        MediaBridge.clearLastError()
        val result = MediaBridge.preparePublishParams(
            context,
            mapOf(
                "title" to "T",
                "body" to "B",
                "media_urls" to listOf(mapOf("url" to "http://example.test/missing.jpg")),
            ),
        ) {
            object : HttpURLConnection(java.net.URL(it)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = 404
                override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
            }
        }
        assertNull(result)
        assertEquals("MEDIA_DOWNLOAD_FAILED", MediaBridge.lastErrorCode())
    }

    @Test
    fun missingMediaUrlsIsNoOp() {
        val context = RuntimeEnvironment.getApplication()
        val params = mapOf<String, Any?>("title" to "T", "body" to "B")
        val result = MediaBridge.preparePublishParams(context, params)
        assertEquals(params, result)
        assertFalse(result!!.containsKey("media_prepared"))
    }

    @Test
    fun emptyMediaUrlsIsNoOp() {
        val context = RuntimeEnvironment.getApplication()
        val params = mapOf<String, Any?>(
            "title" to "T",
            "body" to "B",
            "media_urls" to emptyList<Any>(),
        )
        val result = MediaBridge.preparePublishParams(context, params)
        assertEquals(params, result)
        assertFalse(result!!.containsKey("media_prepared"))
    }

    @Test
    fun mergePreparedKeepsTitleBodyAndSetsFlag() {
        val params = mapOf<String, Any?>(
            "title" to "T",
            "body" to "B",
            "media_urls" to listOf(mapOf("url" to "http://x")),
        )
        val merged = MediaBridge.mergePrepared(params)
        assertEquals("T", merged["title"])
        assertEquals("B", merged["body"])
        assertEquals(true, merged["media_prepared"])
        assertTrue(merged.containsKey("media_urls"))
    }

    @Test
    fun interruptedMediaPreparationStopsBeforeNetworkOrMediaStoreSideEffects() {
        val context = RuntimeEnvironment.getApplication()
        val connections = AtomicInteger()
        val result = AtomicReference<Map<String, Any?>?>()
        val worker = Thread {
            Thread.currentThread().interrupt()
            result.set(
                MediaBridge.preparePublishParams(
                    context,
                    mapOf(
                        "title" to "T",
                        "body" to "B",
                        "media_urls" to listOf(mapOf("url" to "https://example.test/image.jpg")),
                    ),
                ) {
                    connections.incrementAndGet()
                    throw AssertionError("network must not start after cancellation")
                },
            )
        }

        worker.start()
        worker.join()

        assertEquals(null, result.get())
        assertEquals(0, connections.get())
    }

    @Test
    fun dispatcherFailsMediaMissingWithoutRunningEngine() {
        val engine = PlaybookEngine(
            runtime = PlaybookEngineTest.FakePlaybookRuntime(
                startHint = PageHint.EDITOR,
                clickOk = true,
                setTextOk = true,
            ),
            registry = DefaultPlaybookRegistry.create(),
            ledger = MemoryEffectLedger(),
        )
        val reports = mutableListOf<String>()
        val dispatcher = TaskCommandDispatcher(
            engine = engine,
            reporter = TaskEventSink { taskId, status, errorCode, _ ->
                reports += "$taskId:$status:$errorCode"
                true
            },
            preparePublishParams = { null },
        )
        val result = dispatcher.handleMqttPayload(
            """{"task_id":"tm1","playbook":"publish_note@1.0","effect_class":"non_idempotent","params":{"title":"T","body":"B","media_urls":[{"url":"http://x"}]}}""",
        )
        assertEquals("failed", result!!.status)
        assertEquals("MEDIA_MISSING", result.errorCode)
        assertFalse(result.success)
        assertEquals(
            listOf("tm1:executing:null", "tm1:failed:MEDIA_MISSING"),
            reports,
        )
    }

    @Test
    fun dispatcherSkipsPrepForNonPublishPlaybooks() {
        var prepCalls = 0
        val engine = PlaybookEngine(
            runtime = PlaybookEngineTest.FakePlaybookRuntime(startHint = PageHint.HOME),
            registry = DefaultPlaybookRegistry.create(),
            ledger = MemoryEffectLedger(),
        )
        TaskCommandDispatcher(
            engine = engine,
            reporter = TaskEventSink { _, _, _, _ -> true },
            preparePublishParams = {
                prepCalls += 1
                it
            },
        ).handleMqttPayload(
            """{"task_id":"tm2","playbook":"ensure_app_ready@1.0"}""",
        )
        assertEquals(0, prepCalls)
    }

    @Test
    fun dispatcherPassesPreparedParamsThrough() {
        val engine = PlaybookEngine(
            runtime = PlaybookEngineTest.FakePlaybookRuntime(
                startHint = PageHint.EDITOR,
                clickOk = true,
                setTextOk = true,
            ),
            registry = DefaultPlaybookRegistry.create(),
            ledger = MemoryEffectLedger(),
        )
        val result = TaskCommandDispatcher(
            engine = engine,
            reporter = TaskEventSink { _, _, _, _ -> true },
            preparePublishParams = { MediaBridge.mergePrepared(it) },
        ).handleMqttPayload(
            """{"task_id":"tm3","playbook":"publish_note@1.0","effect_class":"non_idempotent","params":{"title":"T","body":"B","start_in_editor":true}}""",
        )
        assertTrue(result!!.success)
    }
}

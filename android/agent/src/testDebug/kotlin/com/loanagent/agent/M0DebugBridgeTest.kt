package com.loanagent.agent

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M0DebugBridgeTest {
    @Test
    @Suppress("DEPRECATION")
    fun debugManifestExportsDumpProtectedReceiver() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, M0DebugCommandReceiver::class.java),
            PackageManager.GET_META_DATA,
        )

        assertTrue(info.exported)
        assertEquals(Manifest.permission.DUMP, info.permission)
    }

    @Test
    fun clickRequiresExplicitConfirmationBeforeControllerInvocation() {
        val controller = DebugFakeController()
        val writer = RecordingDebugResultWriter()
        val bridge = M0DebugBridge(
            controllerProvider = { controller },
            writer = writer,
            scheduler = ManualDebugTimeoutScheduler(),
        )

        bridge.execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "CLICK")
                .putExtra(M0DebugCommandReceiver.EXTRA_SELECTOR, "text=普通按钮;clickable=true"),
        ) {}

        assertEquals(0, controller.actionCalls)
        assertTrue(writer.singleResult().contains("\"status\":\"CONFIRMATION_REQUIRED\""))
    }

    @Test
    fun setTextRejectsWhenThereIsNoCurrentTargetLease() {
        val controller = DebugFakeController(lease = null)
        val writer = RecordingDebugResultWriter()
        val bridge = M0DebugBridge(
            controllerProvider = { controller },
            writer = writer,
            scheduler = ManualDebugTimeoutScheduler(),
        )

        bridge.execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "SET_TEXT")
                .putExtra(M0DebugCommandReceiver.EXTRA_SELECTOR, "contentDescription=fixture text input")
                .putExtra(M0DebugCommandReceiver.EXTRA_TEXT, "safe test")
                .putExtra(M0DebugCommandReceiver.EXTRA_CONFIRMED, true),
        ) {}

        assertEquals(0, controller.actionCalls)
        assertTrue(writer.singleResult().contains("\"status\":\"NO_TARGET_LEASE\""))
    }

    @Test
    fun observePersistsOnlyExistingRedactedSnapshotEncoding() {
        val secret = "私信正文 13800138000 验证码 123456"
        val controller = DebugFakeController(
            snapshot = UiSnapshot(
                packageName = AllowedPackagePolicy.FIXTURE,
                className = "FixtureActivity",
                pageHint = PageHint.INBOX,
                keyElements = listOf(secret),
                nodes = listOf(UiNode(text = secret)),
                truncated = false,
            ),
        )
        val writer = RecordingDebugResultWriter()
        val bridge = M0DebugBridge(
            controllerProvider = { controller },
            writer = writer,
            scheduler = ManualDebugTimeoutScheduler(),
        )

        bridge.execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "OBSERVE"),
        ) {}

        val result = writer.singleResult()
        assertFalse(result.contains(secret))
        assertFalse(result.contains("13800138000"))
        assertFalse(result.contains("123456"))
        assertTrue(result.contains("\"snapshot\":${SnapshotJson.encode(requireNotNull(controller.snapshot))}"))
    }

    @Test
    fun extractCommentsPersistsRedactedItemsWithoutRawSnapshotTree() {
        val controller = DebugFakeController(
            snapshot = UiSnapshot(
                packageName = AllowedPackagePolicy.FIXTURE,
                className = "FixtureActivity",
                pageHint = PageHint.COMMENTS,
                keyElements = listOf("评论", "说点什么"),
                nodes = listOf(
                    UiNode(text = "用户甲", clickable = true),
                    UiNode(text = "想咨询 13800138000", bounds = UiBounds(0, 10, 100, 50)),
                    UiNode(text = "用户乙", clickable = true),
                    UiNode(text = "同问"),
                ),
                truncated = false,
            ),
        )
        val writer = RecordingDebugResultWriter()
        val bridge = M0DebugBridge(
            controllerProvider = { controller },
            writer = writer,
            scheduler = ManualDebugTimeoutScheduler(),
        )

        bridge.execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "EXTRACT_COMMENTS"),
        ) {}

        val result = JSONObject(writer.singleResult())
        assertExtractionEnvelope(result, "EXTRACT_COMMENTS", PageHint.COMMENTS, 2)
        val first = result.getJSONArray("items").getJSONObject(0)
        assertEquals("用户甲", first.getString("author_summary"))
        assertEquals("想咨询 138****8000", first.getString("body_summary"))
        assertEquals("index=1;center=50,30", first.getString("locator_hint"))
        assertFalse(result.toString().contains("13800138000"))
        assertFalse(result.has("snapshot"))
    }

    @Test
    fun extractInboxPersistsRedactedThreadRowsWithoutRawSnapshotTree() {
        val controller = DebugFakeController(
            snapshot = UiSnapshot(
                packageName = AllowedPackagePolicy.FIXTURE,
                className = "FixtureActivity",
                pageHint = PageHint.INBOX,
                keyElements = listOf("消息", "私信", "通知"),
                nodes = listOf(
                    UiNode(text = "小红书用户A", clickable = true, bounds = UiBounds(0, 0, 200, 60)),
                    UiNode(text = "[未读] 请联系 13800138000"),
                    UiNode(text = "小红书用户B", clickable = true),
                    UiNode(text = "昨天已读内容"),
                ),
                truncated = false,
            ),
        )
        val writer = RecordingDebugResultWriter()
        val bridge = M0DebugBridge(
            controllerProvider = { controller },
            writer = writer,
            scheduler = ManualDebugTimeoutScheduler(),
        )

        bridge.execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "EXTRACT_INBOX"),
        ) {}

        val result = JSONObject(writer.singleResult())
        assertExtractionEnvelope(result, "EXTRACT_INBOX", PageHint.INBOX, 2)
        val first = result.getJSONArray("items").getJSONObject(0)
        assertEquals("小红书用户A", first.getString("title_summary"))
        assertEquals("[未读] 请联系 138****8000", first.getString("preview_summary"))
        assertTrue(first.getBoolean("unread_hint"))
        assertEquals("index=0;center=100,30", first.getString("locator_hint"))
        assertFalse(result.toString().contains("13800138000"))
        assertFalse(result.has("snapshot"))
    }

    @Test
    fun extractThreadPersistsRedactedMessagesWithoutRawSnapshotTree() {
        val controller = DebugFakeController(
            snapshot = UiSnapshot(
                packageName = AllowedPackagePolicy.FIXTURE,
                className = "FixtureActivity",
                pageHint = PageHint.INBOX,
                keyElements = listOf("私信"),
                nodes = listOf(
                    UiNode(text = "对方"),
                    UiNode(text = "电话 13800138000"),
                    UiNode(text = "我"),
                    UiNode(text = "请发湖北公积金基数"),
                ),
                truncated = false,
            ),
        )
        val writer = RecordingDebugResultWriter()
        val bridge = M0DebugBridge(
            controllerProvider = { controller },
            writer = writer,
            scheduler = ManualDebugTimeoutScheduler(),
        )

        bridge.execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "EXTRACT_THREAD"),
        ) {}

        val result = JSONObject(writer.singleResult())
        assertExtractionEnvelope(result, "EXTRACT_THREAD", PageHint.INBOX, 2)
        val first = result.getJSONArray("items").getJSONObject(0)
        assertEquals("对方", first.getString("sender_summary"))
        assertEquals("电话 138****8000", first.getString("body_summary"))
        assertFalse(result.toString().contains("13800138000"))
        assertFalse(result.has("snapshot"))
    }

    @Test
    fun timeoutAndLateCallbackWriteAndFinishExactlyOnce() {
        val scheduler = ManualDebugTimeoutScheduler()
        val controller = DebugFakeController(completeWaitImmediately = false)
        val writer = RecordingDebugResultWriter()
        val finishes = AtomicInteger()
        val bridge = M0DebugBridge(
            controllerProvider = { controller },
            writer = writer,
            scheduler = scheduler,
        )

        bridge.execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "WAIT")
                .putExtra(M0DebugCommandReceiver.EXTRA_CONDITION, "appears")
                .putExtra(M0DebugCommandReceiver.EXTRA_SELECTOR, "text=never"),
        ) { finishes.incrementAndGet() }
        scheduler.fire()
        controller.completePendingWait()

        assertEquals(1, writer.results.size)
        assertEquals(1, finishes.get())
        assertTrue(writer.singleResult().contains("\"status\":\"BRIDGE_TIMEOUT\""))
        assertEquals(1, controller.cancelCalls)
    }

    @Test
    fun unsupportedCommandsAndFinalPublishSelectorsAreBlocked() {
        val controller = DebugFakeController()
        val unsupportedWriter = RecordingDebugResultWriter()
        M0DebugBridge(
            { controller },
            unsupportedWriter,
            ManualDebugTimeoutScheduler(),
        ).execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "BACK"),
        ) {}
        assertTrue(unsupportedWriter.singleResult().contains("\"status\":\"UNSUPPORTED_COMMAND\""))

        val publishWriter = RecordingDebugResultWriter()
        M0DebugBridge(
            { controller },
            publishWriter,
            ManualDebugTimeoutScheduler(),
        ).execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "CLICK")
                .putExtra(M0DebugCommandReceiver.EXTRA_SELECTOR, "text=发布;clickable=true")
                .putExtra(M0DebugCommandReceiver.EXTRA_CONFIRMED, true),
        ) {}
        assertTrue(publishWriter.singleResult().contains("\"status\":\"FINAL_ACTION_UNSUPPORTED\""))
        assertEquals(0, controller.actionCalls)
    }

    @Test
    fun swipeRequiresConfirmationAndForwardsSwipeSpec() {
        val controller = DebugFakeController()
        val writer = RecordingDebugResultWriter()
        M0DebugBridge(
            { controller },
            writer,
            ManualDebugTimeoutScheduler(),
        ).execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "SWIPE")
                .putExtra(M0DebugCommandReceiver.EXTRA_START_X, 100)
                .putExtra(M0DebugCommandReceiver.EXTRA_START_Y, 800)
                .putExtra(M0DebugCommandReceiver.EXTRA_END_X, 100)
                .putExtra(M0DebugCommandReceiver.EXTRA_END_Y, 200)
                .putExtra(M0DebugCommandReceiver.EXTRA_DURATION_MS, 500)
                .putExtra(M0DebugCommandReceiver.EXTRA_CONFIRMED, true),
        ) {}

        assertEquals(1, controller.actionCalls)
        assertEquals(SwipeSpec(100, 800, 100, 200, 500), controller.lastSwipe)
        assertTrue(writer.singleResult().contains("\"status\":\"SUCCESS\""))
    }

    @Test
    fun clearCacheUsesInjectedClearerWithoutControllerActions() {
        val controller = DebugFakeController()
        val writer = RecordingDebugResultWriter()
        var cleared = 0
        M0DebugBridge(
            { controller },
            writer,
            ManualDebugTimeoutScheduler(),
            cacheClearer = {
                cleared = 3
                3
            },
        ).execute(
            Intent(M0DebugCommandReceiver.ACTION)
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "CLEAR_CACHE"),
        ) {}

        assertEquals(3, cleared)
        assertEquals(0, controller.actionCalls)
        assertTrue(writer.singleResult().contains("\"status\":\"SUCCESS\""))
        assertTrue(writer.singleResult().contains("\"cleared\":3"))
    }

    @Test
    fun atomicWriterLeavesOnlyCompletePrivateResult() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "debug-writer-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val result = File(directory, M0DebugCommandReceiver.RESULT_FILE_NAME)
        val writer = AtomicDebugResultWriter(result)

        writer.write("""{"status":"FIRST"}""")
        writer.write("""{"status":"SECOND"}""")

        assertEquals("""{"status":"SECOND"}""", result.readText())
        assertFalse(File(directory, "${M0DebugCommandReceiver.RESULT_FILE_NAME}.new").exists())
        assertFalse(File(directory, "${M0DebugCommandReceiver.RESULT_FILE_NAME}.bak").exists())
    }

    @Test
    fun receiverRejectsImplicitOrWrongActionAtParserBoundary() {
        val writer = RecordingDebugResultWriter()
        val bridge = M0DebugBridge(
            controllerProvider = { DebugFakeController() },
            writer = writer,
            scheduler = ManualDebugTimeoutScheduler(),
        )

        bridge.execute(
            Intent("com.example.WRONG")
                .putExtra(M0DebugCommandReceiver.EXTRA_COMMAND, "OBSERVE"),
        ) {}

        assertTrue(writer.singleResult().contains("\"status\":\"INVALID_ACTION\""))
    }
}

private class RecordingDebugResultWriter : DebugResultWriter {
    val results = mutableListOf<String>()

    override fun write(json: String) {
        results += json
    }

    fun singleResult(): String {
        assertEquals(1, results.size)
        return results.single()
    }
}

private fun assertExtractionEnvelope(
    result: JSONObject,
    command: String,
    pageHint: PageHint,
    count: Int,
) {
    assertEquals("m0-debug-1", result.getString("schema_version"))
    assertEquals(command, result.getString("command"))
    assertEquals("SUCCESS", result.getString("status"))
    assertEquals(pageHint.name, result.getString("page_hint"))
    assertEquals(count, result.getInt("count"))
    assertEquals(count, result.getJSONArray("items").length())
}

private class ManualDebugTimeoutScheduler : DebugTimeoutScheduler {
    private var task: (() -> Unit)? = null

    override fun schedule(delayMs: Long, task: () -> Unit): RequestHandle {
        this.task = task
        return RequestHandle { this.task = null }
    }

    fun fire() {
        task?.invoke()
        task = null
    }
}

private class DebugFakeController(
    private val lease: TargetLease? = TargetLease(AllowedPackagePolicy.FIXTURE, 7),
    val snapshot: UiSnapshot? = UiSnapshot(
        AllowedPackagePolicy.FIXTURE,
        "FixtureActivity",
        PageHint.HOME,
        emptyList(),
        emptyList(),
        false,
    ),
    private val completeWaitImmediately: Boolean = true,
) : M0DiagnosticController {
    var actionCalls = 0
    var cancelCalls = 0
    var lastSwipe: SwipeSpec? = null
    private var pendingWait: ((WaitResult) -> Unit)? = null

    override fun currentLease(): TargetLease? = lease

    override fun observe(expectedLease: TargetLease?): UiSnapshot? = snapshot

    override fun executeAction(
        request: ActionRequest,
        callback: (ActionResult) -> Unit,
    ): RequestHandle {
        actionCalls += 1
        lastSwipe = request.swipe
        val path = if (request.action == M0Action.SWIPE) ActionPath.GESTURE else ActionPath.NODE_ACTION
        val stage = if (request.action == M0Action.SWIPE) {
            ExecutionStage.GESTURE_COMPLETED
        } else {
            ExecutionStage.ACTION_ACCEPTED
        }
        callback(
            ActionResult(
                ActionStatus.SUCCESS,
                request.action,
                path,
                false,
                if (request.action == M0Action.SWIPE) "gesture_completed" else "action_accepted",
                stage,
            ),
        )
        return CompletedRequestHandle
    }

    override fun waitForCondition(
        expectedLease: TargetLease,
        condition: WaitCondition,
        timeoutMs: Long,
        callback: (WaitResult) -> Unit,
    ): RequestHandle {
        pendingWait = callback
        if (completeWaitImmediately) completePendingWait()
        return RequestHandle {
            cancelCalls += 1
        }
    }

    override fun runVisualDiagnostic(
        request: VisualDiagnosticRequest,
        callback: (VisualDiagnosticResult) -> Unit,
    ): RequestHandle {
        callback(VisualDiagnosticResult(null, "memory only", "SUCCESS"))
        return CompletedRequestHandle
    }

    fun completePendingWait() {
        pendingWait?.invoke(WaitResult(WaitStatus.MET, 1, 1))
        pendingWait = null
    }
}

package com.loanagent.agent

import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M0ExecutionIntegrationTest {
    @Test
    fun pollingWaitsForSelectorToAppearWithoutUsingCallerTimeAsCondition() {
        val time = FakeTime()
        val snapshots = ArrayDeque(
            listOf(
                snapshot(nodes = emptyList()),
                snapshot(nodes = emptyList()),
                snapshot(nodes = listOf(UiNode(text = "发布"))),
            ),
        )
        val waiter = PollingConditionWaiter(time, time, pollIntervalMs = 10)

        val result = waiter.await(
            WaitCondition.SelectorAppears(Selector(text = "发布")),
            timeoutMs = 50,
            expectedLease = XHS_LEASE,
        ) {
            ConditionProbe(true, XHS_LEASE, snapshots.removeFirstOrNull() ?: snapshot())
        }

        assertEquals(WaitStatus.MET, result.status)
        assertEquals(3, result.checks)
        assertEquals(20, time.nowMillis())
    }

    @Test
    fun pollingWaitsForSelectorToDisappearAndPageHintToChange() {
        val time = FakeTime()
        val waiter = PollingConditionWaiter(time, time, pollIntervalMs = 5)
        var disappearChecks = 0
        val disappeared = waiter.await(
            WaitCondition.SelectorDisappears(Selector(text = "加载中")),
            timeoutMs = 30,
            expectedLease = XHS_LEASE,
        ) {
            disappearChecks += 1
            ConditionProbe(
                true,
                XHS_LEASE,
                snapshot(nodes = if (disappearChecks == 1) listOf(UiNode(text = "加载中")) else emptyList()),
            )
        }
        var pageChecks = 0
        val changed = waiter.await(
            WaitCondition.PageHintChanges(PageHint.HOME),
            timeoutMs = 30,
            expectedLease = XHS_LEASE,
        ) {
            pageChecks += 1
            ConditionProbe(
                true,
                XHS_LEASE,
                snapshot(pageHint = if (pageChecks == 1) PageHint.HOME else PageHint.NOTE_DETAIL),
            )
        }

        assertEquals(WaitStatus.MET, disappeared.status)
        assertEquals(WaitStatus.MET, changed.status)
    }

    @Test
    fun pollingDistinguishesTimeoutPackageSwitchAndStoppedService() {
        val timeoutTime = FakeTime()
        val timeout = PollingConditionWaiter(timeoutTime, timeoutTime, 10).await(
            WaitCondition.SelectorAppears(Selector(text = "never")),
            timeoutMs = 20,
            expectedLease = XHS_LEASE,
        ) { ConditionProbe(true, XHS_LEASE, snapshot()) }
        val switched = PollingConditionWaiter(FakeTime(), FakeTime(), 10).await(
            WaitCondition.SelectorAppears(Selector(text = "never")),
            timeoutMs = 20,
            expectedLease = XHS_LEASE,
        ) {
            ConditionProbe(
                true,
                TargetLease("com.loanagent.fixture", 1),
                snapshot("com.loanagent.fixture"),
            )
        }
        val stopped = PollingConditionWaiter(FakeTime(), FakeTime(), 10).await(
            WaitCondition.SelectorAppears(Selector(text = "never")),
            timeoutMs = 20,
            expectedLease = XHS_LEASE,
        ) { ConditionProbe(false, lease = null, snapshot = null) }

        assertEquals(WaitStatus.TIMEOUT, timeout.status)
        assertEquals(WaitStatus.TARGET_LEASE_LOST, switched.status)
        assertEquals(WaitStatus.SERVICE_STOPPED, stopped.status)
    }

    @Test
    fun interruptedBackgroundPollReturnsServiceStopped() {
        val clock = FakeTime()
        val waiter = PollingConditionWaiter(
            clock,
            PollSleeper { throw InterruptedException("service destroyed") },
            10,
        )

        val result = waiter.await(
            WaitCondition.SelectorAppears(Selector(text = "never")),
            timeoutMs = 100,
            expectedLease = XHS_LEASE,
        ) { ConditionProbe(true, XHS_LEASE, snapshot()) }

        assertEquals(WaitStatus.SERVICE_STOPPED, result.status)
    }

    @Test
    fun gestureResultUsesCompletionCallbackNotDispatchAcceptance() {
        val completedPort = FakeAutomationPort(gestureCompletion = GestureCompletion.COMPLETED)
        val cancelledPort = FakeAutomationPort(gestureCompletion = GestureCompletion.CANCELLED)

        val completed = executeSwipe(completedPort)
        val cancelled = executeSwipe(cancelledPort)

        assertEquals(ExecutionStage.GESTURE_COMPLETED, completed.stage)
        assertEquals(ActionStatus.SUCCESS, completed.status)
        assertEquals(ExecutionStage.GESTURE_CANCELLED, cancelled.stage)
        assertEquals(ActionStatus.FAILED, cancelled.status)
        assertTrue(completedPort.dispatchAccepted)
        assertTrue(cancelledPort.dispatchAccepted)
    }

    @Test
    fun gestureCallbackTimeoutAndPackageSwitchAreStructuredFailures() {
        val noCallback = executeSwipe(FakeAutomationPort(gestureCompletion = null), gestureTimeoutMs = 5)
        val switched = executeSwipe(
            FakeAutomationPort(
                gestureCompletion = GestureCompletion.COMPLETED,
                packageAfterAction = "com.loanagent.fixture",
            ),
        )

        assertEquals(ExecutionStage.GESTURE_CALLBACK_TIMEOUT, noCallback.stage)
        assertEquals(ExecutionStage.TARGET_LEASE_LOST, switched.stage)
    }

    @Test
    fun acceptedNodeClickSurvivesSamePackageWindowGenerationChange() {
        val result = execute(
            FakeAutomationPort(generationAfterAction = 1),
            ActionRequest(
                M0Action.CLICK,
                XHS_LEASE,
                selector = Selector(viewId = "input"),
            ),
        )

        assertEquals(ActionStatus.SUCCESS, result.status)
        assertEquals(ExecutionStage.ACTION_ACCEPTED, result.stage)
        assertEquals(ActionPath.NODE_ACTION, result.path)
    }

    @Test
    fun truncatedSnapshotDefersStableSelectorValidationToAutomationPort() {
        val port = FakeAutomationPort(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(
                        nodes = listOf(UiNode(contentDescription = "发现", clickable = true)),
                        truncated = true,
                    ),
                ),
            ),
        )

        val result = execute(
            port,
            ActionRequest(
                M0Action.CLICK,
                XHS_LEASE,
                selector = Selector(contentDescription = "发现", clickable = true),
            ),
        )

        assertEquals(ActionStatus.SUCCESS, result.status)
        assertEquals(ExecutionStage.ACTION_ACCEPTED, result.stage)
        assertEquals(1, port.clickCalls)
    }

    @Test
    fun postconditionFollowsAcceptedActionIntoNewSamePackageGeneration() {
        val result = execute(
            FakeAutomationPort(
                generationAfterAction = 1,
                snapshots = ArrayDeque(
                    listOf(
                        snapshot(
                            pageHint = PageHint.HOME,
                            nodes = listOf(UiNode(viewId = "input")),
                        ),
                        snapshot(
                            pageHint = PageHint.NOTE_DETAIL,
                            nodes = listOf(UiNode(viewId = "input")),
                        ),
                    ),
                ),
            ),
            ActionRequest(
                M0Action.CLICK,
                XHS_LEASE,
                selector = Selector(viewId = "input"),
                postcondition = WaitCondition.PageHintChanges(PageHint.HOME),
            ),
        )

        assertEquals(ActionStatus.SUCCESS, result.status)
        assertEquals(ExecutionStage.POSTCONDITION_MET, result.stage)
    }

    @Test
    fun failedNodeClickUsesCompletedBoundsGestureAndRecordsFallback() {
        val port = FakeAutomationPort(
            clickAccepted = false,
            clickBounds = UiBounds(10, 20, 30, 60),
            snapshots = ArrayDeque(
                listOf(snapshot(nodes = listOf(UiNode(viewId = "button")))),
            ),
        )

        val result = execute(
            port,
            ActionRequest(M0Action.CLICK, XHS_LEASE, selector = Selector(viewId = "button")),
        )

        assertEquals(ActionStatus.SUCCESS, result.status)
        assertEquals(ExecutionStage.GESTURE_COMPLETED, result.stage)
        assertEquals(ActionPath.BOUNDS_GESTURE_FALLBACK, result.path)
        assertTrue(result.fallbackUsed)
        assertEquals(SwipeSpec(20, 40, 20, 40, 80), port.lastGesture)
    }

    @Test
    fun withoutTrustedKioskCoordinateGesturesFollowDebugPolicy() {
        val swipePort = FakeAutomationPort()
        val swipe = executeSwipe(swipePort, trustedKiosk = false)
        val clickPort = FakeAutomationPort(
            clickAccepted = false,
            clickBounds = UiBounds(10, 20, 30, 60),
            snapshots = ArrayDeque(
                listOf(snapshot(nodes = listOf(UiNode(viewId = "button")))),
            ),
        )
        val click = execute(
            clickPort,
            ActionRequest(M0Action.CLICK, XHS_LEASE, selector = Selector(viewId = "button")),
            trustedKiosk = false,
        )

        if (BuildConfig.DEBUG) {
            assertEquals(ActionStatus.SUCCESS, swipe.status)
            assertEquals(ActionStatus.SUCCESS, click.status)
            assertTrue(swipePort.dispatchAccepted)
            assertTrue(clickPort.dispatchAccepted)
        } else {
            assertEquals(ActionStatus.BLOCKED, swipe.status)
            assertEquals("UNSAFE_GESTURE_BLOCKED", swipe.message)
            assertEquals(ActionStatus.BLOCKED, click.status)
            assertEquals("UNSAFE_GESTURE_BLOCKED", click.message)
            assertFalse(swipePort.dispatchAccepted)
            assertFalse(clickPort.dispatchAccepted)
        }
    }

    @Test
    fun releaseWithoutTrustedKioskBlocksGlobalBackBeforePortCall() {
        val port = FakeAutomationPort()

        val result = execute(
            port,
            ActionRequest(M0Action.BACK, XHS_LEASE),
            trustedKiosk = false,
        )

        assertEquals(ActionStatus.BLOCKED, result.status)
        assertEquals("UNSAFE_GLOBAL_ACTION_BLOCKED", result.message)
        assertEquals(0, port.globalBackCalls)
    }

    @Test
    fun ambiguousActionSelectorIsRejectedWithoutTouchingAnyNode() {
        val port = FakeAutomationPort(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(
                        nodes = listOf(
                            UiNode(viewId = "publish", text = "第一个"),
                            UiNode(viewId = "publish", text = "第二个"),
                        ),
                    ),
                ),
            ),
        )

        val result = execute(
            port,
            ActionRequest(M0Action.CLICK, XHS_LEASE, selector = Selector(viewId = "publish")),
        )

        assertEquals(ActionStatus.AMBIGUOUS, result.status)
        assertEquals(0, port.clickCalls)
    }

    @Test
    fun truncatedSnapshotUsesAutomationPortIndeterminateResultWithoutAcceptingAction() {
        val port = FakeAutomationPort(
            clickAccepted = false,
            clickMatchStatus = SelectorMatchStatus.INDETERMINATE,
            snapshots = ArrayDeque(
                listOf(
                    snapshot(
                        nodes = listOf(UiNode(viewId = "publish")),
                        truncated = true,
                    ),
                ),
            ),
        )

        val result = execute(
            port,
            ActionRequest(M0Action.CLICK, XHS_LEASE, selector = Selector(viewId = "publish")),
        )

        assertEquals(ActionStatus.INDETERMINATE, result.status)
        assertEquals(1, port.clickCalls)
    }

    @Test
    fun truncatedSnapshotWithoutSelectorIsIndeterminateAndSkipsFrameworkQuery() {
        val port = FakeAutomationPort(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(
                        nodes = listOf(UiNode(contentDescription = "发现", clickable = true)),
                        truncated = true,
                    ),
                ),
            ),
        )

        val result = execute(
            port,
            ActionRequest(
                M0Action.CLICK,
                XHS_LEASE,
                selector = Selector(contentDescription = "搜索", clickable = true),
            ),
        )

        assertEquals(ActionStatus.INDETERMINATE, result.status)
        assertEquals(0, port.clickCalls)
    }

    @Test
    fun setTextFailureFallsBackToClipboardEvenWhenImeEnabled() {
        val port = FakeAutomationPort(setTextAccepted = false, imeEnabled = true)
        val text = "clipboard with ime enabled"
        val result = execute(
            port,
            ActionRequest(
                action = M0Action.SET_TEXT,
                expectedLease = XHS_LEASE,
                selector = Selector(viewId = "input"),
                text = text,
            ),
        )

        assertEquals(ActionStatus.SUCCESS, result.status)
        assertEquals(ExecutionStage.ACTION_ACCEPTED, result.stage)
        assertTrue(result.message.contains("input_route=CLIPBOARD"))
        assertTrue(result.message.contains("input_length=${text.length}"))
        assertFalse(result.message.contains(text))
        assertEquals(1, port.clipboardPasteCalls)
        assertFalse(port.silentImeSwitchAttempted)
    }

    @Test
    fun setTextSuccessReportsActionSetTextRouteWithoutPlainText() {
        val port = FakeAutomationPort(setTextAccepted = true)
        val text = "set text diagnostic"
        val result = execute(
            port,
            ActionRequest(
                action = M0Action.SET_TEXT,
                expectedLease = XHS_LEASE,
                selector = Selector(viewId = "input"),
                text = text,
            ),
        )

        assertEquals(ActionStatus.SUCCESS, result.status)
        assertEquals(ExecutionStage.ACTION_ACCEPTED, result.stage)
        assertTrue(result.message.contains("input_route=ACTION_SET_TEXT"))
        assertTrue(result.message.contains("input_length=${text.length}"))
        assertFalse(result.message.contains(text))
        assertEquals(0, port.clipboardPasteCalls)
    }

    @Test
    fun setTextFallsBackToClipboardPasteWhenImeDisabled() {
        val port = FakeAutomationPort(setTextAccepted = false, imeEnabled = false)
        val text = "clipboard diagnostic secret"
        val result = execute(
            port,
            ActionRequest(
                action = M0Action.SET_TEXT,
                expectedLease = XHS_LEASE,
                selector = Selector(viewId = "input"),
                text = text,
            ),
        )

        assertEquals(ActionStatus.SUCCESS, result.status)
        assertEquals(ExecutionStage.ACTION_ACCEPTED, result.stage)
        assertEquals(ActionPath.NODE_ACTION, result.path)
        assertTrue(result.message.contains("input_route=CLIPBOARD"))
        assertTrue(result.message.contains("input_length=${text.length}"))
        assertFalse(result.message.contains(text))
        assertEquals(1, port.clipboardPasteCalls)
        assertEquals(text.length, port.lastClipboardTextLength)
    }

    @Test
    fun nodeActionReportsAcceptedThenPostconditionMetOrTimeout() {
        val metPort = FakeAutomationPort(
            snapshots = ArrayDeque(
                listOf(
                    snapshot(nodes = listOf(UiNode(viewId = "button"))),
                    snapshot(nodes = emptyList()),
                ),
            ),
        )
        val timeoutPort = FakeAutomationPort(
            snapshots = ArrayDeque(
                listOf(snapshot(nodes = listOf(UiNode(viewId = "button")))),
            ),
        )
        val request = ActionRequest(
            action = M0Action.CLICK,
            expectedLease = XHS_LEASE,
            selector = Selector(viewId = "button"),
            timeoutMs = 10,
            postcondition = WaitCondition.SelectorDisappears(Selector(viewId = "button")),
        )

        assertEquals(ExecutionStage.POSTCONDITION_MET, execute(metPort, request).stage)
        assertEquals(ExecutionStage.POSTCONDITION_TIMEOUT, execute(timeoutPort, request).stage)
    }

    @Test
    fun visualDiagnosticCallbackIsForwardedOnlyForOriginalTargetPackage() {
        val port = FakeVisualPort(VisualDiagnosticResult(null, "识别结果", "SUCCESS"))
        val runner = VisualDiagnosticRunner(port)
        var result: VisualDiagnosticResult? = null
        runner.run(VisualDiagnosticRequest(TargetLease(XHS, 1))) { result = it }

        assertEquals("SUCCESS", result?.status)
        assertEquals(1, port.requests)
    }

    @Test
    fun visualDiagnosticDistinguishesPackageSwitchAndServiceStopCallbacks() {
        val switched = VisualDiagnosticRunner(
            FakeVisualPort(VisualDiagnosticResult(null, null, "TARGET_LEASE_LOST")),
        )
        val stopped = VisualDiagnosticRunner(
            FakeVisualPort(VisualDiagnosticResult(null, null, "SERVICE_STOPPED")),
        )
        var switchedResult: VisualDiagnosticResult? = null
        var stoppedResult: VisualDiagnosticResult? = null

        val request = VisualDiagnosticRequest(TargetLease(XHS, 1))
        switched.run(request) { switchedResult = it }
        stopped.run(request) { stoppedResult = it }

        assertEquals("TARGET_LEASE_LOST", switchedResult?.status)
        assertEquals("SERVICE_STOPPED", stoppedResult?.status)
    }

    @Test
    fun coordinatorRunsPollingOnWorkerWithoutBlockingCaller() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callback = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val port = BlockingAutomationPort(entered, release)
        val time = FakeTime()
        val coordinator = M0ExecutionCoordinator(
            port,
            PollingConditionWaiter(time, time, 1),
            executor,
            DirectExecutor,
        )
        var result: ActionResult? = null

        coordinator.execute(
            ActionRequest(M0Action.CLICK, XHS_LEASE, selector = Selector(viewId = "button")),
        ) {
            result = it
            callback.countDown()
        }

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertNull(result)
        release.countDown()
        assertTrue(callback.await(1, TimeUnit.SECONDS))
        assertEquals(ExecutionStage.ACTION_ACCEPTED, result?.stage)
        executor.shutdownNow()
    }

    private fun executeSwipe(
        port: FakeAutomationPort,
        gestureTimeoutMs: Long = 20,
        trustedKiosk: Boolean = true,
    ): ActionResult = execute(
        port,
        ActionRequest(
            action = M0Action.SWIPE,
            expectedLease = XHS_LEASE,
            swipe = SwipeSpec(10, 20, 10, 5, 100),
        ),
        gestureTimeoutMs,
        trustedKiosk,
    )

    private fun execute(
        port: FakeAutomationPort,
        request: ActionRequest,
        gestureTimeoutMs: Long = 20,
        trustedKiosk: Boolean = true,
    ): ActionResult {
        val time = FakeTime()
        val coordinator = M0ExecutionCoordinator(
            port = port,
            waiter = PollingConditionWaiter(time, time, 1),
            worker = DirectExecutor,
            callbackExecutor = DirectExecutor,
            gestureCallbackTimeoutMs = gestureTimeoutMs,
            trustedKiosk = { trustedKiosk },
        )
        var result: ActionResult? = null
        coordinator.execute(request) { result = it }
        return requireNotNull(result)
    }

    private fun snapshot(
        packageName: String = XHS,
        pageHint: PageHint = PageHint.HOME,
        nodes: List<UiNode> = emptyList(),
        truncated: Boolean = false,
    ) = UiSnapshot(packageName, "Activity", pageHint, emptyList(), nodes, truncated)

    private companion object {
        const val XHS = "com.xingin.xhs"
        val XHS_LEASE = TargetLease(XHS, 0)
    }
}

private object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private class FakeTime : MonotonicClock, PollSleeper {
    private var now = 0L

    override fun nowMillis(): Long = now

    override fun sleep(millis: Long) {
        now += millis
    }
}

private class FakeAutomationPort(
    private val gestureCompletion: GestureCompletion? = GestureCompletion.COMPLETED,
    private val packageAfterAction: String = "com.xingin.xhs",
    private val generationAfterAction: Long = 0,
    private val setTextAccepted: Boolean = true,
    private val setTextEditable: Boolean = true,
    private val imeEnabled: Boolean = false,
    private val clickAccepted: Boolean = true,
    private val clickMatchStatus: SelectorMatchStatus = SelectorMatchStatus.UNIQUE,
    private val clickBounds: UiBounds? = null,
    private val snapshots: ArrayDeque<UiSnapshot> = ArrayDeque(
        listOf(
            UiSnapshot(
                "com.xingin.xhs",
                "Activity",
                PageHint.HOME,
                emptyList(),
                listOf(UiNode(viewId = "input", editable = true)),
                false,
            ),
        ),
    ),
) : AutomationPort {
    var dispatchAccepted = false
    var clickCalls = 0
    var globalBackCalls = 0
    var clipboardPasteCalls = 0
    var lastClipboardTextLength: Int? = null
    var silentImeSwitchAttempted = false
    var lastGesture: SwipeSpec? = null
    private var actionRan = false

    private var firstProbe = true

    override fun atomicProbe(): ConditionProbe {
        val packageName = if (actionRan) packageAfterAction else "com.xingin.xhs"
        val generation = when {
            packageName != "com.xingin.xhs" -> 1
            actionRan -> generationAfterAction
            else -> 0
        }
        val lease = TargetLease(packageName, generation)
        val snapshot = if (firstProbe) {
            firstProbe = false
            snapshots.first()
        } else if (snapshots.size > 1) {
            snapshots.removeFirst()
        } else {
            snapshots.first()
        }
        return ConditionProbe(true, lease, snapshot)
    }

    override fun clickNode(expectedLease: TargetLease, selector: Selector): NodeActionAttempt {
        clickCalls += 1
        actionRan = true
        return NodeActionAttempt(
            accepted = clickAccepted,
            fallbackBounds = clickBounds,
            matchStatus = clickMatchStatus,
        )
    }

    override fun setTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt {
        actionRan = true
        return NodeActionAttempt(accepted = setTextAccepted, editable = setTextEditable)
    }

    override fun pasteTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt {
        clipboardPasteCalls += 1
        lastClipboardTextLength = text.length
        actionRan = true
        return NodeActionAttempt(accepted = true, editable = setTextEditable)
    }

    override fun dispatchGesture(
        expectedLease: TargetLease,
        spec: SwipeSpec,
        callback: (GestureCompletion) -> Unit,
    ): Boolean {
        dispatchAccepted = true
        lastGesture = spec
        actionRan = true
        gestureCompletion?.let(callback)
        return true
    }

    override fun globalBack(expectedLease: TargetLease): Boolean {
        globalBackCalls += 1
        actionRan = true
        return true
    }

    override fun imeStatus(): ImeStatus = ImeStatus(imeEnabled, selected = false)
}

private class FakeVisualPort(
    private val result: VisualDiagnosticResult,
) : VisualDiagnosticPort {
    var requests = 0

    override fun request(
        request: VisualDiagnosticRequest,
        callback: (VisualDiagnosticResult) -> Unit,
    ): RequestHandle {
        requests += 1
        callback(result)
        return CompletedRequestHandle
    }
}

private class BlockingAutomationPort(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : AutomationPort {
    override fun atomicProbe(): ConditionProbe {
        entered.countDown()
        release.await(1, TimeUnit.SECONDS)
        return ConditionProbe(
            true,
            TargetLease("com.xingin.xhs", 0),
            UiSnapshot(
                "com.xingin.xhs",
                "Activity",
                PageHint.HOME,
                emptyList(),
                listOf(UiNode(viewId = "button")),
                false,
            ),
        )
    }

    override fun clickNode(
        expectedLease: TargetLease,
        selector: Selector,
    ): NodeActionAttempt = NodeActionAttempt(true)

    override fun setTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt = NodeActionAttempt(true, editable = true)

    override fun pasteTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt = NodeActionAttempt(true, editable = true)

    override fun dispatchGesture(
        expectedLease: TargetLease,
        spec: SwipeSpec,
        callback: (GestureCompletion) -> Unit,
    ): Boolean = false

    override fun globalBack(expectedLease: TargetLease): Boolean = true

    override fun imeStatus(): ImeStatus = ImeStatus(false, false)
}

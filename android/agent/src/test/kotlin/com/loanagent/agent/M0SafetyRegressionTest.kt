package com.loanagent.agent

import android.content.ComponentName
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class M0SafetyRegressionTest {
    @Test
    fun windowGenerationIgnoresNullPackageAndOnlyAdvancesOnPackageChange() {
        val tracker = WindowGenerationTracker()
        val first = tracker.observePackage("com.xingin.xhs")
        assertEquals(first, tracker.observePackage(null))
        assertEquals(first, tracker.observePackage("com.xingin.xhs"))
        val second = tracker.observePackage("com.android.settings")
        assertTrue(second > first)
        assertEquals(second, tracker.observePackage(null))
    }

    @Test
    fun imeStatusMatchesEnabledListAndSafelyParsesSelectedComponent() {
        val expected = ComponentName("com.loanagent.agent", "com.loanagent.agent.M0InputMethodService")
        val reader = ImeStatusResolver(expected)

        assertEquals(
            ImeStatus(enabled = true, selected = true),
            reader.resolve(
                enabledComponents = listOf(expected, ComponentName("other", "Ime")),
                selectedId = expected.flattenToString(),
            ),
        )
        assertEquals(
            ImeStatus(enabled = true, selected = false),
            reader.resolve(listOf(expected), "not/a/valid/component/extra"),
        )
        assertEquals(
            ImeStatus(enabled = false, selected = false),
            reader.resolve(emptyList(), null),
        )
    }

    @Test
    @Config(sdk = [35])
    fun imeStatusUsesSameComponentSafePathOnAndroid15() {
        val expected = ComponentName("com.loanagent.agent", "com.loanagent.agent.M0InputMethodService")

        assertEquals(
            ImeStatus(enabled = true, selected = true),
            ImeStatusResolver(expected).resolve(listOf(expected), expected.flattenToString()),
        )
    }

    @Test
    fun strictSelectorParserRejectsUnknownDuplicateEmptyAndOversizedSelectors() {
        assertThrows(IllegalArgumentException::class.java) {
            StrictSelectorParser.parse("unknown=value")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictSelectorParser.parse("text=a;text=b")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictSelectorParser.parse("clickable=true")
        }
        assertEquals(
            Selector(className = "android.widget.Button"),
            StrictSelectorParser.parse("className=android.widget.Button"),
        )
        assertEquals(
            Selector(className = "android.widget.Button", clickable = true),
            StrictSelectorParser.parse("className=android.widget.Button;clickable=true"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            StrictSelectorParser.parse("text=${"x".repeat(257)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrictSelectorParser.parse("text=a;clickable=yes")
        }

        assertEquals(
            Selector(
                viewId = "com.xingin.xhs:id/title",
                text = "发布",
                contentDescription = "publish",
                className = "android.widget.Button",
                clickable = true,
            ),
            StrictSelectorParser.parse(
                "viewId=com.xingin.xhs:id/title;text=发布;" +
                    "contentDescription=publish;className=android.widget.Button;clickable=true",
            ),
        )
    }

    @Test
    fun targetLeaseDetectsAbaAndSwitchBetweenTwoAllowedPackages() {
        val first = TargetLease("com.xingin.xhs", 7)
        val switched = TargetLease("com.loanagent.fixture", 8)
        val returned = TargetLease("com.xingin.xhs", 9)

        assertFalse(first.matches(switched))
        assertFalse(first.matches(returned))
        assertTrue(first.matches(TargetLease("com.xingin.xhs", 7)))
    }

    @Test
    fun waiterLosesLeaseEvenWhenPackageReturnsToOriginalValue() {
        val time = SafetyFakeTime()
        val waiter = PollingConditionWaiter(time, time, 1)
        val expected = TargetLease("com.xingin.xhs", 1)
        val probes = ArrayDeque(
            listOf(
                ConditionProbe(true, expected, snapshot()),
                ConditionProbe(true, TargetLease("com.loanagent.fixture", 2), snapshot("com.loanagent.fixture")),
                ConditionProbe(true, TargetLease("com.xingin.xhs", 3), snapshot()),
            ),
        )

        val result = waiter.await(
            WaitCondition.SelectorAppears(Selector(text = "never")),
            timeoutMs = 10,
            expectedLease = expected,
        ) { probes.removeFirst() }

        assertEquals(WaitStatus.TARGET_LEASE_LOST, result.status)
    }

    @Test
    fun terminalCallbackRunsExactlyOnce() {
        val count = AtomicInteger()
        val terminal = OnceTerminal<String> { count.incrementAndGet() }

        assertTrue(terminal.complete("first"))
        assertFalse(terminal.complete("second"))
        assertEquals(1, count.get())
    }

    @Test
    fun rejectedWorkerSubmissionCompletesExactlyOnce() {
        val lease = TargetLease("com.xingin.xhs", 1)
        val port = LeaseAutomationPort(lease)
        val coordinator = M0ExecutionCoordinator(
            port = port,
            waiter = PollingConditionWaiter(SafetyFakeTime(), SafetyFakeTime(), 1),
            worker = { throw RejectedExecutionException("destroyed") },
            callbackExecutor = { it.run() },
        )
        val count = AtomicInteger()
        var result: ActionResult? = null

        coordinator.execute(
            ActionRequest(M0Action.BACK, expectedLease = lease),
        ) {
            count.incrementAndGet()
            result = it
        }

        assertEquals(1, count.get())
        assertEquals(ExecutionStage.SERVICE_STOPPED, result?.stage)
    }

    @Test
    fun coordinatorCloseCancelsQueuedRequestExactlyOnce() {
        val lease = TargetLease("com.xingin.xhs", 1)
        val worker = DeferredExecutor()
        val coordinator = M0ExecutionCoordinator(
            port = LeaseAutomationPort(lease),
            waiter = PollingConditionWaiter(SafetyFakeTime(), SafetyFakeTime(), 1),
            worker = worker,
            callbackExecutor = { it.run() },
        )
        val count = AtomicInteger()
        var stage: ExecutionStage? = null
        coordinator.execute(ActionRequest(M0Action.BACK, lease)) {
            count.incrementAndGet()
            stage = it.stage
        }

        coordinator.close()
        worker.runPending()

        assertEquals(1, count.get())
        assertEquals(ExecutionStage.CANCELLED, stage)
    }

    @Test
    fun coordinatorReturnsLeaseLostWhenNodeLookupRacesWindowChange() {
        val original = TargetLease("com.xingin.xhs", 4)
        val port = LeaseRaceAutomationPort(original)
        val coordinator = M0ExecutionCoordinator(
            port = port,
            waiter = PollingConditionWaiter(SafetyFakeTime(), SafetyFakeTime(), 1),
            worker = { it.run() },
            callbackExecutor = { it.run() },
        )
        var result: ActionResult? = null

        coordinator.execute(
            ActionRequest(
                M0Action.CLICK,
                original,
                selector = Selector(text = "发布"),
            ),
        ) { result = it }

        assertEquals(ExecutionStage.TARGET_LEASE_LOST, result?.stage)
    }

    @Test
    fun screenshotDefaultsToMemoryOnlyAndRequiresExplicitSave() {
        val lease = TargetLease("com.xingin.xhs", 1)

        assertFalse(VisualDiagnosticRequest(lease).saveOriginal)
        assertTrue(VisualDiagnosticRequest(lease, saveOriginal = true).saveOriginal)
    }

    @Test
    fun releaseGesturesAndBoundsFallbackRequireTrustedKioskProof() {
        val policy = GestureSafetyPolicy()

        assertFalse(
            policy.allowsCoordinateGesture(
                packageName = "com.xingin.xhs",
                debug = false,
                trustedKiosk = false,
            ),
        )
        assertTrue(
            policy.allowsCoordinateGesture(
                packageName = "com.xingin.xhs",
                debug = true,
                trustedKiosk = false,
            ),
        )
        assertTrue(
            policy.allowsCoordinateGesture(
                packageName = "com.loanagent.fixture",
                debug = true,
                trustedKiosk = false,
            ),
        )
        assertTrue(
            policy.allowsCoordinateGesture(
                packageName = "com.xingin.xhs",
                debug = false,
                trustedKiosk = true,
            ),
        )
    }

    @Test
    fun originalCaptureAllowsOnlyDebugFixtureOrTrustedKiosk() {
        val policy = OriginalCapturePolicy()

        assertFalse(policy.allows("com.xingin.xhs", debug = false, trustedKiosk = false))
        assertTrue(policy.allows("com.xingin.xhs", debug = true, trustedKiosk = false))
        assertFalse(policy.allows("com.loanagent.fixture", debug = false, trustedKiosk = false))
        assertTrue(policy.allows("com.loanagent.fixture", debug = true, trustedKiosk = false))
        assertTrue(policy.allows("com.xingin.xhs", debug = false, trustedKiosk = true))
    }

    @Test
    fun singleFlightGateRejectsBusyWithoutReplacingOriginalHandle() {
        val gate = SingleFlightRequestGate()
        val firstToken = requireNotNull(gate.begin())
        val cancelled = AtomicInteger()
        gate.attach(firstToken, RequestHandle { cancelled.incrementAndGet() })

        assertEquals(null, gate.begin())
        gate.destroy()

        assertEquals(1, cancelled.get())
        assertFalse(gate.finish(firstToken))
    }

    @Test
    fun visualCancellationDoesNotReleaseResourcesUntilProducerCompletes() {
        repeat(50) {
            val callbackExecutor = QueuedExecutor()
            val callbacks = AtomicInteger()
            val callbackResult = AtomicReference<VisualDiagnosticResult>()
            val closes = AtomicInteger()
            val terminal = VisualTaskTerminal(callbackExecutor) { result ->
                callbacks.incrementAndGet()
                callbackResult.set(result)
            }
            val producerResources = ProducerResources(
                AutoCloseable { closes.incrementAndGet() },
            )
            val start = CountDownLatch(1)
            val producerComplete = Thread {
                start.await()
                producerResources.release()
                terminal.complete(VisualDiagnosticResult(null, "ocr", "SUCCESS"))
            }
            val cancel = Thread {
                start.await()
                terminal.cancel()
            }

            producerComplete.start()
            cancel.start()
            start.countDown()
            producerComplete.join()
            cancel.join()
            callbackExecutor.runAll()

            assertEquals(1, callbacks.get())
            assertEquals("CANCELLED", callbackResult.get().status)
            assertEquals(1, closes.get())
        }
    }

    @Test
    fun cancellationCallbackNeverOwnsOcrResources() {
        val callbackExecutor = QueuedExecutor()
        val closes = AtomicInteger()
        val terminal = VisualTaskTerminal(callbackExecutor) {}
        val producerResources = ProducerResources(
            AutoCloseable { closes.incrementAndGet() },
        )

        terminal.cancel()
        callbackExecutor.runAll()

        assertEquals(0, closes.get())
        producerResources.release()
        producerResources.release()
        assertEquals(1, closes.get())
    }

    @Test
    fun serviceDestroyReleasesQueuedProducerButNeverClosesRunningProducer() {
        val queuedCloses = AtomicInteger()
        val queuedRuns = AtomicInteger()
        val queued = ProducerTask(
            releaseIfNotStarted = { queuedCloses.incrementAndGet() },
            work = { queuedRuns.incrementAndGet() },
        )

        assertTrue(queued.cancelBeforeStart())
        queued.run()
        assertEquals(1, queuedCloses.get())
        assertEquals(0, queuedRuns.get())

        val started = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val runningCloses = AtomicInteger()
        val running = ProducerTask(
            releaseIfNotStarted = { runningCloses.incrementAndGet() },
            work = {
                started.countDown()
                allowFinish.await()
                runningCloses.incrementAndGet()
            },
        )
        val thread = Thread(running)
        thread.start()
        started.await()

        assertFalse(running.cancelBeforeStart())
        assertEquals(0, runningCloses.get())
        allowFinish.countDown()
        thread.join()
        assertEquals(1, runningCloses.get())
    }

    @Test
    fun fixturePackageIsDebugOnly() {
        assertTrue(AllowedPackagePolicy(debug = true).allows("com.loanagent.fixture"))
        assertFalse(AllowedPackagePolicy(debug = false).allows("com.loanagent.fixture"))
        assertTrue(AllowedPackagePolicy(debug = false).allows("com.xingin.xhs"))
    }

    private fun snapshot(packageName: String = "com.xingin.xhs") = UiSnapshot(
        packageName,
        "Activity",
        PageHint.HOME,
        emptyList(),
        emptyList(),
        false,
    )
}

private class SafetyFakeTime : MonotonicClock, PollSleeper {
    private var now = 0L

    override fun nowMillis(): Long = now

    override fun sleep(millis: Long) {
        now += millis
    }
}

private class DeferredExecutor : java.util.concurrent.Executor {
    private var pending: Runnable? = null

    override fun execute(command: Runnable) {
        pending = command
    }

    fun runPending() {
        pending?.run()
        pending = null
    }
}

private class QueuedExecutor : java.util.concurrent.Executor {
    private val pending = ConcurrentLinkedQueue<Runnable>()

    override fun execute(command: Runnable) {
        pending += command
    }

    fun runAll() {
        while (true) {
            val next = pending.poll() ?: return
            next.run()
        }
    }
}

private class LeaseAutomationPort(
    private val lease: TargetLease,
) : AutomationPort {
    override fun atomicProbe(): ConditionProbe = ConditionProbe(
        serviceActive = true,
        lease = lease,
        snapshot = UiSnapshot(
            lease.packageName,
            "Activity",
            PageHint.HOME,
            emptyList(),
            emptyList(),
            false,
        ),
    )

    override fun clickNode(expectedLease: TargetLease, selector: Selector): NodeActionAttempt =
        NodeActionAttempt(true)

    override fun setTextNode(
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

private class LeaseRaceAutomationPort(
    private var lease: TargetLease,
) : AutomationPort {
    override fun atomicProbe(): ConditionProbe = ConditionProbe(
        true,
        lease,
        UiSnapshot(
            lease.packageName,
            "Activity",
            PageHint.HOME,
            listOf("发布"),
            listOf(UiNode(text = "发布", clickable = true)),
            false,
        ),
    )

    override fun clickNode(
        expectedLease: TargetLease,
        selector: Selector,
    ): NodeActionAttempt {
        lease = lease.copy(windowGeneration = lease.windowGeneration + 1)
        return NodeActionAttempt(false, leaseLost = true)
    }

    override fun setTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt = NodeActionAttempt(false)

    override fun dispatchGesture(
        expectedLease: TargetLease,
        spec: SwipeSpec,
        callback: (GestureCompletion) -> Unit,
    ): Boolean = false

    override fun globalBack(expectedLease: TargetLease): Boolean = false

    override fun imeStatus(): ImeStatus = ImeStatus(false, false)
}

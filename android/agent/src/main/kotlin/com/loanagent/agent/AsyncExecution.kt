package com.loanagent.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class TargetLease(
    val packageName: String,
    val windowGeneration: Long,
) {
    fun matches(other: TargetLease?): Boolean = this == other
}

class AllowedPackagePolicy(
    private val debug: Boolean,
) {
    fun allows(packageName: String): Boolean =
        packageName == XHS || debug && packageName == FIXTURE

    companion object {
        const val XHS = "com.xingin.xhs"
        const val FIXTURE = "com.loanagent.fixture"
    }
}

class GestureSafetyPolicy {
    fun allowsCoordinateGesture(
        packageName: String,
        debug: Boolean,
        trustedKiosk: Boolean,
    ): Boolean = trustedKiosk ||
        debug && packageName in setOf(AllowedPackagePolicy.FIXTURE, AllowedPackagePolicy.XHS)
}

fun interface TrustedKioskState {
    fun isTrustedKiosk(): Boolean
}

object UntrustedKioskState : TrustedKioskState {
    override fun isTrustedKiosk(): Boolean = false
}

class OriginalCapturePolicy {
    fun allows(packageName: String, debug: Boolean, trustedKiosk: Boolean): Boolean =
        trustedKiosk ||
            debug && packageName in setOf(AllowedPackagePolicy.FIXTURE, AllowedPackagePolicy.XHS)
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

fun interface PollSleeper {
    fun sleep(millis: Long)
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

object ThreadPollSleeper : PollSleeper {
    override fun sleep(millis: Long) {
        Thread.sleep(millis)
    }
}

sealed interface WaitCondition {
    data class SelectorAppears(val selector: Selector) : WaitCondition
    data class SelectorDisappears(val selector: Selector) : WaitCondition
    data class PageHintChanges(val from: PageHint) : WaitCondition
}

data class ConditionProbe(
    val serviceActive: Boolean,
    val lease: TargetLease?,
    val snapshot: UiSnapshot?,
) {
    val packageName: String? get() = lease?.packageName
}

enum class WaitStatus {
    MET,
    TIMEOUT,
    TARGET_LEASE_LOST,
    SERVICE_STOPPED,
    CANCELLED,
}

data class WaitResult(
    val status: WaitStatus,
    val checks: Int,
    val elapsedMs: Long,
)

class PollingConditionWaiter(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val sleeper: PollSleeper = ThreadPollSleeper,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val selectorEngine: SelectorEngine = SelectorEngine(),
) {
    init {
        require(pollIntervalMs > 0)
    }

    fun await(
        condition: WaitCondition,
        timeoutMs: Long,
        expectedLease: TargetLease,
        cancelled: () -> Boolean = { false },
        probe: () -> ConditionProbe,
    ): WaitResult {
        val boundedTimeout = timeoutMs.coerceIn(0, MAX_TIMEOUT_MS)
        val startedAt = clock.nowMillis()
        var checks = 0
        while (true) {
            if (cancelled()) return WaitResult(WaitStatus.CANCELLED, checks, elapsed(startedAt))
            val state = probe()
            checks += 1
            val elapsed = elapsed(startedAt)
            if (!state.serviceActive) return WaitResult(WaitStatus.SERVICE_STOPPED, checks, elapsed)
            if (!expectedLease.matches(state.lease)) {
                return WaitResult(WaitStatus.TARGET_LEASE_LOST, checks, elapsed)
            }
            if (matches(condition, state.snapshot)) return WaitResult(WaitStatus.MET, checks, elapsed)
            if (elapsed >= boundedTimeout) return WaitResult(WaitStatus.TIMEOUT, checks, elapsed)
            try {
                sleeper.sleep(minOf(pollIntervalMs, boundedTimeout - elapsed))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return WaitResult(WaitStatus.SERVICE_STOPPED, checks, elapsed)
            }
        }
    }

    private fun elapsed(startedAt: Long): Long =
        (clock.nowMillis() - startedAt).coerceAtLeast(0)

    private fun matches(condition: WaitCondition, snapshot: UiSnapshot?): Boolean = when (condition) {
        is WaitCondition.SelectorAppears ->
            snapshot != null && selectorEngine.hasAnyMatch(snapshot.nodes, condition.selector)
        is WaitCondition.SelectorDisappears ->
            snapshot != null && !selectorEngine.hasAnyMatch(snapshot.nodes, condition.selector)
        is WaitCondition.PageHintChanges ->
            snapshot != null && snapshot.pageHint != condition.from
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 100L
        const val MAX_TIMEOUT_MS = 30_000L
    }
}

data class SwipeSpec(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
)

data class NodeActionAttempt(
    val accepted: Boolean,
    val editable: Boolean = false,
    val fallbackBounds: UiBounds? = null,
    val leaseLost: Boolean = false,
    val matchStatus: SelectorMatchStatus = SelectorMatchStatus.UNIQUE,
)

enum class GestureCompletion {
    COMPLETED,
    CANCELLED,
}

interface AutomationPort {
    fun atomicProbe(): ConditionProbe

    fun setTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt

    fun pasteTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt = NodeActionAttempt(false)

    fun clickNode(expectedLease: TargetLease, selector: Selector): NodeActionAttempt

    fun dispatchGesture(
        expectedLease: TargetLease,
        spec: SwipeSpec,
        callback: (GestureCompletion) -> Unit,
    ): Boolean

    fun globalBack(expectedLease: TargetLease): Boolean

    fun imeStatus(): ImeStatus
}

data class VisualDiagnosticRequest(
    val expectedLease: TargetLease,
    val saveOriginal: Boolean = false,
    val timeoutMs: Long = 5_000,
)

interface VisualDiagnosticPort {
    fun request(
        request: VisualDiagnosticRequest,
        callback: (VisualDiagnosticResult) -> Unit,
    ): RequestHandle
}

fun interface RequestHandle {
    fun cancel()
}

object CompletedRequestHandle : RequestHandle {
    override fun cancel() = Unit
}

class OnceTerminal<T>(
    private val callback: (T) -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun complete(value: T): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        callback(value)
        return true
    }

    fun isCompleted(): Boolean = completed.get()
}

class SingleFlightRequestGate {
    private val lock = Any()
    private var nextToken = 0L
    private var activeToken: Long? = null
    private var activeHandle: RequestHandle? = null
    private var destroyed = false

    fun begin(): Long? = synchronized(lock) {
        if (destroyed || activeToken != null) return@synchronized null
        (++nextToken).also { activeToken = it }
    }

    fun attach(token: Long, handle: RequestHandle) {
        val cancel = synchronized(lock) {
            if (destroyed || activeToken != token) {
                true
            } else {
                activeHandle = handle
                false
            }
        }
        if (cancel) handle.cancel()
    }

    fun finish(token: Long): Boolean = synchronized(lock) {
        if (destroyed || activeToken != token) return@synchronized false
        activeToken = null
        activeHandle = null
        true
    }

    fun destroy() {
        val handle = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            activeToken = null
            activeHandle.also { activeHandle = null }
        }
        handle?.cancel()
    }
}

class VisualTaskTerminal(
    private val callbackExecutor: Executor,
    private val callback: (VisualDiagnosticResult) -> Unit,
) {
    private val lock = Any()
    private var trackedFile: java.io.File? = null
    private var pending: VisualDiagnosticResult? = null
    private var deliveryScheduled = false
    private var delivered = false

    fun trackFile(file: java.io.File): Boolean {
        val deleteNow = synchronized(lock) {
            if (delivered) true else {
                trackedFile = file
                false
            }
        }
        if (deleteNow) file.delete()
        return !deleteNow
    }

    fun complete(result: VisualDiagnosticResult): Boolean {
        val schedule = synchronized(lock) {
            if (delivered) return false
            val current = pending
            when {
                current == null -> pending = result
                current.status == "SUCCESS" && result.status != "SUCCESS" -> pending = result
                else -> return false
            }
            if (deliveryScheduled) {
                false
            } else {
                deliveryScheduled = true
                true
            }
        }
        if (schedule) {
            try {
                callbackExecutor.execute(::deliver)
            } catch (_: RejectedExecutionException) {
                deliver()
            }
        }
        return true
    }

    fun cancel(): Boolean = complete(VisualDiagnosticResult(null, null, "CANCELLED"))

    private fun deliver() {
        val final: VisualDiagnosticResult
        val toDelete: java.io.File?
        synchronized(lock) {
            if (delivered) return
            final = pending ?: VisualDiagnosticResult(null, null, "CANCELLED")
            delivered = true
            toDelete = trackedFile?.takeUnless {
                final.status == "SUCCESS" && final.screenshot == it
            }
            trackedFile = null
        }
        toDelete?.delete()
        callback(final)
    }
}

class ProducerResources(
    vararg owned: AutoCloseable,
) {
    private val released = AtomicBoolean(false)
    private val resources = owned.toList()

    fun release() {
        if (!released.compareAndSet(false, true)) return
        resources.forEach { resource ->
            try {
                resource.close()
            } catch (_: Exception) {
                // Producer cleanup is best effort and idempotent.
            }
        }
    }
}

class ProducerTask(
    private val releaseIfNotStarted: () -> Unit,
    private val onFinished: () -> Unit = {},
    private val work: () -> Unit,
) : Runnable {
    private val state = AtomicInteger(PENDING)

    override fun run() {
        if (!state.compareAndSet(PENDING, RUNNING)) return
        try {
            work()
        } finally {
            state.set(FINISHED)
            onFinished()
        }
    }

    fun cancelBeforeStart(): Boolean {
        if (!state.compareAndSet(PENDING, FINISHED)) return false
        try {
            releaseIfNotStarted()
        } finally {
            onFinished()
        }
        return true
    }

    private companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val FINISHED = 2
    }
}

interface M0DiagnosticController {
    fun currentLease(): TargetLease?
    fun currentTargetPackage(): String? = currentLease()?.packageName
    fun observe(expectedLease: TargetLease? = currentLease()): UiSnapshot?
    fun executeAction(request: ActionRequest, callback: (ActionResult) -> Unit): RequestHandle
    fun waitForCondition(
        expectedLease: TargetLease,
        condition: WaitCondition,
        timeoutMs: Long,
        callback: (WaitResult) -> Unit,
    ): RequestHandle
    fun runVisualDiagnostic(
        request: VisualDiagnosticRequest,
        callback: (VisualDiagnosticResult) -> Unit,
    ): RequestHandle
}

data class ActionRequest(
    val action: M0Action,
    val expectedLease: TargetLease,
    val selector: Selector? = null,
    val text: String? = null,
    val swipe: SwipeSpec? = null,
    val timeoutMs: Long = 3_000,
    val postcondition: WaitCondition? = null,
)

enum class ExecutionStage {
    ACTION_ACCEPTED,
    GESTURE_COMPLETED,
    GESTURE_CANCELLED,
    GESTURE_CALLBACK_TIMEOUT,
    POSTCONDITION_MET,
    POSTCONDITION_TIMEOUT,
    TARGET_LEASE_LOST,
    SERVICE_STOPPED,
    IME_FALLBACK_REQUIRED,
    PRECONDITION_TIMEOUT,
    CANCELLED,
    REJECTED,
}

private class CoordinatorRequestHandle(
    private val cancelled: AtomicBoolean,
    private val onCancel: () -> Unit,
) : RequestHandle {
    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) onCancel()
    }
}

class M0ExecutionCoordinator(
    private val port: AutomationPort,
    private val waiter: PollingConditionWaiter,
    private val worker: Executor,
    private val callbackExecutor: Executor,
    private val inputStrategy: InputStrategy = InputStrategy(),
    private val gestureCallbackTimeoutMs: Long = 3_000,
    private val allowedPackages: AllowedPackagePolicy = AllowedPackagePolicy(BuildConfig.DEBUG),
    private val selectorEngine: SelectorEngine = SelectorEngine(),
    private val gestureSafetyPolicy: GestureSafetyPolicy = GestureSafetyPolicy(),
    private val trustedKiosk: TrustedKioskState = UntrustedKioskState,
) {
    private val closed = AtomicBoolean(false)
    private val requestIds = AtomicLong()
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, () -> Unit>()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.toList().forEach { it() }
        pending.clear()
    }

    fun waitFor(
        expectedLease: TargetLease,
        condition: WaitCondition,
        timeoutMs: Long,
        callback: (WaitResult) -> Unit,
    ): RequestHandle {
        val cancelled = AtomicBoolean(false)
        val terminal = OnceTerminal(callback)
        val id = register {
            cancelled.set(true)
            completeOnCallback(terminal, WaitResult(WaitStatus.CANCELLED, 0, 0))
        }
        if (id == null) {
            completeOnCallback(terminal, WaitResult(WaitStatus.SERVICE_STOPPED, 0, 0))
            return CompletedRequestHandle
        }
        val handle = CoordinatorRequestHandle(cancelled) {
            pending.remove(id)?.invoke()
        }
        submit(
            onRejected = {
                pending.remove(id)
                completeOnCallback(terminal, WaitResult(WaitStatus.SERVICE_STOPPED, 0, 0))
            },
        ) {
            val result = waiter.await(
                condition,
                timeoutMs,
                expectedLease,
                cancelled::get,
                port::atomicProbe,
            )
            pending.remove(id)
            completeOnCallback(terminal, result)
        }
        return handle
    }

    fun execute(request: ActionRequest, callback: (ActionResult) -> Unit): RequestHandle {
        val lease = request.expectedLease
        val terminal = OnceTerminal(callback)
        if (!allowedPackages.allows(lease.packageName)) {
            completeOnCallback(terminal, rejected(request.action, "outside_allowed_package"))
            return CompletedRequestHandle
        }
        val cancelled = AtomicBoolean(false)
        val id = register {
            cancelled.set(true)
            completeOnCallback(terminal, cancelled(request.action))
        }
        if (id == null) {
            completeOnCallback(terminal, stopped(request.action))
            return CompletedRequestHandle
        }
        val handle = CoordinatorRequestHandle(cancelled) {
            pending.remove(id)?.invoke()
        }
        submit(
            onRejected = {
                pending.remove(id)
                completeOnCallback(terminal, stopped(request.action))
            },
        ) {
            val result = executeOnWorker(request, lease, cancelled)
            pending.remove(id)
            completeOnCallback(terminal, result)
        }
        return handle
    }

    private fun executeOnWorker(
        request: ActionRequest,
        expectedLease: TargetLease,
        cancelled: AtomicBoolean,
    ): ActionResult {
        if (cancelled.get()) return cancelled(request.action)
        val initial = port.atomicProbe()
        if (!initial.serviceActive) return stopped(request.action)
        if (!expectedLease.matches(initial.lease)) return leaseLost(request.action)
        val selector = request.selector
        if (request.action == M0Action.CLICK || request.action == M0Action.SET_TEXT) {
            if (selector == null) return rejected(request.action, "selector_required")
            if (!selector.hasStableIdentity) {
                return rejected(request.action, "selector_requires_stable_identity")
            }
            when (
                selectorEngine.find(
                    initial.snapshot?.nodes.orEmpty(),
                    selector,
                    initial.snapshot?.truncated == true,
                ).status
            ) {
                SelectorMatchStatus.AMBIGUOUS -> return ambiguous(request.action)
                SelectorMatchStatus.INDETERMINATE -> if (
                    !selectorEngine.hasAnyMatch(initial.snapshot?.nodes.orEmpty(), selector)
                ) {
                    return indeterminate(request.action)
                }
                SelectorMatchStatus.UNIQUE -> Unit
                SelectorMatchStatus.NOT_FOUND -> {
                    val precondition = waiter.await(
                        WaitCondition.SelectorAppears(selector),
                        request.timeoutMs,
                        expectedLease,
                        cancelled::get,
                        port::atomicProbe,
                    )
                    if (precondition.status != WaitStatus.MET) {
                        return waitFailure(request.action, precondition, precondition = true)
                    }
                    val matchProbe = port.atomicProbe()
                    if (!matchProbe.serviceActive) return stopped(request.action)
                    if (!expectedLease.matches(matchProbe.lease)) return leaseLost(request.action)
                    when (
                        selectorEngine.find(
                            matchProbe.snapshot?.nodes.orEmpty(),
                            selector,
                            matchProbe.snapshot?.truncated == true,
                        ).status
                    ) {
                        SelectorMatchStatus.NOT_FOUND -> return notFound(request.action)
                        SelectorMatchStatus.AMBIGUOUS -> return ambiguous(request.action)
                        SelectorMatchStatus.INDETERMINATE -> if (
                            !selectorEngine.hasAnyMatch(
                                matchProbe.snapshot?.nodes.orEmpty(),
                                selector,
                            )
                        ) {
                            return indeterminate(request.action)
                        }
                        SelectorMatchStatus.UNIQUE -> Unit
                    }
                }
            }
        }
        if (cancelled.get()) return cancelled(request.action)
        if (!expectedLease.matches(port.atomicProbe().lease)) return leaseLost(request.action)
        return when (request.action) {
            M0Action.CLICK -> executeClick(requireNotNull(selector), request, expectedLease, cancelled)
            M0Action.SET_TEXT -> executeSetText(
                requireNotNull(selector),
                request.text.orEmpty(),
                request,
                expectedLease,
                cancelled,
            )
            M0Action.SWIPE -> executeGesture(
                request.action,
                request.swipe ?: return rejected(request.action, "swipe_required"),
                request,
                expectedLease,
                fallback = false,
                cancelled,
            )
            M0Action.BACK -> {
                if (!trustedKiosk.isTrustedKiosk()) {
                    return blocked(request.action, "UNSAFE_GLOBAL_ACTION_BLOCKED")
                }
                if (!expectedLease.matches(port.atomicProbe().lease)) return leaseLost(request.action)
                if (!port.globalBack(expectedLease)) {
                    if (!expectedLease.matches(port.atomicProbe().lease)) leaseLost(request.action)
                    else rejected(request.action, "global_back_rejected")
                } else {
                    afterAccepted(request, expectedLease, ActionPath.GLOBAL_ACTION, cancelled)
                }
            }
        }
    }

    private fun executeClick(
        selector: Selector,
        request: ActionRequest,
        expectedLease: TargetLease,
        cancelled: AtomicBoolean,
    ): ActionResult {
        val attempt = port.clickNode(expectedLease, selector)
        if (attempt.leaseLost) return leaseLost(request.action)
        when (attempt.matchStatus) {
            SelectorMatchStatus.NOT_FOUND -> return notFound(request.action)
            SelectorMatchStatus.AMBIGUOUS -> return ambiguous(request.action)
            SelectorMatchStatus.INDETERMINATE -> return indeterminate(request.action)
            SelectorMatchStatus.UNIQUE -> Unit
        }
        if (attempt.accepted) {
            return afterAccepted(request, expectedLease, ActionPath.NODE_ACTION, cancelled)
        }
        val bounds = attempt.fallbackBounds
            ?: return rejected(request.action, "click_rejected_no_bounds")
        return executeGesture(
            request.action,
            SwipeSpec(bounds.centerX, bounds.centerY, bounds.centerX, bounds.centerY, 80),
            request,
            expectedLease,
            fallback = true,
            cancelled,
        )
    }

    private fun executeSetText(
        selector: Selector,
        text: String,
        request: ActionRequest,
        expectedLease: TargetLease,
        cancelled: AtomicBoolean,
    ): ActionResult {
        val attempt = port.setTextNode(expectedLease, selector, text)
        if (attempt.leaseLost) return leaseLost(request.action)
        when (attempt.matchStatus) {
            SelectorMatchStatus.NOT_FOUND -> return notFound(request.action)
            SelectorMatchStatus.AMBIGUOUS -> return ambiguous(request.action)
            SelectorMatchStatus.INDETERMINATE -> return indeterminate(request.action)
            SelectorMatchStatus.UNIQUE -> Unit
        }
        val route = inputStrategy.choose(
            editable = attempt.editable,
            setTextSupported = attempt.accepted,
            imeEnabled = port.imeStatus().enabled,
        )
        val inputMessage = inputRouteMessage(route, text)
        return when (route) {
            InputRoute.ACTION_SET_TEXT -> afterAccepted(
                request,
                expectedLease,
                ActionPath.NODE_ACTION,
                cancelled,
                inputMessage,
            )
            InputRoute.CLIPBOARD -> executeClipboardPaste(
                selector,
                text,
                request,
                expectedLease,
                cancelled,
                inputMessage,
            )
            InputRoute.MANUAL_IME,
            InputRoute.BLOCKED_ENABLE_IME_MANUALLY,
            InputRoute.BLOCKED_NOT_EDITABLE,
            -> result(
                request.action,
                ActionStatus.IME_FALLBACK_REQUIRED,
                ExecutionStage.IME_FALLBACK_REQUIRED,
                inputMessage,
            )
        }
    }

    private fun executeClipboardPaste(
        selector: Selector,
        text: String,
        request: ActionRequest,
        expectedLease: TargetLease,
        cancelled: AtomicBoolean,
        inputMessage: String,
    ): ActionResult {
        val attempt = port.pasteTextNode(expectedLease, selector, text)
        if (attempt.leaseLost) return leaseLost(request.action)
        when (attempt.matchStatus) {
            SelectorMatchStatus.NOT_FOUND -> return notFound(request.action)
            SelectorMatchStatus.AMBIGUOUS -> return ambiguous(request.action)
            SelectorMatchStatus.INDETERMINATE -> return indeterminate(request.action)
            SelectorMatchStatus.UNIQUE -> Unit
        }
        if (!attempt.accepted) {
            return rejected(request.action, "clipboard_paste_rejected $inputMessage")
        }
        return afterAccepted(request, expectedLease, ActionPath.NODE_ACTION, cancelled, inputMessage)
    }

    private fun executeGesture(
        action: M0Action,
        spec: SwipeSpec,
        request: ActionRequest,
        expectedLease: TargetLease,
        fallback: Boolean,
        cancelled: AtomicBoolean,
    ): ActionResult {
        if (
            !gestureSafetyPolicy.allowsCoordinateGesture(
                expectedLease.packageName,
                BuildConfig.DEBUG,
                trustedKiosk.isTrustedKiosk(),
            )
        ) {
            return blocked(action, "UNSAFE_GESTURE_BLOCKED")
        }
        if (!expectedLease.matches(port.atomicProbe().lease)) return leaseLost(action)
        val completion = arrayOfNulls<GestureCompletion>(1)
        val latch = CountDownLatch(1)
        val dispatched = port.dispatchGesture(expectedLease, spec) {
            completion[0] = it
            latch.countDown()
        }
        if (!dispatched) {
            return if (!expectedLease.matches(port.atomicProbe().lease)) leaseLost(action)
            else rejected(action, "gesture_dispatch_rejected")
        }
        val callbackReceived = try {
            latch.await(gestureCallbackTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return stopped(action)
        }
        if (cancelled.get()) return cancelled(action)
        if (!callbackReceived) {
            return result(
                action,
                ActionStatus.FAILED,
                ExecutionStage.GESTURE_CALLBACK_TIMEOUT,
                "gesture_callback_timeout",
                if (fallback) ActionPath.BOUNDS_GESTURE_FALLBACK else ActionPath.GESTURE,
                fallback,
            )
        }
        val callbackProbe = port.atomicProbe()
        if (!callbackProbe.serviceActive) return stopped(action)
        if (!expectedLease.matches(callbackProbe.lease)) return leaseLost(action)
        if (completion[0] != GestureCompletion.COMPLETED) {
            return result(
                action,
                ActionStatus.FAILED,
                ExecutionStage.GESTURE_CANCELLED,
                "gesture_cancelled",
                if (fallback) ActionPath.BOUNDS_GESTURE_FALLBACK else ActionPath.GESTURE,
                fallback,
            )
        }
        if (request.postcondition != null) {
            return evaluatePostcondition(
                request,
                expectedLease,
                if (fallback) ActionPath.BOUNDS_GESTURE_FALLBACK else ActionPath.GESTURE,
                fallback,
                cancelled,
            )
        }
        return result(
            action,
            ActionStatus.SUCCESS,
            ExecutionStage.GESTURE_COMPLETED,
            "gesture_completed",
            if (fallback) ActionPath.BOUNDS_GESTURE_FALLBACK else ActionPath.GESTURE,
            fallback,
        )
    }

    private fun afterAccepted(
        request: ActionRequest,
        expectedLease: TargetLease,
        path: ActionPath,
        cancelled: AtomicBoolean,
        message: String? = null,
    ): ActionResult {
        if (cancelled.get()) return cancelled(request.action)
        if (request.postcondition != null) {
            val probe = port.atomicProbe()
            if (!probe.serviceActive) return stopped(request.action)
            val postActionLease = probe.lease
            if (postActionLease?.packageName != expectedLease.packageName) {
                return leaseLost(request.action)
            }
            return evaluatePostcondition(
                request,
                postActionLease,
                path,
                false,
                cancelled,
                message,
            )
        }
        return result(
            request.action,
            ActionStatus.SUCCESS,
            ExecutionStage.ACTION_ACCEPTED,
            message ?: "action_accepted",
            path,
        )
    }

    private fun evaluatePostcondition(
        request: ActionRequest,
        expectedLease: TargetLease,
        path: ActionPath,
        fallback: Boolean,
        cancelled: AtomicBoolean,
        successMessage: String? = null,
    ): ActionResult {
        val wait = waiter.await(
            requireNotNull(request.postcondition),
            request.timeoutMs,
            expectedLease,
            cancelled::get,
            port::atomicProbe,
        )
        return when (wait.status) {
            WaitStatus.MET -> result(
                request.action,
                ActionStatus.SUCCESS,
                ExecutionStage.POSTCONDITION_MET,
                successMessage ?: "postcondition_met",
                path,
                fallback,
            )
            WaitStatus.TIMEOUT -> result(
                request.action,
                ActionStatus.FAILED,
                ExecutionStage.POSTCONDITION_TIMEOUT,
                "postcondition_timeout",
                path,
                fallback,
            )
            WaitStatus.TARGET_LEASE_LOST -> leaseLost(request.action)
            WaitStatus.SERVICE_STOPPED -> stopped(request.action)
            WaitStatus.CANCELLED -> cancelled(request.action)
        }
    }

    private fun waitFailure(action: M0Action, wait: WaitResult, precondition: Boolean): ActionResult =
        when (wait.status) {
            WaitStatus.TIMEOUT -> result(
                action,
                ActionStatus.NOT_FOUND,
                if (precondition) ExecutionStage.PRECONDITION_TIMEOUT else ExecutionStage.POSTCONDITION_TIMEOUT,
                "condition_timeout",
            )
            WaitStatus.TARGET_LEASE_LOST -> leaseLost(action)
            WaitStatus.SERVICE_STOPPED -> stopped(action)
            WaitStatus.CANCELLED -> cancelled(action)
            WaitStatus.MET -> error("MET is not a failure")
        }

    private fun register(cancel: () -> Unit): Long? {
        if (closed.get()) return null
        val id = requestIds.incrementAndGet()
        pending[id] = cancel
        if (closed.get()) pending.remove(id)?.invoke()
        return if (pending.containsKey(id)) id else null
    }

    private fun submit(onRejected: () -> Unit, work: () -> Unit) {
        try {
            worker.execute(work)
        } catch (_: RejectedExecutionException) {
            onRejected()
        }
    }

    private fun <T> completeOnCallback(terminal: OnceTerminal<T>, value: T) {
        try {
            callbackExecutor.execute { terminal.complete(value) }
        } catch (_: RejectedExecutionException) {
            terminal.complete(value)
        }
    }

    private fun leaseLost(action: M0Action) = result(
        action,
        ActionStatus.FAILED,
        ExecutionStage.TARGET_LEASE_LOST,
        "target_lease_lost",
    )

    private fun stopped(action: M0Action) = result(
        action,
        ActionStatus.FAILED,
        ExecutionStage.SERVICE_STOPPED,
        "service_stopped",
    )

    private fun cancelled(action: M0Action) = result(
        action,
        ActionStatus.FAILED,
        ExecutionStage.CANCELLED,
        "cancelled",
    )

    private fun rejected(action: M0Action, message: String) = result(
        action,
        ActionStatus.FAILED,
        ExecutionStage.REJECTED,
        message,
    )

    private fun notFound(action: M0Action) = result(
        action,
        ActionStatus.NOT_FOUND,
        ExecutionStage.REJECTED,
        "selector_not_found",
    )

    private fun ambiguous(action: M0Action) = result(
        action,
        ActionStatus.AMBIGUOUS,
        ExecutionStage.REJECTED,
        "selector_ambiguous",
    )

    private fun indeterminate(action: M0Action) = result(
        action,
        ActionStatus.INDETERMINATE,
        ExecutionStage.REJECTED,
        "selector_traversal_indeterminate",
    )

    private fun blocked(action: M0Action, message: String) = result(
        action,
        ActionStatus.BLOCKED,
        ExecutionStage.REJECTED,
        message,
    )

    private fun result(
        action: M0Action,
        status: ActionStatus,
        stage: ExecutionStage,
        message: String,
        path: ActionPath = ActionPath.NONE,
        fallback: Boolean = false,
    ) = ActionResult(status, action, path, fallback, message, stage)

    private fun inputRouteMessage(route: InputRoute, text: String): String =
        "input_route=${route.name} input_length=${text.length}"
}

class VisualDiagnosticRunner(
    private val port: VisualDiagnosticPort,
) {
    fun run(
        request: VisualDiagnosticRequest,
        callback: (VisualDiagnosticResult) -> Unit,
    ): RequestHandle = port.request(request, callback)
}

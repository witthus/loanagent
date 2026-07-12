package com.loanagent.agent

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class M0AccessibilityService :
    AccessibilityService(),
    AutomationPort,
    VisualDiagnosticPort,
    M0DiagnosticController {
    @Volatile
    private var serviceActive = false
    private val targetLock = Any()
    internal val leaseGeneration = WindowGenerationTracker()
    private var worker: ExecutorService? = null
    private var coordinator: M0ExecutionCoordinator? = null
    private var visualRunner: VisualDiagnosticRunner? = null
    private val trustedKiosk: TrustedKioskState = UntrustedKioskState
    internal var lastGestureBoundaryStatus: String? = null
        private set
    internal var lastGlobalActionBoundaryStatus: String? = null
        private set
    private val visualCancellations = java.util.concurrent.ConcurrentHashMap.newKeySet<() -> Unit>()
    private val visualProducers = java.util.concurrent.ConcurrentHashMap.newKeySet<ProducerTask>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val mainCallbackExecutor: Executor = Executor { command ->
        if (!mainHandler.post(command)) throw RejectedExecutionException("main looper stopped")
    }

    override fun onServiceConnected() {
        serviceActive = true
        instance = this
        DiagnosticCache(this).deleteExpired()
        val backgroundWorker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "m0-accessibility-worker").apply { isDaemon = true }
        }
        worker = backgroundWorker
        coordinator = M0ExecutionCoordinator(
            port = this,
            waiter = PollingConditionWaiter(),
            worker = backgroundWorker,
            callbackExecutor = mainCallbackExecutor,
        )
        visualRunner = VisualDiagnosticRunner(this)
        startDebugKeepAliveIfPresent()
    }

    private fun startDebugKeepAliveIfPresent() {
        if (!BuildConfig.DEBUG) return
        try {
            val starter = Class.forName("com.loanagent.agent.M0DebugKeepAliveService")
            starter.getMethod("start", android.content.Context::class.java).invoke(null, this)
        } catch (_: ReflectiveOperationException) {
            // Release builds omit the debug keep-alive service.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            // Prefer the active window package. Fall back to the event package only when the
            // active root is unavailable (startup / Robolectric). This avoids SystemUI/IME
            // window noise invalidating a stable XHS/fixture lease.
            val focused = focusedWindowRoot()
            val activePackage = try {
                focused?.packageName?.toString() ?: event.packageName?.toString()
            } finally {
                focused?.let(::recycle)
            }
            leaseGeneration.observePackage(activePackage)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceActive = false
        coordinator?.close()
        coordinator = null
        visualRunner = null
        visualCancellations.toList().forEach { it() }
        visualCancellations.clear()
        visualProducers.toList().forEach(ProducerTask::cancelBeforeStart)
        worker?.shutdownNow()
        worker = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun currentLease(): TargetLease? = leaseProbe()

    override fun executeAction(
        request: ActionRequest,
        callback: (ActionResult) -> Unit,
    ): RequestHandle {
        val activeCoordinator = coordinator
        if (!serviceActive || activeCoordinator == null) {
            callback(stoppedAction(request.action))
            return CompletedRequestHandle
        }
        return activeCoordinator.execute(request, callback)
    }

    override fun waitForCondition(
        expectedLease: TargetLease,
        condition: WaitCondition,
        timeoutMs: Long,
        callback: (WaitResult) -> Unit,
    ): RequestHandle {
        val activeCoordinator = coordinator
        if (!serviceActive || activeCoordinator == null) {
            callback(WaitResult(WaitStatus.SERVICE_STOPPED, 0, 0))
            return CompletedRequestHandle
        }
        return activeCoordinator.waitFor(expectedLease, condition, timeoutMs, callback)
    }

    override fun runVisualDiagnostic(
        request: VisualDiagnosticRequest,
        callback: (VisualDiagnosticResult) -> Unit,
    ): RequestHandle {
        val runner = visualRunner
        if (!serviceActive || runner == null) {
            callback(VisualDiagnosticResult(null, null, "SERVICE_STOPPED"))
            return CompletedRequestHandle
        }
        return runner.run(request, callback)
    }

    override fun atomicProbe(): ConditionProbe = synchronized(targetLock) {
        if (!serviceActive) {
            return@synchronized ConditionProbe(false, lease = null, snapshot = null)
        }
        val root = focusedWindowRoot()
            ?: return@synchronized ConditionProbe(true, lease = null, snapshot = null)
        try {
            val packageName = root.packageName?.toString()
            if (packageName == null || !isPackageAllowed(packageName)) {
                return@synchronized ConditionProbe(true, lease = null, snapshot = null)
            }
            val lease = TargetLease(packageName, leaseGeneration.current())
            val budget = NodeBudget(MAX_NODES)
            val raw = copyNode(root, 0, budget)
            val snapshot = SnapshotBuilder().build(
                packageName,
                root.className?.toString().orEmpty(),
                raw,
                sourceTruncated = budget.truncated,
            )
            ConditionProbe(true, lease, snapshot)
        } catch (_: RuntimeException) {
            ConditionProbe(true, lease = null, snapshot = null)
        } finally {
            recycle(root)
        }
    }

    /**
     * Prefer the focused/active interactive window root. On HyperOS, search and other
     * overlays can leave [rootInActiveWindow] pointing at the underlying IndexActivity.
     */
    private fun focusedWindowRoot(): AccessibilityNodeInfo? {
        val ranked = windows.orEmpty()
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.layer },
            )
        for (window in ranked) {
            val candidate = window.root ?: continue
            val packageName = candidate.packageName?.toString()
            if (packageName != null && isPackageAllowed(packageName)) {
                return candidate
            }
            recycle(candidate)
        }
        return rootInActiveWindow
    }

    override fun observe(expectedLease: TargetLease?): UiSnapshot? {
        val probe = atomicProbe()
        if (expectedLease != null && !expectedLease.matches(probe.lease)) return null
        return probe.snapshot
    }

    override fun clickNode(
        expectedLease: TargetLease,
        selector: Selector,
    ): NodeActionAttempt = withNodeAttempt(expectedLease, selector) { node, bounds ->
        NodeActionAttempt(
            accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            editable = node.isEditable,
            fallbackBounds = bounds,
        )
    }

    override fun setTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt = withNodeAttempt(expectedLease, selector) { node, bounds ->
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text.take(MAX_INPUT_LENGTH),
            )
        }
        NodeActionAttempt(
            accepted = node.isEditable &&
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
            editable = node.isEditable,
            fallbackBounds = bounds,
        )
    }

    override fun pasteTextNode(
        expectedLease: TargetLease,
        selector: Selector,
        text: String,
    ): NodeActionAttempt = withNodeAttempt(expectedLease, selector) { node, bounds ->
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        NodeActionAttempt(
            accepted = node.isEditable &&
                clipboard != null &&
                pasteFromClipboard(clipboard, node, text.take(MAX_INPUT_LENGTH)),
            editable = node.isEditable,
            fallbackBounds = bounds,
        )
    }

    override fun dispatchGesture(
        expectedLease: TargetLease,
        spec: SwipeSpec,
        callback: (GestureCompletion) -> Unit,
    ): Boolean {
        if (
            !GestureSafetyPolicy().allowsCoordinateGesture(
                expectedLease.packageName,
                BuildConfig.DEBUG,
                trustedKiosk.isTrustedKiosk(),
            )
        ) {
            lastGestureBoundaryStatus = "UNSAFE_GESTURE_BLOCKED"
            return false
        }
        lastGestureBoundaryStatus = null
        val once = OnceTerminal(callback)
        val path = Path().apply {
            moveTo(spec.startX.toFloat(), spec.startY.toFloat())
            lineTo(spec.endX.toFloat(), spec.endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    spec.durationMs.coerceIn(50, 2_000),
                ),
            )
            .build()
        val accepted = synchronized(targetLock) {
            if (!leaseValidLocked(expectedLease)) return@synchronized false
            // Dispatch while holding the lease lock, but never await the callback under the lock.
            super<AccessibilityService>.dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (expectedLease.matches(leaseProbe())) {
                            once.complete(GestureCompletion.COMPLETED)
                        } else {
                            once.complete(GestureCompletion.CANCELLED)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        once.complete(GestureCompletion.CANCELLED)
                    }
                },
                null,
            )
        }
        return accepted
    }

    override fun globalBack(expectedLease: TargetLease): Boolean {
        if (!trustedKiosk.isTrustedKiosk()) {
            lastGlobalActionBoundaryStatus = "UNSAFE_GLOBAL_ACTION_BLOCKED"
            return false
        }
        lastGlobalActionBoundaryStatus = null
        return synchronized(targetLock) {
            leaseValidLocked(expectedLease) && performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun imeStatus(): ImeStatus = M0InputMethodService.status(this)

    @SuppressLint("NewApi")
    override fun request(
        request: VisualDiagnosticRequest,
        callback: (VisualDiagnosticResult) -> Unit,
    ): RequestHandle {
        val cancelled = AtomicBoolean(false)
        val timeoutRef = AtomicReference<Runnable?>()
        val cancelRef = AtomicReference<(() -> Unit)?>()
        val terminal = VisualTaskTerminal(mainCallbackExecutor) { result ->
            timeoutRef.getAndSet(null)?.let(mainHandler::removeCallbacks)
            cancelRef.get()?.let(visualCancellations::remove)
            callback(result)
        }
        fun finish(result: VisualDiagnosticResult) {
            timeoutRef.getAndSet(null)?.let(mainHandler::removeCallbacks)
            terminal.complete(result)
        }
        val timeout = Runnable {
            cancelled.set(true)
            finish(VisualDiagnosticResult(null, null, "SCREENSHOT_CALLBACK_TIMEOUT"))
        }
        timeoutRef.set(timeout)
        val cancel: () -> Unit = {
            cancelled.set(true)
            mainHandler.removeCallbacks(timeout)
            terminal.cancel()
            Unit
        }
        cancelRef.set(cancel)
        visualCancellations += cancel
        val effectiveRequest = request.copy(
            saveOriginal = request.saveOriginal &&
                OriginalCapturePolicy().allows(
                    request.expectedLease.packageName,
                    BuildConfig.DEBUG,
                    trustedKiosk = trustedKiosk.isTrustedKiosk(),
                ),
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finish(VisualDiagnosticResult(null, null, "BLOCKED_API_BELOW_30"))
            return CompletedRequestHandle
        }
        if (!serviceActive) {
            finish(VisualDiagnosticResult(null, null, "SERVICE_STOPPED"))
            return CompletedRequestHandle
        }
        if (!request.expectedLease.matches(leaseProbe())) {
            finish(VisualDiagnosticResult(null, null, "TARGET_LEASE_LOST"))
            return CompletedRequestHandle
        }
        if (!mainHandler.postDelayed(timeout, request.timeoutMs.coerceIn(100, 30_000))) {
            finish(VisualDiagnosticResult(null, null, "SERVICE_STOPPED"))
            return CompletedRequestHandle
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainCallbackExecutor,
                object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (cancelled.get() || !effectiveRequest.expectedLease.matches(leaseProbe())) {
                        screenshot.hardwareBuffer.close()
                        finish(
                            VisualDiagnosticResult(
                                null,
                                null,
                                if (cancelled.get()) "CANCELLED" else "TARGET_LEASE_LOST",
                            ),
                        )
                        return
                    }
                    val activeWorker = worker
                    if (activeWorker == null || activeWorker.isShutdown) {
                        screenshot.hardwareBuffer.close()
                        finish(VisualDiagnosticResult(null, null, "SERVICE_STOPPED"))
                        return
                    }
                    val taskRef = AtomicReference<ProducerTask>()
                    try {
                        val producer = ProducerTask(
                            releaseIfNotStarted = { screenshot.hardwareBuffer.close() },
                            onFinished = { taskRef.get()?.let(visualProducers::remove) },
                            work = {
                                processScreenshot(
                                    effectiveRequest,
                                    screenshot,
                                    cancelled,
                                    terminal,
                                    ::finish,
                                )
                            },
                        )
                        taskRef.set(producer)
                        visualProducers += producer
                        activeWorker.execute(producer)
                    } catch (_: RejectedExecutionException) {
                        val producer = taskRef.get()
                        if (producer == null) {
                            screenshot.hardwareBuffer.close()
                        } else {
                            producer.cancelBeforeStart()
                        }
                        finish(VisualDiagnosticResult(null, null, "SERVICE_STOPPED"))
                    }
                }

                    override fun onFailure(errorCode: Int) {
                        val status = if (effectiveRequest.expectedLease.matches(leaseProbe())) {
                            "SCREENSHOT_FAILED:$errorCode"
                        } else {
                            "TARGET_LEASE_LOST"
                        }
                        finish(VisualDiagnosticResult(null, null, status))
                    }
                },
            )
        } catch (error: RuntimeException) {
            finish(
                VisualDiagnosticResult(
                    null,
                    null,
                    "SCREENSHOT_FAILED:${error.javaClass.simpleName}",
                ),
            )
        }
        return CoordinatorRequestHandleForVisual(cancel)
    }

    @SuppressLint("NewApi")
    private fun processScreenshot(
        request: VisualDiagnosticRequest,
        screenshot: ScreenshotResult,
        cancelled: AtomicBoolean,
        terminal: VisualTaskTerminal,
        finish: (VisualDiagnosticResult) -> Unit,
    ) {
        val hardwareBitmap: Bitmap?
        try {
            try {
                hardwareBitmap = Bitmap.wrapHardwareBuffer(
                    screenshot.hardwareBuffer,
                    screenshot.colorSpace,
                )
            } finally {
                screenshot.hardwareBuffer.close()
            }
            val source = hardwareBitmap
                ?: run {
                    finish(VisualDiagnosticResult(null, null, "SCREENSHOT_DECODE_FAILED"))
                    return
                }
            val bitmap = try {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                source.recycle()
            } ?: run {
                finish(VisualDiagnosticResult(null, null, "SCREENSHOT_DECODE_FAILED"))
                return
            }
            processOcr(request, bitmap, cancelled, terminal, finish)
        } catch (error: Exception) {
            finish(
                VisualDiagnosticResult(
                    null,
                    null,
                    "SCREENSHOT_DECODE_FAILED:${error.javaClass.simpleName}",
                ),
            )
        }
    }

    private fun processOcr(
        request: VisualDiagnosticRequest,
        bitmap: Bitmap,
        cancelled: AtomicBoolean,
        terminal: VisualTaskTerminal,
        finish: (VisualDiagnosticResult) -> Unit,
    ) {
        if (cancelled.get()) {
            bitmap.recycle()
            finish(VisualDiagnosticResult(null, null, "CANCELLED"))
            return
        }
        val recognizer = try {
            TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )
        } catch (error: Exception) {
            bitmap.recycle()
            finish(
                VisualDiagnosticResult(
                    null,
                    null,
                    "OCR_CLIENT_FAILED:${error.javaClass.simpleName}",
                ),
            )
            return
        }
        val resources = ProducerResources(
            AutoCloseable { bitmap.recycle() },
            AutoCloseable { recognizer.close() },
        )
        val result = AtomicReference<VisualDiagnosticResult?>()
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (error: Exception) {
            resources.release()
            finish(
                VisualDiagnosticResult(
                    null,
                    null,
                    "OCR_FAILED:${error.javaClass.simpleName}",
                ),
            )
            return
        }
        task
            .addOnSuccessListener { text ->
                if (cancelled.get()) {
                    result.set(VisualDiagnosticResult(null, null, "CANCELLED"))
                } else if (!request.expectedLease.matches(leaseProbe())) {
                    result.set(VisualDiagnosticResult(null, null, "TARGET_LEASE_LOST"))
                } else {
                    var file: File? = null
                    try {
                        if (request.saveOriginal) {
                            if (!request.expectedLease.matches(leaseProbe())) {
                                result.set(
                                    VisualDiagnosticResult(null, null, "TARGET_LEASE_LOST"),
                                )
                                return@addOnSuccessListener
                            }
                            file = DiagnosticCache(this).newFile("screen", "png")
                            FileOutputStream(file).use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                            }
                            if (!terminal.trackFile(file)) {
                                result.set(VisualDiagnosticResult(null, null, "CANCELLED"))
                                return@addOnSuccessListener
                            }
                            if (
                                cancelled.get() ||
                                !request.expectedLease.matches(leaseProbe())
                            ) {
                            result.set(
                                VisualDiagnosticResult(
                                    null,
                                    null,
                                        if (cancelled.get()) "CANCELLED" else "TARGET_LEASE_LOST",
                                ),
                            )
                                return@addOnSuccessListener
                            }
                        }
                        val redacted = SensitiveTextRedactor()
                            .redact(text.text, false)
                            .orEmpty()
                        result.set(VisualDiagnosticResult(file, redacted, "SUCCESS"))
                    } catch (error: Exception) {
                        file?.delete()
                        result.set(
                            VisualDiagnosticResult(
                                null,
                                null,
                                "SCREENSHOT_SAVE_FAILED:${error.javaClass.simpleName}",
                            ),
                        )
                    }
                }
            }
            .addOnFailureListener { error ->
                val status = when {
                    cancelled.get() -> "CANCELLED"
                    !request.expectedLease.matches(leaseProbe()) -> "TARGET_LEASE_LOST"
                    else -> "OCR_FAILED:${error.javaClass.simpleName}"
                }
                result.set(VisualDiagnosticResult(null, null, status))
            }
            .addOnCanceledListener {
                result.set(VisualDiagnosticResult(null, null, "OCR_CANCELLED"))
            }
            .addOnCompleteListener {
                val finalResult = when {
                    cancelled.get() -> VisualDiagnosticResult(null, null, "CANCELLED")
                    !request.expectedLease.matches(leaseProbe()) ->
                        VisualDiagnosticResult(null, null, "TARGET_LEASE_LOST")
                    else -> result.get()
                        ?: VisualDiagnosticResult(null, null, "OCR_CANCELLED")
                }
                resources.release()
                finish(finalResult)
            }
    }

    private fun withNodeAttempt(
        expectedLease: TargetLease,
        selector: Selector,
        operation: (AccessibilityNodeInfo, UiBounds?) -> NodeActionAttempt,
    ): NodeActionAttempt {
        if (!expectedLease.matches(leaseProbe())) return NodeActionAttempt(false, leaseLost = true)
        val root = focusedWindowRoot() ?: return NodeActionAttempt(false, leaseLost = true)
        return try {
            if (!expectedLease.matches(leaseForRootLocked(root))) {
                return NodeActionAttempt(false, leaseLost = true)
            }
            if (!selector.hasStableIdentity) {
                return NodeActionAttempt(
                    accepted = false,
                    matchStatus = SelectorMatchStatus.NOT_FOUND,
                )
            }
            val candidates = queryStableNodes(root, selector)
                ?: return NodeActionAttempt(
                    accepted = false,
                    matchStatus = SelectorMatchStatus.INDETERMINATE,
                )
            val exactMatches = mutableListOf<AccessibilityNodeInfo>()
            try {
                candidates.forEach { candidate ->
                    if (matches(candidate, selector)) {
                        exactMatches += candidate
                    } else {
                        recycle(candidate)
                    }
                }
            } catch (_: RuntimeException) {
                candidates.forEach(::recycle)
                return NodeActionAttempt(
                    accepted = false,
                    matchStatus = SelectorMatchStatus.INDETERMINATE,
                )
            }
            if (exactMatches.size > 1) {
                exactMatches.forEach(::recycle)
                return NodeActionAttempt(
                    accepted = false,
                    matchStatus = SelectorMatchStatus.AMBIGUOUS,
                )
            }
            if (exactMatches.isEmpty()) {
                return NodeActionAttempt(
                    accepted = false,
                    matchStatus = SelectorMatchStatus.NOT_FOUND,
                )
            }
            val node = exactMatches.single()
            try {
                if (!expectedLease.matches(leaseProbe())) {
                    return NodeActionAttempt(false, leaseLost = true)
                }
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val bounds = if (rect.width() > 0 && rect.height() > 0) {
                    UiBounds(rect.left, rect.top, rect.right, rect.bottom)
                } else {
                    null
                }
                val attempt = operation(node, bounds)
                if (attempt.accepted) {
                    attempt
                } else if (!expectedLease.matches(leaseProbe())) {
                    NodeActionAttempt(false, leaseLost = true)
                } else {
                    attempt
                }
            } finally {
                if (node !== root) recycle(node)
            }
        } catch (_: RuntimeException) {
            if (expectedLease.matches(leaseProbe())) NodeActionAttempt(false)
            else NodeActionAttempt(false, leaseLost = true)
        } finally {
            recycle(root)
        }
    }

    private fun queryStableNodes(
        root: AccessibilityNodeInfo,
        selector: Selector,
    ): List<AccessibilityNodeInfo>? = try {
        when {
            selector.viewId != null ->
                root.findAccessibilityNodeInfosByViewId(selector.viewId)
            selector.text != null ->
                root.findAccessibilityNodeInfosByText(selector.text)
            selector.contentDescription != null ->
                root.findAccessibilityNodeInfosByText(selector.contentDescription)
            else -> null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun leaseProbe(): TargetLease? = synchronized(targetLock) {
        if (!serviceActive) return@synchronized null
        val root = focusedWindowRoot() ?: return@synchronized null
        try {
            leaseForRootLocked(root)
        } catch (_: RuntimeException) {
            null
        } finally {
            recycle(root)
        }
    }

    private fun leaseValidLocked(expected: TargetLease): Boolean {
        if (!serviceActive) return false
        val root = focusedWindowRoot() ?: return false
        return try {
            expected.matches(leaseForRootLocked(root))
        } catch (_: RuntimeException) {
            false
        } finally {
            recycle(root)
        }
    }

    private fun leaseForRootLocked(root: AccessibilityNodeInfo): TargetLease? {
        val packageName = root.packageName?.toString() ?: return null
        if (!isPackageAllowed(packageName)) return null
        return TargetLease(packageName, leaseGeneration.current())
    }

    private fun copyNode(node: AccessibilityNodeInfo, depth: Int, budget: NodeBudget): RawUiNode {
        if (depth > MAX_DEPTH || !budget.take()) {
            budget.truncated = true
            return RawUiNode(text = "[TRUNCATED]")
        }
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val children = mutableListOf<RawUiNode>()
        if (depth < MAX_DEPTH) {
            for (index in 0 until node.childCount) {
                if (budget.remaining <= 0) {
                    budget.truncated = true
                    break
                }
                val child = try {
                    node.getChild(index)
                } catch (_: RuntimeException) {
                    null
                } ?: continue
                try {
                    children += copyNode(child, depth + 1, budget)
                } finally {
                    recycle(child)
                }
            }
        } else if (node.childCount > 0) {
            budget.truncated = true
        }
        return RawUiNode(
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            clickable = node.isClickable,
            editable = node.isEditable,
            password = node.isPassword,
            bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }

    private fun matches(node: AccessibilityNodeInfo, selector: Selector): Boolean =
        (selector.viewId == null || node.viewIdResourceName == selector.viewId) &&
            (selector.text == null || node.text?.toString() == selector.text) &&
            (selector.contentDescription == null ||
                node.contentDescription?.toString() == selector.contentDescription) &&
            (selector.className == null || node.className?.toString() == selector.className) &&
            (selector.clickable == null || node.isClickable == selector.clickable)

    private fun pasteFromClipboard(
        clipboard: ClipboardManager,
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        val previous = clipboard.primaryClip
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } finally {
            restoreClipboard(clipboard, previous)
        }
    }

    private fun restoreClipboard(clipboard: ClipboardManager, previous: ClipData?) {
        if (previous != null) {
            clipboard.setPrimaryClip(previous)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, ""))
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: RuntimeException) {
            // Stale framework nodes are discarded.
        }
    }

    private fun stoppedAction(action: M0Action) = ActionResult(
        ActionStatus.FAILED,
        action,
        ActionPath.NONE,
        false,
        "service_stopped",
        ExecutionStage.SERVICE_STOPPED,
    )

    private class CoordinatorRequestHandleForVisual(
        private val cancelAction: () -> Unit,
    ) : RequestHandle {
        private val cancelled = AtomicBoolean(false)

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelAction()
        }
    }

    private class NodeBudget(var remaining: Int) {
        var truncated = false

        fun take(): Boolean {
            if (remaining <= 0) {
                truncated = true
                return false
            }
            remaining -= 1
            return true
        }
    }

    companion object {
        private const val MAX_DEPTH = 20
        private const val MAX_NODES = 500
        private const val MAX_INPUT_LENGTH = 4_000
        private const val CLIPBOARD_LABEL = "loanagent-input"
        private val allowedPolicy get() = AllowedPackagePolicy(BuildConfig.DEBUG)

        @Volatile
        var instance: M0AccessibilityService? = null
            private set

        fun isPackageAllowed(packageName: String): Boolean = allowedPolicy.allows(packageName)
    }
}

data class VisualDiagnosticResult(
    val screenshot: File?,
    val ocrText: String?,
    val status: String,
)

package com.loanagent.agent

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AccessibilityPlaybookRuntime(
    private val context: Context,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val sleeper: PollSleeper = ThreadPollSleeper,
) : PlaybookRuntime, TaskExecutionContextBindable {
    private val extractor = ContentExtractors()
    private val executionContext = AtomicReference<TaskExecutionContext?>()

    override fun bindExecutionContext(context: TaskExecutionContext): RequestHandle {
        check(executionContext.compareAndSet(null, context))
        return RequestHandle { executionContext.compareAndSet(context, null) }
    }

    private fun checkExecution() {
        executionContext.get()?.check()
    }

    override fun accessibilityAlive(): Boolean = M0AccessibilityService.instance != null

    override fun ensureScreenReady(timeoutMs: Long): String? {
        val service = M0AccessibilityService.instance ?: return "A11Y_DOWN"
        val power = service.getSystemService(android.os.PowerManager::class.java)
        val keyguard = service.getSystemService(android.app.KeyguardManager::class.java)
        if (power == null || keyguard == null) return ScreenReadyPolicy.ERROR_SECURE_OR_FAILED

        val gate = ScreenReadyPolicy.gate(
            interactive = power.isInteractive,
            keyguardLocked = keyguard.isKeyguardLocked,
            keyguardSecure = keyguard.isKeyguardSecure,
        )
        if (gate == null) return null
        if (gate == ScreenReadyPolicy.ERROR_SECURE_OR_FAILED) {
            return ScreenReadyPolicy.ERROR_SECURE_OR_FAILED
        }

        checkExecution()
        val wakeResult = try {
            ScreenWakeActivity.request(
                service,
                timeoutMs.coerceAtLeast(5_000L),
                ::checkExecution,
            )
        } catch (cancelled: TaskExecutionCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "ensureScreenReady wake failed", error)
            ScreenWakeResult.StartFailed
        }

        when (wakeResult) {
            ScreenWakeResult.Ok -> {
                if (
                    ScreenWakeActivity.waitUntilReady(
                        service,
                        3_000L,
                        checkCancelled = ::checkExecution,
                    )
                ) {
                    return null
                }
            }
            ScreenWakeResult.SecureKeyguard -> return ScreenReadyPolicy.ERROR_SECURE_OR_FAILED
            else -> Unit
        }

        // Swipe-lock fallback: upward gesture on the lock screen.
        if (keyguard.isKeyguardLocked && !keyguard.isKeyguardSecure) {
            swipeUnlockFallback()
            if (
                ScreenWakeActivity.waitUntilReady(
                    service,
                    4_000L,
                    checkCancelled = ::checkExecution,
                )
            ) {
                return null
            }
        }

        return if (power.isInteractive && !keyguard.isKeyguardLocked) {
            null
        } else {
            ScreenReadyPolicy.ERROR_SECURE_OR_FAILED
        }
    }

    private fun swipeUnlockFallback() {
        // Typical swipe-up unlock on ~1080x2400 panels; harmless if already unlocked.
        swipe(540, 2_000, 540, 700, durationMs = 450)
        sleep(400)
        swipe(540, 1_900, 540, 600, durationMs = 450)
    }

    override fun launchXhs(): Boolean {
        // Must start from the AccessibilityService context. applicationContext /
        // FGS starts are treated as background on HyperOS and silently fail, which
        // surfaces as XHS_NOT_FOREGROUND even though the task "launched".
        val service = M0AccessibilityService.instance
        val starter: Context = service ?: context
        val alreadyForeground = currentLease()?.packageName == XHS_PACKAGE
        val launch = starter.packageManager.getLaunchIntentForPackage(XHS_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (alreadyForeground) {
            // Reset nested note/DM sheets back to the main task root.
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            launch.addFlags(
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        if (startActivityOnMain(starter, launch)) return true
        // Visible trampoline Activity is a second exemption path when direct start is blocked.
        val trampoline =
            Intent(starter, XhsLaunchTrampolineActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
            }
        return startActivityOnMain(starter, trampoline)
    }

    override fun waitForXhsForeground(timeoutMs: Long): Boolean {
        val deadline = saturatingAdd(clock.nowMillis(), timeoutMs.coerceAtLeast(0))
        while (clock.nowMillis() < deadline) {
            checkExecution()
            if (isXhsForeground()) return true
            sleep(350)
        }
        checkExecution()
        return isXhsForeground()
    }

    private fun isXhsForeground(): Boolean {
        if (currentLease()?.packageName == XHS_PACKAGE) return true
        // Focused window may briefly be SystemUI/IME; still accept when an XHS window is active.
        val service = M0AccessibilityService.instance ?: return false
        return service.hasAllowedPackageWindow(XHS_PACKAGE)
    }

    private fun startActivityOnMain(starter: Context, intent: Intent): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val runner = Runnable {
            try {
                starter.startActivity(intent)
                ok.set(true)
            } catch (error: Exception) {
                Log.w(TAG, "startActivity failed for ${intent.component ?: intent.`package`}", error)
            } finally {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runner.run()
        } else {
            android.os.Handler(Looper.getMainLooper()).post(runner)
            awaitCooperatively(latch, 3_000)
        }
        return ok.get()
    }

    companion object {
        private const val TAG = "A11yPlaybookRuntime"
        private const val XHS_PACKAGE = "com.xingin.xhs"
        private const val WAIT_SLICE_MS = 100L
    }

    override fun currentPageHint(): PageHint? = observe()?.pageHint

    override fun currentLease(): TargetLease? = M0AccessibilityService.instance?.currentLease()

    override fun observe(): UiSnapshot? {
        val service = M0AccessibilityService.instance ?: return null
        val lease = service.currentLease() ?: return null
        return service.observe(lease)
    }

    override fun extractComments(maxItems: Int): List<ExtractedComment> {
        val snapshot = observe() ?: return emptyList()
        return extractor.extractComments(snapshot.nodes, maxItems)
    }

    override fun extractInboxThreads(maxItems: Int): List<ExtractedInboxThread> {
        val snapshot = observe() ?: return emptyList()
        return extractor.extractInboxThreads(snapshot.nodes, maxItems)
    }

    override fun extractDmMessages(maxItems: Int): List<ExtractedDmMessage> {
        val snapshot = observe() ?: return emptyList()
        return extractor.extractDmMessages(snapshot.nodes, maxItems)
    }

    override fun extractProfileNotes(maxItems: Int): List<ExtractedProfileNote> {
        val snapshot = observe() ?: return emptyList()
        return extractor.extractProfileNotes(snapshot.nodes, maxItems)
    }

    override fun looksLikeInboxListSurface(): Boolean {
        val snapshot = observe() ?: return false
        return extractor.looksLikeInboxListSurface(snapshot.nodes)
    }

    override fun looksLikeOpenDmThreadSurface(): Boolean {
        val snapshot = observe() ?: return false
        return extractor.looksLikeOpenDmThreadSurface(snapshot.nodes)
    }

    override fun looksLikeCommentsSurface(): Boolean {
        val snapshot = observe() ?: return false
        return extractor.looksLikeCommentsSurface(snapshot.nodes)
    }

    override fun looksLikeProfileSurface(): Boolean =
        SurfaceNavigator.looksLikeProfile(this)

    override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean {
        val service = M0AccessibilityService.instance ?: return false
        val lease = service.currentLease() ?: return false
        val parsed = try {
            StrictSelectorParser.parse(selector)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (FinalActionPolicy.blocks(parsed) && !allowFinal) {
            return false
        }
        return awaitAction(
            service,
            ActionRequest(
                action = M0Action.CLICK,
                expectedLease = lease,
                selector = parsed,
                timeoutMs = timeoutMs,
            ),
            timeoutMs,
        )
    }

    override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean {
        val needle = fragment.trim()
        if (needle.length < 2) return false
        val deadline = saturatingAdd(clock.nowMillis(), timeoutMs.coerceAtLeast(0))
        while (clock.nowMillis() < deadline) {
            checkExecution()
            val snapshot = observe()
            if (snapshot == null) {
                sleep(300)
                continue
            }
            val match = snapshot.nodes.firstOrNull { node ->
                val text = node.text.orEmpty()
                val desc = node.contentDescription.orEmpty()
                (text.contains(needle) || desc.contains(needle)) &&
                    (node.clickable || node.bounds != null)
            }
            if (match == null) {
                sleep(300)
                continue
            }
            val bounds = match.bounds
            if (bounds != null && tap(bounds.centerX, bounds.centerY)) {
                return true
            }
            val label = match.text?.takeIf { it.contains(needle) }
                ?: match.contentDescription?.takeIf { it.contains(needle) }
            if (label != null) {
                if (match.text != null &&
                    click("text=$label", allowFinal = false, timeoutMs = 2_000)
                ) {
                    return true
                }
                if (match.contentDescription != null &&
                    click("contentDescription=$label", allowFinal = false, timeoutMs = 2_000)
                ) {
                    return true
                }
            }
            sleep(300)
        }
        return false
    }

    override fun setText(selector: String, text: String, timeoutMs: Long): Boolean {
        val service = M0AccessibilityService.instance ?: return false
        val lease = service.currentLease() ?: return false
        val parsed = try {
            StrictSelectorParser.parse(selector)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return awaitAction(
            service,
            ActionRequest(
                action = M0Action.SET_TEXT,
                expectedLease = lease,
                selector = parsed,
                text = text,
                timeoutMs = timeoutMs,
            ),
            timeoutMs,
        )
    }

    override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        val service = M0AccessibilityService.instance ?: return false
        val lease = service.currentLease() ?: return false
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val accepted = service.dispatchGesture(
            lease,
            SwipeSpec(x, y, x, y, durationMs.coerceIn(50, 500)),
        ) { completion ->
            ok.set(completion == GestureCompletion.COMPLETED)
            latch.countDown()
        }
        if (!accepted) return false
        return awaitCooperatively(latch, 5_000) && ok.get()
    }

    override fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean {
        val service = M0AccessibilityService.instance ?: return false
        val lease = service.currentLease() ?: return false
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val accepted = service.dispatchGesture(
            lease,
            SwipeSpec(startX, startY, endX, endY, durationMs.coerceIn(50, 2_000)),
        ) { completion ->
            ok.set(completion == GestureCompletion.COMPLETED)
            latch.countDown()
        }
        if (!accepted) return false
        return awaitCooperatively(latch, 5_000) && ok.get()
    }

    override fun globalBack(): Boolean {
        val service = M0AccessibilityService.instance ?: return false
        val lease = service.currentLease() ?: return false
        return service.globalBack(lease)
    }

    override fun sleep(ms: Long) {
        var remaining = ms.coerceAtLeast(0)
        while (remaining > 0) {
            checkExecution()
            val slice = minOf(remaining, WAIT_SLICE_MS)
            sleeper.sleep(slice)
            remaining -= slice
        }
        checkExecution()
    }

    private fun awaitAction(
        service: M0AccessibilityService,
        request: ActionRequest,
        timeoutMs: Long,
    ): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<ActionResult?>(null)
        val handle = service.executeAction(request) {
            result.set(it)
            latch.countDown()
        }
        return try {
            val finished = awaitCooperatively(latch, saturatingAdd(timeoutMs, 2_000))
            if (!finished) handle.cancel()
            finished && result.get()?.status == ActionStatus.SUCCESS
        } catch (cancelled: TaskExecutionCancelledException) {
            handle.cancel()
            throw cancelled
        }
    }

    private fun awaitCooperatively(latch: CountDownLatch, timeoutMs: Long): Boolean {
        val deadline = saturatingAdd(clock.nowMillis(), timeoutMs.coerceAtLeast(0))
        while (latch.count > 0 && clock.nowMillis() < deadline) {
            checkExecution()
            val remaining = (deadline - clock.nowMillis()).coerceAtLeast(1)
            if (latch.await(minOf(remaining, WAIT_SLICE_MS), TimeUnit.MILLISECONDS)) return true
        }
        checkExecution()
        return latch.count == 0L
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

}

class CloudBridgeCoordinator(
    private val context: Context,
    private val heartbeatClient: HeartbeatClient = HeartbeatClient(),
    private val eventReporter: TaskEventSink = TaskEventReporter(),
    private val commandPollClient: CommandPollClient = CommandPollClient(),
    private val heartbeatScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cloud-heartbeat").apply { isDaemon = true }
        },
    private val pollScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cloud-poll").apply { isDaemon = true }
        },
    private val playbookExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "playbook-engine").apply { isDaemon = true }
        },
    private val commandTimeoutExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "playbook-timeout").apply { isDaemon = true }
        },
    private val isSupported: () -> Boolean = { SupportedDeviceGate.isSupported() },
    private val shutdownWaitMs: Long = SHUTDOWN_WAIT_MS,
) {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var pollFuture: ScheduledFuture<*>? = null
    private var mqtt: MqttCommandClient? = null
    private var engine: PlaybookEngine? = null

    fun start() {
        check(!closed.get()) { "CloudBridgeCoordinator is permanently stopped" }
        if (!started.compareAndSet(false, true)) return
        if (!isSupported()) {
            Log.w(TAG, "refuse cloud bridge on unsupported device")
            started.set(false)
            return
        }
        try {
            CloudBridgeConfig.init(context)
            val runtime = AccessibilityPlaybookRuntime(context)
            val engine = PlaybookEngine(
                runtime = runtime,
                registry = DefaultPlaybookRegistry.create(),
                ledger = SharedPreferencesEffectLedger(context),
            ).also { this.engine = it }
            val dispatcher = TaskCommandDispatcher(
                engine = engine,
                reporter = eventReporter,
                preparePublishParamsWithContext = { params, execution ->
                    MediaBridge.preparePublishParams(context, params, execution)
                },
                timeoutScheduler = ExecutorCommandTimeoutScheduler(commandTimeoutExecutor),
            )
            dispatcher.recoverPending()
            // Heartbeat and command poll must not share one thread: a stalled poll/HTTP
            // call would otherwise starve heartbeats and flip the device offline.
            heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
                {
                    if (!started.get()) return@scheduleAtFixedRate
                    try {
                        heartbeatClient.send(
                            a11yBound = runtime.accessibilityAlive(),
                            wifiConnected = networkWifi(context),
                            cellularOk = networkCellular(context),
                            manufacturer = android.os.Build.MANUFACTURER,
                            model = android.os.Build.MODEL,
                        )
                    } catch (error: Exception) {
                        Log.w(TAG, "heartbeat failed", error)
                    }
                },
                0,
                CloudBridgeConfig.HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            pollFuture = pollScheduler.scheduleAtFixedRate(
                {
                    if (!started.get()) return@scheduleAtFixedRate
                    try {
                        val payloads = commandPollClient.poll()
                        for (payload in payloads) {
                            if (!started.get()) break
                            val receivedAt = SystemClock.elapsedRealtime()
                            playbookExecutor.execute {
                                try {
                                    dispatcher.handleMqttPayload(payload, receivedAt)
                                } catch (error: Exception) {
                                    Log.e(TAG, "dispatch failed", error)
                                }
                            }
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "command poll failed", error)
                    }
                },
                2_000,
                CloudBridgeConfig.COMMAND_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            mqtt = MqttCommandClient(
                onCommand = { payload ->
                    if (!started.get()) return@MqttCommandClient
                    val receivedAt = SystemClock.elapsedRealtime()
                    playbookExecutor.execute {
                        try {
                            dispatcher.handleMqttPayload(payload, receivedAt)
                        } catch (error: Exception) {
                            Log.e(TAG, "dispatch failed", error)
                        }
                    }
                },
            ).also { it.start() }
            CloudBridgeStatusHub.update {
                it.copy(
                    bridgeRunning = true,
                    controlPlaneHost = CloudBridgeConfig.CONTROL_PLANE_BASE_URL,
                )
            }
            Log.i(TAG, "cloud bridge started for ${CloudBridgeConfig.DEVICE_ID}")
        } catch (error: Exception) {
            Log.e(TAG, "cloud bridge start failed", error)
            CloudBridgeStatusHub.update { it.copy(bridgeRunning = false) }
            stop()
            throw error
        }
    }

    fun stop(): Boolean {
        closed.set(true)
        started.set(false)
        CloudBridgeStatusHub.update { it.copy(bridgeRunning = false) }
        heartbeatFuture?.cancel(true)
        heartbeatFuture = null
        pollFuture?.cancel(true)
        pollFuture = null
        engine?.cancelActive()
        heartbeatClient.cancelActive()
        (eventReporter as? CloudNetworkClient)?.cancelActive()
        commandPollClient.cancelActive()
        val mqttStopped = mqtt?.stop() ?: true
        mqtt = null
        engine = null
        playbookExecutor.shutdownNow()
        heartbeatScheduler.shutdownNow()
        pollScheduler.shutdownNow()
        commandTimeoutExecutor.shutdownNow()
        val playbookStopped = awaitTermination(playbookExecutor, "playbook executor")
        val heartbeatStopped = awaitTermination(heartbeatScheduler, "cloud heartbeat scheduler")
        val pollStopped = awaitTermination(pollScheduler, "cloud poll scheduler")
        val timeoutStopped = awaitTermination(commandTimeoutExecutor, "playbook timeout scheduler")
        val clean = mqttStopped && playbookStopped && heartbeatStopped && pollStopped && timeoutStopped
        if (clean) {
            Log.i(TAG, "cloud bridge stopped")
        } else {
            Log.e(TAG, "cloud bridge entered permanently stopped state after incomplete shutdown")
        }
        return clean
    }

    internal fun isPermanentlyStopped(): Boolean = closed.get()

    private fun awaitTermination(executor: ExecutorService, label: String): Boolean =
        try {
            if (!executor.awaitTermination(shutdownWaitMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "$label did not terminate within ${shutdownWaitMs}ms")
                false
            } else {
                true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "interrupted while stopping $label")
            false
        }

    companion object {
        private const val TAG = "CloudBridge"
        private const val SHUTDOWN_WAIT_MS = 1_000L

        private fun networkWifi(context: Context): Boolean? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        private fun networkCellular(context: Context): Boolean? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
    }
}

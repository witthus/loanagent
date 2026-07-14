package com.loanagent.agent

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AccessibilityPlaybookRuntime(
    private val context: Context,
) : PlaybookRuntime {
    private val extractor = ContentExtractors()

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

        val wakeResult = runCatching {
            ScreenWakeActivity.request(service, timeoutMs.coerceAtLeast(5_000L))
        }.getOrElse {
            Log.w(TAG, "ensureScreenReady wake failed", it)
            ScreenWakeResult.StartFailed
        }

        when (wakeResult) {
            ScreenWakeResult.Ok -> {
                if (ScreenWakeActivity.waitUntilReady(service, 3_000L)) return null
            }
            ScreenWakeResult.SecureKeyguard -> return ScreenReadyPolicy.ERROR_SECURE_OR_FAILED
            else -> Unit
        }

        // Swipe-lock fallback: upward gesture on the lock screen.
        if (keyguard.isKeyguardLocked && !keyguard.isKeyguardSecure) {
            swipeUnlockFallback()
            if (ScreenWakeActivity.waitUntilReady(service, 4_000L)) return null
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
        SystemClock.sleep(400)
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
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isXhsForeground()) return true
            SystemClock.sleep(350)
        }
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
            latch.await(3, TimeUnit.SECONDS)
        }
        return ok.get()
    }

    companion object {
        private const val TAG = "A11yPlaybookRuntime"
        private const val XHS_PACKAGE = "com.xingin.xhs"
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
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = observe()
            if (snapshot == null) {
                SystemClock.sleep(300)
                continue
            }
            val match = snapshot.nodes.firstOrNull { node ->
                val text = node.text.orEmpty()
                val desc = node.contentDescription.orEmpty()
                (text.contains(needle) || desc.contains(needle)) &&
                    (node.clickable || node.bounds != null)
            }
            if (match == null) {
                SystemClock.sleep(300)
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
            SystemClock.sleep(300)
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
        return latch.await(5, TimeUnit.SECONDS) && ok.get()
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
        return latch.await(5, TimeUnit.SECONDS) && ok.get()
    }

    override fun globalBack(): Boolean {
        val service = M0AccessibilityService.instance ?: return false
        val lease = service.currentLease() ?: return false
        return service.globalBack(lease)
    }

    override fun sleep(ms: Long) {
        SystemClock.sleep(ms.coerceAtLeast(0))
    }

    private fun awaitAction(
        service: M0AccessibilityService,
        request: ActionRequest,
        timeoutMs: Long,
    ): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<ActionResult?>(null)
        service.executeAction(request) {
            result.set(it)
            latch.countDown()
        }
        val finished = latch.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
        return finished && result.get()?.status == ActionStatus.SUCCESS
    }
}

class CloudBridgeCoordinator(
    private val context: Context,
    private val heartbeatClient: HeartbeatClient = HeartbeatClient(),
    private val eventReporter: TaskEventReporter = TaskEventReporter(),
    private val commandPollClient: CommandPollClient = CommandPollClient(),
) {
    private val started = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cloud-bridge").apply { isDaemon = true }
    }
    private val playbookExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "playbook-engine").apply { isDaemon = true }
    }
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var pollFuture: ScheduledFuture<*>? = null
    private var mqtt: MqttCommandClient? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!SupportedDeviceGate.isSupported()) {
            Log.w(TAG, "refuse cloud bridge on unsupported device")
            started.set(false)
            return
        }
        CloudBridgeConfig.init(context)
        val runtime = AccessibilityPlaybookRuntime(context)
        val engine = PlaybookEngine(
            runtime = runtime,
            registry = DefaultPlaybookRegistry.create(),
            ledger = SharedPreferencesEffectLedger(context),
        )
        val dispatcher = TaskCommandDispatcher(
            engine = engine,
            reporter = eventReporter,
            context = context,
        )
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            {
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
        pollFuture = scheduler.scheduleAtFixedRate(
            {
                try {
                    val payloads = commandPollClient.poll()
                    for (payload in payloads) {
                        playbookExecutor.execute {
                            try {
                                dispatcher.handleMqttPayload(payload)
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
                playbookExecutor.execute {
                    try {
                        dispatcher.handleMqttPayload(payload)
                    } catch (error: Exception) {
                        Log.e(TAG, "dispatch failed", error)
                    }
                }
            },
        ).also { it.start() }
        Log.i(TAG, "cloud bridge started for ${CloudBridgeConfig.DEVICE_ID}")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        heartbeatFuture?.cancel(true)
        heartbeatFuture = null
        pollFuture?.cancel(true)
        pollFuture = null
        mqtt?.stop()
        mqtt = null
        playbookExecutor.shutdownNow()
        Log.i(TAG, "cloud bridge stopped")
    }

    companion object {
        private const val TAG = "CloudBridge"

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

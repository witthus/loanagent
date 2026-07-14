package com.loanagent.agent

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class ScreenWakeResult {
    Ok,
    SecureKeyguard,
    DismissFailed,
    Timeout,
    StartFailed,
}

/**
 * Turns the screen on and dismisses a non-secure (none / swipe) keyguard.
 * Started from [M0AccessibilityService] so HyperOS treats it as an a11y-driven start.
 */
class ScreenWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWakeFlags()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null) {
            finishWith(ScreenWakeResult.DismissFailed)
            return
        }
        if (keyguard.isKeyguardSecure && keyguard.isKeyguardLocked) {
            finishWith(ScreenWakeResult.SecureKeyguard)
            return
        }
        if (!keyguard.isKeyguardLocked) {
            // Still turn screen on; then done.
            finishWith(ScreenWakeResult.Ok)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        finishWith(ScreenWakeResult.Ok)
                    }

                    override fun onDismissCancelled() {
                        finishWith(ScreenWakeResult.DismissFailed)
                    }

                    override fun onDismissError() {
                        finishWith(ScreenWakeResult.DismissFailed)
                    }
                },
            )
            // If the callback never fires (OEM quirk), time out via companion waiter.
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            finishWith(ScreenWakeResult.Ok)
        }
    }

    private fun applyWakeFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
    }

    private fun finishWith(result: ScreenWakeResult) {
        Companion.complete(result)
        if (!isFinishing) finish()
    }

    companion object {
        private const val TAG = "ScreenWakeActivity"
        private val pendingLatch = AtomicReference<CountDownLatch?>(null)
        private val pendingResult = AtomicReference<ScreenWakeResult?>(null)

        fun request(starter: Context, timeoutMs: Long): ScreenWakeResult {
            // Must run off the main thread: we block on the dismiss callback latch, while
            // Activity.onCreate / requestDismissKeyguard need the main looper.
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "ScreenWakeActivity.request must not run on the main thread"
            }
            acquireBriefWakeLock(starter)
            val latch = CountDownLatch(1)
            pendingResult.set(null)
            pendingLatch.set(latch)
            val intent =
                Intent(starter, ScreenWakeActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    )
                }
            val started = CountDownLatch(1)
            val startOk = java.util.concurrent.atomic.AtomicBoolean(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    starter.startActivity(intent)
                    startOk.set(true)
                } catch (error: Exception) {
                    Log.w(TAG, "failed to start ScreenWakeActivity", error)
                    complete(ScreenWakeResult.StartFailed)
                } finally {
                    started.countDown()
                }
            }
            started.await(3, TimeUnit.SECONDS)
            if (!startOk.get()) {
                pendingLatch.compareAndSet(latch, null)
                return pendingResult.get() ?: ScreenWakeResult.StartFailed
            }
            val finished = latch.await(timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            return if (!finished) {
                pendingLatch.compareAndSet(latch, null)
                ScreenWakeResult.Timeout
            } else {
                pendingResult.get() ?: ScreenWakeResult.DismissFailed
            }
        }

        fun complete(result: ScreenWakeResult) {
            pendingResult.set(result)
            pendingLatch.getAndSet(null)?.countDown()
        }

        fun waitUntilReady(
            context: Context,
            timeoutMs: Long,
        ): Boolean {
            val power = context.getSystemService(PowerManager::class.java) ?: return false
            val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (power.isInteractive && !keyguard.isKeyguardLocked) return true
                SystemClock.sleep(200)
            }
            return power.isInteractive && !keyguard.isKeyguardLocked
        }

        @Suppress("DEPRECATION")
        private fun acquireBriefWakeLock(context: Context) {
            try {
                val power = context.getSystemService(PowerManager::class.java) ?: return
                if (power.isInteractive) return
                val wakeLock =
                    power.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "loanagent:screenwake",
                    )
                wakeLock.setReferenceCounted(false)
                wakeLock.acquire(3_000L)
            } catch (error: Exception) {
                Log.w(TAG, "wake lock failed", error)
            }
        }
    }
}

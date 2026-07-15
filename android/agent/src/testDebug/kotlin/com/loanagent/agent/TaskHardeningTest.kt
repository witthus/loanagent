package com.loanagent.agent

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskHardeningTest {
    @Test
    fun nonIdempotentDoesNotExecuteWhenExecutingReportFails() {
        val runs = AtomicInteger()
        val ledger = MemoryEffectLedger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId, effectCommitted = true)
            },
            ledger = ledger,
            reporter = TaskEventSink { _, status, _, _ ->
                reports += status
                status != "executing"
            },
        )

        val result = dispatcher.handleMqttPayload(commandJson("executing-fail", "non_idempotent"))

        assertEquals(0, runs.get())
        assertEquals("failed", result?.status)
        assertEquals("EXECUTING_REPORT_FAILED", result?.errorCode)
        assertEquals(listOf("executing", "failed"), reports)
        assertEquals(LedgerStage.FINAL, ledger.entry("executing-fail")?.stage)
    }

    @Test
    fun finalReportFailureIsDurableAndRedeliveryOnlyReReports() {
        val runs = AtomicInteger()
        val reports = mutableListOf<String>()
        var failFirstFinal = true
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId)
            },
            reporter = TaskEventSink { _, status, _, _ ->
                reports += status
                if (status == "succeeded" && failFirstFinal) {
                    failFirstFinal = false
                    false
                } else {
                    true
                }
            },
        )
        val payload = commandJson("final-retry", "idempotent")

        dispatcher.handleMqttPayload(payload)
        dispatcher.handleMqttPayload(payload)

        assertEquals(1, runs.get())
        assertEquals(listOf("executing", "succeeded", "succeeded"), reports)
    }

    @Test
    fun startupRecoveryReReportsPendingWithoutCommandRedelivery() {
        val ledger = MemoryEffectLedger()
        val started = command("startup-started", EffectClass.NON_IDEMPOTENT)
        val committed = command("startup-committed", EffectClass.NON_IDEMPOTENT)
        val final = command("startup-final", EffectClass.READONLY)
        assertEquals(LedgerClaim.Acquired, ledger.claim(started))
        ledger.release(started.taskId)
        assertEquals(LedgerClaim.Acquired, ledger.claim(committed))
        assertTrue(
            ledger.storeEffectCommitted(
                committed,
                PlaybookResult.succeeded(committed.taskId, effectCommitted = true),
            ),
        )
        ledger.release(committed.taskId)
        assertEquals(LedgerClaim.Acquired, ledger.claim(final))
        assertTrue(ledger.storeFinal(final, PlaybookResult.failed(final.taskId, "SAVED_FAILURE")))
        ledger.release(final.taskId)
        val runs = AtomicInteger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId)
            },
            ledger = ledger,
            reporter = recordingReporter(reports),
        )

        dispatcher.recoverPending()

        assertEquals(0, runs.get())
        assertEquals(listOf("unknown", "effect_committed", "succeeded", "failed"), reports)
        assertTrue(ledger.pendingRecovery().isEmpty())
        assertTrue(ledger.entry(started.taskId)?.reported == true)
        assertTrue(ledger.entry(committed.taskId)?.reported == true)
        assertTrue(ledger.entry(final.taskId)?.reported == true)
    }

    @Test
    fun corruptLedgerRemainsPoisonedAndIsActivelyReportedAtStartup() {
        val context = RuntimeEnvironment.getApplication()
        val prefsName = "task-hardening-startup-corrupt"
        val taskId = "startup-corrupt"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("task:$taskId", "{broken")
            .commit()
        val ledger = SharedPreferencesEffectLedger(context, prefsName)
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                throw AssertionError("corrupt task must never execute: ${command.taskId}")
            },
            ledger = ledger,
            reporter = recordingReporter(reports),
        )

        dispatcher.recoverPending()

        assertEquals(listOf("unknown"), reports)
        assertEquals(LedgerStage.CORRUPT, ledger.entry(taskId)?.stage)
        assertTrue(ledger.claim(command(taskId, EffectClass.NON_IDEMPOTENT)) is LedgerClaim.Corrupt)
        ledger.release(taskId)
    }

    @Test
    fun finalReportFailureIsActivelyRetriedOnStartupWithoutReExecution() {
        val ledger = MemoryEffectLedger()
        val runs = AtomicInteger()
        val first = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId)
            },
            ledger = ledger,
            reporter = TaskEventSink { _, status, _, _ -> status != "succeeded" },
        )
        first.handleMqttPayload(commandJson("startup-final-retry", "readonly"))
        assertEquals(1, runs.get())
        assertEquals(LedgerStage.FINAL, ledger.entry("startup-final-retry")?.stage)
        assertFalse(ledger.entry("startup-final-retry")?.reported == true)
        val recoveredReports = mutableListOf<String>()
        val restarted = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId)
            },
            ledger = ledger,
            reporter = recordingReporter(recoveredReports),
        )

        restarted.recoverPending()

        assertEquals(1, runs.get())
        assertEquals(listOf("succeeded"), recoveredReports)
        assertTrue(ledger.entry("startup-final-retry")?.reported == true)
    }

    @Test
    fun concurrentMqttAndHttpDeliveryClaimsOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runs = AtomicInteger()
        val reports = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                entered.countDown()
                release.await()
                PlaybookResult.succeeded(command.taskId)
            },
            reporter = TaskEventSink { _, status, _, _ ->
                reports += status
                true
            },
        )
        val pool = Executors.newFixedThreadPool(2)
        val payload = commandJson("dual-channel", "idempotent")

        val mqtt = pool.submit<PlaybookResult?> { dispatcher.handleMqttPayload(payload) }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val http = pool.submit<PlaybookResult?> { dispatcher.handleMqttPayload(payload) }
        assertNull(http.get(1, TimeUnit.SECONDS))
        release.countDown()
        assertEquals("succeeded", mqtt.get(1, TimeUnit.SECONDS)?.status)
        pool.shutdownNow()

        assertEquals(1, runs.get())
        assertEquals(listOf("executing", "succeeded"), reports)
    }

    @Test
    fun sharedPreferencesLedgerRecoversStartedCommittedAndFinal() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("task-hardening-recovery", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val command = command("recover", EffectClass.NON_IDEMPOTENT)
        val started = SharedPreferencesEffectLedger(context, "task-hardening-recovery")
        assertEquals(LedgerClaim.Acquired, started.claim(command))
        started.release(command.taskId)

        val recoveredStarted = SharedPreferencesEffectLedger(context, "task-hardening-recovery")
        assertEquals(
            LedgerStage.STARTED,
            (recoveredStarted.claim(command) as LedgerClaim.Existing).entry.stage,
        )
        recoveredStarted.release(command.taskId)
        val committedResult = PlaybookResult.succeeded(
            command.taskId,
            effectCommitted = true,
            resultPayload = mapOf("kind" to "publish", "title_summary" to "saved"),
        )
        assertTrue(recoveredStarted.storeEffectCommitted(command, committedResult))
        recoveredStarted.release(command.taskId)

        val recoveredCommitted = SharedPreferencesEffectLedger(context, "task-hardening-recovery")
        val committedEntry = (recoveredCommitted.claim(command) as LedgerClaim.Existing).entry
        assertEquals(LedgerStage.EFFECT_COMMITTED, committedEntry.stage)
        assertEquals(committedResult, committedEntry.result)
        recoveredCommitted.release(command.taskId)
        assertTrue(recoveredCommitted.storeFinal(command, committedResult))
        recoveredCommitted.release(command.taskId)

        val recoveredFinal = SharedPreferencesEffectLedger(context, "task-hardening-recovery")
        val finalEntry = (recoveredFinal.claim(command) as LedgerClaim.Existing).entry
        assertEquals(LedgerStage.FINAL, finalEntry.stage)
        assertEquals(committedResult, finalEntry.result)
    }

    @Test
    fun recoveredStartedAndCommittedNeverReExecuteNonIdempotentTask() {
        val ledger = MemoryEffectLedger()
        val command = command("recovered-started", EffectClass.NON_IDEMPOTENT)
        assertEquals(LedgerClaim.Acquired, ledger.claim(command))
        ledger.release(command.taskId)
        val runs = AtomicInteger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { playbookCommand, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(playbookCommand.taskId, effectCommitted = true)
            },
            ledger = ledger,
            reporter = recordingReporter(reports),
        )

        val startedResult = dispatcher.handleMqttPayload(
            commandJson("recovered-started", "non_idempotent"),
        )

        assertEquals(0, runs.get())
        assertEquals("unknown", startedResult?.status)
        assertEquals("EFFECT_UNKNOWN", startedResult?.errorCode)
        assertEquals(listOf("unknown"), reports)

        val committedLedger = MemoryEffectLedger()
        val committedCommand = command("recovered-committed", EffectClass.NON_IDEMPOTENT)
        assertEquals(LedgerClaim.Acquired, committedLedger.claim(committedCommand))
        val saved = PlaybookResult.succeeded(committedCommand.taskId, effectCommitted = true)
        assertTrue(committedLedger.storeEffectCommitted(committedCommand, saved))
        committedLedger.release(committedCommand.taskId)
        val committedReports = mutableListOf<String>()
        val committedDispatcher = dispatcher(
            playbook = Playbook { playbookCommand, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(playbookCommand.taskId, effectCommitted = true)
            },
            ledger = committedLedger,
            reporter = recordingReporter(committedReports),
        )

        val committedResult = committedDispatcher.handleMqttPayload(
            commandJson("recovered-committed", "non_idempotent"),
        )

        assertEquals(0, runs.get())
        assertEquals(saved, committedResult)
        assertEquals(listOf("effect_committed", "succeeded"), committedReports)
    }

    @Test
    fun recoveredRepeatableStartedTaskEndsFailedWithoutLoopingOrReExecution() {
        for (effectClass in listOf(EffectClass.READONLY, EffectClass.IDEMPOTENT)) {
            val taskId = "repeatable-${effectClass.name.lowercase()}"
            val ledger = MemoryEffectLedger()
            val command = command(taskId, effectClass)
            assertEquals(LedgerClaim.Acquired, ledger.claim(command))
            ledger.release(taskId)
            val runs = AtomicInteger()
            val dispatcher = dispatcher(
                playbook = Playbook { playbookCommand, _ ->
                    runs.incrementAndGet()
                    PlaybookResult.succeeded(playbookCommand.taskId)
                },
                ledger = ledger,
            )

            val first = dispatcher.handleMqttPayload(
                commandJson(taskId, effectClass.name.lowercase()),
            )
            val second = dispatcher.handleMqttPayload(
                commandJson(taskId, effectClass.name.lowercase()),
            )

            assertEquals(0, runs.get())
            assertEquals("failed", first?.status)
            assertEquals("EXECUTION_INTERRUPTED", first?.errorCode)
            assertEquals(first, second)
        }
    }

    @Test
    fun recoveredFinalOnlyReReportsSavedResult() {
        val ledger = MemoryEffectLedger()
        val command = command("recovered-final", EffectClass.READONLY)
        assertEquals(LedgerClaim.Acquired, ledger.claim(command))
        val saved = PlaybookResult.succeeded(command.taskId)
        assertTrue(ledger.storeFinal(command, saved))
        ledger.release(command.taskId)
        val runs = AtomicInteger()
        val payloads = mutableListOf<Map<String, Any?>?>()
        val dispatcher = dispatcher(
            playbook = Playbook { playbookCommand, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(playbookCommand.taskId)
            },
            ledger = ledger,
            reporter = TaskEventSink { _, _, _, resultPayload ->
                payloads += resultPayload
                true
            },
        )

        assertEquals(saved, dispatcher.handleMqttPayload(commandJson("recovered-final", "readonly")))
        assertEquals(0, runs.get())
        assertEquals(listOf(null), payloads)
    }

    @Test
    fun readonlyAndIdempotentTimeoutFailWithoutRunningLaterSteps() {
        for (effectClass in listOf("readonly", "idempotent")) {
            val scheduler = ManualCommandTimeoutScheduler()
            val laterSteps = AtomicInteger()
            val runtime = HookRuntime(onSleep = scheduler::fire)
            val reports = mutableListOf<String>()
            val dispatcher = dispatcher(
                playbook = Playbook { command, guardedRuntime ->
                    guardedRuntime.sleep(1)
                    laterSteps.incrementAndGet()
                    PlaybookResult.succeeded(command.taskId)
                },
                runtime = runtime,
                reporter = recordingReporter(reports),
                timeoutScheduler = scheduler,
            )

            val result = dispatcher.handleMqttPayload(commandJson("timeout-$effectClass", effectClass))

            assertEquals("failed", result?.status)
            assertEquals("EXECUTION_TIMEOUT", result?.errorCode)
            assertEquals(0, laterSteps.get())
            assertEquals(listOf("executing", "failed"), reports)
        }
    }

    @Test
    fun commandThatExpiredWhileQueuedNeverStartsPlaybook() {
        val clock = MutableMonotonicClock(now = 1_001)
        val runs = AtomicInteger()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId)
            },
            clock = clock,
        )

        val result = dispatcher.handleMqttPayload(
            commandJson("expired-in-queue", "readonly"),
            receivedAtMillis = 0,
        )

        assertEquals(0, runs.get())
        assertEquals("failed", result?.status)
        assertEquals("EXECUTION_TIMEOUT", result?.errorCode)
    }

    @Test
    fun nonIdempotentTimeoutAfterFinalActionIsUnknown() {
        val scheduler = ManualCommandTimeoutScheduler()
        val runtime = HookRuntime(onSleep = scheduler::fire)
        val dispatcher = dispatcher(
            playbook = Playbook { command, guardedRuntime ->
                guardedRuntime.beginSideEffect()
                guardedRuntime.click("text=发送", allowFinal = true)
                guardedRuntime.sleep(1)
                PlaybookResult.succeeded(command.taskId, effectCommitted = true)
            },
            runtime = runtime,
            timeoutScheduler = scheduler,
        )

        val result = dispatcher.handleMqttPayload(commandJson("timeout-effect", "non_idempotent"))

        assertEquals("unknown", result?.status)
        assertEquals("EFFECT_UNKNOWN", result?.errorCode)
        assertFalse(result?.effectCommitted == true)
    }

    @Test
    fun completionWinsWhenTimeoutCallbackIsCancelledAtBoundary() {
        val scheduler = ManualCommandTimeoutScheduler()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ -> PlaybookResult.succeeded(command.taskId) },
            reporter = recordingReporter(reports),
            timeoutScheduler = scheduler,
        )

        val result = dispatcher.handleMqttPayload(commandJson("timeout-race", "readonly"))
        scheduler.fire()

        assertEquals("succeeded", result?.status)
        assertEquals(listOf("executing", "succeeded"), reports)
        assertFalse(scheduler.hasPendingTask())
    }

    @Test
    fun completionAndTimeoutUseSingleAtomicWinner() {
        repeat(100) { generation ->
            val context = TaskExecutionContext(
                generation = generation.toLong(),
                deadlineMillis = Long.MAX_VALUE,
                clock = SystemMonotonicClock,
            )
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val completed = pool.submit<Boolean> {
                start.await()
                context.complete()
            }
            val timedOut = pool.submit {
                start.await()
                context.timeout(generation.toLong())
            }

            start.countDown()
            timedOut.get(1, TimeUnit.SECONDS)
            val completionWon = completed.get(1, TimeUnit.SECONDS)
            pool.shutdownNow()

            assertEquals(
                if (completionWon) null else TaskCancellationReason.TIMEOUT,
                context.cancellationReason(),
            )
        }
    }

    @Test
    fun timeoutDuringExecutingReportStopsBeforePlaybookAndReportsTimeout() {
        val scheduler = ManualCommandTimeoutScheduler()
        val runs = AtomicInteger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId)
            },
            reporter = TaskEventSink { _, status, _, _ ->
                reports += status
                if (status == "executing") scheduler.fire()
                true
            },
            timeoutScheduler = scheduler,
        )

        val result = dispatcher.handleMqttPayload(commandJson("timeout-executing-report", "readonly"))

        assertEquals(0, runs.get())
        assertEquals("EXECUTION_TIMEOUT", result?.errorCode)
        assertEquals(listOf("executing", "failed"), reports)
    }

    @Test
    fun timeoutDuringMediaPreparationStopsBeforePlaybookAndReportsTimeout() {
        val scheduler = ManualCommandTimeoutScheduler()
        val runs = AtomicInteger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbookName = "publish_note",
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId, effectCommitted = true)
            },
            reporter = recordingReporter(reports),
            timeoutScheduler = scheduler,
            preparePublishParams = { params ->
                scheduler.fire()
                params
            },
        )

        val result = dispatcher.handleMqttPayload(
            commandJson("timeout-media", "non_idempotent", playbookName = "publish_note"),
        )

        assertEquals(0, runs.get())
        assertEquals("EXECUTION_TIMEOUT", result?.errorCode)
        assertEquals(listOf("executing", "failed"), reports)
    }

    @Test
    fun timeoutAfterDurableFinalBeforeCompletionCasLeavesOriginalPending() {
        val scheduler = ManualCommandTimeoutScheduler()
        val ledger = TimeoutOnFirstFinalLedger(scheduler)
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ -> PlaybookResult.succeeded(command.taskId) },
            ledger = ledger,
            reporter = recordingReporter(reports),
            timeoutScheduler = scheduler,
        )

        val result = dispatcher.handleMqttPayload(commandJson("timeout-final-store", "readonly"))

        // Timeout wins before complete() CAS: keep durable succeeded, do not rewrite or report.
        assertEquals("succeeded", result?.status)
        assertEquals("succeeded", ledger.entry("timeout-final-store")?.result?.status)
        assertEquals(LedgerStage.FINAL, ledger.entry("timeout-final-store")?.stage)
        assertEquals(listOf("executing"), reports)
        assertFalse(ledger.entry("timeout-final-store")?.reported == true)
    }

    @Test
    fun screenReadinessLoopChecksCancellationBeforeSleeping() {
        val sleeps = AtomicInteger()

        assertThrows(TaskExecutionCancelledException::class.java) {
            ScreenWakeActivity.waitUntilReady(
                context = RuntimeEnvironment.getApplication(),
                timeoutMs = 5_000,
                clock = MutableMonotonicClock(0),
                sleeper = PollSleeper { sleeps.incrementAndGet() },
                checkCancelled = {
                    throw TaskExecutionCancelledException(TaskCancellationReason.TIMEOUT)
                },
            )
        }

        assertEquals(0, sleeps.get())
    }

    @Test
    fun terminalReportOnlyRunsAfterCompletionCasWins() {
        val scheduler = ManualCommandTimeoutScheduler()
        val ledger = MemoryEffectLedger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ -> PlaybookResult.succeeded(command.taskId) },
            ledger = ledger,
            reporter = object : TaskEventSink {
                override fun report(
                    taskId: String,
                    status: String,
                    errorCode: String?,
                    resultPayload: Map<String, Any?>?,
                ): Boolean {
                    reports += status
                    return true
                }

                override fun report(
                    taskId: String,
                    status: String,
                    errorCode: String?,
                    resultPayload: Map<String, Any?>?,
                    execution: TaskExecutionContext,
                ): Boolean {
                    // Timeout after complete() CAS must not cancel reporting.
                    if (status == "succeeded") {
                        scheduler.fire()
                    }
                    return report(taskId, status, errorCode, resultPayload)
                }
            },
            timeoutScheduler = scheduler,
        )

        val result = dispatcher.handleMqttPayload(commandJson("complete-before-report", "readonly"))

        assertEquals("succeeded", result?.status)
        assertEquals(listOf("executing", "succeeded"), reports)
        assertTrue(ledger.entry("complete-before-report")?.reported == true)
        assertFalse(scheduler.hasPendingTask())
    }

    @Test
    fun lateTimeoutCallbackCannotCancelNextTaskOnReusedThread() {
        val scheduler = RetainingCommandTimeoutScheduler()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ -> PlaybookResult.succeeded(command.taskId) },
            reporter = recordingReporter(reports),
            timeoutScheduler = scheduler,
        )

        assertEquals(
            "succeeded",
            dispatcher.handleMqttPayload(commandJson("generation-one", "readonly"))?.status,
        )
        assertEquals(
            "succeeded",
            dispatcher.handleMqttPayload(commandJson("generation-two", "readonly"))?.status,
        )
        scheduler.fire(index = 0)

        assertEquals(
            listOf("executing", "succeeded", "executing", "succeeded"),
            reports,
        )
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun interruptedExecutionDoesNotPolluteReusedCommandThread() {
        val interruptedOnSecondRun = AtomicReference<Boolean>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                if (command.taskId == "interrupt-first") {
                    Thread.currentThread().interrupt()
                    throw InterruptedException("cancel current command")
                }
                interruptedOnSecondRun.set(Thread.currentThread().isInterrupted)
                PlaybookResult.succeeded(command.taskId)
            },
        )

        try {
            val first = dispatcher.handleMqttPayload(commandJson("interrupt-first", "readonly"))
            val second = dispatcher.handleMqttPayload(commandJson("interrupt-second", "readonly"))

            assertEquals("EXECUTION_INTERRUPTED", first?.errorCode)
            assertEquals("succeeded", second?.status)
            assertEquals(false, interruptedOnSecondRun.get())
            assertFalse(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun mqttStartAndStopAreSerializedWithoutOrphanWorker() {
        val socket = BlockingConnectSocket()
        val client = MqttCommandClient(
            onCommand = {},
            socketFactory = { socket },
            joinTimeoutMs = 1_000,
        )
        client.start()
        assertTrue(socket.connectEntered.await(1, TimeUnit.SECONDS))
        assertTrue(client.workerAlive())
        assertTrue(client.stop())
        assertFalse(client.workerAlive())
        // A later start after a completed stop is intentional, not an orphan from racing stop.
        client.start()
        assertTrue(client.workerAlive())
        assertTrue(client.stop())
        assertFalse(client.workerAlive())
    }

    @Test
    fun mqttZeroJoinTimeoutDoesNotHangForever() {
        val socket = BlockingConnectSocket()
        val client = MqttCommandClient(
            onCommand = {},
            socketFactory = { socket },
            joinTimeoutMs = 0,
        )
        client.start()
        assertTrue(socket.connectEntered.await(1, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()
        client.stop()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        // joinTimeoutMs<=0 must skip Thread.join(0) forever-wait semantics.
        assertTrue("stop() blocked for ${elapsedMs}ms with joinTimeoutMs=0", elapsedMs < 500)
    }

    @Test
    fun allowFinalAuthorizationWithoutEffectBoundaryTimesOutAsFailed() {
        val scheduler = ManualCommandTimeoutScheduler()
        val dispatcher = dispatcher(
            playbook = Playbook { command, runtime ->
                runtime.click("text=打开发布入口", allowFinal = true)
                runtime.sleep(1)
                PlaybookResult.succeeded(command.taskId)
            },
            runtime = HookRuntime(onSleep = scheduler::fire),
            timeoutScheduler = scheduler,
        )

        val result = dispatcher.handleMqttPayload(
            commandJson("authorized-not-committed", "non_idempotent"),
        )

        assertEquals("failed", result?.status)
        assertEquals("EXECUTION_TIMEOUT", result?.errorCode)
    }

    @Test
    fun explicitEffectBoundaryMakesUncertainTimeoutUnknown() {
        val scheduler = ManualCommandTimeoutScheduler()
        val dispatcher = dispatcher(
            playbook = Playbook { command, runtime ->
                runtime.beginSideEffect()
                runtime.sleep(1)
                PlaybookResult.succeeded(command.taskId)
            },
            runtime = HookRuntime(onSleep = scheduler::fire),
            timeoutScheduler = scheduler,
        )

        val result = dispatcher.handleMqttPayload(
            commandJson("boundary-timeout", "non_idempotent"),
        )

        assertEquals("unknown", result?.status)
        assertEquals("EFFECT_UNKNOWN", result?.errorCode)
    }

    @Test
    fun missingAndInvalidEffectClassNeverExecute() {
        for (effectJson in listOf("", ""","effect_class":"unsafe"""")) {
            val ledger = MemoryEffectLedger()
            val runs = AtomicInteger()
            val reports = mutableListOf<String>()
            val dispatcher = dispatcher(
                playbook = Playbook { command, _ ->
                    runs.incrementAndGet()
                    PlaybookResult.succeeded(command.taskId)
                },
                ledger = ledger,
                reporter = recordingReporter(reports),
            )
            val taskId = "invalid-effect-${reports.size}-${effectJson.length}"
            val payload =
                """{"task_id":"$taskId","playbook":"test@1.0","timeout_sec":1$effectJson}"""

            val result = dispatcher.handleMqttPayload(payload)

            assertEquals(0, runs.get())
            assertEquals("failed", result?.status)
            assertEquals("INVALID_EFFECT_CLASS", result?.errorCode)
            assertEquals(listOf("failed"), reports)
            assertEquals(LedgerStage.FINAL, ledger.entry(taskId)?.stage)
        }
    }

    @Test
    fun effectCommittedIsDurableBeforeCheckpointAndFinalIsDurableBeforeTerminalReport() {
        val ledger = MemoryEffectLedger()
        val observedStages = mutableListOf<LedgerStage?>()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                PlaybookResult.succeeded(command.taskId, effectCommitted = true)
            },
            ledger = ledger,
            reporter = TaskEventSink { taskId, status, _, _ ->
                reports += status
                observedStages += ledger.entry(taskId)?.stage
                true
            },
        )

        dispatcher.handleMqttPayload(commandJson("effect-order", "non_idempotent"))

        assertEquals(listOf("executing", "effect_committed", "succeeded"), reports)
        assertEquals(
            listOf(LedgerStage.STARTED, LedgerStage.EFFECT_COMMITTED, LedgerStage.FINAL),
            observedStages,
        )
    }

    @Test
    fun finalCommitFailureNeverReportsTerminalAndLeavesStartedRecoverable() {
        val ledger = FailingFinalLedger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ -> PlaybookResult.succeeded(command.taskId) },
            ledger = ledger,
            reporter = recordingReporter(reports),
        )

        val result = dispatcher.handleMqttPayload(commandJson("final-commit-fail", "readonly"))

        assertEquals("succeeded", result?.status)
        assertEquals(listOf("executing"), reports)
        assertEquals(LedgerStage.STARTED, ledger.entry("final-commit-fail")?.stage)
    }

    @Test
    fun effectCommittedFinalCommitFailureReportsOnlyDurableCheckpoint() {
        val ledger = FailingFinalLedger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                PlaybookResult.succeeded(command.taskId, effectCommitted = true)
            },
            ledger = ledger,
            reporter = recordingReporter(reports),
        )

        val result = dispatcher.handleMqttPayload(
            commandJson("effect-final-commit-fail", "non_idempotent"),
        )

        assertEquals("succeeded", result?.status)
        assertEquals(listOf("executing", "effect_committed"), reports)
        assertEquals(
            LedgerStage.EFFECT_COMMITTED,
            ledger.entry("effect-final-commit-fail")?.stage,
        )
    }

    @Test
    fun effectCheckpointCommitFailureReportsNoCheckpointOrTerminal() {
        val ledger = FailingEffectCommitLedger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                PlaybookResult.succeeded(command.taskId, effectCommitted = true)
            },
            ledger = ledger,
            reporter = recordingReporter(reports),
        )

        val result = dispatcher.handleMqttPayload(
            commandJson("effect-checkpoint-commit-fail", "non_idempotent"),
        )

        assertEquals("unknown", result?.status)
        assertEquals("EFFECT_UNKNOWN", result?.errorCode)
        assertEquals(listOf("executing"), reports)
        assertEquals(
            LedgerStage.STARTED,
            ledger.entry("effect-checkpoint-commit-fail")?.stage,
        )
    }

    @Test
    fun timeoutFinalCommitFailureDoesNotReportTerminal() {
        val scheduler = ManualCommandTimeoutScheduler()
        val ledger = FailingFinalLedger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, runtime ->
                runtime.sleep(1)
                PlaybookResult.succeeded(command.taskId)
            },
            runtime = HookRuntime(onSleep = scheduler::fire),
            ledger = ledger,
            reporter = recordingReporter(reports),
            timeoutScheduler = scheduler,
        )

        val result = dispatcher.handleMqttPayload(commandJson("timeout-final-commit-fail", "readonly"))

        assertEquals("failed", result?.status)
        assertEquals("EXECUTION_TIMEOUT", result?.errorCode)
        assertEquals(listOf("executing"), reports)
        assertEquals(LedgerStage.STARTED, ledger.entry("timeout-final-commit-fail")?.stage)
    }

    @Test
    fun ledgerClaimCommitFailureNeverExecutesNonIdempotentTask() {
        val runs = AtomicInteger()
        val reports = mutableListOf<String>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                runs.incrementAndGet()
                PlaybookResult.succeeded(command.taskId, effectCommitted = true)
            },
            ledger = RejectingEffectLedger,
            reporter = recordingReporter(reports),
        )

        val result = dispatcher.handleMqttPayload(commandJson("claim-fail", "non_idempotent"))

        assertEquals(0, runs.get())
        assertEquals("LEDGER_COMMIT_FAILED", result?.errorCode)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun corruptFutureAndPartialLedgerEntriesFailClosedWithoutExecution() {
        val context = RuntimeEnvironment.getApplication()
        val cases = mapOf(
            "corrupt-json" to "{broken",
            "future-version" to """{"version":2,"task_id":"future-version"}""",
            "partial-json" to """{"version":1,"task_id":"partial-json","stage":"STARTED"}""",
        )
        for ((taskId, raw) in cases) {
            val prefsName = "task-hardening-$taskId"
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putString("task:$taskId", raw)
                .commit()
            val runs = AtomicInteger()
            val reports = mutableListOf<String>()
            val ledger = SharedPreferencesEffectLedger(context, prefsName)
            val dispatcher = dispatcher(
                playbook = Playbook { command, _ ->
                    runs.incrementAndGet()
                    PlaybookResult.succeeded(command.taskId, effectCommitted = true)
                },
                ledger = ledger,
                reporter = recordingReporter(reports),
            )

            val result = dispatcher.handleMqttPayload(commandJson(taskId, "non_idempotent"))

            assertEquals(0, runs.get())
            assertEquals("unknown", result?.status)
            assertEquals("EFFECT_UNKNOWN", result?.errorCode)
            assertEquals(listOf("unknown"), reports)
            assertTrue(ledger.claim(command(taskId, EffectClass.NON_IDEMPOTENT)) is LedgerClaim.Corrupt)
            ledger.release(taskId)
        }
    }

    @Test
    fun allFourSideEffectPlaybooksMarkOnlyTheirFinalActionBoundary() {
        val cases = listOf(
            Triple(
                PublishNotePlaybook() as Playbook,
                "publish_note",
                mapOf("title" to "title", "body" to "body", "start_in_editor" to true),
            ),
            Triple(
                ReplyCommentPlaybook() as Playbook,
                "reply_comment",
                mapOf("text" to "reply", "title_summary" to "note"),
            ),
            Triple(
                PostCommentPlaybook() as Playbook,
                "post_comment",
                mapOf("text" to "comment", "title_summary" to "note"),
            ),
            Triple(
                ReplyDmPlaybook() as Playbook,
                "reply_dm",
                mapOf("text" to "dm", "open_title_hint" to "thread"),
            ),
        )

        for ((playbook, playbookName, params) in cases) {
            val runtime = BoundaryRecordingRuntime()
            val result = playbook.run(
                PlaybookCommand(
                    taskId = "boundary-$playbookName",
                    playbook = "$playbookName@1.0",
                    params = params,
                    effectClass = EffectClass.NON_IDEMPOTENT,
                ),
                runtime,
            )

            assertEquals("$playbookName should succeed in the boundary fixture", "succeeded", result.status)
            assertTrue("$playbookName should commit an effect", result.effectCommitted)
            assertEquals(
                "$playbookName must mark exactly one irreversible boundary",
                1,
                runtime.events.count { it == "effect_boundary" },
            )
            val boundary = runtime.events.indexOf("effect_boundary")
            val finalClick = runtime.events.indexOfLast {
                it == "click:text=发送:true" || it == "click:text=发布笔记:true"
            }
            assertTrue("$playbookName must mark immediately before its final click", boundary >= 0)
            assertEquals(boundary + 1, finalClick)
        }
    }

    @Test
    fun coordinatorStartupRecoversLedgerBeforeStartingDeliveryChannels() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("playbook_effect_ledger", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val ledger = SharedPreferencesEffectLedger(context)
        val pending = command("coordinator-startup", EffectClass.NON_IDEMPOTENT)
        assertEquals(LedgerClaim.Acquired, ledger.claim(pending))
        ledger.release(pending.taskId)
        val reports = mutableListOf<String>()
        val rejectedScheduler = Executors.newSingleThreadScheduledExecutor().apply {
            shutdownNow()
        }
        val coordinator = CloudBridgeCoordinator(
            context = context,
            eventReporter = recordingReporter(reports),
            heartbeatScheduler = rejectedScheduler,
            pollScheduler = rejectedScheduler,
            isSupported = { true },
        )

        assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
            coordinator.start()
        }

        assertEquals(listOf("unknown"), reports)
        assertTrue(SharedPreferencesEffectLedger(context).entry(pending.taskId)?.reported == true)
    }

    @Test
    fun failedResultNeverSendsResultPayload() {
        val payloads = mutableListOf<Map<String, Any?>?>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                PlaybookResult.failed(
                    command.taskId,
                    "EXPECTED_FAILURE",
                    resultPayload = mapOf("kind" to "notes", "items" to emptyList<Any>()),
                )
            },
            reporter = TaskEventSink { _, _, _, resultPayload ->
                payloads += resultPayload
                true
            },
        )

        dispatcher.handleMqttPayload(commandJson("failed-payload", "readonly"))

        assertEquals(listOf(null, null), payloads)
    }

    @Test
    fun succeededPayloadIsRejectedWhenPlaybookDoesNotAllowItsKind() {
        val reports = mutableListOf<String>()
        val errors = mutableListOf<String?>()
        val payloads = mutableListOf<Map<String, Any?>?>()
        val dispatcher = dispatcher(
            playbook = Playbook { command, _ ->
                PlaybookResult.succeeded(
                    command.taskId,
                    resultPayload = mapOf("kind" to "notes", "items" to emptyList<Any>()),
                )
            },
            reporter = TaskEventSink { _, status, error, payload ->
                reports += status
                errors += error
                payloads += payload
                true
            },
        )

        val result = dispatcher.handleMqttPayload(commandJson("invalid-payload", "readonly"))

        assertEquals("failed", result?.status)
        assertEquals("INVALID_RESULT_PAYLOAD", result?.errorCode)
        assertEquals(listOf("executing", "failed"), reports)
        assertEquals(listOf(null, "INVALID_RESULT_PAYLOAD"), errors)
        assertEquals(listOf(null, null), payloads)
    }

    @Test
    fun succeededPayloadIsRejectedWhenAllowedKindContainsUnapprovedShape() {
        val dispatcher = dispatcher(
            playbookName = "sync_notes",
            playbook = Playbook { command, _ ->
                PlaybookResult.succeeded(
                    command.taskId,
                    resultPayload = mapOf(
                        "kind" to "notes",
                        "items" to listOf(
                            mapOf(
                                "title_summary" to "safe",
                                "raw_phone" to "13800138000",
                            ),
                        ),
                    ),
                )
            },
        )

        val result = dispatcher.handleMqttPayload(
            commandJson("invalid-shape", "readonly", playbookName = "sync_notes"),
        )

        assertEquals("failed", result?.status)
        assertEquals("INVALID_RESULT_PAYLOAD", result?.errorCode)
        assertNull(result?.resultPayload)
    }

    @Test
    fun coordinatorStopTerminatesSchedulerAndExecutionThreads() {
        val context = RuntimeEnvironment.getApplication()
        val heartbeatScheduler = Executors.newSingleThreadScheduledExecutor()
        val pollScheduler = Executors.newSingleThreadScheduledExecutor()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        executor.execute {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val coordinator = CloudBridgeCoordinator(
            context = context,
            heartbeatScheduler = heartbeatScheduler,
            pollScheduler = pollScheduler,
            playbookExecutor = executor,
        )

        coordinator.stop()

        assertTrue(heartbeatScheduler.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(pollScheduler.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(heartbeatScheduler.isTerminated)
        assertTrue(pollScheduler.isTerminated)
        assertTrue(executor.isTerminated)
    }

    @Test
    fun coordinatorStartupExceptionShutsDownEveryExecutor() {
        val context = RuntimeEnvironment.getApplication()
        val rejectedScheduler = Executors.newSingleThreadScheduledExecutor().apply {
            shutdownNow()
        }
        val executor = Executors.newSingleThreadExecutor()
        val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val coordinator = CloudBridgeCoordinator(
            context = context,
            heartbeatScheduler = rejectedScheduler,
            pollScheduler = rejectedScheduler,
            playbookExecutor = executor,
            commandTimeoutExecutor = timeoutExecutor,
            isSupported = { true },
        )

        assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
            coordinator.start()
        }

        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(timeoutExecutor.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(executor.isTerminated)
        assertTrue(timeoutExecutor.isTerminated)
    }

    @Test
    fun coordinatorStopIsBoundedAndPermanentlyClosesAfterBlockedWorker() {
        val context = RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                release.await()
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val coordinator = CloudBridgeCoordinator(
            context = context,
            playbookExecutor = executor,
            shutdownWaitMs = 5,
        )

        assertFalse(coordinator.stop())
        assertTrue(coordinator.isPermanentlyStopped())
        assertThrows(IllegalStateException::class.java) { coordinator.start() }

        release.countDown()
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(coordinator.stop())
    }

    @Test
    fun coordinatorRepeatedStartAndStopRemainIdempotent() {
        val coordinator = CloudBridgeCoordinator(
            context = RuntimeEnvironment.getApplication(),
            isSupported = { false },
            shutdownWaitMs = 5,
        )

        coordinator.start()
        coordinator.start()
        assertTrue(coordinator.stop())
        assertTrue(coordinator.stop())
        assertThrows(IllegalStateException::class.java) { coordinator.start() }
    }

    @Test
    fun mqttShutdownClosesSocketAndJoinsThreadDuringConnect() {
        val socket = BlockingConnectSocket()
        val client = MqttCommandClient(
            onCommand = {},
            socketFactory = { socket },
            joinTimeoutMs = 1_000,
        )
        client.start()
        assertTrue(socket.connectEntered.await(1, TimeUnit.SECONDS))

        assertTrue(client.stop())

        assertTrue(socket.closed)
        assertFalse(client.workerAlive())
    }

    @Test
    fun mqttShutdownWinsRaceBeforeSocketIsPublished() {
        val factoryEntered = CountDownLatch(1)
        val factoryInterruptedByStop = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val socket = BlockingConnectSocket()
        val client = MqttCommandClient(
            onCommand = {},
            socketFactory = {
                factoryEntered.countDown()
                while (true) {
                    try {
                        releaseFactory.await()
                        break
                    } catch (_: InterruptedException) {
                        factoryInterruptedByStop.countDown()
                        // Deliberately expose the socket-publication race after stop().
                    }
                }
                socket
            },
            joinTimeoutMs = 1_000,
        )
        client.start()
        assertTrue(factoryEntered.await(1, TimeUnit.SECONDS))
        val stopper = Executors.newSingleThreadExecutor()

        val stopped = stopper.submit<Boolean> { client.stop() }
        assertTrue(factoryInterruptedByStop.await(1, TimeUnit.SECONDS))
        releaseFactory.countDown()

        assertTrue(stopped.get(1, TimeUnit.SECONDS))
        stopper.shutdownNow()
        assertTrue(socket.closed)
        assertEquals(1L, socket.connectEntered.count)
        assertFalse(client.workerAlive())
    }

    @Test
    fun mqttShutdownClosesSocketAndJoinsThreadDuringRead() {
        CloudBridgeConfig.init(RuntimeEnvironment.getApplication())
        val socket = BlockingReadSocket()
        val client = MqttCommandClient(
            onCommand = {},
            socketFactory = { socket },
            joinTimeoutMs = 1_000,
        )
        client.start()
        assertTrue(socket.readEntered.await(1, TimeUnit.SECONDS))

        assertTrue(client.stop())

        assertTrue(socket.closed)
        assertFalse(client.workerAlive())
    }

    private fun dispatcher(
        playbook: Playbook,
        playbookName: String = "test",
        runtime: PlaybookRuntime = HookRuntime(),
        ledger: EffectLedger = MemoryEffectLedger(),
        reporter: TaskEventSink = TaskEventSink { _, _, _, _ -> true },
        timeoutScheduler: CommandTimeoutScheduler = ManualCommandTimeoutScheduler(),
        clock: MonotonicClock = SystemMonotonicClock,
        preparePublishParams: ((Map<String, Any?>) -> Map<String, Any?>?)? = null,
    ): TaskCommandDispatcher {
        val registry = PlaybookRegistry().register(playbookName, playbook)
        return TaskCommandDispatcher(
            engine = PlaybookEngine(runtime, registry, ledger, clock),
            reporter = reporter,
            preparePublishParams = preparePublishParams,
            clock = clock,
            timeoutScheduler = timeoutScheduler,
        )
    }

    private fun commandJson(
        taskId: String,
        effectClass: String,
        playbookName: String = "test",
    ): String =
        """{"task_id":"$taskId","playbook":"$playbookName@1.0","effect_class":"$effectClass","timeout_sec":1}"""

    private fun command(taskId: String, effectClass: EffectClass): PlaybookCommand =
        PlaybookCommand(
            taskId = taskId,
            playbook = "test@1.0",
            effectClass = effectClass,
            timeoutSec = 1,
        )

    private fun recordingReporter(reports: MutableList<String>): TaskEventSink =
        TaskEventSink { _, status, _, _ ->
            reports += status
            true
        }
}

private class ManualCommandTimeoutScheduler : CommandTimeoutScheduler {
    private var pending: (() -> Unit)? = null

    override fun schedule(delayMs: Long, task: () -> Unit): RequestHandle {
        pending = task
        return RequestHandle { pending = null }
    }

    fun fire() {
        pending?.invoke()
        pending = null
    }

    fun hasPendingTask(): Boolean = pending != null
}

private class RetainingCommandTimeoutScheduler : CommandTimeoutScheduler {
    private val tasks = mutableListOf<() -> Unit>()

    override fun schedule(delayMs: Long, task: () -> Unit): RequestHandle {
        tasks += task
        return RequestHandle {}
    }

    fun fire(index: Int) {
        tasks[index].invoke()
    }
}

private class HookRuntime(
    private val onSleep: () -> Unit = {},
) : PlaybookRuntime {
    var clicks = 0

    override fun accessibilityAlive(): Boolean = true
    override fun launchXhs(): Boolean = true
    override fun waitForXhsForeground(timeoutMs: Long): Boolean = true
    override fun currentPageHint(): PageHint = PageHint.HOME
    override fun currentLease(): TargetLease = TargetLease("com.xingin.xhs", 1)
    override fun observe(): UiSnapshot? = null
    override fun extractComments(maxItems: Int): List<ExtractedComment> = emptyList()
    override fun extractInboxThreads(maxItems: Int): List<ExtractedInboxThread> = emptyList()
    override fun extractDmMessages(maxItems: Int): List<ExtractedDmMessage> = emptyList()
    override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean {
        clicks += 1
        return true
    }
    override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean = true
    override fun setText(selector: String, text: String, timeoutMs: Long): Boolean = true
    override fun tap(x: Int, y: Int, durationMs: Long): Boolean = true
    override fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean = true
    override fun globalBack(): Boolean = true
    override fun sleep(ms: Long) = onSleep()
}

private class BoundaryRecordingRuntime : PlaybookRuntime {
    val events = mutableListOf<String>()
    private var pageHint = PageHint.HOME
    private var openDm = false
    private var typedText: String? = null

    override fun beginSideEffect() {
        events += "effect_boundary"
    }

    override fun accessibilityAlive(): Boolean = true
    override fun launchXhs(): Boolean = true
    override fun waitForXhsForeground(timeoutMs: Long): Boolean = true
    override fun currentPageHint(): PageHint = pageHint
    override fun currentLease(): TargetLease = TargetLease("com.xingin.xhs", 1)
    override fun observe(): UiSnapshot =
        UiSnapshot(
            packageName = "com.xingin.xhs",
            className = "FixtureActivity",
            pageHint = pageHint,
            keyElements = emptyList(),
            nodes = buildList {
                add(
                    UiNode(
                        text = typedText ?: "留下你的想法吧",
                        contentDescription = null,
                        className = "android.widget.EditText",
                        clickable = true,
                        editable = true,
                        bounds = UiBounds(20, 20, 400, 120),
                    ),
                )
                if (typedText != null) {
                    add(
                        UiNode(
                            text = "发送",
                            clickable = true,
                            bounds = UiBounds(900, 100, 1_060, 180),
                        ),
                    )
                }
                add(
                    UiNode(
                        text = "编辑主页",
                        bounds = UiBounds(20, 200, 300, 300),
                    ),
                )
                add(
                    UiNode(
                        contentDescription = "笔记,note",
                        clickable = true,
                        bounds = UiBounds(20, 500, 500, 800),
                    ),
                )
                add(
                    UiNode(
                        text = "首页",
                        clickable = true,
                        bounds = UiBounds(0, 2_200, 200, 2_400),
                    ),
                )
                add(
                    UiNode(
                        text = "消息",
                        clickable = true,
                        bounds = UiBounds(650, 2_200, 850, 2_400),
                    ),
                )
                add(
                    UiNode(
                        text = "我",
                        clickable = true,
                        bounds = UiBounds(880, 2_200, 1_080, 2_400),
                    ),
                )
            },
            truncated = false,
        )

    override fun extractComments(maxItems: Int): List<ExtractedComment> = emptyList()
    override fun extractInboxThreads(maxItems: Int): List<ExtractedInboxThread> =
        listOf(
            ExtractedInboxThread(
                titleSummary = "thread",
                previewSummary = "preview",
                unreadHint = false,
                locatorHint = "text=thread",
            ),
        )
    override fun extractDmMessages(maxItems: Int): List<ExtractedDmMessage> = emptyList()
    override fun looksLikeCommentsSurface(): Boolean = true
    override fun looksLikeInboxListSurface(): Boolean = true
    override fun looksLikeOpenDmThreadSurface(): Boolean = openDm

    override fun click(selector: String, allowFinal: Boolean, timeoutMs: Long): Boolean {
        events += "click:$selector:$allowFinal"
        if (selector.contains("首页")) pageHint = PageHint.HOME
        if (selector.contains("消息")) pageHint = PageHint.INBOX
        if (selector.contains("thread")) {
            pageHint = PageHint.INBOX
            openDm = true
        }
        if (selector.contains("评论")) pageHint = PageHint.COMMENTS
        return true
    }

    override fun clickTextContaining(fragment: String, timeoutMs: Long): Boolean {
        if (fragment == "thread") {
            pageHint = PageHint.INBOX
            openDm = true
        }
        return true
    }
    override fun setText(selector: String, text: String, timeoutMs: Long): Boolean {
        typedText = text
        events += "setText:$selector"
        return true
    }
    override fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        when {
            x in 650..850 && y >= 2_000 -> pageHint = PageHint.INBOX
            x <= 250 && y >= 2_000 -> pageHint = PageHint.HOME
        }
        return true
    }
    override fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean = true

    override fun globalBack(): Boolean {
        openDm = false
        pageHint = PageHint.HOME
        return true
    }
    override fun sleep(ms: Long) = Unit
}

private class MutableMonotonicClock(
    var now: Long,
) : MonotonicClock {
    override fun nowMillis(): Long = now
}

private class TimeoutOnFirstFinalLedger(
    private val scheduler: ManualCommandTimeoutScheduler,
) : EffectLedger {
    private val delegate = MemoryEffectLedger()
    private var firstFinal = true

    override fun claim(command: PlaybookCommand): LedgerClaim = delegate.claim(command)
    override fun entry(taskId: String): LedgerEntry? = delegate.entry(taskId)
    override fun pendingRecovery(): List<LedgerEntry> = delegate.pendingRecovery()
    override fun storeEffectCommitted(
        command: PlaybookCommand,
        result: PlaybookResult,
    ): Boolean = delegate.storeEffectCommitted(command, result)
    override fun storeFinal(command: PlaybookCommand, result: PlaybookResult): Boolean {
        val stored = delegate.storeFinal(command, result)
        if (firstFinal) {
            firstFinal = false
            scheduler.fire()
        }
        return stored
    }
    override fun markReported(taskId: String): Boolean = delegate.markReported(taskId)
    override fun release(taskId: String) = delegate.release(taskId)
}

private class FailingFinalLedger : EffectLedger {
    private val delegate = MemoryEffectLedger()

    override fun claim(command: PlaybookCommand): LedgerClaim = delegate.claim(command)
    override fun entry(taskId: String): LedgerEntry? = delegate.entry(taskId)
    override fun storeEffectCommitted(
        command: PlaybookCommand,
        result: PlaybookResult,
    ): Boolean = delegate.storeEffectCommitted(command, result)
    override fun storeFinal(command: PlaybookCommand, result: PlaybookResult): Boolean = false
    override fun release(taskId: String) = delegate.release(taskId)
}

private class FailingEffectCommitLedger : EffectLedger {
    private val delegate = MemoryEffectLedger()

    override fun claim(command: PlaybookCommand): LedgerClaim = delegate.claim(command)
    override fun entry(taskId: String): LedgerEntry? = delegate.entry(taskId)
    override fun storeEffectCommitted(
        command: PlaybookCommand,
        result: PlaybookResult,
    ): Boolean = false
    override fun storeFinal(command: PlaybookCommand, result: PlaybookResult): Boolean =
        delegate.storeFinal(command, result)
    override fun release(taskId: String) = delegate.release(taskId)
}

private object RejectingEffectLedger : EffectLedger {
    override fun claim(command: PlaybookCommand): LedgerClaim = LedgerClaim.CommitFailed
    override fun entry(taskId: String): LedgerEntry? = null
    override fun storeEffectCommitted(command: PlaybookCommand, result: PlaybookResult): Boolean = false
    override fun storeFinal(command: PlaybookCommand, result: PlaybookResult): Boolean = false
    override fun release(taskId: String) = Unit
}

private class BlockingConnectSocket : Socket() {
    val connectEntered = CountDownLatch(1)
    private val closedLatch = CountDownLatch(1)
    @Volatile var closed = false

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        connectEntered.countDown()
        closedLatch.await()
        throw SocketException("closed")
    }

    override fun close() {
        closed = true
        closedLatch.countDown()
    }
}

private class BlockingReadSocket : Socket() {
    val readEntered = CountDownLatch(1)
    private val closedLatch = CountDownLatch(1)
    @Volatile var closed = false
    private val mqttHandshake = byteArrayOf(
        0x20,
        0x02,
        0x00,
        0x00,
        0x90.toByte(),
        0x03,
        0x00,
        0x01,
        0x00,
    )

    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit

    override fun getInputStream(): InputStream =
        object : InputStream() {
            private val handshake = ByteArrayInputStream(mqttHandshake)

            override fun read(): Int {
                val next = handshake.read()
                if (next >= 0) return next
                readEntered.countDown()
                closedLatch.await()
                throw SocketException("closed")
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                var count = 0
                while (count < length) {
                    val next = read()
                    if (next < 0) return if (count == 0) -1 else count
                    buffer[offset + count] = next.toByte()
                    count += 1
                }
                return count
            }
        }

    override fun getOutputStream(): ByteArrayOutputStream = ByteArrayOutputStream()

    override fun close() {
        closed = true
        closedLatch.countDown()
    }
}

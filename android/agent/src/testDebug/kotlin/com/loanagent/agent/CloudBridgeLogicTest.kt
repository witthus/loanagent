package com.loanagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskCommandDispatcherTest {
    @Test
    fun dedupesSameTaskIdAndReportsOnce() {
        var accessChecks = 0
        val runtime = object : PlaybookEngineTest.FakePlaybookRuntime(hint = PageHint.HOME) {
            override fun accessibilityAlive(): Boolean {
                accessChecks += 1
                return true
            }
        }
        val engine = PlaybookEngine(
            runtime = runtime,
            registry = DefaultPlaybookRegistry.create(),
            ledger = MemoryEffectLedger(),
        )
        val reports = mutableListOf<String>()
        val dispatcher = TaskCommandDispatcher(
            engine = engine,
            reporter = TaskEventSink { taskId, status, _, _ ->
                reports += "$taskId:$status"
                true
            },
        )
        val payload =
            """{"task_id":"t1","playbook":"ensure_app_ready@1.0","account_id":"a1"}"""
        dispatcher.handleMqttPayload(payload)
        dispatcher.handleMqttPayload(payload)
        assertEquals(1, accessChecks)
        assertEquals(listOf("t1:succeeded"), reports)
    }

    @Test
    fun rejectsUnsupportedPlaybook() {
        val engine = PlaybookEngine(
            runtime = PlaybookEngineTest.FakePlaybookRuntime(),
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
        )
        dispatcher.handleMqttPayload(
            """{"task_id":"t2","playbook":"not_a_real_playbook@1.0"}""",
        )
        assertEquals(listOf("t2:failed:UNSUPPORTED_PLAYBOOK"), reports)
    }

    @Test
    fun passesParamsToPublishNote() {
        val engine = PlaybookEngine(
            runtime = PlaybookEngineTest.FakePlaybookRuntime(
                hint = PageHint.EDITOR,
                clickOk = true,
                setTextOk = true,
            ),
            registry = DefaultPlaybookRegistry.create(),
            ledger = MemoryEffectLedger(),
        )
        val result = TaskCommandDispatcher(
            engine = engine,
            reporter = TaskEventSink { _, _, _, _ -> true },
        ).handleMqttPayload(
            """{"task_id":"t3","playbook":"publish_note@1.0","effect_class":"non_idempotent","params":{"title":"T","body":"B","start_in_editor":true}}""",
        )
        assertTrue(result!!.success)
        assertTrue(result.effectCommitted)
    }

    @Test
    fun replyCommentReportsWrongPage() {
        val engine = PlaybookEngine(
            runtime = PlaybookEngineTest.FakePlaybookRuntime(hint = PageHint.HOME),
            registry = DefaultPlaybookRegistry.create(),
            ledger = MemoryEffectLedger(),
        )
        val reports = mutableListOf<String>()
        TaskCommandDispatcher(
            engine = engine,
            reporter = TaskEventSink { taskId, status, errorCode, _ ->
                reports += "$taskId:$status:$errorCode"
                true
            },
        ).handleMqttPayload(
            """{"task_id":"t4","playbook":"reply_comment@1.0","effect_class":"non_idempotent","params":{"text":"hi"}}""",
        )
        assertEquals(listOf("t4:failed:WRONG_PAGE"), reports)
    }

    @Test
    fun postCommentReportsWrongPage() {
        val engine = PlaybookEngine(
            runtime = PlaybookEngineTest.FakePlaybookRuntime(hint = PageHint.HOME),
            registry = DefaultPlaybookRegistry.create(),
            ledger = MemoryEffectLedger(),
        )
        val reports = mutableListOf<String>()
        TaskCommandDispatcher(
            engine = engine,
            reporter = TaskEventSink { taskId, status, errorCode, _ ->
                reports += "$taskId:$status:$errorCode"
                true
            },
        ).handleMqttPayload(
            """{"task_id":"t5","playbook":"post_comment@1.0","effect_class":"non_idempotent","params":{"text":"hi"}}""",
        )
        assertEquals(listOf("t5:failed:WRONG_PAGE"), reports)
    }
}

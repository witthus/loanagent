# Auto Surface Navigation Implementation Plan

> **For agentic workers:** Implement task-by-task with TDD. Steps use checkbox syntax.

**Goal:** Cloud tasks auto-navigate to comments / DM surfaces from any screen before acting.

**Architecture:** Shared `SurfaceNavigator` on Agent; CP adds `title_summary` / existing `open_title_hint` to task params.

**Tech Stack:** Kotlin Android agent playbooks; FastAPI notes/inbox services; Robolectric unit tests.

---

### Task 1: SurfaceNavigator + unit tests

**Files:**
- Create: `android/agent/src/main/kotlin/com/loanagent/agent/SurfaceNavigator.kt`
- Create: `android/agent/src/test/kotlin/com/loanagent/agent/SurfaceNavigatorTest.kt`

- [ ] Failing tests for goInbox / goDmThread / goNoteComments
- [ ] Implement navigator
- [ ] Green

### Task 2: Wire playbooks

**Files:**
- Modify: `ReadPlaybooks.kt`, `SideEffectPlaybooks.kt`
- Modify: `PlaybookEngineTest.kt` expectations for HOME→nav paths

- [ ] Update playbooks to call navigator
- [ ] Fix/extend engine tests

### Task 3: CP params

**Files:**
- Modify: `control-plane/src/loanagent/notes.py`
- Modify: `control-plane/tests/test_ops_social.py` (assert title_summary in params)

- [ ] Pass `title_summary` on sync/reply comment
- [ ] Tests green

### Task 4: Verify

- [ ] `docker compose ... :agent:testDebugUnitTest`
- [ ] `pytest` notes/ops social subset
- [ ] Stage APK when agent green

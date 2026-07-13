# Ops Comment Tree & Inbox Transcript Implementation Plan

> **For agentic workers:** Implement task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist comment trees and inbox transcripts for ops, show hierarchy/time simply, align sync-notes control, keep DB-default + reconcile sync.

**Architecture:** New `note_comment_nodes` table + strengthened `inbox_messages`; agent extractors emit tree/messages; CP replace-on-sync; ops-web tree + preview UI.

**Tech Stack:** FastAPI/psycopg, Kotlin agent extractors/playbooks, Vue 3 ops-web, Docker Compose tests.

---

## File map

| File | Responsibility |
| --- | --- |
| `control-plane/src/loanagent/db.py` | Migration 19 |
| `control-plane/src/loanagent/notes.py` | Comment tree CRUD/replace; reply by node_id |
| `control-plane/src/loanagent/notes_routes.py` | Comments API shape |
| `control-plane/src/loanagent/inbox.py` | Message replace + preview rebuild |
| `control-plane/src/loanagent/tasks.py` | Ingest comment tree payload |
| `android/.../ContentExtractors.kt` | Tree comments + time; DM posted_at if present |
| `android/.../ReadPlaybooks.kt` | Comment payload tree; inbox open threads + messages |
| `ops-web/.../CommentsView.vue` | Align controls; tree UI |
| `ops-web/.../InboxView.vue` | Richer preview display |
| `ops-web/.../InboxThreadView.vue` | Show posted_at_text |
| Tests | CP social + Android ContentExtractors |

---

### Task 1: Schema migration 19

- [ ] Add `note_comment_nodes` + `inbox_messages.sort_index` / `posted_at_text` in `db.py`
- [ ] Verify via pytest migrate helper / existing migrate on TestClient

### Task 2: CP notes comment tree

- [ ] Add `NoteCommentNodeRecord` + `replace_comment_tree_from_payload` + `list_comment_nodes`
- [ ] Wire `_ingest_result_payload` kind=comments to tree replace
- [ ] Reply uses node_id from nodes table (fallback old comments if needed)
- [ ] Tests: replace deletes stale; nested reply fields stored

### Task 3: CP inbox transcript

- [ ] On ingest threads with `messages`, replace messages for thread; set preview from last messages
- [ ] When syncing account, empty threads still clears
- [ ] Test preview + message replace

### Task 4: Agent extractors + playbooks

- [ ] Parse comment meta time into `postedAtText`; detect reply indent /「回复」chains as child
- [ ] Emit `replies` in payload
- [ ] Inbox sync: for each thread up to `max_threads`, open, extract messages, attach to payload, back
- [ ] Unit tests for extractor tree + interest filter regression

### Task 5: Ops UI

- [ ] CommentsView: flex-end align sync button with select; render tree by depth
- [ ] InboxView: show multi-line preview; empty hint if no preview
- [ ] InboxThreadView: prefer `posted_at_text`

### Task 6: Verify + commit

- [ ] Docker: control-plane pytest (ops social / inbox / notes)
- [ ] Docker: agent ContentExtractorsTest + PlaybookEngineTest
- [ ] Deploy optional; git commit as user requested

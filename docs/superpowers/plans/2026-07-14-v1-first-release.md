# V1 First Release Implementation Plan

> **For agentic workers:** Execute task-by-task without asking the user. Complete implement → deploy → E2E device click tests.

**Goal:** Ship V1: multi-device online status, select account(s) to publish notes / sync+reply comments / sync+reply DMs from ops-web to Android debug agents.

**Architecture:** Ops-web → control-plane tasks → debug Agent cloud bridge → XHS playbooks. One account ↔ one device; multi-device = multi-account sequential/batch dispatch.

**Tech Stack:** Vue ops-web, FastAPI control-plane, Kotlin debug Agent, Docker deploy.

---

### Task 1: Fix Today DM deep link + inbox empty messages + lead wipe
### Task 2: Multi-account publish in PublishView
### Task 3: Minimal account↔device bind in AccountsView
### Task 4: Deploy CP (ops-web embed) + restage/install Agent
### Task 5: E2E click matrix on Redmi (publish, comment, reply comment, DM reply)
### Task 6: Write V1 release test report

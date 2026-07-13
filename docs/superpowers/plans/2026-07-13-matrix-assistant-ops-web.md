# 矩阵助手 Ops Web Implementation Plan

> **Status:** Implemented (W0–W5). Checkboxes below are historical task notes.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Jinja `/ops` with Vue 3 SPA「矩阵助手」: beginner Chinese ops UI, desktop-first, `platform` reserved (xhs only), cut over in one switch.

**Architecture:** New `ops-web/` (Vite + Vue 3 + TS + Pinia + Element Plus + Vue Router) talks to existing FastAPI `/api/v1` with Cookie `ops_session`. Control Plane adds schema v16 `platform` columns and engagement manual-create API. Production serves `ops-web/dist` from FastAPI; Jinja HTML routes redirect to SPA.

**Tech Stack:** Vue 3, Vite, TypeScript, Element Plus, Pinia, Vue Router, Vitest; FastAPI/psycopg; Docker (node image for frontend build, existing control-plane for API/tests).

**Spec:** `docs/superpowers/specs/2026-07-13-matrix-assistant-ops-web-design.md`

---

## File map

| Path | Responsibility |
| --- | --- |
| `ops-web/` | Vue SPA root |
| `ops-web/src/platform.ts` | `Platform`, `ACTIVE_PLATFORMS`, labels |
| `ops-web/src/i18n/zh-CN.ts` | Chinese copy + platform word table |
| `ops-web/src/lib/humanize.ts` | error_code → 人话 |
| `ops-web/src/lib/api.ts` | fetch wrapper, credentials include |
| `ops-web/src/lib/auth.ts` | login/logout against `/ops/login` |
| `ops-web/src/composables/useTaskPoll.ts` | poll `/api/v1/tasks/{id}` |
| `ops-web/src/layouts/AppShell.vue` | sidebar + topbar |
| `ops-web/src/views/*.vue` | one view per nav item |
| `ops-web/src/router/index.ts` | routes + auth guard |
| `docker/ops-web/Dockerfile` | node build image |
| `control-plane/.../db.py` | schema v16 platform columns |
| `control-plane/.../accounts.py` etc. | expose `platform` |
| `control-plane/.../engagement*.py` | manual chain create |
| `control-plane/.../main.py` | mount SPA static + SPA fallback |
| `control-plane/.../web/routes.py` | login API JSON + HTML redirect to SPA |

---

### Task 1: Scaffold `ops-web` (W0)

**Files:**
- Create: `ops-web/package.json`, `ops-web/vite.config.ts`, `ops-web/tsconfig.json`, `ops-web/tsconfig.app.json`, `ops-web/index.html`, `ops-web/src/main.ts`, `ops-web/src/App.vue`, `ops-web/src/vite-env.d.ts`, `docker/ops-web/Dockerfile`, `ops-web/.dockerignore`

- [ ] **Step 1: Create package.json**

```json
{
  "name": "matrix-assistant",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "element-plus": "^2.9.0",
    "@element-plus/icons-vue": "^2.3.1",
    "pinia": "^2.3.0",
    "vue": "^3.5.13",
    "vue-router": "^4.5.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.2.1",
    "typescript": "~5.7.2",
    "vite": "^6.0.7",
    "vitest": "^2.1.8",
    "vue-tsc": "^2.2.0",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.1"
  }
}
```

- [ ] **Step 2: Create vite.config.ts** with `/api` and `/ops` proxy to `http://control-plane:8000` (compose service name; local override via env).

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_API_PROXY || 'http://127.0.0.1:8000', changeOrigin: true },
      '/ops': { target: process.env.VITE_API_PROXY || 'http://127.0.0.1:8000', changeOrigin: true },
    },
  },
  test: { environment: 'jsdom' },
})
```

- [ ] **Step 3: Add `docker/ops-web/Dockerfile`** (node 22 bookworm slim) for `npm ci` / `npm test` / `npm run build`. Add compose service `ops-web` profile `web` binding `../ops-web`.

- [ ] **Step 4: Verify scaffold**

```bash
docker compose -f infra/compose.yaml --profile web run --rm ops-web npm install
docker compose -f infra/compose.yaml --profile web run --rm ops-web npm test
```

Expected: vitest exits 0 (even with zero tests initially after adding a smoke test).

- [ ] **Step 5: Commit** (only if user asked; otherwise skip git commits until requested)

---

### Task 2: Platform + humanize + API client (W0)

**Files:**
- Create: `ops-web/src/platform.ts`, `ops-web/src/i18n/zh-CN.ts`, `ops-web/src/lib/humanize.ts`, `ops-web/src/lib/api.ts`, `ops-web/src/lib/humanize.test.ts`

- [ ] **Step 1: Write failing humanize test**

```ts
import { describe, expect, it } from 'vitest'
import { humanizeError } from './humanize'

describe('humanizeError', () => {
  it('maps A11Y_DOWN', () => {
    expect(humanizeError('A11Y_DOWN').title).toContain('辅助功能')
  })
  it('falls back unknown codes', () => {
    expect(humanizeError('WEIRD').detail).toContain('WEIRD')
  })
})
```

- [ ] **Step 2: Implement platform.ts**

```ts
export type Platform = 'xhs' | 'douyin'
export const ACTIVE_PLATFORMS: Platform[] = ['xhs']
export const DEFAULT_PLATFORM: Platform = 'xhs'
export function platformLabel(p: Platform): string {
  return p === 'xhs' ? '小红书' : '抖音'
}
export function isPlatformActive(p: Platform): boolean {
  return ACTIVE_PLATFORMS.includes(p)
}
```

- [ ] **Step 3: Implement humanize + zh-CN word table** (`publishVerb[platform]`: xhs→发笔记, douyin→发视频 unused).

- [ ] **Step 4: Implement api.ts**

```ts
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      ...(init.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...init.headers,
    },
  })
  if (res.status === 401) throw new Error('UNAUTHORIZED')
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || res.statusText)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}
```

- [ ] **Step 5: Run tests**

```bash
docker compose -f infra/compose.yaml --profile web run --rm ops-web npm test
```

Expected: PASS

---

### Task 3: App shell, router, login (W0)

**Files:**
- Create: `ops-web/src/layouts/AppShell.vue`, `ops-web/src/views/LoginView.vue`, `ops-web/src/views/TodayView.vue` (stub), `ops-web/src/router/index.ts`, `ops-web/src/stores/auth.ts`
- Modify: `ops-web/src/main.ts`, `ops-web/src/App.vue`
- Modify CP: add `POST /ops/api/login` JSON `{ok:true}` + cookie (keep form login for transition) OR document SPA posts `application/x-www-form-urlencoded` to existing `/ops/login` and follows redirect — prefer **JSON login endpoint** under `/ops/api/login` returning `{ok:true}` without redirect for SPA.

- [ ] **Step 1: Auth store** — `login(token)`, `logout()`, `isAuthed` (probe `GET /api/v1/devices` or lightweight `/ops/api/session`).

- [ ] **Step 2: AppShell** — left nav groups 日常工作 / 系统 (paths from spec §2); brand「矩阵助手」; help + logout.

- [ ] **Step 3: Router** — `/login`, `/` Today stub, placeholders for other routes rendering `ComingSoon` with Chinese title until later tasks fill them.

- [ ] **Step 4: CP JSON login** in `web/routes.py`:

```python
@router.post("/api/login")
async def api_login(request: Request) -> Response:
    body = await request.json()
    token = str(body.get("token") or "")
    if not _valid_ops_token(token):
        return JSONResponse({"ok": False, "error": "令牌不正确"}, status_code=401)
    response = JSONResponse({"ok": True})
    response.set_cookie("ops_session", token, httponly=True, samesite="lax")
    return response
```

- [ ] **Step 5: Manual check** — open Vite, login with OPS_TOKEN, see shell.

---

### Task 4: Schema v16 `platform` (W1)

**Files:**
- Modify: `control-plane/src/loanagent/db.py` (`FLEET_SCHEMA_VERSION = 16`)
- Modify: `accounts.py`, `content.py`, notes models, inbox, engagement as needed
- Test: `control-plane/tests/test_platform_field.py` (new)

- [ ] **Step 1: Migration 16**

```sql
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'xhs'
  CHECK (platform IN ('xhs','douyin'));
ALTER TABLE content_assets ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'xhs'
  CHECK (platform IN ('xhs','douyin'));
ALTER TABLE engagement_chains ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'xhs'
  CHECK (platform IN ('xhs','douyin'));
ALTER TABLE engagement_chains ADD COLUMN IF NOT EXISTS mode TEXT NOT NULL DEFAULT 'auto'
  CHECK (mode IN ('auto','manual'));
ALTER TABLE engagement_chains ADD COLUMN IF NOT EXISTS engager_account_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
-- published_notes / inbox_threads: add platform default xhs if tables exist
```

- [ ] **Step 2: Repository serialize `platform` on AccountRecord / Content / list filters `?platform=xhs`.**

- [ ] **Step 3: Reject cross-platform publish** (account.platform != content.platform → 400).

- [ ] **Step 4: Tests via**

```bash
docker compose -f infra/compose.yaml run --rm control-plane uv run pytest tests/test_platform_field.py -q
```

---

### Task 5: Today + Contents + Publish views (W1)

**Files:**
- Create: `ops-web/src/views/TodayView.vue`, `ContentsView.vue`, `PublishView.vue`, `ops-web/src/lib/todoAggregate.ts`, `todoAggregate.test.ts`
- Create CP (optional): `GET /api/v1/ops/todo` aggregating threads/schedules/alerts — **or** aggregate client-side from existing list APIs in W1 to reduce backend work.

- [ ] **Step 1: Client-side `buildTodoItems({threads, schedules, alerts, failedTasks, notesWithComments})`** pure function + vitest.

- [ ] **Step 2: TodayView** loads lists with `platform=xhs`, shows cards with single primary button.

- [ ] **Step 3: ContentsView** list + upload title/body/media using existing `/api/v1/content` + `/media`.

- [ ] **Step 4: PublishView** wizard: account (PUBLISHER_*) → content → immediate or schedule; checkbox `engagement_mode=manual`; call `/publish/immediate` with params; `useTaskPoll`.

---

### Task 6: Comments + Inbox + Leads (W2)

**Files:**
- Create: `CommentsView.vue`, `InboxView.vue`, `InboxThreadView.vue`, `LeadsView.vue`

- [ ] Wire to `/notes`, `/notes/{id}/sync-comments`, `/notes/{id}/comments`, `/comments/{id}/reply`, `/inbox/*`, `/inbox/leads`. Chinese labels; task poll; compliance errors via humanize.

---

### Task 7: Engagement auto + manual (W3)

**Files:**
- Modify: `engagement.py`, `engagement_routes.py`
- Create: `EngagementView.vue`, `EngagementArrangeView.vue`

- [ ] **Step 1: API** `POST /api/v1/engagement/chains` body:

```json
{
  "publish_task_id": "...",
  "note_id": "...",
  "mode": "manual",
  "engager_account_ids": ["e1","e2"],
  "platform": "xhs"
}
```

Auto path: when publish succeeds and mode!=manual, existing orchestrator picks ENGAGERs same `platform` (random/round-robin).

- [ ] **Step 2: Skip auto** if publish params `engagement_mode=manual`.

- [ ] **Step 3: UI** list chains; arrange page multi-select ENGAGERs; stop chain.

- [ ] **Step 4: pytest** for manual create + platform pool isolation.

---

### Task 8: System pages + Help (W4)

**Files:**
- Create: `AccountsView.vue`, `DevicesView.vue`, `TasksView.vue`, `AlertsView.vue`, `HelpView.vue`

- [ ] Full tables from `/api/v1/accounts|devices|tasks|alerts`; role/platform Chinese; task detail shows raw `error_code` in expandable section; help 5–8 steps.

---

### Task 9: Serve SPA + cutover (W5)

**Files:**
- Modify: `docker/control-plane/Dockerfile` (multi-stage copy `ops-web/dist` → `/app/static/ops-web`)
- Modify: `loanagent/main.py` mount StaticFiles + catch-all for SPA
- Modify: `web/routes.py` — HTML pages `RedirectResponse("/app/")` or serve index; keep `/ops/api/login`

- [ ] **Step 1: Build in Docker, copy dist into runtime image.**

- [ ] **Step 2: `app.mount("/assets", ...)` and route `GET /app/{path:path}` → index.html.**

- [ ] **Step 3: Redirect `/ops/`, `/ops/devices`, … → `/app/...` equivalents.**

- [ ] **Step 4: Compose/CI build includes ops-web.**

- [ ] **Step 5: Smoke** login → today → open devices.

---

## Spec coverage check

| Spec item | Task |
| --- | --- |
| Vue SPA replace Jinja | 1, 3, 9 |
| 今日待办 | 5 |
| 发笔记/内容库 | 5 |
| 评论/私信/线索 | 6 |
| 互动链 auto+manual | 7 |
| 账号/设备/任务/告警/帮助 | 8 |
| platform reserve | 2, 4 |
| humanize | 2 |
| desktop-first CSS vars | 3 (AppShell) |
| Cookie auth | 3 |
| one cutover | 9 |

## Execution note

User requested: write plan then **start implementing immediately**. Proceed Task 1 → … without waiting for another confirmation. Do not commit unless user asks.

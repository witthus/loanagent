# XHS Photo Permission + Gallery Self-Check Implementation Plan

> **For agentic workers:** Implement task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Matrix Assistant status page detects Xiaohongshu album/photo permission and can self-check MediaStore writes of a bundled image into `DCIM/Camera`, reducing `MEDIA_MISSING` failures caused by missing gallery access or failed writes.

**Architecture:** Pure decision helpers + `KeepAliveEnvironment` probe + status UI button. MediaStore insert shared in main (not debug-only) so Release/status can run self-check without INTERNET.

**Tech Stack:** Kotlin, Android MediaStore, PackageManager GET_PERMISSIONS, JUnit

## Global Constraints

- Do not open XHS UI during self-check
- Do not request storage permissions for the agent package
- Bundled asset only (no cloud download in this feature)
- Bump agent to versionName `0.1.6` / versionCode `7`
- Docker-only Gradle builds

## File map

| File | Responsibility |
|------|----------------|
| `android/agent/src/main/kotlin/.../XhsPhotoAccess.kt` | Pure: which permission names count as granted |
| `android/agent/src/main/kotlin/.../GalleryMediaWriter.kt` | Insert/query/delete image file into MediaStore DCIM/Camera |
| `android/agent/src/main/kotlin/.../PublishMediaSelfCheck.kt` | Orchestrate asset → write → verify → XHS perm → cleanup |
| `android/agent/src/main/assets/selfcheck_media.png` | Tiny PNG |
| `KeepAliveHealthChecker.kt` / `AndroidKeepAliveEnvironment.kt` | Issue + screenLine + probe |
| `AgentStatusActivity.kt` | Explain text, issue button, self-check button |
| `KeepAliveHealthCheckerTest.kt` / `XhsPhotoAccessTest.kt` | Unit tests |
| `android/agent/build.gradle.kts` | Version bump |

---

### Task 1: Permission decision + health checker

**Files:**
- Create: `android/agent/src/main/kotlin/com/loanagent/agent/XhsPhotoAccess.kt`
- Create: `android/agent/src/test/kotlin/com/loanagent/agent/XhsPhotoAccessTest.kt`
- Modify: `KeepAliveHealthChecker.kt`, `AndroidKeepAliveEnvironment.kt`
- Modify: `android/agent/src/test/kotlin/com/loanagent/agent/KeepAliveHealthCheckerTest.kt`

**Interfaces:**
- Produces: `XhsPhotoAccess.isGranted(granted: Set<String>): Boolean`
- Produces: `KeepAliveEnvironment.xhsPhotoAccessGranted(): Boolean` (false if not installed)
- Produces: `SettingsAction.XHS_APP_DETAILS`

- [x] **Step 1:** Failing tests for decide + checker issue `XHS_PHOTO_DENIED`
- [x] **Step 2:** Implement; tests green via Docker Gradle

---

### Task 2: Gallery writer + self-check

**Files:**
- Create: `GalleryMediaWriter.kt`, `PublishMediaSelfCheck.kt`
- Create: `assets/selfcheck_media.png`
- Test: pure result enum / writer contract tests where feasible

**Interfaces:**
- Produces: `GalleryMediaWriter.insertImage(context, bytes, displayName, mime): Uri?`
- Produces: `GalleryMediaWriter.deleteByDisplayName(context, displayName): Boolean`
- Produces: `PublishMediaSelfCheck.run(context): PublishMediaSelfCheckResult`

- [x] **Step 1:** Implement writer mirroring MediaBridge MediaStore path
- [x] **Step 2:** Self-check loads asset, inserts, queries, checks XHS perms, deletes on success
- [x] **Step 3:** Unit test result mapping (mockable seam if needed)

---

### Task 3: Status UI wiring

**Files:**
- Modify: `AgentStatusActivity.kt`

- [x] **Step 1:** Grey explain under keep-alive card
- [x] **Step 2:** `XHS_APP_DETAILS` → app details for `com.xingin.xhs`
- [x] **Step 3:** Button「发布素材自检」runs self-check off main thread, shows result TextView/Toast, refreshHome

---

### Task 4: Version, build, publish, install

**Files:**
- Modify: `android/agent/build.gradle.kts` → `0.1.6` / `7`
- Optionally: `ops/m0/device-owner-pilot-runbook.md` version hint
- Optionally: `ops/m0/keep-alive-screen-check.sh` photo probe line

- [x] **Step 1:** Bump version
- [x] **Step 2:** `bash ops/m0/stage-apks.sh` (Docker) — staged manually after assemble (stage script stdout pipe issue)
- [x] **Step 3:** `bash ops/m0/publish-agent-release.sh`
- [x] **Step 4:** scp `agent-latest.apk` (+ `latest.json`) to `ubuntu@119.45.36.208:/opt/loanagent/agent-releases/`
- [x] **Step 5:** adb install `-r` on Note 12 Turbo; verify versionName/code `0.1.6` / `7`
- [x] **Step 6:** Re-apply HyperOS appops `10021/10020/10008 allow` if install resets them

---

## Done when

- Status page shows photo permission state + self-check button
- Unit tests for `XhsPhotoAccess` + checker green
- Device reports `0.1.6` / `7`
- `https://android.hashhub.com/downloads/agent-latest.apk` (or equivalent) serves new sha

#!/usr/bin/env python3
"""Cloud→device E2E matrix driver (runs inside control-plane container)."""
from __future__ import annotations

import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from typing import Any

OPS = os.environ["OPS_TOKEN"]
BASE = "http://127.0.0.1:8000"
ACCOUNT = os.environ.get("E2E_ACCOUNT_ID", "phone-publisher-1")
DEVICE = os.environ.get("E2E_DEVICE_ID", "dev-dc938474adafd365")
NOTE_ID = os.environ.get("E2E_NOTE_ID", "03495390-5e88-4c28-9260-48e959d47180")
RUN = dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%SZ")
RESULTS: list[dict[str, Any]] = []


def call(method: str, path: str, body: Any = None, extra: dict | None = None, timeout: int = 30):
    headers = {"Authorization": f"Bearer {OPS}", "Content-Type": "application/json"}
    if extra:
        headers.update(extra)
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode() or "{}"
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return exc.code, payload


def wait_task(task_id: str, secs: int = 180) -> dict:
    last = None
    detail: dict = {}
    for _ in range(max(1, secs // 2)):
        _, detail = call("GET", f"/api/v1/tasks/{task_id}")
        state = (detail.get("status"), detail.get("error_code"), detail.get("effect_committed"))
        if state != last:
            print(f"  {task_id[:8]} {state}", flush=True)
            last = state
        if detail.get("status") in {
            "succeeded",
            "failed",
            "cancelled",
            "unknown",
            "reconcile_required",
        }:
            return detail
        time.sleep(2)
    return detail


def record(name: str, ok: bool, detail: dict | None = None, **extra: Any) -> None:
    row = {"name": name, "ok": ok, **extra}
    if detail:
        row.update(
            {
                "task_id": detail.get("task_id"),
                "status": detail.get("status"),
                "error_code": detail.get("error_code"),
                "effect_committed": detail.get("effect_committed"),
            }
        )
    RESULTS.append(row)
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name} {json.dumps({k: row.get(k) for k in ('task_id','status','error_code','effect_committed','http','note') if k in row}, ensure_ascii=False)}", flush=True)


def create_task(playbook: str, params: dict, wait_secs: int = 180) -> dict:
    code, body = call(
        "POST",
        "/api/v1/tasks",
        {"account_id": ACCOUNT, "playbook": playbook, "params": params},
        extra={"Idempotency-Key": str(uuid.uuid4())},
    )
    if code >= 400:
        return {"status": "http_error", "http": code, "body": body}
    return wait_task(body["task_id"], wait_secs)


def main() -> int:
    print(f"E2E_RUN={RUN} account={ACCOUNT} device={DEVICE}", flush=True)

    # 0) preflight
    code, devices = call("GET", "/api/v1/devices")
    items = devices if isinstance(devices, list) else devices.get("items", [])
    device = next((d for d in items if d.get("device_id") == DEVICE), None)
    record(
        "preflight_device_online",
        bool(device and device.get("online") and device.get("a11y_bound")),
        http=code,
        note=json.dumps(
            {
                k: (device or {}).get(k)
                for k in ("online", "a11y_bound", "wifi_connected", "last_seen_at")
            },
            ensure_ascii=False,
        ),
    )

    # 1) ensure_app_ready
    detail = create_task("ensure_app_ready@1.0", {}, 90)
    record("ensure_app_ready", detail.get("status") == "succeeded", detail)

    # 2) sync notes
    code, body = call("POST", "/api/v1/notes/sync", {"account_id": ACCOUNT})
    if code >= 400:
        record("sync_notes", False, http=code, note=str(body)[:200])
    else:
        detail = wait_task(body["task_id"], 180)
        record("sync_notes", detail.get("status") == "succeeded", detail)

    # 3) inbox sync
    code, body = call("POST", "/api/v1/inbox/sync", {"account_id": ACCOUNT})
    if code >= 400:
        record("inbox_sync", False, http=code, note=str(body)[:200])
    else:
        detail = wait_task(body["task_id"], 180)
        record("inbox_sync", detail.get("status") == "succeeded", detail)

    # 4) open + reply existing thread (if any)
    code, threads = call("GET", f"/api/v1/inbox/threads?account_id={ACCOUNT}")
    titems = threads if isinstance(threads, list) else threads.get("items", [])
    if not titems:
        record("inbox_open_reply", False, note="no_threads")
    else:
        thread_id = titems[0].get("thread_id") or titems[0].get("id")
        code, body = call("POST", f"/api/v1/inbox/threads/{thread_id}/open", {})
        if code >= 400:
            record("inbox_open", False, http=code, note=str(body)[:200])
        else:
            detail = wait_task(body["task_id"], 120)
            record("inbox_open", detail.get("status") == "succeeded", detail)
        reply_text = f"e2e-dm {RUN}"
        code, body = call(
            "POST",
            f"/api/v1/inbox/threads/{thread_id}/reply",
            {"account_id": ACCOUNT, "text": reply_text},
        )
        if code >= 400:
            record("inbox_reply", False, http=code, note=str(body)[:200])
        else:
            detail = wait_task(body["task_id"], 180)
            record(
                "inbox_reply",
                detail.get("status") == "succeeded" and detail.get("effect_committed") is True,
                detail,
            )

    # 5) media + content + immediate publish
    boundary = f"----e2e{uuid.uuid4().hex}"
    png = (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
        b"\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00"
        b"\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82"
    )
    multipart = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="file"; filename="e2e.png"\r\n'
        "Content-Type: image/png\r\n\r\n"
    ).encode() + png + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        BASE + "/api/v1/media",
        data=multipart,
        headers={
            "Authorization": f"Bearer {OPS}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            media = json.load(resp)
        media_ok = True
    except urllib.error.HTTPError as exc:
        media = json.loads(exc.read().decode() or "{}")
        media_ok = False
    record("media_upload", media_ok, note=str(media.get("media_id") or media)[:120])

    content_id = None
    if media_ok:
        code, content = call(
            "POST",
            "/api/v1/content",
            {
                "title": f"云测发帖-{RUN}",
                "body": f"e2e matrix publish {RUN}",
                "media_ids": [media["media_id"]],
            },
        )
        content_ok = code < 400
        content_id = content.get("content_id") if content_ok else None
        record("content_create", content_ok, http=code, note=str(content_id or content)[:120])
    else:
        record("content_create", False, note="skipped_no_media")

    if content_id:
        code, body = call(
            "POST",
            "/api/v1/publish/immediate",
            {"account_id": ACCOUNT, "content_id": content_id},
        )
        if code >= 400:
            record("publish_immediate", False, http=code, note=str(body)[:200])
        else:
            detail = wait_task(body["task_id"], 300)
            record(
                "publish_immediate",
                detail.get("status") == "succeeded" and detail.get("effect_committed") is True,
                detail,
            )

    # 6) comments on known note
    code, body = call(
        "POST",
        f"/api/v1/notes/{NOTE_ID}/comments",
        {"text": f"e2e-post {RUN}"},
    )
    if code >= 400:
        record("post_comment", False, http=code, note=str(body)[:200])
        comment_id = None
    else:
        detail = wait_task(body["task_id"], 180)
        record(
            "post_comment",
            detail.get("status") == "succeeded" and detail.get("effect_committed") is True,
            detail,
        )
        code, comments = call("GET", f"/api/v1/notes/{NOTE_ID}/comments")
        items = comments if isinstance(comments, list) else comments.get("items", [])
        comment_id = None
        for item in items:
            blob = str(item.get("body_summary") or "")
            if RUN in blob or f"e2e-post {RUN}" in blob:
                comment_id = item.get("comment_id")
                break
        if comment_id is None and items:
            # sync then retry
            code, sync_body = call("POST", f"/api/v1/notes/{NOTE_ID}/sync-comments", {})
            if code < 400:
                wait_task(sync_body["task_id"], 120)
            _, comments = call("GET", f"/api/v1/notes/{NOTE_ID}/comments")
            items = comments if isinstance(comments, list) else comments.get("items", [])
            for item in items:
                if "e2e-post" in str(item.get("body_summary") or ""):
                    comment_id = item.get("comment_id")
                    break

    if comment_id:
        code, body = call(
            "POST",
            f"/api/v1/comments/{comment_id}/reply",
            {"text": f"e2e-reply {RUN}"},
        )
        if code >= 400:
            record("reply_comment", False, http=code, note=str(body)[:200])
        else:
            detail = wait_task(body["task_id"], 180)
            record(
                "reply_comment",
                detail.get("status") == "succeeded" and detail.get("effect_committed") is True,
                detail,
            )
    else:
        record("reply_comment", False, note="no_comment_target")

    # 7) schedule create / pause account / cancel task / resume
    code, content = call(
        "POST",
        "/api/v1/content",
        {"title": f"排期-{RUN}", "body": "schedule body", "media_ids": []},
    )
    if code < 400:
        content_id = content["content_id"]
        start = (dt.datetime.now(dt.UTC) + dt.timedelta(days=1)).isoformat()
        end = (dt.datetime.now(dt.UTC) + dt.timedelta(days=1, hours=1)).isoformat()
        code, sched = call(
            "POST",
            "/api/v1/schedules",
            {
                "account_id": ACCOUNT,
                "content_id": content_id,
                "window_start": start,
                "window_end": end,
            },
        )
        record("schedule_create", code < 400, http=code, note=str(sched.get("schedule_id") or sched)[:120])
        if code < 400:
            sid = sched["schedule_id"]
            code, deleted = call("DELETE", f"/api/v1/schedules/{sid}")
            record("schedule_delete", code < 400, http=code, note=str(deleted)[:80])
    else:
        record("schedule_create", False, http=code, note=str(content)[:120])

    # cancel an accepted ensure task before execute if possible
    code, body = call(
        "POST",
        "/api/v1/tasks",
        {"account_id": ACCOUNT, "playbook": "ensure_app_ready@1.0", "params": {}},
        extra={"Idempotency-Key": str(uuid.uuid4())},
    )
    if code < 400:
        tid = body["task_id"]
        # brief window then cancel
        time.sleep(0.2)
        ccode, cbody = call("POST", f"/api/v1/tasks/{tid}/cancel", {})
        if ccode < 400:
            detail = wait_task(tid, 30)
            record("task_cancel", detail.get("status") == "cancelled", detail)
        else:
            # already executing/terminal — still acceptable if terminal
            detail = wait_task(tid, 90)
            record(
                "task_cancel",
                detail.get("status") in {"cancelled", "succeeded"},
                detail,
                note=f"cancel_http={ccode}",
            )
    else:
        record("task_cancel", False, http=code, note=str(body)[:120])

    code, paused = call("POST", f"/api/v1/accounts/{ACCOUNT}/pause", {})
    record("account_pause", code < 400, http=code, note=str(paused)[:80])
    code, blocked = call(
        "POST",
        "/api/v1/tasks",
        {"account_id": ACCOUNT, "playbook": "ensure_app_ready@1.0", "params": {}},
        extra={"Idempotency-Key": str(uuid.uuid4())},
    )
    record(
        "paused_blocks_dispatch",
        code >= 400,
        http=code,
        note=str(blocked.get("detail") or blocked)[:120],
    )
    code, resumed = call("POST", f"/api/v1/accounts/{ACCOUNT}/resume", {})
    record("account_resume", code < 400, http=code, note=str(resumed)[:80])

    # network policy rejection smoke (restore after)
    call(
        "PATCH",
        f"/api/v1/accounts/{ACCOUNT}",
        {"network_policy": "cellular_only"},
    )
    code, blocked = call(
        "POST",
        "/api/v1/tasks",
        {
            "account_id": ACCOUNT,
            "playbook": "publish_note@1.0",
            "params": {"title": "blocked", "body": "blocked"},
        },
        extra={"Idempotency-Key": str(uuid.uuid4())},
    )
    detail = blocked.get("detail") if isinstance(blocked, dict) else None
    policy_ok = code == 409 and isinstance(detail, dict) and detail.get("code") == "NETWORK_POLICY_VIOLATION"
    record("network_policy_blocks_publish", policy_ok, http=code, note=str(blocked)[:160])
    call(
        "PATCH",
        f"/api/v1/accounts/{ACCOUNT}",
        {"network_policy": "wifi_allowed", "daily_publish_quota": 20},
    )

    failed = [r for r in RESULTS if not r["ok"]]
    print("=== SUMMARY ===", flush=True)
    print(json.dumps({"run": RUN, "passed": len(RESULTS) - len(failed), "failed": len(failed), "total": len(RESULTS)}, ensure_ascii=False), flush=True)
    for row in failed:
        print("FAIL_DETAIL", json.dumps(row, ensure_ascii=False), flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env bash
set -euo pipefail
SERIAL="${REDMI_SERIAL:-192.168.3.157:40849}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

docker compose -f "$REPO_ROOT/infra/compose.yaml" --profile android run --rm --no-deps \
  -e REDMI_SERIAL="$SERIAL" \
  android-builder bash -s <<'EOS'
set -euo pipefail
SERIAL="${REDMI_SERIAL}"
adb connect "$SERIAL" >/dev/null
adb -s "$SERIAL" shell uiautomator dump /sdcard/uidump.xml >/dev/null
adb -s "$SERIAL" pull /sdcard/uidump.xml /tmp/uidump.xml >/dev/null
python3 <<'PY'
import re, subprocess
serial = "192.168.3.157:40849"
xml = open("/tmp/uidump.xml").read()
vals = sorted({m.group(1) for m in re.finditer(r'(?:text|content-desc)="([^"]+)"', xml) if m.group(1)})
print("LABELS", len(vals))
for v in vals[:40]:
    print(repr(v)[:140])
for label in ("从相册选择", "下一步", "开始创作", "添加标题", "允许", "始终允许", "完成"):
    for m in re.finditer(rf'text="{re.escape(label)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups())
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        print(f"TAP {label} {cx},{cy}")
        subprocess.check_call(["adb", "-s", serial, "shell", "input", "tap", str(cx), str(cy)])
        break
PY
sleep 2
adb -s "$SERIAL" shell uiautomator dump /sdcard/uidump2.xml >/dev/null
adb -s "$SERIAL" pull /sdcard/uidump2.xml /tmp/uidump2.xml >/dev/null
python3 <<'PY'
import re
xml = open("/tmp/uidump2.xml").read()
vals = sorted({m.group(1) for m in re.finditer(r'(?:text|content-desc)="([^"]+)"', xml) if m.group(1)})
print("AFTER", len(vals))
for v in vals[:50]:
    print(repr(v)[:140])
PY
EOS

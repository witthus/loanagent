#!/usr/bin/env bash
set -euo pipefail

script=/workspace/ops/m0/stage-apks.sh
compose=/workspace/infra/compose.yaml

grep -q "docker cp" "$script"
grep -q "/home/gradle/work/staged" "$script"
if grep -q "/workspace/ops/m0/generated" "$script"; then
  echo "stage script must not write the host bind mount from the container" >&2
  exit 1
fi
grep -q "chmod 0644" "$script"
grep -q "classification=UNTRUSTED_DEBUG_TEST_ONLY" "$script"
grep -q "HOME: /home/gradle/.gradle/adb-home" "$compose"
grep -q "ANDROID_USER_HOME: /home/gradle/.gradle/android-home" "$compose"
if grep -q "android-home:/home/gradle/.android" "$compose"; then
  echo "android home must use the writable Gradle cache volume" >&2
  exit 1
fi

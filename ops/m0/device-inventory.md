# M0 Xiaomi / Redmi Device Inventory

This is a template, not proof of execution. Keep values as `NOT_RUN` until copied from real device
output produced inside `android-builder`.

## Fleet gate

- Automated inventory gate: `NOT_RUN`
- Manual checklist gate: `NOT_RUN`
- Physical Go/No-Go: `NOT_RUN`
- Evidence file:
- Operator / timestamp:

## Xiaomi target A

- Serial: `NOT_RUN`
- Manufacturer: `NOT_RUN`
- Brand: `NOT_RUN`
- Model: `NOT_RUN`
- Domestic ROM / build: `NOT_RUN`
- Android version / SDK: `NOT_RUN`
- `sys.boot_completed`: `NOT_RUN`
- Device Owner component: `NOT_RUN`
- Device Controller version: `NOT_RUN`
- Agent version before install: `NOT_RUN`
- Agent version after upgrade: `NOT_RUN`
- Last install result: `NOT_RUN`
- Last reboot recovery result: `NOT_RUN`
- Checklist status: `NOT_RUN`

## Redmi target B

- Serial: `NOT_RUN`
- Manufacturer: `NOT_RUN`
- Brand: `NOT_RUN`
- Model: `NOT_RUN`
- Domestic ROM / build: `NOT_RUN`
- Android version / SDK: `NOT_RUN`
- `sys.boot_completed`: `NOT_RUN`
- Device Owner component: `NOT_RUN`
- Device Controller version: `NOT_RUN`
- Agent version before install: `NOT_RUN`
- Agent version after upgrade: `NOT_RUN`
- Last install result: `NOT_RUN`
- Last reboot recovery result: `NOT_RUN`
- Checklist status: `NOT_RUN`

## Additional evidence

For each device, attach the output of:

```text
adb -s <serial> shell dpm list owners
adb -s <serial> shell dumpsys package com.loanagent.devicecontroller
adb -s <serial> shell dumpsys package com.loanagent.agent
adb -s <serial> shell getprop
```

These are evidence commands to be run by `adb` inside the Docker Android builder only. The
`<serial>` marker must be replaced by a serial discovered by the container; do not run host `adb`.

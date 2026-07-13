# Redmi Note 12 Turbo M0 执行器真机测试

当前状态（2026-07-12 单机）：Redmi Note 12 Turbo 无障碍矩阵 `PARTIAL`（fixture + 核心 XHS
动作已通；INBOX hint / XHS SET_TEXT 仍缺）——见 `xhs-flow-matrix.md`。第二机 `NOT_RUN`；
DPC 两机门禁 `BLOCKED`；整体 Go/No-Go 仍为 `NO-GO`。以下是操作手册；真机证据以矩阵文件为准。

## 1. Docker-only 构建与交付

宿主机只运行 Docker、Docker Compose 和 Git。Android SDK、Gradle、ADB、Python 与校验工具
全部在 Compose 容器中运行。

```bash
docker compose -f infra/compose.yaml --profile android build android-builder
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  ./gradlew clean test lint lintRelease assembleDebug assembleRelease \
  -Dorg.gradle.dependency.verification.console=verbose --no-daemon \
  --project-cache-dir /home/gradle/work/project-cache \
  --dependency-verification=strict

bash ops/m0/stage-apks.sh
```

该脚本只调用 Compose Android 容器。稳定路径为
`ops/m0/generated/agent-m0-debug.apk`、`ops/m0/generated/device-controller-debug.apk` 和
`ops/m0/generated/checksums.sha256`；同时生成 `signing-certificates.sha256` 与
`provenance.txt`。脚本在容器内核对 APK checksum、提取签名证书 SHA-256，并明确标记
`UNTRUSTED_DEBUG_TEST_ONLY`。不要根据文件名推断构建身份，也不得作为生产签名制品。

## 2. 无线 ADB 配对、连接与安装

手机开启开发者选项和无线调试。配对端口与连接端口通常不同，环境变量必须填真实值。

```bash
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  adb pair "$REDMI_PAIR_ENDPOINT" "$REDMI_PAIR_CODE"
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  adb connect "$REDMI_ADB_ENDPOINT"
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  adb -s "$REDMI_SERIAL" install -r /workspace/ops/m0/generated/agent-m0-debug.apk
docker compose -f infra/compose.yaml --profile android run --rm --no-deps android-builder \
  adb -s "$REDMI_SERIAL" install -r \
  /home/gradle/work/build/fixture-app/outputs/apk/debug/fixture-app-debug.apk
```

第二机重复配对、连接和安装，使用独立 endpoint 与 serial。ADB 只用于安装、采集设备信息和读取
系统状态；不得用 ADB 静默开启无障碍或切换默认输入法。

## 3. 用户手动开启能力

1. 打开 Loanagent Agent，确认“无障碍: DISABLED”。
2. 点“打开系统无障碍设置”，由用户在系统 UI 中找到 `Loanagent M0 Accessibility`，阅读提示并
   手动开启。返回后确认状态为 `ENABLED`。
3. 点“打开系统输入法设置”，由用户手动启用 `Loanagent M0 Manual IME`。需要 fallback 时再由
   用户通过系统键盘切换器手动选中；Agent 不会也不能静默切换。
4. 确认前台包只会显示 `com.xingin.xhs` 或 `com.loanagent.fixture`。切换到其他 App 时动作必须
   返回拒绝。
5. 单步等待输入支持 `appears`、`disappears`、`pageChange:HOME`；前两者使用 selector 输入框。
   timeout 限制为 100–30000 ms。动作的 postcondition 使用相同语法，结果必须明确记录
   `ACTION_ACCEPTED`、`GESTURE_COMPLETED`、`POSTCONDITION_MET` 或
   `POSTCONDITION_TIMEOUT`，不得把 gesture dispatch 接受误记为完成。

## 4. Debug-only ADB 单步桥

以下 receiver 只存在于 debug APK，且必须使用显式组件；系统以 `android.permission.DUMP`
限制为 adb shell/system 调用。每个临时容器都先重新连接无线 ADB。先设置宿主机环境变量
`REDMI_ADB_ENDPOINT` 和 `REDMI_SERIAL`，再按需执行一条命令：

```bash
# OBSERVE
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  -e REDMI_ADB_ENDPOINT -e REDMI_SERIAL android-builder bash -lc '
    adb connect "$REDMI_ADB_ENDPOINT" >/dev/null
    adb -s "$REDMI_SERIAL" shell am broadcast \
      -a com.loanagent.agent.action.M0_DEBUG_COMMAND \
      -n com.loanagent.agent/.M0DebugCommandReceiver \
      --es command OBSERVE
    adb -s "$REDMI_SERIAL" shell run-as com.loanagent.agent \
      cat files/m0-debug-result.json
  '

# CLICK（必须显式 confirmed=true）
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  -e REDMI_ADB_ENDPOINT -e REDMI_SERIAL android-builder bash -lc '
    adb connect "$REDMI_ADB_ENDPOINT" >/dev/null
    adb -s "$REDMI_SERIAL" shell am broadcast \
      -a com.loanagent.agent.action.M0_DEBUG_COMMAND \
      -n com.loanagent.agent/.M0DebugCommandReceiver \
      --es command CLICK \
      --es selector "text=普通按钮;clickable=true" \
      --ez confirmed true
    adb -s "$REDMI_SERIAL" shell run-as com.loanagent.agent \
      cat files/m0-debug-result.json
  '

# SET_TEXT（空字符串可用于清空；必须显式 confirmed=true）
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  -e REDMI_ADB_ENDPOINT -e REDMI_SERIAL android-builder bash -lc '
    adb connect "$REDMI_ADB_ENDPOINT" >/dev/null
    adb -s "$REDMI_SERIAL" shell am broadcast \
      -a com.loanagent.agent.action.M0_DEBUG_COMMAND \
      -n com.loanagent.agent/.M0DebugCommandReceiver \
      --es command SET_TEXT \
      --es selector "contentDescription=fixture text input" \
      --es text "M0 safe test" \
      --ez confirmed true
    adb -s "$REDMI_SERIAL" shell run-as com.loanagent.agent \
      cat files/m0-debug-result.json
  '

# WAIT；condition 还可为 disappears 或 pageChange:HOME
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  -e REDMI_ADB_ENDPOINT -e REDMI_SERIAL android-builder bash -lc '
    adb connect "$REDMI_ADB_ENDPOINT" >/dev/null
    adb -s "$REDMI_SERIAL" shell am broadcast \
      -a com.loanagent.agent.action.M0_DEBUG_COMMAND \
      -n com.loanagent.agent/.M0DebugCommandReceiver \
      --es command WAIT \
      --es condition appears \
      --es selector "text=普通按钮" \
      --ei timeout_ms 3000
    adb -s "$REDMI_SERIAL" shell run-as com.loanagent.agent \
      cat files/m0-debug-result.json
  '

# OCR_MEMORY；只返回脱敏 OCR 文本，不保存截图
docker compose -f infra/compose.yaml --profile android run --rm --no-deps \
  -e REDMI_ADB_ENDPOINT -e REDMI_SERIAL android-builder bash -lc '
    adb connect "$REDMI_ADB_ENDPOINT" >/dev/null
    adb -s "$REDMI_SERIAL" shell am broadcast \
      -a com.loanagent.agent.action.M0_DEBUG_COMMAND \
      -n com.loanagent.agent/.M0DebugCommandReceiver \
      --es command OCR_MEMORY \
      --ei timeout_ms 5000
    adb -s "$REDMI_SERIAL" shell run-as com.loanagent.agent \
      cat files/m0-debug-result.json
  '
```

桥只接受 `OBSERVE`、`CLICK`、`SET_TEXT`、`WAIT`、`OCR_MEMORY`，每次广播只执行一次。前台
lease 必须是 `com.xingin.xhs` 或 `com.loanagent.fixture`；切换窗口后旧 lease 立即失效。
`timeout_ms` 限制为 100–8000 ms，bridge watchdog 最晚 9 秒结束。`SWIPE`、`BACK`、循环、
保存原图以及明显的发布/发送 selector 均拒绝。结果只会原子覆盖 app-private
`files/m0-debug-result.json`，不得改用公共存储或从 logcat 采集 selector、输入文本和 OCR 内容。

## 5. Fixture 测试顺序

1. 启动 Loanagent Fixture，观察首页 snapshot。
2. 使用 `text=普通按钮;clickable=true` 单次点击，确认节点动作成功。
3. 使用输入框的 viewId 或 `desc=fixture text input` 单次 setText，再清空。
4. 打开弹窗并重新观察。
5. 分别触发 `BUSINESS_BLOCKED`、`LOGIN_REQUIRED`，确认页面分类。
6. 对列表执行一次 swipe。
7. 将自绘区域置于屏幕内，主动点击“截图 + 中文 OCR（仅内存）”，确认识别到
   `自绘 OCR：中文识别 2026` 的合理子集。
   默认路径不写截图文件；仅在用户再次确认“保存原图”时写入 app-private cache。
8. 清除诊断缓存。

## 6. 小红书安全测试顺序

按 `ops/m0/xhs-flow-matrix.md` 逐行执行。先观察，再定位，再执行一个可逆动作。输入只使用无敏感
测试文本；进入发布入口、编辑器、评论区或消息页后，不点击最终发布/发送，不上传素材，不处理
真实私信。每次动作前检查前台包、selector 和二次确认弹窗。若 page_hint 为 `UNKNOWN`，停止动作
并记录，不通过坐标猜测。

所有真实小红书步骤在实际完成并附证据前保持 `NOT_RUN`。

## 7. OCR 无 GMS 依据

直接依赖使用 `com.google.mlkit:text-recognition-chinese:16.0.1` 的 bundled 入口，而不是把
`com.google.android.gms:play-services-mlkit-text-recognition-chinese` 作为 unbundled 入口。
Google 的 Text Recognition v2 Android 文档将前者列为 **Bundled**，说明模型在构建时静态链接
进应用、安装后立即可用；后者入口才由 Google Play services 动态下载模型：
<https://developers.google.com/ml-kit/vision/text-recognition/v2/android>。

因此本 APK 的中文 OCR 不需要设备具备 GMS，也没有首次运行模型下载。Redmi Note 12 Turbo
（`OS3.0.5.0.VMRCNXM`、Android SDK 35）已完成仅内存中文 OCR 真机验证；第二款目标 ROM
兼容性仍为 `NOT_RUN`。Agent manifest 还显式移除了 OCR 依赖合并进来的
`INTERNET` 与 `ACCESS_NETWORK_STATE` 权限；可用容器内 `aapt dump permissions` 复核最终 APK
没有网络权限。

## 8. 隐私清理与记录

1. 在 Agent 中点“清除全部诊断缓存”。
2. 确认没有把 snapshot、截图或 OCR 文本复制到公共存储；用户主动分享出的文件按测试记录制度
   单独删除。
3. 删除测试输入和小红书草稿，确认没有发布或发送。
4. 记录 Agent/fixture/小红书版本、ROM build、APK SHA-256、操作者和时间。
5. 若设备离场，卸载 fixture 与 M0 Agent；DPC 清理按 Device Owner checklist 执行。

真机结束前，`ops/m0/device-owner-checklist.md` 的两机门禁仍保持 `NOT_RUN`。

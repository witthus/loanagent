package com.loanagent.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AgentStatusActivity : Activity() {
    private lateinit var identityCard: TextView
    private lateinit var connectionCard: TextView
    private lateinit var keepAliveCard: LinearLayout
    private lateinit var readinessLine: TextView
    private lateinit var mediaSelfCheckResult: TextView
    private lateinit var diagnosticsPanel: LinearLayout
    private lateinit var diagnosticsToggle: Button
    private lateinit var output: TextView
    private lateinit var selectorInput: EditText
    private lateinit var textInput: EditText
    private lateinit var waitConditionInput: EditText
    private lateinit var waitTimeoutInput: EditText
    private lateinit var postconditionInput: EditText
    private val requestGate = SingleFlightRequestGate()
    private var destroyed = false
    private var diagnosticsExpanded = false
    private var mediaSelfCheckBusy = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTicker = object : Runnable {
        override fun run() {
            if (destroyed) return
            refreshHome()
            uiHandler.postDelayed(this, 2_500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destroyed = false
        DiagnosticCache(this).deleteExpired()
        if (SupportedDeviceGate.isSupported()) {
            startDebugKeepAliveIfPresent()
        }
        setContentView(buildUi())
        refreshHome()
    }

    private fun startDebugKeepAliveIfPresent() {
        if (!BuildConfig.DEBUG) return
        try {
            val starter = Class.forName("com.loanagent.agent.M0DebugKeepAliveService")
            starter.getMethod("start", android.content.Context::class.java).invoke(null, this)
        } catch (_: ReflectiveOperationException) {
            // Release builds omit the debug keep-alive / cloud bridge.
        }
    }

    override fun onResume() {
        super.onResume()
        if (::identityCard.isInitialized) refreshHome()
        uiHandler.removeCallbacks(refreshTicker)
        uiHandler.post(refreshTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshTicker)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        uiHandler.removeCallbacks(refreshTicker)
        requestGate.destroy()
        super.onDestroy()
    }

    private fun buildUi(): View {
        identityCard = TextView(this).apply {
            textSize = 15f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
        }
        connectionCard = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
        }
        keepAliveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        readinessLine = TextView(this).apply {
            textSize = 14f
            setPadding(0, 4, 0, 8)
        }
        mediaSelfCheckResult = TextView(this).apply {
            textSize = 13f
            setPadding(0, 4, 0, 8)
            setTextIsSelectable(true)
        }
        output = TextView(this).apply {
            id = DiagnosticIds.OUTPUT
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 32)
        }
        selectorInput = EditText(this).apply {
            id = DiagnosticIds.SELECTOR
            hint = "selector: viewId=...;text=...;contentDescription=...;className=...;clickable=true"
            maxLines = 3
        }
        textInput = EditText(this).apply {
            hint = "单次 setText 内容（不会保存）"
            maxLines = 3
        }
        waitConditionInput = EditText(this).apply {
            id = DiagnosticIds.WAIT_CONDITION
            hint = "wait: appears | disappears | pageChange:HOME"
            setText("appears")
        }
        waitTimeoutInput = EditText(this).apply {
            id = DiagnosticIds.WAIT_TIMEOUT
            hint = "timeout ms（100..30000）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3000")
        }
        postconditionInput = EditText(this).apply {
            id = DiagnosticIds.POSTCONDITION
            hint = "可选 postcondition: disappears | appears | pageChange:HOME"
        }
        diagnosticsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "高级诊断（仅单次、用户触发）"
                textSize = 13f
            })
            button("打开系统无障碍设置") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            button("打开系统输入法设置") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }.id = DiagnosticIds.IME_SETTINGS_BUTTON
            button("启动小红书") { launchXhs() }
            button("观察当前页面") { observePage() }
            button("导出已脱敏 snapshot") { exportSnapshot() }
            addView(selectorInput)
            addView(textInput)
            addView(waitConditionInput)
            addView(waitTimeoutInput)
            addView(postconditionInput)
            button("单步等待条件") { runWait() }.id = DiagnosticIds.WAIT_BUTTON
            button("单次 Click（需确认）") {
                confirmDangerous("确认执行一次 click？") { lease, token ->
                    runParsedAction(M0Action.CLICK, lease = lease, token = token)
                }
            }.id = DiagnosticIds.CLICK_BUTTON
            button("单次 SetText（需确认）") {
                confirmDangerous("确认向匹配节点写入一次文本？") { lease, token ->
                    runParsedAction(M0Action.SET_TEXT, lease = lease, token = token)
                }
            }
            button("单次向上 Swipe（需确认）") {
                confirmDangerous("确认执行一次屏幕中心向上滑动？") { lease, token ->
                    val metrics = resources.displayMetrics
                    runParsedAction(
                        M0Action.SWIPE,
                        SwipeSpec(
                            metrics.widthPixels / 2,
                            metrics.heightPixels * 3 / 4,
                            metrics.widthPixels / 2,
                            metrics.heightPixels / 4,
                            500,
                        ),
                        lease,
                        token,
                    )
                }
            }
            button("单次 Back（需确认）") {
                confirmDangerous("确认执行一次全局返回？") { lease, token ->
                    runParsedAction(M0Action.BACK, lease = lease, token = token)
                }
            }
            button("截图 + 中文 OCR（仅内存）") {
                screenshotAndOcr(saveOriginal = false)
            }.id = DiagnosticIds.SCREENSHOT_MEMORY_BUTTON
            button("截图 + OCR 并保存原图（需确认）") {
                confirmDangerous("确认将一次截图保存到应用私有缓存？") { lease, token ->
                    screenshotAndOcr(saveOriginal = true, lease = lease, token = token)
                }
            }
            button("清除全部诊断缓存") {
                val count = DiagnosticCache(this@AgentStatusActivity).clear()
                output.text = "CLEARED:$count"
            }
            addView(TextView(context).apply {
                text = "结果（本地；密码与手机号/验证码/身份证字段已脱敏）"
            })
            addView(output)
        }
        diagnosticsToggle = Button(this).apply {
            text = "高级诊断 ▾"
            setOnClickListener {
                diagnosticsExpanded = !diagnosticsExpanded
                diagnosticsPanel.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
                text = if (diagnosticsExpanded) "高级诊断 ▴" else "高级诊断 ▾"
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            addView(TextView(context).apply {
                text = "矩阵助手"
                textSize = 22f
            })
            addView(TextView(context).apply {
                text = "设备健康 · 熄屏保活 · 云端连接"
                textSize = 13f
                setTextColor(0xFF666666.toInt())
            })
            sectionTitle("身份")
            addView(identityCard)
            button("复制设备 ID") { copyDeviceId() }
            sectionTitle("连接")
            addView(connectionCard)
            sectionTitle("熄屏保活检查")
            addView(keepAliveCard)
            sectionTitle("执行就绪")
            addView(readinessLine)
            addView(
                TextView(context).apply {
                    text =
                        "云端发布图由矩阵助手写入系统相册 DCIM/Camera；小红书需开启照片/相册权限才能选到素材。"
                    textSize = 12f
                    setTextColor(0xFF666666.toInt())
                    setPadding(0, 0, 0, 6)
                },
            )
            button("发布素材自检") { runPublishMediaSelfCheck() }
            addView(mediaSelfCheckResult)
            button("刷新本页状态") { refreshHome() }
            addView(TextView(context).apply {
                text = "刷新：重读身份/连接/保活检查（心跳由后台约 30s 自动更新）"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, 8)
            })
            addView(diagnosticsToggle)
            addView(diagnosticsPanel)
        }
        return ScrollView(this).apply { addView(content) }
    }

    private fun LinearLayout.sectionTitle(label: String) {
        addView(
            TextView(context).apply {
                text = label
                textSize = 16f
                setPadding(0, 16, 0, 4)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
    }

    private fun LinearLayout.button(label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            setOnClickListener { action() }
            addView(this)
        }

    private fun refreshHome() {
        val snap = SupportedDeviceGate.snapshotFromBuild()
        val supported = SupportedDeviceGate.isSupported(snap)
        val deviceId = DeviceIdentityStore.deviceId(this)
        val bridge = CloudBridgeStatusHub.get()
        val env = AndroidKeepAliveEnvironment(this) { controllerProvider() != null }
        env.syncOemBatteryAckWithSystem()
        val checker = KeepAliveHealthChecker(env)
        val issues = checker.issues()

        if (!supported) {
            identityCard.text = buildString {
                append("⚠ 设备不受支持\n")
                append(SupportedDeviceGate.unsupportedMessage(snap))
                append("\n\n已阻止云端心跳与任务通道。")
            }
            connectionCard.text = "—"
            keepAliveCard.removeAllViews()
            keepAliveCard.addView(TextView(this).apply { text = "—" })
            readinessLine.text = "—"
            return
        }

        // Drop legacy bad parse of JSON null → "null"
        val rawName = bridge.deviceDisplayName?.trim()
            ?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
        val displayName = rawName ?: "未命名（请到 Ops「设备」页改名）"
        val bound = bridge.boundAccount
        identityCard.text = buildString {
            append("显示名: $displayName\n")
            append("设备 ID: $deviceId\n")
            if (bound == null) {
                append("绑定账号: 未绑定\n")
            } else {
                val name = bound.displayName
                    ?.trim()
                    ?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                    ?: bound.accountId
                append("绑定账号: $name\n")
                append("账号 ID: ${bound.accountId}\n")
                append("角色: ${bound.role} · 状态: ${bound.status}\n")
            }
            append("版本: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n")
            append("本机: ${snap.summaryLine()}")
            bridge.geoLabel
                ?.trim()
                ?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                ?.let { append("\n地区: $it") }
        }

        connectionCard.text = buildConnectionText(bridge, env)

        keepAliveCard.removeAllViews()
        if (issues.isEmpty()) {
            keepAliveCard.addView(
                TextView(this).apply {
                    text = "✓ 熄屏保活检查通过"
                    setTextColor(0xFF1B7F3A.toInt())
                    textSize = 15f
                },
            )
        } else {
            keepAliveCard.addView(
                TextView(this).apply {
                    text = "下列问题需处理（每项下方有跳转按钮）："
                    textSize = 13f
                    setPadding(0, 0, 0, 6)
                },
            )
            issues.forEach { issue ->
                keepAliveCard.addView(
                    TextView(this).apply {
                        text = "⚠ ${issue.message}"
                        setTextColor(0xFFB00020.toInt())
                        textSize = 14f
                        setPadding(0, 8, 0, 2)
                    },
                )
                addIssueActionButtons(keepAliveCard, issue, env)
            }
        }
        keepAliveCard.addView(
            TextView(this).apply {
                text =
                    "说明: 自启动等厂商项请用 ops/m0/keep-alive-screen-check.sh 核对；发布选图依赖上方小红书相册权限与「发布素材自检」"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 8, 0, 0)
            },
        )
        readinessLine.text = checker.screenLine()
    }

    private fun runPublishMediaSelfCheck() {
        if (mediaSelfCheckBusy) return
        mediaSelfCheckBusy = true
        mediaSelfCheckResult.text = "自检进行中…"
        mediaSelfCheckResult.setTextColor(0xFF666666.toInt())
        Thread {
            val result = try {
                PublishMediaSelfCheck.run(applicationContext)
            } catch (error: Exception) {
                PublishMediaSelfCheckResult(
                    PublishMediaSelfCheckCode.WRITE_FAILED,
                    "自检异常: ${error.message}",
                )
            }
            runOnUiThread {
                mediaSelfCheckBusy = false
                if (destroyed) return@runOnUiThread
                val ok = result.code == PublishMediaSelfCheckCode.OK
                mediaSelfCheckResult.text = if (ok) "✓ ${result.message}" else "⚠ ${result.message}"
                mediaSelfCheckResult.setTextColor(
                    if (ok) 0xFF1B7F3A.toInt() else 0xFFB00020.toInt(),
                )
                refreshHome()
            }
        }.start()
    }

    private fun addIssueActionButtons(
        parent: LinearLayout,
        issue: KeepAliveIssue,
        env: AndroidKeepAliveEnvironment,
    ) {
        fun addBtn(action: SettingsAction) {
            if (action == SettingsAction.NONE) return
            parent.addView(
                Button(this).apply {
                    text = actionLabel(action)
                    setOnClickListener {
                        openSettings(action, env)
                        refreshHome()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
        }
        addBtn(issue.settingsAction)
        addBtn(issue.secondaryAction)
        if (issue.code == "BATTERY_OPTIMIZED" &&
            !env.ignoringBatteryOptimizations() &&
            !env.oemBatteryUnrestrictedAcked()
        ) {
            addBtn(SettingsAction.ACK_OEM_BATTERY_UNRESTRICTED)
        }
    }

    private fun buildConnectionText(
        bridge: CloudBridgeSnapshot,
        env: KeepAliveEnvironment,
    ): String {
        if (!BuildConfig.DEBUG) {
            return "本构建无云桥（Release）"
        }
        val bridgeLine = if (env.cloudBridgeRunning() || bridge.bridgeRunning) {
            "云桥服务: 运行中"
        } else {
            "云桥服务: 未运行"
        }
        val hb = when {
            bridge.lastHeartbeatOk == true && bridge.lastHeartbeatAtMs != null -> {
                val ago = ((System.currentTimeMillis() - bridge.lastHeartbeatAtMs) / 1000).coerceAtLeast(0)
                "最近心跳: 成功 · ${ago}s 前"
            }
            bridge.lastHeartbeatOk == false ->
                "最近心跳: 失败 · ${bridge.lastHeartbeatError ?: "unknown"}"
            else -> "最近心跳: 尚无"
        }
        val server = when (bridge.lastHeartbeatOk) {
            true -> "服务器: 可达"
            false -> "服务器: 不可达"
            null -> "服务器: 未知"
        }
        val host = bridge.controlPlaneHost ?: "—"
        val poll = when {
            bridge.lastPollOk == true && bridge.lastPollAtMs != null -> {
                val ago = ((System.currentTimeMillis() - bridge.lastPollAtMs) / 1000).coerceAtLeast(0)
                "HTTP 拉令: 正常 · ${ago}s 前"
            }
            bridge.lastPollOk == false -> "HTTP 拉令: 失败 · ${bridge.lastPollError ?: "unknown"}"
            else -> "HTTP 拉令: 尚无"
        }
        val mqtt = when (bridge.mqttConnected) {
            true -> "MQTT: 已连接（非主通道）"
            false -> "MQTT: 未连接（非主通道，HTTP 通即可）"
            null -> "MQTT: 未知（非主通道）"
        }
        val net = when {
            bridge.wifiConnected == true -> "网络: Wi‑Fi"
            bridge.cellularOk == true -> "网络: 蜂窝"
            bridge.wifiConnected == false && bridge.cellularOk == false -> "网络: 无有效上报"
            else -> "网络: 探测中"
        }
        return listOf(bridgeLine, hb, server, "控制面: $host", poll, mqtt, net).joinToString("\n")
    }

    private fun actionLabel(action: SettingsAction): String = when (action) {
        SettingsAction.ACCESSIBILITY -> "去开启无障碍"
        SettingsAction.INPUT_METHOD -> "去设置输入法（启用并选中 Loanagent）"
        SettingsAction.BATTERY_OPTIMIZATION -> "去允许忽略电池优化"
        SettingsAction.APP_BATTERY_DETAILS -> "打开应用详情（设耗电/无限制）"
        SettingsAction.ACK_OEM_BATTERY_UNRESTRICTED -> "已设为无限制（清除本提示）"
        SettingsAction.LOCK_SCREEN_SECURITY -> "去改锁屏（无密码/上滑）"
        SettingsAction.START_CLOUD_BRIDGE -> "启动云桥"
        SettingsAction.XHS_APP_DETAILS -> "去小红书权限（照片/相册）"
        SettingsAction.NONE -> "—"
    }

    private fun openSettings(action: SettingsAction, env: AndroidKeepAliveEnvironment? = null) {
        when (action) {
            SettingsAction.ACCESSIBILITY ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SettingsAction.INPUT_METHOD ->
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            SettingsAction.BATTERY_OPTIMIZATION -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                }
            }
            SettingsAction.APP_BATTERY_DETAILS -> {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            }
            SettingsAction.ACK_OEM_BATTERY_UNRESTRICTED -> {
                env?.ackOemBatteryUnrestricted()
            }
            SettingsAction.LOCK_SCREEN_SECURITY -> {
                val intents = listOf(
                    Intent(Settings.ACTION_SECURITY_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                )
                for (intent in intents) {
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        return
                    }
                }
            }
            SettingsAction.START_CLOUD_BRIDGE -> {
                startDebugKeepAliveIfPresent()
            }
            SettingsAction.XHS_APP_DETAILS -> {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${XhsPhotoAccess.XHS_PACKAGE}")
                    },
                )
            }
            SettingsAction.NONE -> Unit
        }
    }

    private fun copyDeviceId() {
        val id = DeviceIdentityStore.deviceId(this)
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("device_id", id))
        output.text = "COPIED_DEVICE_ID:$id"
    }

    private fun launchXhs() {
        val intent = packageManager.getLaunchIntentForPackage(XhsPhotoAccess.XHS_PACKAGE)
        if (intent == null) output.text = "XHS_NOT_INSTALLED" else startActivity(intent)
    }

    private fun observePage() {
        val controller = controllerProvider()
        val lease = controller?.currentLease()
        val snapshot = lease?.let { controller.observe(it) }
        output.text = if (snapshot == null) {
            "OBSERVE_FAILED_ENABLE_SERVICE_AND_OPEN_TARGET"
        } else {
            SnapshotJson.encode(snapshot)
        }
    }

    private fun exportSnapshot() {
        val controller = controllerProvider()
        val lease = controller?.currentLease()
        val snapshot = lease?.let { controller.observe(it) }
        if (snapshot == null) {
            output.text = "EXPORT_FAILED_NO_TARGET_SNAPSHOT"
            return
        }
        val file = DiagnosticCache(this).writeSnapshot(snapshot)
        val uri = Uri.Builder()
            .scheme("content")
            .authority("$packageName.diagnostics")
            .appendPath(file.name)
            .build()
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "导出已脱敏 snapshot"))
        output.text = "EXPORTED_PRIVATE_CACHE:${file.name}"
    }

    private fun screenshotAndOcr(
        saveOriginal: Boolean,
        lease: TargetLease? = controllerProvider()?.currentLease(),
        token: Long? = null,
    ) {
        val controller = controllerProvider()
        if (controller == null || lease == null) {
            token?.let(requestGate::finish)
            output.text = "SCREENSHOT_BLOCKED_ACCESSIBILITY_DISABLED"
            return
        }
        if (!lease.matches(controller.currentLease())) {
            token?.let(requestGate::finish)
            output.text = "TARGET_LEASE_LOST"
            return
        }
        val requestToken = token ?: beginRequest() ?: return
        output.text = "SCREENSHOT_RUNNING"
        val handle = controller.runVisualDiagnostic(
            VisualDiagnosticRequest(lease, saveOriginal),
        ) { result ->
            runOnUiThread {
                finishRequest(requestToken) {
                    output.text = buildString {
                        append(result.status)
                        append("\ncache=${result.screenshot?.name ?: "NONE"}")
                        append("\nocr=${result.ocrText ?: "NONE"}")
                    }
                }
            }
        }
        requestGate.attach(requestToken, handle)
    }

    private fun runWait() {
        val controller = controllerProvider()
        val lease = controller?.currentLease()
        if (controller == null || lease == null) {
            output.text = "ACCESSIBILITY_DISABLED"
            return
        }
        val parsed = try {
            parseCondition(waitConditionInput.text.toString()) to parseTimeout()
        } catch (_: IllegalArgumentException) {
            output.text = "INVALID_WAIT"
            return
        }
        val token = beginRequest() ?: return
        output.text = "WAIT_RUNNING"
        val handle = controller.waitForCondition(lease, parsed.first, parsed.second) { result ->
            runOnUiThread {
                finishRequest(token) {
                    output.text = "wait=${result.status};checks=${result.checks};elapsedMs=${result.elapsedMs}"
                    refreshHome()
                }
            }
        }
        requestGate.attach(token, handle)
    }

    private fun runParsedAction(
        action: M0Action,
        swipe: SwipeSpec? = null,
        lease: TargetLease,
        token: Long,
    ) {
        val request = try {
            ActionRequest(
                action = action,
                selector = if (action == M0Action.CLICK || action == M0Action.SET_TEXT) {
                    StrictSelectorParser.parse(selectorInput.text.toString())
                } else {
                    null
                },
                text = if (action == M0Action.SET_TEXT) textInput.text.toString() else null,
                swipe = swipe,
                timeoutMs = parseTimeout(),
                postcondition = parseOptionalPostcondition(),
                expectedLease = lease,
            )
        } catch (_: IllegalArgumentException) {
            requestGate.finish(token)
            output.text = "INVALID_ACTION_OR_CONDITION"
            return
        }
        runAction(request, token)
    }

    private fun runAction(request: ActionRequest, token: Long) {
        val controller = controllerProvider()
        val lease = request.expectedLease
        if (controller == null) {
            requestGate.finish(token)
            output.text = "ACCESSIBILITY_DISABLED"
            return
        }
        if (!lease.matches(controller.currentLease())) {
            requestGate.finish(token)
            output.text = "TARGET_LEASE_LOST"
            return
        }
        output.text = "ACTION_RUNNING"
        val handle = controller.executeAction(request) { result ->
            runOnUiThread {
                finishRequest(token) {
                    output.text = "status=${result.status};stage=${result.stage};action=${result.action};" +
                        "path=${result.path};fallback=${result.fallbackUsed};message=${result.message}"
                    refreshHome()
                }
            }
        }
        requestGate.attach(token, handle)
    }

    private fun beginRequest(): Long? {
        val token = requestGate.begin()
        if (token == null) output.text = "REQUEST_IN_FLIGHT"
        return token
    }

    private inline fun finishRequest(token: Long, update: () -> Unit) {
        if (destroyed || !requestGate.finish(token)) return
        update()
    }

    private fun parseTimeout(): Long =
        waitTimeoutInput.text.toString().toLongOrNull()?.coerceIn(100, 30_000)
            ?: throw IllegalArgumentException("Invalid timeout")

    private fun parseOptionalPostcondition(): WaitCondition? =
        postconditionInput.text.toString().trim().takeIf(String::isNotEmpty)?.let(::parseCondition)

    private fun parseCondition(raw: String): WaitCondition {
        val value = raw.trim()
        return when {
            value == "appears" ->
                WaitCondition.SelectorAppears(StrictSelectorParser.parse(selectorInput.text.toString()))
            value == "disappears" ->
                WaitCondition.SelectorDisappears(StrictSelectorParser.parse(selectorInput.text.toString()))
            value.startsWith("pageChange:") -> {
                val hint = PageHint.valueOf(value.substringAfter(':').trim())
                WaitCondition.PageHintChanges(hint)
            }
            else -> throw IllegalArgumentException("Unknown wait condition")
        }
    }

    private fun confirmDangerous(
        message: String,
        action: (TargetLease, Long) -> Unit,
    ) {
        val controller = controllerProvider()
        val lease = controller?.currentLease()
        if (controller == null || lease == null) {
            output.text = "ACCESSIBILITY_DISABLED"
            return
        }
        val token = beginRequest() ?: return
        var confirmed = false
        AlertDialog.Builder(this)
            .setTitle("M0 单次危险动作确认")
            .setMessage("$message\n租约: $lease")
            .setNegativeButton("取消") { _, _ ->
                requestGate.finish(token)
            }
            .setPositiveButton("确认") { _, _ ->
                confirmed = true
                if (!lease.matches(controller.currentLease())) {
                    requestGate.finish(token)
                    output.text = "TARGET_LEASE_LOST"
                } else {
                    action(lease, token)
                }
            }
            .create()
            .apply {
                setOnDismissListener {
                    if (!confirmed) requestGate.finish(token)
                }
                show()
            }
    }

    companion object {
        private val defaultControllerProvider: () -> M0DiagnosticController? = {
            M0AccessibilityService.instance
        }

        internal var controllerProvider: () -> M0DiagnosticController? = defaultControllerProvider

        internal fun resetControllerProvider() {
            controllerProvider = defaultControllerProvider
        }
    }
}

object DiagnosticIds {
    const val WAIT_CONDITION = 0x2001
    const val WAIT_TIMEOUT = 0x2002
    const val POSTCONDITION = 0x2003
    const val WAIT_BUTTON = 0x2004
    const val IME_SETTINGS_BUTTON = 0x2005
    const val CLICK_BUTTON = 0x2006
    const val SELECTOR = 0x2007
    const val OUTPUT = 0x2008
    const val SCREENSHOT_MEMORY_BUTTON = 0x2009
}

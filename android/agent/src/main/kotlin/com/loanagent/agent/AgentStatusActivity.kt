package com.loanagent.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AgentStatusActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var selectorInput: EditText
    private lateinit var textInput: EditText
    private lateinit var waitConditionInput: EditText
    private lateinit var waitTimeoutInput: EditText
    private lateinit var postconditionInput: EditText
    private val requestGate = SingleFlightRequestGate()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destroyed = false
        DiagnosticCache(this).deleteExpired()
        if (SupportedDeviceGate.isSupported()) {
            startDebugKeepAliveIfPresent()
        }
        setContentView(buildUi())
        refreshStatus()
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
        if (::status.isInitialized) refreshStatus()
    }

    override fun onDestroy() {
        destroyed = true
        requestGate.destroy()
        super.onDestroy()
    }

    private fun buildUi(): View {
        status = TextView(this).apply { textSize = 16f }
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
            addView(TextView(context).apply {
                text = "矩阵助手"
                textSize = 20f
            })
            addView(TextView(context).apply {
                text = "M0 无障碍执行器诊断（仅单次、用户触发）"
                textSize = 14f
            })
            addView(status)
            button("刷新状态") { refreshStatus() }
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
        return ScrollView(this).apply { addView(content) }
    }

    private fun LinearLayout.button(label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            setOnClickListener { action() }
            addView(this)
        }

    private fun refreshStatus() {
        val snap = SupportedDeviceGate.snapshotFromBuild()
        val supported = SupportedDeviceGate.isSupported(snap)
        val ime = M0InputMethodService.status(this)
        val controller = controllerProvider()
        val deviceId = DeviceIdentityStore.deviceId(this)
        status.text = buildString {
            append("\n应用版本: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            if (!supported) {
                append("\n\n⚠ 设备不受支持\n")
                append(SupportedDeviceGate.unsupportedMessage(snap))
                append("\n\n已阻止云端心跳与任务通道。\n")
                return@buildString
            }
            append("\n设备型号: ${SupportedDeviceGate.REQUIRED_LABEL}（已通过）")
            append("\n本机: ${snap.summaryLine()}")
            append("\n设备 ID: $deviceId")
            append("\n无障碍: ${if (controller != null) "ENABLED" else "DISABLED"}")
            append("\nIME: enabled=${ime.enabled}, selected=${ime.selected}")
            append("\n前台租约: ${controller?.currentLease() ?: "NONE"}")
            append("\n恢复记录: ${AgentRecoveryStore(this@AgentStatusActivity).status()}\n")
            append("\n说明: 打开本页并保持运行后，云端「设备」页会出现此设备，可新建账号并绑定。\n")
        }
    }

    private fun launchXhs() {
        val intent = packageManager.getLaunchIntentForPackage(XHS_PACKAGE)
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
                    refreshStatus()
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
                    refreshStatus()
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
        private const val XHS_PACKAGE = "com.xingin.xhs"
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

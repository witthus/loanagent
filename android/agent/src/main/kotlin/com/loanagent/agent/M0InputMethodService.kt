package com.loanagent.agent

import android.content.ComponentName
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

class M0InputMethodService : InputMethodService() {
    override fun onCreateInputView(): View {
        val input = EditText(this).apply {
            hint = "M0 手动输入（不保存）"
            maxLines = 3
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(input)
            addView(Button(context).apply {
                text = "Commit text"
                setOnClickListener {
                    commitText(currentInputConnection, input.text?.toString().orEmpty())
                    input.text?.clear()
                }
            })
            addView(Button(context).apply {
                text = "Clear field"
                setOnClickListener { clearText(currentInputConnection) }
            })
        }
    }

    internal fun commitText(connection: InputConnection?, text: String): Boolean =
        connection?.commitText(text.take(MAX_INPUT_LENGTH), 1) == true

    internal fun clearText(connection: InputConnection?): Boolean {
        connection ?: return false
        connection.beginBatchEdit()
        return try {
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.commitText("", 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    companion object {
        private const val MAX_INPUT_LENGTH = 4_000

        fun status(context: Context): ImeStatus {
            val expected = ComponentName(context, M0InputMethodService::class.java)
            val enabled = try {
                val manager = context.getSystemService(InputMethodManager::class.java)
                manager?.enabledInputMethodList.orEmpty().map { info ->
                    ComponentName(info.packageName, info.serviceName)
                }
            } catch (_: SecurityException) {
                emptyList()
            }
            val selected = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                )
            } catch (_: SecurityException) {
                null
            }
            return ImeStatusResolver(expected).resolve(enabled, selected)
        }
    }
}

internal class ImeStatusResolver(
    private val expected: ComponentName,
) {
    fun resolve(
        enabledComponents: List<ComponentName>,
        selectedId: String?,
    ): ImeStatus {
        val selected = try {
            selectedId?.let(ComponentName::unflattenFromString)
        } catch (_: RuntimeException) {
            null
        }
        return ImeStatus(
            enabled = expected in enabledComponents,
            selected = expected == selected,
        )
    }
}

data class ImeStatus(
    val enabled: Boolean,
    val selected: Boolean,
)

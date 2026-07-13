package com.loanagent.fixture

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

class FixtureActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildPage())
    }

    fun fixtureStatus(): String = status.text.toString()

    private fun buildPage(): View {
        status = TextView(this).apply {
            id = FixtureIds.STATUS
            text = "Fixture READY：首页 关注 发现"
            textSize = 18f
        }
        val list = ListView(this).apply {
            id = FixtureIds.SCROLL_LIST
            adapter = ArrayAdapter(
                this@FixtureActivity,
                android.R.layout.simple_list_item_1,
                (1..40).map { "可滚动列表项 $it" },
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600,
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 40)
            addView(status)
            addView(Button(context).apply {
                id = FixtureIds.NORMAL_BUTTON
                text = "普通按钮"
                contentDescription = "fixture normal button"
                setOnClickListener { status.text = "普通按钮已点击" }
            })
            addView(EditText(context).apply {
                id = FixtureIds.TEXT_INPUT
                hint = "Fixture 输入框"
                contentDescription = "fixture text input"
            })
            addView(Button(context).apply {
                id = FixtureIds.DIALOG_BUTTON
                text = "打开弹窗"
                setOnClickListener {
                    AlertDialog.Builder(this@FixtureActivity)
                        .setTitle("Fixture 弹窗")
                        .setMessage("可定位的弹窗正文")
                        .setPositiveButton("确定", null)
                        .show()
                }
            })
            addView(Button(context).apply {
                id = FixtureIds.BUSINESS_BLOCKED
                text = "BUSINESS_BLOCKED"
                setOnClickListener { status.text = "业务升级维护中，当前暂不可用" }
            })
            addView(Button(context).apply {
                id = FixtureIds.LOGIN_REQUIRED
                text = "LOGIN_REQUIRED"
                setOnClickListener { status.text = "请登录：手机号与验证码" }
            })
            addView(TextView(context).apply {
                text = "下方为故意没有可访问节点的自绘区域，用于 OCR fallback。"
            })
            addView(CustomDrawnRegion(context).apply {
                id = FixtureIds.CUSTOM_DRAWN
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    240,
                )
            })
            addView(list)
        }
        return ScrollView(this).apply { addView(content) }
    }
}

object FixtureIds {
    const val STATUS = 0x1001
    const val NORMAL_BUTTON = 0x1002
    const val TEXT_INPUT = 0x1003
    const val SCROLL_LIST = 0x1004
    const val DIALOG_BUTTON = 0x1005
    const val BUSINESS_BLOCKED = 0x1006
    const val LOGIN_REQUIRED = 0x1007
    const val CUSTOM_DRAWN = 0x1008
}

private class CustomDrawnRegion(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(70, 70, 120))
        canvas.drawText("自绘 OCR：中文识别 2026", 24f, 120f, paint)
    }
}

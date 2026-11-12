package com.tvbox.kotlin.core.ui.dialog

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import com.tvbox.kotlin.core.ui.R as uiR

/**
 * 通用提示弹窗：提示文案 + 左右按钮，用于配置缺失等需用户确认的场景。
 * 与列表选择/确认类弹窗统一主题，保证全局弹窗样式一致。
 */
class TipDialog(
    context: Context,
    private val tip: String,
    private val left: String,
    private val right: String,
    private val listener: OnListener
) : BaseDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_tip)
        setCanceledOnTouchOutside(false)
        findViewById<TextView>(uiR.id.tipInfo).text = tip
        findViewById<TextView>(uiR.id.leftBtn).text = left
        findViewById<TextView>(uiR.id.rightBtn).text = right
        findViewById<TextView>(uiR.id.leftBtn).setOnClickListener {
            dismiss()
            listener.left()
        }
        findViewById<TextView>(uiR.id.rightBtn).setOnClickListener {
            dismiss()
            listener.right()
        }
        setOnCancelListener { listener.cancel() }
    }

    interface OnListener {
        fun left()
        fun right()
        fun cancel()
    }
}

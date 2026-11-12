package com.tvbox.kotlin.core.ui.dialog

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import com.tvbox.kotlin.core.ui.R as uiR

/**
 * 简介弹窗：点击详情页简介弹出完整内容，内容过长时可滚动。
 * setContentView 在 onCreate 中完成，[setDescribe] 可能在 show() 之前调用，
 * 因此先暂存文本，onCreate 时统一赋值。
 */
class DescDialog(context: Context) : BaseDialog(context) {

    private var describeText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_desc)
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(uiR.id.describe)?.text = describeText
    }

    fun setDescribe(describe: String): DescDialog {
        describeText = describe
        // 已 show 时同步刷新；未 show 时由 onCreate 赋值
        findViewById<TextView>(uiR.id.describe)?.text = describeText
        return this
    }
}

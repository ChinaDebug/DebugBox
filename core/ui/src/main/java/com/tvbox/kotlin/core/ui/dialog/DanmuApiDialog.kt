package com.tvbox.kotlin.core.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.tvbox.kotlin.core.common.util.HawkUtils
import com.tvbox.kotlin.core.ui.R as uiR

/**
 * 弹幕接口设置对话框：编辑兼容接口根地址。
 *
 * 默认使用内置在线弹幕库地址；保存后在线搜索与弹幕拉取立即使用新地址。
 */
class DanmuApiDialog(
    context: Context,
    private val onSaved: ((String) -> Unit)? = null
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_danmu_api)
        setCanceledOnTouchOutside(true)

        val input = findViewById<EditText>(uiR.id.et_danmu_api)
        // 内置默认地址不展示，输入框留空；用户填写的内容才作为自定义地址
        val current = HawkUtils.getDanmuApi()
        if (current.trim().trimEnd('/') != HawkUtils.DEFAULT_DANMU_API) {
            input.setText(current)
            input.setSelection(input.text.length)
        }

        findViewById<View>(uiR.id.btn_danmu_api_default).setOnClickListener {
            // 清空即回到内置接口，不展示内置地址
            input.setText("")
        }
        findViewById<View>(uiR.id.btn_danmu_api_cancel).setOnClickListener { dismiss() }
        findViewById<View>(uiR.id.btn_danmu_api_confirm).setOnClickListener {
            val value = input.text.toString().trim().ifEmpty { HawkUtils.DEFAULT_DANMU_API }
            HawkUtils.setDanmuApi(value)
            Toast.makeText(context, uiR.string.danmu_api_saved, Toast.LENGTH_SHORT).show()
            onSaved?.invoke(value)
            dismiss()
        }

        // 遥控器默认焦点落在输入框，确认后可直接编辑地址
        input.requestFocus()
    }
}

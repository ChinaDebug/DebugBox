package com.tvbox.kotlin.core.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Window
import android.widget.TextView
import com.tvbox.kotlin.core.ui.R

/**
 * 通用确认对话框，复用 [R.layout.dialog_confirm] 布局。
 *
 * 用法：
 * ```
 * ConfirmClearDialog(context)
 *     .title("确定清除所有收藏吗？")
 *     .onConfirm { doClear() }
 *     .show()
 * ```
 *
 * 在 onDestroy 前应主动 dismiss 避免窗口泄漏。
 */
class ConfirmClearDialog(context: Context) : BaseDialog(context, R.style.CustomDialogStyle) {

    private var titleText: String
    private var titleColor: Int = Color.WHITE
    private var confirmText: String
    private var cancelText: String
    private var onConfirm: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private lateinit var tvTitle: TextView
    private lateinit var btnConfirm: TextView
    private lateinit var btnCancel: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        titleText = context.getString(R.string.confirm_clear_all_data)
        confirmText = context.getString(R.string.dialog_confirm)
        cancelText = context.getString(R.string.dialog_cancel)
    }

    fun title(text: String, color: Int = Color.WHITE): ConfirmClearDialog {
        titleText = text
        titleColor = color
        return this
    }

    fun confirmText(text: String): ConfirmClearDialog {
        confirmText = text
        return this
    }

    fun cancelText(text: String): ConfirmClearDialog {
        cancelText = text
        return this
    }

    fun onConfirm(block: () -> Unit): ConfirmClearDialog {
        onConfirm = block
        return this
    }

    fun onCancel(block: () -> Unit): ConfirmClearDialog {
        onCancel = block
        return this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_confirm)
        setCanceledOnTouchOutside(true)
        tvTitle = findViewById(R.id.confirmation)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnCancel = findViewById(R.id.btnCancel)
        tvTitle.text = titleText
        tvTitle.setTextColor(titleColor)
        btnConfirm.text = confirmText
        btnCancel.text = cancelText
        btnConfirm.setOnClickListener {
            dismiss()
            onConfirm?.invoke()
        }
        btnCancel.setOnClickListener {
            dismiss()
            onCancel?.invoke()
        }
    }
}

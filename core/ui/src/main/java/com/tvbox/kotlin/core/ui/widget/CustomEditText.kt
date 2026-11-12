package com.tvbox.kotlin.core.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.annotation.AttrRes
import androidx.appcompat.widget.AppCompatEditText

/**
 * TV 遥控适配的输入框。
 *
 * 光标位于文本中间时，左右键移动光标（不移焦）；
 * 光标位于文本边界时放行，由各界面 OnKeyListener 在 ACTION_UP 处理移焦
 * （与系统按键事件流一致，避免 ACTION_DOWN 拦截移焦引发焦点来回跳闪）。
 */
class CustomEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 光标在文本中间：左右键移动光标（按下时判断，此时光标尚未移动）
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val len = text?.length ?: 0
                    if (selectionStart < len) {
                        setSelection(selectionStart + 1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (selectionStart > 0) {
                        setSelection(selectionStart - 1)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

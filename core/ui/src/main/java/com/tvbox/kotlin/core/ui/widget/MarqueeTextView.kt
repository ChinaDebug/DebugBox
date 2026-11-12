package com.tvbox.kotlin.core.ui.widget

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 跑马灯 TextView：始终报告自身已聚焦以驱动 Marquee 滚动，
 * 首次附加到窗口时锁定测量尺寸并清除 weight，避免父布局重排导致闪烁。
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr) {

    private var sizeLocked = false

    init {
        isSelected = true
        setSingleLine(true)
        marqueeRepeatLimit = -1
        ellipsize = TextUtils.TruncateAt.MARQUEE
    }

    override fun isFocused(): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (sizeLocked) return
        post {
            val w = width
            val h = height
            val lp = layoutParams ?: return@post
            if (w <= 0 || h <= 0) return@post
            var changed = false
            if (lp.width != w) { lp.width = w; changed = true }
            if (lp.height != h) { lp.height = h; changed = true }
            if (lp is LinearLayout.LayoutParams && lp.weight != 0f) {
                lp.weight = 0f
                changed = true
            }
            if (changed) layoutParams = lp
            sizeLocked = true
        }
    }
}

package com.tvbox.kotlin.core.ui.callback

import com.kingja.loadsir.callback.Callback
import com.tvbox.kotlin.core.ui.R

/**
 * 全局空数据状态。
 */
class EmptyCallback : Callback() {
    override fun onCreateView(): Int = R.layout.loadsir_empty_layout
}

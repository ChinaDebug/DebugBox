package com.tvbox.kotlin.core.ui.callback

import com.kingja.loadsir.callback.Callback
import com.tvbox.kotlin.core.ui.R

/**
 * 全局加载中状态。
 */
class LoadingCallback : Callback() {
    override fun onCreateView(): Int = R.layout.loadsir_loading_layout
}

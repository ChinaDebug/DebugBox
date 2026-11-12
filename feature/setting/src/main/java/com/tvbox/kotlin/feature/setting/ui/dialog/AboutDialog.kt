package com.tvbox.kotlin.feature.setting.ui.dialog

import android.content.Context
import android.widget.TextView
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.setting.BuildConfig

/** 关于弹窗：展示免责声明与构建时间 */
class AboutDialog(context: Context) : BaseDialog(context) {

    init {
        setContentView(uiR.layout.dialog_about)
        findViewById<TextView>(uiR.id.tvBuildTime)?.text = BuildConfig.BUILD_TIME
    }
}

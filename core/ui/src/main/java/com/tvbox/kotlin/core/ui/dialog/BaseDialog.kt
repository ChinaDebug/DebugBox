package com.tvbox.kotlin.core.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tvbox.kotlin.core.ui.R as uiR

/**
 * 弹窗基类：
 * 使用 [uiR.style.CustomDialogStyle]（透明背景、无系统变暗），
 * 展示时隐藏系统栏并允许内容延伸到刘海/挖孔区域。
 * 各模块自定义弹窗应继承本类（而非直接继承 Dialog），
 * 否则弹出时会显现状态栏/刘海区域，无法全屏沉浸。
 */
open class BaseDialog(context: Context, style: Int) : Dialog(context, style) {

    constructor(context: Context) : this(context, uiR.style.CustomDialogStyle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adaptCutout()
    }

    override fun show() {
        try {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            super.show()
            hideSystemBar()
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } catch (_: Throwable) {
            super.show()
        }
    }

    private fun adaptCutout() {
        // 内容延伸到刘海/挖孔区域，避免黑边遮挡
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window?.attributes ?: return
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window?.attributes = lp
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.setDecorFitsSystemWindows(false)
            }
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBar() {
        val window = window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            var uiOptions = window.decorView.systemUiVisibility
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.decorView.systemUiVisibility = uiOptions
        }
    }
}

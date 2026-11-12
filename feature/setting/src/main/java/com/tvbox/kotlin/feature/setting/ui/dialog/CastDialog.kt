package com.tvbox.kotlin.feature.setting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.server.cast.CastBridge
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 投屏设置对话框。
 * - 允许接收投屏：开关切换时实际启动/停止投屏接收服务（本机是否可被其他设备搜索到），
 *   并通过 [onStateChanged] 通知宿主同步显示状态；
 * - 投屏设备名：自定义风格输入弹窗，设置本机对外展示的设备名（投屏发现/设备列表可见）。
 */
class CastDialog(
    context: Context,
    private val onStateChanged: (() -> Unit)? = null
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val appPreferences = AppPreferences.getShared(context)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_cast)
        setCanceledOnTouchOutside(true)
        val tvCastEnable = findViewById<TextView>(uiR.id.tvCastEnable)
        val tvCastName = findViewById<TextView>(uiR.id.tvCastName)
        val llCastEnable = findViewById<View>(uiR.id.llCastEnable)
        val llCastName = findViewById<View>(uiR.id.llCastName)

        fun refresh() {
            val enable = appPreferences.getBoolean(AppConstants.CAST_ENABLE, true)
            tvCastEnable.text = context.getString(if (enable) uiR.string.state_on else uiR.string.state_off)
            val name = appPreferences.getString(AppConstants.CAST_DEVICE_NAME, Build.MODEL)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
            tvCastName.text = name
        }
        refresh()

        // 允许接收投屏：切换开关并实际启动/停止投屏接收服务
        llCastEnable.setOnClickListener {
            scope.launch {
                val enable = !appPreferences.getBoolean(AppConstants.CAST_ENABLE, true)
                appPreferences.put(AppConstants.CAST_ENABLE, enable)
                try {
                    val bridge = CastBridge.getInstance(context)
                    if (enable) {
                        bridge.startCastServer()
                    } else {
                        bridge.stopCastServer()
                    }
                } catch (t: Throwable) {
                }
                refresh()
                onStateChanged?.invoke()
            }
        }

        // 投屏设备名：自定义风格输入弹窗，保存后投屏发现/设备列表即时生效
        llCastName.setOnClickListener { showNameInputDialog() }
    }

    private fun showNameInputDialog() {
        val dialog = BaseDialog(context, uiR.style.CustomDialogStyle)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(uiR.layout.dialog_cast_name)
        dialog.setCanceledOnTouchOutside(true)

        val input = dialog.findViewById<EditText>(uiR.id.input_cast_name)
        val current = appPreferences.getString(AppConstants.CAST_DEVICE_NAME, Build.MODEL)
        input.setText(current)
        input.setSelection(input.text.length)

        dialog.findViewById<View>(uiR.id.btn_cast_name_ok).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(context, context.getString(uiR.string.cast_device_name), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scope.launch {
                appPreferences.put(AppConstants.CAST_DEVICE_NAME, name)
            }
            Toast.makeText(context, context.getString(uiR.string.toast_cast_name_saved), Toast.LENGTH_SHORT).show()
            findViewById<TextView>(uiR.id.tvCastName).text = name
            dialog.dismiss()
        }
        dialog.findViewById<View>(uiR.id.btn_cast_name_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}

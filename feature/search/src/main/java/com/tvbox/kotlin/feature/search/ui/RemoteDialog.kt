package com.tvbox.kotlin.feature.search.ui

import android.app.Dialog
import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.ui.QRCodeGen
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import me.jessyan.autosize.utils.AutoSizeUtils

/**
 * 远程搜索弹窗：展示本机局域网地址二维码与地址文本，
 * 手机/电脑扫码后可向本机推送搜索关键字。
 */
class RemoteDialog(
    context: Context,
    private val controlManager: ControlManager
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    init {
        setCanceledOnTouchOutside(true)
        setContentView(com.tvbox.kotlin.feature.search.R.layout.dialog_remote)
        refreshQRCode()
    }

    private fun refreshQRCode() {
        val ivQRCode = findViewById<ImageView>(com.tvbox.kotlin.feature.search.R.id.ivQRCode)
        val tvAddress = findViewById<TextView>(com.tvbox.kotlin.feature.search.R.id.tvAddress)
        val address = controlManager.getAddress(false)
        tvAddress.text = context.getString(uiR.string.api_scan_hint, address)
        val size = AutoSizeUtils.mm2px(context, 240f)
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, size, size))
    }
}

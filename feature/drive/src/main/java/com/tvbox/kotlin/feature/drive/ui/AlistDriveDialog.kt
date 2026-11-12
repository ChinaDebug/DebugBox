package com.tvbox.kotlin.feature.drive.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.tvbox.kotlin.core.common.util.StorageDriveType
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.drive.R
import org.json.JSONObject

/**
 * Alist 网页网盘配置弹窗（AlistDriveDialog）。
 *
 * 字段：空间名称 / Alist 地址 / 初始路径 / 密码。
 * 确认后回调 [onConfirm]，参数为 (name, type, configJson)，configJson 为
 * {"url":"...","initPath":"...","password":"..."}。
 */
class AlistDriveDialog(
    context: Context
) : BaseDialog(context) {

    private var onConfirm: ((name: String, type: StorageDriveType.TYPE, configJson: String) -> Unit)? = null

    fun onConfirm(listener: (name: String, type: StorageDriveType.TYPE, configJson: String) -> Unit): AlistDriveDialog {
        onConfirm = listener
        return this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_alistdrive)

        val etName = findViewById<EditText>(R.id.etName)
        val etUrl = findViewById<EditText>(R.id.etUrl)
        val etInitPath = findViewById<EditText>(R.id.etInitPath)
        val etPassword = findViewById<EditText>(R.id.etPassword)

        etName.isFocusableInTouchMode = true
        etName.requestFocus()

        findViewById<View>(R.id.btnConfirm).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(context, context.getString(uiR.string.toast_drive_name_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var url = etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(context, context.getString(uiR.string.toast_alist_addr_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!url.endsWith("/")) url += "/"
            val config = JSONObject()
            config.put("url", url)
            val initPath = normalizeInitPath(etInitPath.text.toString().trim())
            config.put("initPath", initPath)
            val password = etPassword.text.toString().trim()
            config.put("password", password)
            onConfirm?.invoke(name, StorageDriveType.TYPE.ALISTWEB, config.toString())
            dismiss()
        }
        findViewById<View>(R.id.btnCancel).setOnClickListener { dismiss() }
    }

    private fun normalizeInitPath(path: String): String {
        var result = path
        if (result.startsWith("/")) result = result.substring(1)
        if (result.endsWith("/")) result = result.substring(0, result.length - 1)
        return result
    }
}

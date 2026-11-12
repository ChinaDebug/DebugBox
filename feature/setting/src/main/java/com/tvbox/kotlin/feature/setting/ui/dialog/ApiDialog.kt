package com.tvbox.kotlin.feature.setting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.util.HawkListHelper
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.ui.QRCodeGen
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.core.ui.dialog.LocalFilePickerDialog
import com.tvbox.kotlin.feature.setting.ui.SettingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 订阅设置对话框。
 * 展示本机二维码与访问地址，提供点播/直播/EPG 三个输入框；
 * 点播地址支持历史、清除、本地配置文件选择（file:// 与 ./ 归一化为 clan://localhost/，
 * 经本机 HTTP /file/ 服务读取本地文件）。
 */
class ApiDialog(
    context: Context,
    private val viewModel: SettingViewModel,
    private val controlManager: ControlManager
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /** 事件收集任务：web 推送接口地址时同步到编辑框（仅同步不保存） */
    private var eventJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_api)
        setCanceledOnTouchOutside(true)

        val ivQRCode = findViewById<ImageView>(uiR.id.ivQRCode)
        val tvAddress = findViewById<TextView>(uiR.id.tvAddress)
        val inputApi = findViewById<EditText>(uiR.id.input)
        val inputLive = findViewById<EditText>(uiR.id.input_live)
        val inputEpg = findViewById<EditText>(uiR.id.input_epg)

        inputApi.setText(viewModel.apiUrl.value)
        inputLive.setText(viewModel.liveUrl.value)
        inputEpg.setText(viewModel.epgUrl.value)

        // 监听 web 订阅设置"确定"推送：仅同步到对应编辑框，不保存（由"提交全部并重载"统一落盘）
        eventJob = scope.launch {
            EventHub.events.collect { event ->
                if (event is TypedEvent) {
                    when (event.type) {
                        ControlManager.TYPE_API_URL_CHANGE ->
                            (event.payload as? String)?.let { inputApi.setText(it) }
                        ControlManager.TYPE_LIVE_URL_CHANGE ->
                            (event.payload as? String)?.let { inputLive.setText(it) }
                        ControlManager.TYPE_EPG_URL_CHANGE ->
                            (event.payload as? String)?.let { inputEpg.setText(it) }
                    }
                }
            }
        }

        refreshQRCode(ivQRCode, tvAddress)

        // 确定：归一化地址并保存（点播/直播/EPG 统一将 file:// 与 ./ 转成本机 clan 地址，
        // 经 App 内置 HTTP /file/ 读取本地文件）
        findViewById<View>(uiR.id.inputSubmit).setOnClickListener {
            var newApi = normalizeConfigAddress(inputApi.text.toString().trim())
            val newLive = normalizeConfigAddress(inputLive.text.toString().trim())
            val newEpg = normalizeConfigAddress(inputEpg.text.toString().trim())
            if (newApi.isNotEmpty()) {
                HawkListHelper.addToList(AppConstants.API_HISTORY, newApi, 20)
                scope.launch {
                    if (newApi != viewModel.apiUrl.value) {
                        viewModel.saveApiUrl(newApi)
                        Toast.makeText(context, context.getString(uiR.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (newLive.isNotEmpty()) {
                HawkListHelper.addToList(AppConstants.LIVE_HISTORY, newLive, 20)
            }
            if (newEpg.isNotEmpty()) {
                HawkListHelper.addToList(AppConstants.EPG_HISTORY, newEpg, 20)
            }
            scope.launch {
                viewModel.saveLiveUrl(newLive)
                viewModel.saveEpgUrl(newEpg)
            }
            dismiss()
        }

        // 点播历史 / 清除
        findViewById<View>(uiR.id.apiHistory).setOnClickListener {
            val history = HawkListHelper.getList(AppConstants.API_HISTORY)
            if (history.isEmpty()) {
                Toast.makeText(context, context.getString(uiR.string.toast_no_api_history), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiHistoryDialog(context, AppConstants.API_HISTORY, context.getString(uiR.string.dia_history_list)) { value ->
                inputApi.setText(value)
                if (value != viewModel.apiUrl.value) {
                    scope.launch {
                        viewModel.saveApiUrl(value)
                        Toast.makeText(context, context.getString(uiR.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
        }
        findViewById<View>(uiR.id.apiClear).setOnClickListener { inputApi.setText("") }

        // 本地配置文件选择：点播订阅 json/txt，选主存储文件转本机 clan 地址并立即保存
        findViewById<View>(uiR.id.apiLocal).setOnClickListener {
            pickLocalConfigFile(listOf("json", "txt")) { clan ->
                inputApi.setText(clan)
                if (clan != viewModel.apiUrl.value) {
                    scope.launch {
                        viewModel.saveApiUrl(clan)
                        Toast.makeText(
                            context,
                            context.getString(uiR.string.toast_cache_cleared),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        // 直播历史 / 清除
        findViewById<View>(uiR.id.liveHistory).setOnClickListener {
            val history = HawkListHelper.getList(AppConstants.LIVE_HISTORY)
            if (history.isEmpty()) {
                Toast.makeText(context, context.getString(uiR.string.toast_no_live_history), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiHistoryDialog(context, AppConstants.LIVE_HISTORY, context.getString(uiR.string.dia_history_live)) { value ->
                inputLive.setText(value)
                scope.launch { viewModel.saveLiveUrl(value) }
            }.show()
        }
        findViewById<View>(uiR.id.liveClear).setOnClickListener { inputLive.setText("") }

        // 本地文件选择：直播源 txt/json/m3u
        findViewById<View>(uiR.id.liveLocal).setOnClickListener {
            pickLocalConfigFile(listOf("txt", "json", "m3u")) { clan ->
                inputLive.setText(clan)
                if (clan != viewModel.liveUrl.value) {
                    scope.launch { viewModel.saveLiveUrl(clan) }
                }
            }
        }

        // EPG 历史 / 清除
        findViewById<View>(uiR.id.EPGHistory).setOnClickListener {
            val history = HawkListHelper.getList(AppConstants.EPG_HISTORY)
            if (history.isEmpty()) {
                Toast.makeText(context, context.getString(uiR.string.toast_no_epg_history), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiHistoryDialog(context, AppConstants.EPG_HISTORY, context.getString(uiR.string.dia_history_epg)) { value ->
                inputEpg.setText(value)
                scope.launch { viewModel.saveEpgUrl(value) }
            }.show()
        }
        findViewById<View>(uiR.id.epgClear).setOnClickListener { inputEpg.setText("") }

        // 本地文件选择：EPG xml/gz/json
        findViewById<View>(uiR.id.epgLocal).setOnClickListener {
            pickLocalConfigFile(listOf("xml", "gz", "json")) { clan ->
                inputEpg.setText(clan)
                if (clan != viewModel.epgUrl.value) {
                    scope.launch { viewModel.saveEpgUrl(clan) }
                }
            }
        }
    }

    override fun onStop() {
        eventJob?.cancel()
        eventJob = null
        super.onStop()
    }

    /**
     * 归一化配置地址：file:// 与相对路径 ./ 统一转成本机 clan 地址，
     * 经 App 内置 HTTP /file/ 读取本地文件（点播/直播/EPG 共用）。
     */
    private fun normalizeConfigAddress(input: String): String {
        var result = input
        if (result.startsWith("file://")) {
            result = result.replace("file://", "clan://localhost/")
        } else if (result.startsWith("./")) {
            result = result.replace("./", "clan://localhost/")
        }
        return result
    }

    /** 打开本地配置文件选择：选主存储文件转成本机 clan 地址，非主存储路径提示；onPicked 回调 clan 地址 */
    private fun pickLocalConfigFile(
        extensions: List<String>,
        onPicked: (clanUrl: String) -> Unit
    ) {
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath
        LocalFilePickerDialog(context).apply {
            mode = LocalFilePickerDialog.PickMode.FILE
            this.extensions = extensions
            onPathSelectedListener = object : LocalFilePickerDialog.OnPathSelectedListener {
                override fun onPathSelected(path: String) {
                    val clan = if (path.startsWith(storageRoot)) {
                        "clan://localhost/" + path.removePrefix(storageRoot).trimStart('/')
                    } else {
                        null
                    }
                    if (clan == null) {
                        Toast.makeText(
                            context,
                            context.getString(uiR.string.toast_local_config_unsupported),
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    onPicked(clan)
                }
            }
        }.show()
    }

    private fun refreshQRCode(ivQRCode: ImageView, tvAddress: TextView) {
        try {
            controlManager.startServer()
        } catch (_: Throwable) {
        }
        val address = controlManager.getAddress(false)
        tvAddress.text = context.getString(uiR.string.api_scan_hint, address)
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, 300, 300))
    }
}

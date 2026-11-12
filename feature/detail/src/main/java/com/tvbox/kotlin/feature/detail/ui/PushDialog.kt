package com.tvbox.kotlin.feature.detail.ui

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.server.cast.discovery.DeviceDiscovery
import com.tvbox.kotlin.core.server.cast.model.CastDevice
import com.tvbox.kotlin.core.ui.R as CoreUiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.detail.R
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 推送设备选择对话框。
 *
 * 搜索局域网内的 TVBox 设备（仅 deviceType == TVBOX），选择后通过接收端
 * RemoteServer 端口 9978 的 /action 接口，以 do=mirror 将当前影片镜像推送过去，
 * 由接收端自行解析站点并播放。
 */
class PushDialog(context: Context) : BaseDialog(context, CoreUiR.style.CustomDialogStyle) {

    private val deviceList = mutableListOf<CastDevice>()
    private val adapter = PushDeviceAdapter()
    private val discovery = DeviceDiscovery(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var vodId: String? = null
    private var sourceKey: String? = null
    private var isPushing = false

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvScanning: TextView
    private lateinit var tvEmpty: TextView

    init {
        setContentView(R.layout.dialog_push)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvScanning = findViewById(R.id.tvScanning)
        tvEmpty = findViewById(R.id.tvEmpty)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        // 设备点击：防止重复点击
        adapter.onItemClick = { device ->
            if (!isPushing) {
                isPushing = true
                pushToDevice(device)
            }
        }

        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { startDiscovery() }
        findViewById<TextView>(R.id.btnCancel).setOnClickListener { dismiss() }

        // 开始搜索设备
        startDiscovery()
    }

    /** 设置推送数据（当前影片 id 与站点 key） */
    fun setPushData(vodId: String, sourceKey: String) {
        this.vodId = vodId
        this.sourceKey = sourceKey
    }

    /** 开始搜索设备 */
    private fun startDiscovery() {
        deviceList.clear()
        adapter.notifyDataSetChanged()

        progressBar.visibility = View.VISIBLE
        tvScanning.visibility = View.VISIBLE
        tvScanning.setText(CoreUiR.string.push_scanning)
        tvEmpty.visibility = View.GONE

        discovery.startDiscovery(object : DeviceDiscovery.DeviceDiscoveryListener {
            override fun onDeviceFound(device: CastDevice) {
                // 只添加 TVBox 类型的设备（用于镜像推送），按 host:port 去重
                if (device.deviceType == CastDevice.DeviceType.TVBOX &&
                    deviceList.none { it.hostAddress == device.hostAddress && it.port == device.port }
                ) {
                    deviceList.add(device)
                    adapter.notifyDataSetChanged()
                }
                tvEmpty.visibility = if (deviceList.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onDiscoveryComplete() {
                progressBar.visibility = View.GONE
                tvScanning.visibility = View.GONE
                if (deviceList.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.setText(CoreUiR.string.push_no_devices)
                }
            }

            override fun onDiscoveryError(error: String) {
                progressBar.visibility = View.GONE
                tvScanning.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.setText(CoreUiR.string.push_scan_error)
            }
        })
    }

    /** 推送影片到指定设备：POST http://ip:9978/action (id, sourceKey, do=mirror) */
    private fun pushToDevice(device: CastDevice) {
        val id = vodId
        val key = sourceKey
        if (id == null || key == null) {
            Toast.makeText(context, "推送数据为空", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, "正在推送到: ${device.name}", Toast.LENGTH_SHORT).show()

        Thread {
            var result: String? = null
            try {
                val url = "http://${device.hostAddress}:${REMOTE_SERVER_PORT}/action"
                val form = FormBody.Builder()
                    .add("id", id)
                    .add("sourceKey", key)
                    .add("do", "mirror")
                    .build()
                val request = Request.Builder().url(url).post(form).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        result = response.body?.string()?.trim()
                    }
                }
            } catch (_: Exception) {
                result = null
            }
            mainHandler.post { handlePushResult(result) }
        }.start()

        dismiss()
    }

    /** 处理推送结果，映射 DetailActivity.pushVod */
    private fun handlePushResult(result: String?) {
        when (result) {
            "mirrored" -> toast(CoreUiR.string.push_success)
            "source_not_found" -> toast("接收端无此站点")
            "missing_params" -> toast("参数缺失")
            else -> toast(CoreUiR.string.push_failed)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    override fun dismiss() {
        discovery.stopDiscovery()
        super.dismiss()
    }

    /** 设备列表适配器 */
    private inner class PushDeviceAdapter : RecyclerView.Adapter<PushDeviceAdapter.ViewHolder>() {

        var onItemClick: ((CastDevice) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_push_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(deviceList[position])
        }

        override fun getItemCount(): Int = deviceList.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvDeviceName)
            private val tvIp: TextView = itemView.findViewById(R.id.tvDeviceIp)

            fun bind(device: CastDevice) {
                tvName.text = device.name
                // 显示推送端口 9978，而非投屏端口 9979
                tvIp.text = "${device.hostAddress}:$REMOTE_SERVER_PORT"
                itemView.setOnClickListener { onItemClick?.invoke(device) }
            }
        }
    }

    companion object {
        /** 接收端 RemoteServer 推送端口（非投屏 9979） */
        private const val REMOTE_SERVER_PORT = 9978
    }
}

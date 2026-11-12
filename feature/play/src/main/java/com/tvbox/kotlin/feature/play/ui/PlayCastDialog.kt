package com.tvbox.kotlin.feature.play.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.server.cast.model.CastDevice
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.play.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 播放页投屏对话框。
 *
 * 与 [com.tvbox.kotlin.feature.push.ui.CastDialog] 结构一致，
 * 独立存在于 feature:play 以避免跨模块依赖。
 *
 * 选择设备后回调 [onDeviceSelected]，由调用方触发投屏。
 */
class PlayCastDialog(
    context: Context,
    private val devices: StateFlow<List<CastDevice>>,
    private val scanning: StateFlow<Boolean>
) : BaseDialog(context, com.tvbox.kotlin.core.ui.R.style.CustomDialogStyle) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adapter = DeviceAdapter()
    private val rv: RecyclerView
    private val progressBar: ProgressBar
    private val tvScanning: TextView
    private val tvEmpty: TextView

    var onDeviceSelected: ((CastDevice) -> Unit)? = null
    var refreshCallback: (() -> Unit)? = null
    var cancelCallback: (() -> Unit)? = null

    init {
        setContentView(R.layout.dialog_play_cast)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        rv = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        tvScanning = findViewById(R.id.tvScanning)
        tvEmpty = findViewById(R.id.tvEmpty)

        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = adapter

        adapter.onItemClick = { device ->
            onDeviceSelected?.invoke(device)
            dismiss()
        }

        findViewById<TextView>(R.id.btnRefresh).setOnClickListener {
            refreshCallback?.invoke()
        }
        findViewById<TextView>(R.id.btnCancel).setOnClickListener {
            cancelCallback?.invoke()
            dismiss()
        }

        observeState()
        setOnDismissListener { scope.cancel() }
    }

    private fun observeState() {
        scope.launch {
            devices.collect { list ->
                adapter.setData(list)
                tvEmpty.visibility = if (list.isEmpty() && !scanning.value) View.VISIBLE else View.GONE
            }
        }
        scope.launch {
            scanning.collect { isScanning ->
                progressBar.visibility = if (isScanning) View.VISIBLE else View.GONE
                tvScanning.visibility = if (isScanning) View.VISIBLE else View.GONE
                if (!isScanning && adapter.itemCount == 0) {
                    tvEmpty.visibility = View.VISIBLE
                } else if (!isScanning) {
                    tvEmpty.visibility = View.GONE
                }
            }
        }
    }

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {
        private val items = ArrayList<CastDevice>()
        var onItemClick: ((CastDevice) -> Unit)? = null

        fun setData(list: List<CastDevice>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_play_cast_device, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvDeviceName)
            private val tvIp: TextView = itemView.findViewById(R.id.tvDeviceIp)

            fun bind(device: CastDevice) {
                tvName.text = device.name
                tvIp.text = "${device.hostAddress}:${device.port}"
                itemView.setOnClickListener { onItemClick?.invoke(device) }
            }
        }
    }
}

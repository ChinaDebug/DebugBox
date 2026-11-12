package com.tvbox.kotlin.feature.detail.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.feature.detail.R

/**
 * 剧集线路适配器：横向展示多线路，选中线路下方显示主题色下划线。
 * item 不绑定自定义点击监听，点击交由 TvRecyclerView 的 OnItemListener 分发，
 * 保证触摸/遥控点击行为一致。
 */
class SeriesFlagAdapter : RecyclerView.Adapter<SeriesFlagAdapter.ViewHolder>() {

    private val items = mutableListOf<VodInfo.VodSeriesFlag>()

    fun setData(list: List<VodInfo.VodSeriesFlag>?) {
        items.clear()
        if (!list.isNullOrEmpty()) items.addAll(list)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): VodInfo.VodSeriesFlag? =
        items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_flag, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFlag: TextView = itemView.findViewById(R.id.tvSeriesFlag)
        private val selectLine: View = itemView.findViewById(R.id.tvSeriesFlagSelect)

        fun bind(item: VodInfo.VodSeriesFlag) {
            tvFlag.text = item.name
            selectLine.visibility = if (item.selected) View.VISIBLE else View.GONE
        }
    }
}

package com.tvbox.kotlin.feature.detail.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.VodSeriesGroup
import com.tvbox.kotlin.feature.detail.R

/**
 * 剧集分组适配器：横向展示分组区间（如 1-20、21-40），选中分组下方显示主题色下划线。
 * 复用线路 item_series_flag 布局。
 * item 不绑定自定义点击监听，点击交由 TvRecyclerView 的 OnItemListener 分发，
 * 保证触摸/遥控点击行为一致。
 */
class SeriesGroupAdapter : RecyclerView.Adapter<SeriesGroupAdapter.ViewHolder>() {

    private val items = mutableListOf<VodSeriesGroup>()

    fun setData(list: List<VodSeriesGroup>?) {
        items.clear()
        if (!list.isNullOrEmpty()) items.addAll(list)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): VodSeriesGroup? = items.getOrNull(position)

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

        fun bind(item: VodSeriesGroup) {
            tvFlag.text = item.name
            selectLine.visibility = if (item.selected) View.VISIBLE else View.GONE
        }
    }
}

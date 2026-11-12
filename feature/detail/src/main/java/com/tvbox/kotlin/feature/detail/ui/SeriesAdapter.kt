package com.tvbox.kotlin.feature.detail.ui

import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.feature.detail.R

/**
 * 剧集列表适配器：网格展示单集，选中集数高亮主题色。
 * 支持点击播放与长按批量播放。
 */
class SeriesAdapter : RecyclerView.Adapter<SeriesAdapter.ViewHolder>() {

    private val items = mutableListOf<VodInfo.VodSeries>()

    private var onItemClickListener: ((Int, VodInfo.VodSeries) -> Unit)? = null
    private var onItemLongClickListener: ((Int, VodInfo.VodSeries) -> Unit)? = null

    /** 主题色，运行时由 Activity 注入，避免依赖主题属性解析 */
    private var themeColor: Int = Color.WHITE

    fun setThemeColor(color: Int) {
        themeColor = color
    }

    fun setOnItemClickListener(listener: (Int, VodInfo.VodSeries) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (Int, VodInfo.VodSeries) -> Unit) {
        onItemLongClickListener = listener
    }

    fun setData(list: List<VodInfo.VodSeries>?) {
        items.clear()
        if (!list.isNullOrEmpty()) items.addAll(list)
        notifyDataSetChanged()
    }

    fun getData(): List<VodInfo.VodSeries> = items

    fun getItem(position: Int): VodInfo.VodSeries? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], themeColor, onItemClickListener, onItemLongClickListener)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSeries: TextView = itemView.findViewById(R.id.tvSeries)

        fun bind(
            item: VodInfo.VodSeries,
            themeColor: Int,
            clickListener: ((Int, VodInfo.VodSeries) -> Unit)?,
            longClickListener: ((Int, VodInfo.VodSeries) -> Unit)?
        ) {
            tvSeries.text = item.name
            // 聚焦时背景为主题色，文字统一白色保证对比；未聚焦时选中项用主题色。
            // 聚焦时启用跑马灯滚动展示超长集名，失焦恢复省略号截断
            val applyColor = { focused: Boolean ->
                tvSeries.setTextColor(if (focused) Color.WHITE else if (item.selected) themeColor else Color.WHITE)
                tvSeries.isSelected = focused
                tvSeries.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
                if (focused) tvSeries.marqueeRepeatLimit = -1
            }
            applyColor(itemView.isFocused)
            itemView.setOnFocusChangeListener { v, hasFocus ->
                // 转发给宿主 TvRecyclerView，保持其焦点态（选中项边框/onItemSelected）同步
                (itemView.parent as? com.owen.tvrecyclerview.widget.TvRecyclerView)?.onFocusChange(itemView, hasFocus)
                applyColor(hasFocus)
            }
            itemView.setOnClickListener {
                clickListener?.invoke(bindingAdapterPosition, item)
            }
            itemView.setOnLongClickListener {
                longClickListener?.invoke(bindingAdapterPosition, item)
                true
            }
        }
    }
}

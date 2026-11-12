package com.tvbox.kotlin.feature.home.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.home.R

/**
 * 分类筛选弹窗的单行选项适配器。
 */
class GridFilterKVAdapter : RecyclerView.Adapter<GridFilterKVAdapter.KVHolder>() {

    private val data = ArrayList<String>()

    /** 当前选中项位置（由外部点击维护），-1 表示无选中 */
    var selectedPosition: Int = -1

    var onItemClickListener: ((View, Int) -> Unit)? = null

    fun setNewData(list: List<String>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KVHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_grid_filter_value, parent, false)
        return KVHolder(view)
    }

    override fun onBindViewHolder(holder: KVHolder, position: Int) {
        holder.tvValue.text = data[position]
        applyColor(holder, position, holder.itemView.isFocused)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            // 转发给宿主 TvRecyclerView，保持其焦点态（选中边框/onItemSelected）同步
            (holder.itemView.parent as? com.owen.tvrecyclerview.widget.TvRecyclerView)
                ?.onFocusChange(holder.itemView, hasFocus)
            applyColor(holder, position, hasFocus)
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onItemClickListener?.invoke(holder.itemView, pos)
            }
        }
    }

    /** 聚焦时背景为主题色，文字统一白色保证对比；未聚焦时选中项用主题色 */
    private fun applyColor(holder: KVHolder, position: Int, focused: Boolean) {
        holder.tvValue.setTextColor(
            if (focused) Color.WHITE else if (position == selectedPosition) themeColor(holder.itemView) else Color.WHITE
        )
    }

    /** 从主题解析 color_theme 主题色 */
    private fun themeColor(view: View): Int {
        val ta = view.context.theme.obtainStyledAttributes(intArrayOf(uiR.attr.color_theme))
        val color = ta.getColor(0, Color.WHITE)
        ta.recycle()
        return color
    }

    override fun getItemCount(): Int = data.size

    class KVHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvValue: TextView = itemView.findViewById(R.id.filterValue)
    }
}

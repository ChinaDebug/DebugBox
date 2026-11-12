package com.tvbox.kotlin.feature.search.ui

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.tvbox.kotlin.feature.search.R

/**
 * 聚合搜索左侧源过滤列表适配器。
 *
 * - 首项固定为"全部"，焦点落到该项时展示全部结果；
 * - 其余项为有结果的站点，点击或获焦时按站点过滤结果；
 * - [setSelectedName] 控制高亮，[refreshVisibleSelection] 用于在不重建的情况下刷新可见项选中态。
 */
class FastSearchSiteAdapter : RecyclerView.Adapter<FastSearchSiteAdapter.SiteHolder>() {

    private val data = ArrayList<String>()

    /** 当前选中站点名，空表示未选中 */
    private var selectedName: String = ""

    private var onItemClickListener: ((String, Int) -> Unit)? = null
    private var onItemSelectedListener: ((String) -> Unit)? = null

    fun setOnItemClickListener(listener: (String, Int) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemSelectedListener(listener: (String) -> Unit) {
        onItemSelectedListener = listener
    }

    fun setNewData(list: List<String>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun addData(name: String) {
        if (data.contains(name)) return
        data.add(name)
        notifyItemInserted(data.size - 1)
    }

    fun getData(): List<String> = data

    fun reset() {
        data.clear()
        selectedName = ""
        notifyDataSetChanged()
    }

    /** 设置当前选中站点名并刷新可见项 */
    fun setSelectedName(name: String) {
        selectedName = name
    }

    /** 刷新可见 Item 的选中态，避免全量刷新闪烁 */
    fun refreshVisibleSelection(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? SiteHolder ?: continue
            holder.updateSelected(child)
        }
    }

    private fun isSelected(name: String): Boolean =
        selectedName.isNotEmpty() && selectedName == name

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SiteHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_fast_search_site, parent, false)
        return SiteHolder(view, this)
    }

    override fun onBindViewHolder(holder: SiteHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size

    class SiteHolder(
        itemView: View,
        private val adapter: FastSearchSiteAdapter
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvSearchSite: TextView = itemView.findViewById(R.id.tvSearchSite)

        init {
            itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                // 转发给宿主 TvRecyclerView，保持其焦点态（选中边框/onItemSelected）同步
                (itemView.parent as? TvRecyclerView)?.onFocusChange(itemView, hasFocus)
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < adapter.data.size) {
                        adapter.onItemSelectedListener?.invoke(adapter.data[pos])
                    }
                } else {
                    // 焦点离开后刷新选中视觉：选中项恢复主题色，避免停留聚焦态白字
                    updateSelected(itemView)
                }
            }
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < adapter.data.size) {
                    adapter.onItemClickListener?.invoke(adapter.data[pos], pos)
                }
            }
        }

        fun bind(name: String) {
            tvSearchSite.text = name
            updateSelected(itemView)
        }

        fun updateSelected(view: View) {
            val selected = adapter.isSelected(tvSearchSite.text.toString())
            view.isSelected = selected
            // 选中且未聚焦时主题色文字，聚焦时保持白色以衬托焦点背景
            tvSearchSite.setTextColor(
                if (selected && !view.isFocused) resolveThemeColor(view) else Color.WHITE
            )
        }

        private fun resolveThemeColor(view: View): Int {
            val typedValue = TypedValue()
            view.context.theme.resolveAttribute(
                com.tvbox.kotlin.core.ui.R.attr.color_theme, typedValue, true
            )
            return if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                typedValue.data
            } else {
                Color.WHITE
            }
        }
    }
}

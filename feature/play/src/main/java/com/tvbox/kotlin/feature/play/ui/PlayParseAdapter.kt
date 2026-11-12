package com.tvbox.kotlin.feature.play.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.ParseBean

/**
 * 解析器列表适配器。
 *
 * 渲染 [ParseBean] 列表，展示解析器名称与选中态，点击后回调切换。
 * 复用 item_play_parse.xml 作为条目布局。
 */
class PlayParseAdapter : RecyclerView.Adapter<PlayParseAdapter.ViewHolder>() {

    private val items = mutableListOf<ParseBean>()
    private var selectedPosition = -1

    /** 条目点击回调，参数为解析器与位置 */
    var onItemClickListener: ((ParseBean, Int) -> Unit)? = null

    /** 设置解析器列表，默认选中标记为 default 的条目 */
    fun setData(list: List<ParseBean>) {
        items.clear()
        items.addAll(list)
        selectedPosition = list.indexOfFirst { it.isDefault() }
        notifyDataSetChanged()
    }

    /** 选中指定位置的解析器，刷新新旧选中态 */
    fun setSelected(position: Int) {
        if (position == selectedPosition) return
        val old = selectedPosition
        selectedPosition = position
        if (old >= 0) notifyItemChanged(old)
        if (position >= 0) notifyItemChanged(position)
    }

    /** 按解析器名称选中 */
    fun setSelectedByName(name: String?) {
        if (name.isNullOrEmpty()) return
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) setSelected(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(com.tvbox.kotlin.feature.play.R.layout.item_play_parse, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(
            com.tvbox.kotlin.feature.play.R.id.tvParse
        )

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos < 0 || pos >= items.size) return@setOnClickListener
                setSelected(pos)
                onItemClickListener?.invoke(items[pos], pos)
            }
        }

        fun bind(bean: ParseBean, selected: Boolean) {
            textView.text = bean.name
            textView.isSelected = selected
        }
    }
}

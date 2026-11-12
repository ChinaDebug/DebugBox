package com.tvbox.kotlin.feature.play.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放器弹出菜单条目数据。
 *
 * @param id 条目标识，由调用方定义语义（如选集索引、设置项 id）
 * @param title 展示文本
 * @param selected 是否选中
 */
data class PopupMenuItem(
    val id: Int,
    val title: String,
    val selected: Boolean = false
)

/**
 * 播放器弹出菜单适配器。
 *
 * 用于选集列表、解码器选择、缩放选择、倍速选择等弹出菜单场景。
 * 复用 item_play_parse.xml 作为条目布局，渲染文本与选中态。
 */
class PlayerPopupMenuAdapter : RecyclerView.Adapter<PlayerPopupMenuAdapter.ViewHolder>() {

    private val items = mutableListOf<PopupMenuItem>()

    /** 条目点击回调 */
    var onItemClickListener: ((PopupMenuItem, Int) -> Unit)? = null

    /** 设置菜单条目列表 */
    fun setData(list: List<PopupMenuItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 单条更新，用于切换选中态时局部刷新 */
    fun updateItem(item: PopupMenuItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            items[index] = item
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(com.tvbox.kotlin.feature.play.R.layout.item_play_parse, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
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
                onItemClickListener?.invoke(items[pos], pos)
            }
        }

        fun bind(item: PopupMenuItem) {
            textView.text = item.title
            textView.isSelected = item.selected
        }
    }
}

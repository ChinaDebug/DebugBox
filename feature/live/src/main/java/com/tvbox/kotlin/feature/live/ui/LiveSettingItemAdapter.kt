package com.tvbox.kotlin.feature.live.ui

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.live.R

/**
 * 直播设置项适配器，展示某分组下的可选项（如解码器、缩放模式）。
 * 单选模式：[selectItem] 切换选中并刷新旧新两项；选中项文字以主题色显示。
 */
class LiveSettingItemAdapter : RecyclerView.Adapter<LiveSettingItemAdapter.Holder>() {

    private val data = ArrayList<LiveSettingItem>()

    private var selectedItemIndex = -1
    private var focusedItemIndex = -1

    private var onItemClickListener: ((Int, LiveSettingItem) -> Unit)? = null

    fun setOnItemClickListener(listener: (Int, LiveSettingItem) -> Unit) {
        onItemClickListener = listener
    }

    fun setNewData(list: List<LiveSettingItem>?) {
        data.clear()
        if (list != null) data.addAll(list)
        selectedItemIndex = -1
        focusedItemIndex = -1
        notifyDataSetChanged()
    }

    fun getSelectedItemIndex(): Int = selectedItemIndex

    /** 选中指定项，[notify] 控制是否刷新 UI */
    fun selectItem(index: Int, notify: Boolean) {
        if (index in data.indices) {
            data[index].selected = true
        }
        val old = selectedItemIndex
        selectedItemIndex = index
        if (old in data.indices && old != index) data[old].selected = false
        if (notify) {
            if (old in data.indices) notifyItemChanged(old)
            if (index in data.indices) notifyItemChanged(index)
        }
    }

    /** 多选模式：切换指定项选中状态 */
    fun toggleItem(index: Int, notify: Boolean) {
        if (index !in data.indices) return
        data[index].selected = !data[index].selected
        if (notify) notifyItemChanged(index)
    }

    fun setFocusedItemIndex(index: Int) {
        if (focusedItemIndex == index) return
        val old = focusedItemIndex
        focusedItemIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun getItem(position: Int): LiveSettingItem? = data.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_setting, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            data[position],
            position == focusedItemIndex,
            onItemClickListener
        )
    }

    override fun getItemCount(): Int = data.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvSettingItemName)

        fun bind(
            item: LiveSettingItem,
            focused: Boolean,
            listener: ((Int, LiveSettingItem) -> Unit)?
        ) {
            tvName.text = item.itemName
            itemView.isSelected = item.selected || focused
            // 选中且非聚焦时主题色（LiveSettingItemAdapter）
            val color = if (item.selected && !focused) {
                resolveThemeColor(itemView)
            } else {
                ContextCompat.getColor(itemView.context, uiR.color.color_FFFFFF)
            }
            tvName.setTextColor(color)
            itemView.setOnClickListener {
                listener?.invoke(bindingAdapterPosition, item)
            }
        }

        private fun resolveThemeColor(view: View): Int {
            val typedValue = TypedValue()
            view.context.theme.resolveAttribute(uiR.attr.color_theme, typedValue, true)
            return if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                typedValue.data
            } else {
                Color.WHITE
            }
        }
    }
}

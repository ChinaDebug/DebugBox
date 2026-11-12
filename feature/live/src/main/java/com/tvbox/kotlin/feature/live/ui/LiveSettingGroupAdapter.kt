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

/** 直播设置分组 */
data class LiveSettingGroup(
    val groupIndex: Int,
    val groupName: String,
    val items: MutableList<LiveSettingItem> = mutableListOf()
)

/** 直播设置项 */
data class LiveSettingItem(
    val itemIndex: Int,
    val itemName: String,
    var selected: Boolean = false
)

/**
 * 直播设置分组适配器，展示“线路选择/画面比例/播放解码”等分组。
 * 当前分组文字以主题色显示。
 */
class LiveSettingGroupAdapter : RecyclerView.Adapter<LiveSettingGroupAdapter.Holder>() {

    private val data = ArrayList<LiveSettingGroup>()

    private var selectedGroupIndex = -1
    private var focusedGroupIndex = -1

    private var onItemClickListener: ((Int, LiveSettingGroup) -> Unit)? = null

    fun setOnItemClickListener(listener: (Int, LiveSettingGroup) -> Unit) {
        onItemClickListener = listener
    }

    fun setNewData(list: List<LiveSettingGroup>?) {
        data.clear()
        if (list != null) data.addAll(list)
        selectedGroupIndex = -1
        focusedGroupIndex = -1
        notifyDataSetChanged()
    }

    fun setSelectedGroupIndex(index: Int) {
        if (selectedGroupIndex == index) return
        val old = selectedGroupIndex
        selectedGroupIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun getSelectedGroupIndex(): Int = selectedGroupIndex

    fun setFocusedGroupIndex(index: Int) {
        if (focusedGroupIndex == index) return
        val old = focusedGroupIndex
        focusedGroupIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun getItem(position: Int): LiveSettingGroup? = data.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_setting_group, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            data[position],
            position == selectedGroupIndex,
            position == focusedGroupIndex,
            onItemClickListener
        )
    }

    override fun getItemCount(): Int = data.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvSettingGroupName)

        fun bind(
            group: LiveSettingGroup,
            selected: Boolean,
            focused: Boolean,
            listener: ((Int, LiveSettingGroup) -> Unit)?
        ) {
            tvName.text = group.groupName
            itemView.isSelected = selected || focused
            // 选中且非聚焦时主题色（LiveSettingGroupAdapter）
            val color = if (selected && !focused) {
                resolveThemeColor(itemView)
            } else {
                ContextCompat.getColor(itemView.context, uiR.color.color_FFFFFF)
            }
            tvName.setTextColor(color)
            itemView.setOnClickListener {
                listener?.invoke(bindingAdapterPosition, group)
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

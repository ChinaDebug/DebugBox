package com.tvbox.kotlin.feature.live.ui

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.LiveChannelItem
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.live.R

/**
 * 直播频道列表适配器，展示频道号与频道名。
 * 通过 [setSelectedChannelIndex] / [setFocusedChannelIndex] 控制高亮，
 * 选中频道（号+名）以主题色显示，点击时由 Activity 触发播放。
 */
class LiveChannelAdapter : RecyclerView.Adapter<LiveChannelAdapter.Holder>() {

    private val data = ArrayList<LiveChannelItem>()

    private var selectedChannelIndex = -1
    private var focusedChannelIndex = -1

    private var onItemClickListener: ((Int, LiveChannelItem) -> Unit)? = null

    fun setOnItemClickListener(listener: (Int, LiveChannelItem) -> Unit) {
        onItemClickListener = listener
    }

    fun setNewData(list: List<LiveChannelItem>?) {
        data.clear()
        if (list != null) data.addAll(list)
        selectedChannelIndex = -1
        focusedChannelIndex = -1
        notifyDataSetChanged()
    }

    fun setSelectedChannelIndex(index: Int) {
        if (selectedChannelIndex == index) return
        val old = selectedChannelIndex
        selectedChannelIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun getSelectedChannelIndex(): Int = selectedChannelIndex

    fun setFocusedChannelIndex(index: Int) {
        if (focusedChannelIndex == index) return
        val old = focusedChannelIndex
        focusedChannelIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun getItem(position: Int): LiveChannelItem? = data.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_channel, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            data[position],
            position == selectedChannelIndex,
            position == focusedChannelIndex,
            onItemClickListener
        )
    }

    override fun getItemCount(): Int = data.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvNum: TextView = itemView.findViewById(R.id.tvChannelNum)
        private val tvName: TextView = itemView.findViewById(R.id.tvChannelName)

        fun bind(
            item: LiveChannelItem,
            selected: Boolean,
            focused: Boolean,
            listener: ((Int, LiveChannelItem) -> Unit)?
        ) {
            tvNum.text = item.channelNum.toString()
            tvName.text = item.channelName
            itemView.isSelected = selected || focused
            // 选中且非聚焦时主题色，聚焦/普通为白色（LiveChannelItemAdapter）
            val color = if (selected && !focused) {
                resolveThemeColor(itemView)
            } else {
                ContextCompat.getColor(itemView.context, uiR.color.color_FFFFFF)
            }
            tvNum.setTextColor(color)
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

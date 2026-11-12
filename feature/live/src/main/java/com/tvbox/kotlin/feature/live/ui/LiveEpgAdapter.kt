package com.tvbox.kotlin.feature.live.ui

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.Epginfo
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.widget.AudioWaveView
import com.tvbox.kotlin.feature.live.R
import java.util.Date

/** EPG 日期条目 */
data class LiveEpgDate(
    val index: Int,
    val datePresented: String,
    val dateParamVal: Date
)

/**
 * EPG 节目单适配器，展示单个频道的节目时间与名称。
 * 通过 [setSelectedEpgIndex] / [setFocusedEpgIndex] 控制高亮，
 * 当前时段节目显示"直播"+波动图，已过/未到且频道支持回看时显示"回看/预约"。
 */
class LiveEpgAdapter : RecyclerView.Adapter<LiveEpgAdapter.Holder>() {

    private val data = ArrayList<Epginfo>()

    private var selectedEpgIndex = -1
    private var focusedEpgIndex = -1

    /** 当前频道是否支持回看，控制"回看/预约"标记显示（CanBack） */
    var includeBack: Boolean = false

    private var onItemClickListener: ((Int, Epginfo) -> Unit)? = null

    fun setOnItemClickListener(listener: (Int, Epginfo) -> Unit) {
        onItemClickListener = listener
    }

    fun setNewData(list: List<Epginfo>?) {
        data.clear()
        if (list != null) data.addAll(list)
        selectedEpgIndex = -1
        focusedEpgIndex = -1
        notifyDataSetChanged()
    }

    fun setSelectedEpgIndex(index: Int) {
        if (selectedEpgIndex == index) return
        val old = selectedEpgIndex
        selectedEpgIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun setFocusedEpgIndex(index: Int) {
        if (focusedEpgIndex == index) return
        val old = focusedEpgIndex
        focusedEpgIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun getItem(position: Int): Epginfo? = data.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epglist, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            data[position],
            position == selectedEpgIndex,
            position == focusedEpgIndex,
            includeBack,
            onItemClickListener
        )
    }

    override fun getItemCount(): Int = data.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tv_epg_time)
        private val tvName: TextView = itemView.findViewById(R.id.tv_epg_name)
        private val tvShiyi: TextView = itemView.findViewById(R.id.shiyi)
        private val waveView: AudioWaveView = itemView.findViewById(R.id.wqddg_AudioWaveView)

        fun bind(
            item: Epginfo,
            selected: Boolean,
            focused: Boolean,
            includeBack: Boolean,
            listener: ((Int, Epginfo) -> Unit)?
        ) {
            tvTime.text = "${item.start ?: ""} - ${item.end ?: ""}"
            tvName.text = item.title
            itemView.isSelected = selected || focused
            val themeColor = resolveThemeColor(itemView)
            val white = ContextCompat.getColor(itemView.context, uiR.color.color_FFFFFF)
            // LiveEpgAdapter：当前时段显示"直播"+波动图（直播中图标），
            // 已结束/未开始且支持回看时显示"回看/预约"
            waveView.visibility = View.GONE
            val now = Date()
            val start = item.startdateTime
            val end = item.enddateTime
            when {
                start != null && end != null && !now.before(start) && !now.after(end) -> {
                    tvShiyi.visibility = View.VISIBLE
                    tvShiyi.text = itemView.context.getString(uiR.string.live_now)
                    waveView.visibility = View.VISIBLE
                    tvName.setTextColor(themeColor)
                    tvTime.setTextColor(themeColor)
                }
                end != null && now.after(end) && includeBack -> {
                    tvShiyi.visibility = View.VISIBLE
                    tvShiyi.text = itemView.context.getString(uiR.string.live_epg_back)
                    tvName.setTextColor(white)
                    tvTime.setTextColor(white)
                }
                start != null && now.before(start) && includeBack -> {
                    tvShiyi.visibility = View.VISIBLE
                    tvShiyi.text = itemView.context.getString(uiR.string.live_reserve)
                    tvName.setTextColor(white)
                    tvTime.setTextColor(white)
                }
                else -> {
                    tvShiyi.visibility = View.GONE
                    tvName.setTextColor(white)
                    tvTime.setTextColor(white)
                }
            }
            itemView.setOnClickListener {
                listener?.invoke(bindingAdapterPosition, item)
            }
        }

        /** 从当前主题解析 color_theme 属性 */
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

/**
 * EPG 日期列表适配器，展示“今天/明天”等可选日期。
 */
class LiveEpgDateAdapter : RecyclerView.Adapter<LiveEpgDateAdapter.Holder>() {

    private val data = ArrayList<LiveEpgDate>()

    private var selectedIndex = -1
    private var focusedIndex = -1

    private var onItemClickListener: ((Int, LiveEpgDate) -> Unit)? = null

    fun setOnItemClickListener(listener: (Int, LiveEpgDate) -> Unit) {
        onItemClickListener = listener
    }

    fun setNewData(list: List<LiveEpgDate>?) {
        data.clear()
        if (list != null) data.addAll(list)
        selectedIndex = -1
        focusedIndex = -1
        notifyDataSetChanged()
    }

    fun setSelectedIndex(index: Int) {
        if (selectedIndex == index) return
        val old = selectedIndex
        selectedIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun setFocusedIndex(index: Int) {
        if (focusedIndex == index) return
        val old = focusedIndex
        focusedIndex = index
        if (old in data.indices) notifyItemChanged(old)
        if (index in data.indices) notifyItemChanged(index)
    }

    fun getData(): List<LiveEpgDate> = data

    fun getItem(position: Int): LiveEpgDate? = data.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_setting_group, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            data[position],
            position == selectedIndex,
            position == focusedIndex,
            onItemClickListener
        )
    }

    override fun getItemCount(): Int = data.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvSettingGroupName)

        fun bind(
            item: LiveEpgDate,
            selected: Boolean,
            focused: Boolean,
            listener: ((Int, LiveEpgDate) -> Unit)?
        ) {
            tvName.text = item.datePresented
            itemView.isSelected = selected || focused
            itemView.setOnClickListener {
                listener?.invoke(bindingAdapterPosition, item)
            }
        }
    }
}

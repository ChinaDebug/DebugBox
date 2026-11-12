package com.tvbox.kotlin.feature.home.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.MovieSort
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.home.R

/**
 * 首页分类列表适配器，横向展示分类项。
 * 通过 [setActivatedPosition] 控制当前 ViewPager 对应分类的高亮状态，
 * 焦点动效由 HomeActivity 在 TvRecyclerView 的 OnItemListener 中处理。
 */
class SortAdapter : RecyclerView.Adapter<SortAdapter.SortHolder>() {

    private val data = ArrayList<MovieSort.SortData>()

    /** 当前高亮的分类位置，对应 ViewPager 选中页 */
    private var activatedPosition = -1

    fun getData(): List<MovieSort.SortData> = data

    fun getItem(position: Int): MovieSort.SortData =
        if (position in data.indices) data[position] else throw IndexOutOfBoundsException("sort position=$position size=${data.size}")

    fun setNewData(list: List<MovieSort.SortData>?) {
        data.clear()
        if (list != null) data.addAll(list)
        activatedPosition = -1
        notifyDataSetChanged()
    }

    /** 设置高亮位置，自动刷新新旧位置的绑定状态 */
    fun setActivatedPosition(position: Int) {
        if (activatedPosition == position) return
        val old = activatedPosition
        activatedPosition = position
        if (old in data.indices) notifyItemChanged(old, PAYLOAD_ACTIVATED)
        if (position in data.indices) notifyItemChanged(position, PAYLOAD_ACTIVATED)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SortHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_sort, parent, false)
        return SortHolder(view)
    }

    override fun onBindViewHolder(holder: SortHolder, position: Int) {
        holder.bind(data[position], position == activatedPosition)
    }

    override fun onBindViewHolder(holder: SortHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ACTIVATED)) {
            holder.updateActivated(position == activatedPosition)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = data.size

    class SortHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)

        fun bind(sortData: MovieSort.SortData, activated: Boolean) {
            tvTitle.text = sortData.name
            updateActivated(activated)
        }

        /** 仅刷新高亮样式，避免重绘整个 item 导致焦点动效中断 */
        fun updateActivated(activated: Boolean) {
            itemView.isActivated = activated
            // 激活项常驻放大
            itemView.scaleX = if (activated) 1.1f else 1.0f
            itemView.scaleY = if (activated) 1.1f else 1.0f
            tvTitle.paint.isFakeBoldText = activated
            tvTitle.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (activated) uiR.color.color_FFFFFF else uiR.color.color_FFFFFF_70
                )
            )
            tvTitle.invalidate()
        }
    }

    companion object {
        private const val PAYLOAD_ACTIVATED = "payload_activated"
    }
}

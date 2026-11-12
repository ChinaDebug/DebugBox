package com.tvbox.kotlin.feature.history.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.core.ui.SourceInfoProvider
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.history.R

/**
 * 历史记录列表适配器：网格展示已观看影片。
 *
 * - 进度展示：底部 "看到" 前缀 + 集名跑马灯（仅焦点时滚动）；
 * - 源名：通过 [SourceInfoProvider] 查询，由 :app 注入避免直接依赖 core:spider；
 * - 删除模式：覆盖 [delFrameLayout] 蒙层；
 * - 更新徽章：通过 [hasUpdateProvider] 查询视频更新状态，有更新时显示。
 */
class HistoryAdapter(
    private val sourceInfoProvider: SourceInfoProvider,
    private val hasUpdateProvider: ((VodInfo) -> Boolean)? = null
) : RecyclerView.Adapter<HistoryAdapter.HistoryHolder>() {

    private val data = ArrayList<VodInfo>()
    private var deleteMode = false

    private var onItemClickListener: ((VodInfo, Int) -> Unit)? = null
    private var onItemLongClickListener: ((VodInfo, Int) -> Unit)? = null

    fun setNewData(list: List<VodInfo>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun remove(position: Int) {
        if (position < 0 || position >= data.size) return
        data.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, data.size - position)
    }

    fun getData(): List<VodInfo> = data

    fun setDeleteMode(enabled: Boolean) {
        if (deleteMode == enabled) return
        deleteMode = enabled
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(listener: (VodInfo, Int) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (VodInfo, Int) -> Unit) {
        onItemLongClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_grid, parent, false)
        return HistoryHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
        val item = data[position]
        holder.bind(
            item,
            deleteMode,
            sourceInfoProvider,
            hasUpdateProvider,
            onItemClickListener,
            onItemLongClickListener
        )
    }

    override fun getItemCount(): Int = data.size

    class HistoryHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvYear: TextView = itemView.findViewById(R.id.tvYear)
        private val tvNotePrefix: TextView = itemView.findViewById(R.id.tvNotePrefix)
        private val tvNote: TextView = itemView.findViewById(R.id.tvNote)
        private val delLayer: FrameLayout = itemView.findViewById(R.id.delFrameLayout)
        private val tvUpdateBadge: TextView = itemView.findViewById(R.id.tvUpdateBadge)
        private val tvArea: TextView = itemView.findViewById(R.id.tvArea)
        private val tvLang: TextView = itemView.findViewById(R.id.tvLang)

        fun bind(
            item: VodInfo,
            deleteMode: Boolean,
            sourceInfoProvider: SourceInfoProvider,
            hasUpdateProvider: ((VodInfo) -> Boolean)?,
            click: ((VodInfo, Int) -> Unit)?,
            longClick: ((VodInfo, Int) -> Unit)?
        ) {
            // 显式隐藏地区/语言标签，避免站点来源右侧出现空占位图标
            tvArea.visibility = View.GONE
            tvLang.visibility = View.GONE
            tvName.text = item.name.orEmpty()

            val sourceName = sourceInfoProvider.getSourceName(item.sourceKey)
            if (sourceName.isEmpty()) {
                tvYear.visibility = View.GONE
            } else {
                tvYear.text = sourceName
                tvYear.visibility = View.VISIBLE
            }

            val note = item.note
            if (note.isNullOrEmpty()) {
                tvNote.visibility = View.GONE
                tvNotePrefix.visibility = View.GONE
            } else {
                tvNote.text = note
                tvNote.isSelected = itemView.hasFocus()
                tvNotePrefix.text = itemView.context.getString(uiR.string.history_watched_prefix)
                tvNotePrefix.visibility = View.VISIBLE
                tvNote.visibility = View.VISIBLE
            }

            val hasUpdate = hasUpdateProvider?.invoke(item) == true
            tvUpdateBadge.visibility = if (hasUpdate) View.VISIBLE else View.GONE
            delLayer.visibility = if (deleteMode) View.VISIBLE else View.GONE

            CoilUtils.load(itemView.context, ivThumb, item.pic, radius = 14)

            itemView.setOnFocusChangeListener { _, hasFocus ->
                val parent = itemView.parent
                if (parent is TvRecyclerView) {
                    parent.onFocusChange(itemView, hasFocus)
                }
                if (tvNote.visibility == View.VISIBLE) {
                    tvNote.isSelected = hasFocus
                }
            }
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) click?.invoke(item, pos)
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    longClick?.invoke(item, pos)
                    true
                } else false
            }
        }
    }
}

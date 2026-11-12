package com.tvbox.kotlin.feature.home.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.core.ui.R as uiR

/**
 * 首页聚合页热播列表适配器，渲染首页热播/历史推荐卡片。
 * 绑定 core:ui 的 item_user_hot_vod 布局。
 * 使用 [AsyncListDiffer] 做增量更新，替代全量 notifyDataSetChanged。
 */
class HomeHotVodAdapter(initialStyle: VideoStyle.Style? = null) : RecyclerView.Adapter<HomeHotVodAdapter.Holder>() {

    private val differ = AsyncListDiffer(this, VideoDiffCallback)

    /**
     * 卡片渲染风格，null 时使用默认竖图卡片（豆瓣热播/历史记录）。
     * 可动态切换（站点推荐↔降级内容），切换时刷新全部 item 使卡片尺寸重新应用。
     */
    var style: VideoStyle.Style? = initialStyle
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 标题（豆瓣热播/站点推荐/历史模式源名），为空时隐藏年份标签 */
    var title: String = ""
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 历史模式：显示"看到"前缀与更新徽章 */
    var historyMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 删除模式（历史模式长按切换） */
    var deleteMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** 更新徽章判定，由 UserFragment 提供 */
    var hasUpdate: (Movie.Video) -> Boolean = { false }

    var onItemClickListener: ((Movie.Video, Int) -> Unit)? = null
    var onItemLongClickListener: ((Movie.Video, Int) -> Boolean)? = null

    fun setNewData(list: List<Movie.Video>) {
        differ.submitList(list)
    }

    fun removeAt(position: Int) {
        val current = differ.currentList
        if (position in current.indices) {
            differ.submitList(current.toMutableList().apply { removeAt(position) })
        }
    }

    fun getData(): List<Movie.Video> = differ.currentList

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(uiR.layout.item_user_hot_vod, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(differ.currentList[position], this)
    }

    override fun getItemCount(): Int = differ.currentList.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(uiR.id.ivThumb)
        private val tvYear: TextView = itemView.findViewById(uiR.id.tvYear)
        private val tvRate: TextView = itemView.findViewById(uiR.id.tvRate)
        private val tvNotePrefix: TextView = itemView.findViewById(uiR.id.tvNotePrefix)
        private val tvNote: TextView = itemView.findViewById(uiR.id.tvNote)
        private val tvName: TextView = itemView.findViewById(uiR.id.tvName)
        private val tvUpdateBadge: TextView = itemView.findViewById(uiR.id.tvUpdateBadge)
        private val delFrameLayout: FrameLayout = itemView.findViewById(uiR.id.delFrameLayout)

        fun bind(video: Movie.Video, adapter: HomeHotVodAdapter) {
            tvYear.text = adapter.title
            tvYear.visibility = if (adapter.title.isEmpty()) View.GONE else View.VISIBLE
            // 评分标签统一并入备注显示，避免与标题标签冲突
            tvRate.visibility = View.GONE
            tvName.text = video.name
            if (video.note.isNullOrEmpty()) {
                tvNote.visibility = View.GONE
                tvNotePrefix.visibility = View.GONE
            } else {
                tvNote.text = video.note
                tvNote.visibility = View.VISIBLE
                tvNote.isSelected = itemView.hasFocus()
                // 历史模式固定显示"看到"前缀（避免空文本占位标签）
                tvNotePrefix.text = "看到"
                tvNotePrefix.visibility = if (adapter.historyMode) View.VISIBLE else View.GONE
            }
            delFrameLayout.visibility = if (adapter.deleteMode) View.VISIBLE else View.GONE
            if (adapter.historyMode && !video.sourceKey.isNullOrEmpty() && !video.id.isNullOrEmpty()) {
                tvUpdateBadge.visibility = if (adapter.hasUpdate(video)) View.VISIBLE else View.GONE
            } else {
                tvUpdateBadge.visibility = View.GONE
            }
            if (video.pic.isNullOrEmpty()) {
                ivThumb.setImageResource(uiR.drawable.img_loading_placeholder)
            } else {
                CoilUtils.load(itemView.context, ivThumb, video.pic, radius = 14)
            }
            // 始终应用卡片尺寸：style 非空按源站比例，null 恢复默认竖图，避免复用残留源站风格尺寸
            VideoStyle.applyStyleToImage(ivThumb, adapter.style)
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.onItemClickListener?.invoke(video, pos)
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    adapter.onItemLongClickListener?.invoke(video, pos) ?: false
                } else {
                    false
                }
            }
        }
    }
}

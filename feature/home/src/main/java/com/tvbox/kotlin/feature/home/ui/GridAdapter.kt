package com.tvbox.kotlin.feature.home.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.feature.home.R

/**
 * 影片网格适配器，渲染封面、名称、年份、地区、备注等信息。
 * 点击事件通过 [setOnItemClickListener] 回调，由 GridFragment 处理跳转详情。
 * [showList] 为 true 时使用单行列表布局（文件夹模式）。
 * 使用 [AsyncListDiffer] 做增量更新，替代全量 notifyDataSetChanged，减少滚动重绑定开销。
 */
class GridAdapter(
    private val showList: Boolean = false,
    private val style: VideoStyle.Style? = null
) : RecyclerView.Adapter<GridAdapter.GridHolder>() {

    private val differ = AsyncListDiffer(this, VideoDiffCallback)

    /** 源站 style 为 list 类型时强制使用单行列表布局 */
    private val useList: Boolean = showList || style?.type == "list"

    private var onItemClickListener: ((Movie.Video, Int) -> Unit)? = null

    private var onItemLongClickListener: ((Movie.Video, Int) -> Boolean)? = null

    fun setNewData(list: List<Movie.Video>?) {
        differ.submitList(list)
    }

    fun addData(list: List<Movie.Video>) {
        if (list.isEmpty()) return
        differ.submitList(differ.currentList + list)
    }

    fun getData(): List<Movie.Video> = differ.currentList

    fun clear() {
        differ.submitList(emptyList())
    }

    fun setOnItemClickListener(listener: (Movie.Video, Int) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (Movie.Video, Int) -> Boolean) {
        onItemLongClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridHolder {
        val layout = if (useList) R.layout.item_list else R.layout.item_grid
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return GridHolder(view)
    }

    override fun onBindViewHolder(holder: GridHolder, position: Int) {
        // 列表布局下不应用 style 动态宽高，保持固定行高
        holder.bind(differ.currentList[position], if (useList) null else style, onItemClickListener, onItemLongClickListener)
    }

    override fun getItemCount(): Int = differ.currentList.size

    class GridHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // item_list（文件夹模式）布局缺少 tvYear/tvArea/tvLang，按需判空
        private val ivThumb = itemView.findViewById<ImageView>(R.id.ivThumb)
        private val tvYear = itemView.findViewById<TextView>(R.id.tvYear)
        private val tvArea = itemView.findViewById<TextView>(R.id.tvArea)
        private val tvLang = itemView.findViewById<TextView>(R.id.tvLang)
        private val tvNote = itemView.findViewById<TextView>(R.id.tvNote)
        private val tvName = itemView.findViewById<TextView>(R.id.tvName)

        fun bind(
            video: Movie.Video,
            style: VideoStyle.Style?,
            listener: ((Movie.Video, Int) -> Unit)?,
            longListener: ((Movie.Video, Int) -> Boolean)?
        ) {
            tvName?.text = video.name
            // 年份 >0 显示，地区/语言始终隐藏
            if (tvYear != null && tvArea != null && tvLang != null && tvNote != null) {
                if (video.year > 0) {
                    tvYear.text = video.year.toString()
                    tvYear.visibility = View.VISIBLE
                } else {
                    tvYear.visibility = View.GONE
                }
                tvArea.visibility = View.GONE
                tvLang.visibility = View.GONE
                tvNote.text = video.note.orEmpty()
                // 备注为空时隐藏标签，避免空白背景块
                tvNote.visibility = if (video.note.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            ivThumb?.let {
                CoilUtils.load(itemView.context, it, video.pic, radius = 14)
                if (style != null) VideoStyle.applyStyleToImage(it, style)
            }
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    listener?.invoke(video, pos)
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    longListener?.invoke(video, pos) ?: false
                } else {
                    false
                }
            }
        }
    }
}

/**
 * 影片列表 Diff 规则：id + 源站标识判同，展示字段判内容是否变化。
 * GridAdapter 与 HomeHotVodAdapter 共用。
 */
object VideoDiffCallback : DiffUtil.ItemCallback<Movie.Video>() {
    override fun areItemsTheSame(oldItem: Movie.Video, newItem: Movie.Video): Boolean {
        return oldItem.sourceKey == newItem.sourceKey && oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Movie.Video, newItem: Movie.Video): Boolean {
        return oldItem.name == newItem.name &&
            oldItem.pic == newItem.pic &&
            oldItem.note == newItem.note &&
            oldItem.year == newItem.year &&
            oldItem.action == newItem.action
    }
}

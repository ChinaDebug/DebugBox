package com.tvbox.kotlin.feature.search.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.feature.search.R

/**
 * 搜索结果适配器，支持文字列表与缩略图两种视图模式。
 *
 * - [VIEW_TYPE_LITE]：大文字列表场景，站点名前置、片名在后；
 * - [VIEW_TYPE_THUMB]：展示封面、站点、备注、名称，信息密度更高。
 *
 * 点击跳转详情，长按触发快速播放回调。
 */
class SearchAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 文字列表视图类型 */
    private val VIEW_TYPE_LITE = 0

    /** 缩略图视图类型 */
    private val VIEW_TYPE_THUMB = 1

    private val data = ArrayList<Movie.Video>()

    /** 当前视图模式，0 文字列表，1 缩略图 */
    private var viewType = VIEW_TYPE_LITE

    /** 站点 key -> 站点名称 查询，用于缩略图模式显示来源 */
    private var sourceNameProvider: ((String?) -> String)? = null

    private var onItemClickListener: ((Movie.Video, Int) -> Unit)? = null
    private var onItemLongClickListener: ((Movie.Video, Int) -> Unit)? = null

    fun setViewType(type: Int) {
        if (type == viewType) return
        viewType = if (type == 1) VIEW_TYPE_THUMB else VIEW_TYPE_LITE
        notifyDataSetChanged()
    }

    fun setSourceNameProvider(provider: (String?) -> String) {
        sourceNameProvider = provider
    }

    fun setOnItemClickListener(listener: (Movie.Video, Int) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (Movie.Video, Int) -> Unit) {
        onItemLongClickListener = listener
    }

    fun setNewData(list: List<Movie.Video>?) {
        data.clear()
        if (list != null) data.addAll(list)
        notifyDataSetChanged()
    }

    fun addData(list: List<Movie.Video>) {
        if (list.isEmpty()) return
        val start = data.size
        data.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun getData(): List<Movie.Video> = data

    fun clear() {
        data.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_THUMB) R.layout.item_search else R.layout.item_search_lite
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return if (viewType == VIEW_TYPE_THUMB) ThumbHolder(view) else LiteHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val video = data[position]
        when (holder) {
            is LiteHolder -> holder.bind(video, sourceNameProvider)
            is ThumbHolder -> holder.bind(video, sourceNameProvider)
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onItemClickListener?.invoke(video, pos)
            }
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onItemLongClickListener?.invoke(video, pos)
            }
            true
        }
    }

    override fun getItemCount(): Int = data.size

    /** 文字列表 ViewHolder，站点名前置、片名在后，中间留间距隔开 */
    class LiteHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)

        fun bind(video: Movie.Video, sourceNameProvider: ((String?) -> String)?) {
            val name = video.name.orEmpty()
            val siteName = sourceNameProvider?.invoke(video.sourceKey).orEmpty()
            if (siteName.isEmpty()) {
                tvName.text = name
            } else {
                // 站点名以半透明白色弱化置于片名之前，中间空两格保证视觉间距
                val spannable = SpannableString("$siteName  $name")
                spannable.setSpan(
                    ForegroundColorSpan(
                        itemView.context.getColor(com.tvbox.kotlin.core.ui.R.color.color_FFFFFF_60)
                    ),
                    0,
                    siteName.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                tvName.text = spannable
            }
        }
    }

    /** 缩略图 ViewHolder，绑定封面、站点、备注、名称 */
    class ThumbHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        private val tvSite: TextView = itemView.findViewById(R.id.tvSite)
        private val tvNote: TextView = itemView.findViewById(R.id.tvNote)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)

        fun bind(video: Movie.Video, sourceNameProvider: ((String?) -> String)?) {
            tvName.text = video.name.orEmpty()
            tvNote.text = video.note.orEmpty()
            tvNote.visibility = if (video.note.isNullOrEmpty()) View.GONE else View.VISIBLE
            val siteName = sourceNameProvider?.invoke(video.sourceKey).orEmpty()
            tvSite.text = siteName
            tvSite.visibility = if (siteName.isEmpty()) View.GONE else View.VISIBLE
            CoilUtils.load(itemView.context, ivThumb, video.pic, radius = 14)
        }
    }
}

package com.tvbox.kotlin.feature.collect.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.data.entity.VodCollect
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.core.ui.SourceInfoProvider
import com.tvbox.kotlin.feature.collect.R

/**
 * 收藏列表适配器：网格展示收藏项的封面、名称与源名。
 *
 * - 删除模式下显示 [item_grid] 内置的 delFrameLayout 蒙层；
 * - 源名通过 [SourceInfoProvider] 查询，由 :app 模块注入避免直接依赖 core:spider；
 * - 点击事件交由 [setOnItemClickListener] 回调；
 * - 长按事件交由 [setOnItemLongClickListener] 回调（用于切换删除模式）。
 */
class CollectAdapter(
    private val sourceInfoProvider: SourceInfoProvider
) : RecyclerView.Adapter<CollectAdapter.CollectHolder>() {

    private val data = ArrayList<VodCollect>()
    private var deleteMode = false

    private var onItemClickListener: ((VodCollect, Int) -> Unit)? = null
    private var onItemLongClickListener: ((VodCollect, Int) -> Unit)? = null

    fun setNewData(list: List<VodCollect>) {
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

    fun getData(): List<VodCollect> = data

    fun setDeleteMode(enabled: Boolean) {
        if (deleteMode == enabled) return
        deleteMode = enabled
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(listener: (VodCollect, Int) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (VodCollect, Int) -> Unit) {
        onItemLongClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_grid, parent, false)
        return CollectHolder(view)
    }

    override fun onBindViewHolder(holder: CollectHolder, position: Int) {
        holder.bind(
            data[position],
            deleteMode,
            sourceInfoProvider,
            onItemClickListener,
            onItemLongClickListener
        )
    }

    override fun getItemCount(): Int = data.size

    class CollectHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvYear: TextView = itemView.findViewById(R.id.tvYear)
        private val tvArea: TextView = itemView.findViewById(R.id.tvArea)
        private val tvLang: TextView = itemView.findViewById(R.id.tvLang)
        private val tvNote: TextView = itemView.findViewById(R.id.tvNote)
        private val delLayer: FrameLayout = itemView.findViewById(R.id.delFrameLayout)

        fun bind(
            item: VodCollect,
            deleteMode: Boolean,
            sourceInfoProvider: SourceInfoProvider,
            click: ((VodCollect, Int) -> Unit)?,
            longClick: ((VodCollect, Int) -> Unit)?
        ) {
            tvName.text = item.name.orEmpty()
            val sourceName = sourceInfoProvider.getSourceName(item.sourceKey)
            if (sourceName.isEmpty()) {
                tvYear.visibility = View.GONE
            } else {
                tvYear.text = sourceName
                tvYear.visibility = View.VISIBLE
            }
            tvArea.visibility = View.GONE
            tvLang.visibility = View.GONE
            tvNote.visibility = View.GONE
            delLayer.visibility = if (deleteMode) View.VISIBLE else View.GONE
            CoilUtils.load(itemView.context, ivThumb, item.pic, radius = 14)
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

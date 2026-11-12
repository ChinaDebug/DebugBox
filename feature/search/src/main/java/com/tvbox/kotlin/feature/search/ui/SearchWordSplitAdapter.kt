package com.tvbox.kotlin.feature.search.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.feature.search.R

/**
 * 分词列表适配器，横向展示搜索关键字的分词，点击以该分词重新搜索。
 */
class SearchWordSplitAdapter : RecyclerView.Adapter<SearchWordSplitAdapter.SplitHolder>() {

    private val data = ArrayList<String>()

    private var onItemClickListener: ((String, Int) -> Unit)? = null

    fun setOnItemClickListener(listener: (String, Int) -> Unit) {
        onItemClickListener = listener
    }

    fun setNewData(list: List<String>?) {
        data.clear()
        if (list != null) data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SplitHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_word_split, parent, false)
        return SplitHolder(view)
    }

    override fun onBindViewHolder(holder: SplitHolder, position: Int) {
        holder.bind(data[position], onItemClickListener)
    }

    override fun getItemCount(): Int = data.size

    class SplitHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSearchWord: TextView = itemView.findViewById(R.id.tvSearchWord)

        fun bind(word: String, listener: ((String, Int) -> Unit)?) {
            tvSearchWord.text = word
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    listener?.invoke(word, pos)
                }
            }
        }
    }
}

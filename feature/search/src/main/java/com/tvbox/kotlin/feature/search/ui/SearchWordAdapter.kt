package com.tvbox.kotlin.feature.search.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.feature.search.R

/**
 * 热门搜索词适配器，纵向列表展示，点击触发搜索。
 */
class SearchWordAdapter : RecyclerView.Adapter<SearchWordAdapter.WordHolder>() {

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

    fun getData(): List<String> = data

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_word_hot, parent, false)
        return WordHolder(view)
    }

    override fun onBindViewHolder(holder: WordHolder, position: Int) {
        holder.bind(data[position], onItemClickListener)
    }

    override fun getItemCount(): Int = data.size

    class WordHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
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

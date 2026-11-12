package com.tvbox.kotlin.feature.detail.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.detail.R

/**
 * 详情页快速搜索对话框：
 * 顶部为分词词条（横向列表），下方为各源搜索结果列表（纵向列表），
 * 点击分词换词重搜，点击结果跳转对应源详情。
 */
class QuickSearchDialog(context: Context) : BaseDialog(context) {

    private val resultList = mutableListOf<Movie.Video>()
    private val wordList = mutableListOf<String>()
    private val resultAdapter = QuickSearchResultAdapter()
    private val wordAdapter = QuickSearchWordAdapter()

    /** 分词词条点击回调 */
    var onWordClick: ((String) -> Unit)? = null

    /** 搜索结果点击回调 */
    var onResultClick: ((Movie.Video) -> Unit)? = null

    /** 源名解析器：sourceKey -> 站点显示名，为 null 时直接显示 key */
    var sourceNameResolver: ((String?) -> String?)? = null

    init {
        setContentView(R.layout.dialog_quick_search)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val gridViewWord = findViewById<TvRecyclerView>(R.id.mGridViewWord)
        gridViewWord.layoutManager = V7LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        gridViewWord.adapter = wordAdapter
        wordAdapter.onItemClick = { word ->
            onWordClick?.invoke(word)
        }

        val gridView = findViewById<TvRecyclerView>(R.id.mGridView)
        gridView.layoutManager = V7LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        gridView.adapter = resultAdapter
        resultAdapter.onItemClick = { video ->
            onResultClick?.invoke(video)
            dismiss()
        }
    }

    /** 更新分词词条列表 */
    fun setWords(words: List<String>) {
        wordList.clear()
        wordList.addAll(words)
        wordAdapter.notifyDataSetChanged()
    }

    /** 清空并设置搜索结果 */
    fun setResults(results: List<Movie.Video>) {
        resultList.clear()
        resultList.addAll(results)
        resultAdapter.notifyDataSetChanged()
    }

    /** 追加搜索结果 */
    fun addResults(results: List<Movie.Video>) {
        resultList.addAll(results)
        resultAdapter.notifyDataSetChanged()
    }

    /** 分词词条适配器 */
    private inner class QuickSearchWordAdapter : RecyclerView.Adapter<QuickSearchWordAdapter.WordHolder>() {

        var onItemClick: ((String) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_quick_search_word, parent, false)
            return WordHolder(view)
        }

        override fun onBindViewHolder(holder: WordHolder, position: Int) {
            holder.bind(wordList[position])
        }

        override fun getItemCount(): Int = wordList.size

        inner class WordHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvWord: TextView = itemView.findViewById(R.id.tvSearchWord)

            fun bind(word: String) {
                tvWord.text = word
                itemView.setOnClickListener { onItemClick?.invoke(word) }
            }
        }
    }

    /** 搜索结果适配器 */
    private inner class QuickSearchResultAdapter : RecyclerView.Adapter<QuickSearchResultAdapter.ResultHolder>() {

        var onItemClick: ((Movie.Video) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_quick_search_lite, parent, false)
            return ResultHolder(view)
        }

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            holder.bind(resultList[position])
        }

        override fun getItemCount(): Int = resultList.size

        inner class ResultHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvName)

            fun bind(video: Movie.Video) {
                val sourceName = sourceNameResolver?.invoke(video.sourceKey) ?: video.sourceKey ?: ""
                val content = "${video.name ?: ""} ${video.type ?: ""} ${video.note ?: ""}".trim()
                if (sourceName.isEmpty()) {
                    tvName.text = content
                } else {
                    // 站点名以半透明白色弱化置于片名之前，中间空两格保证视觉间距（与搜索页一致）
                    val spannable = SpannableString("$sourceName  $content")
                    spannable.setSpan(
                        ForegroundColorSpan(
                            itemView.context.getColor(com.tvbox.kotlin.core.ui.R.color.color_FFFFFF_60)
                        ),
                        0,
                        sourceName.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    tvName.text = spannable
                }
                itemView.setOnClickListener { onItemClick?.invoke(video) }
            }
        }
    }
}

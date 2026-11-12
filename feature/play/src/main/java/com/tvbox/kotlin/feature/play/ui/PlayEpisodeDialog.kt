package com.tvbox.kotlin.feature.play.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.ui.R as CoreUiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.play.R
import java.util.Collections

/**
 * 选集弹窗。
 *
 * - 顶部横排展示线路（VodSeriesFlag），点击切换剧集列表；
 * - 下方网格展示当前线路剧集，选中当前播放集高亮主题色；
 * - 倒序按钮反转剧集顺序；
 * - 点击剧集回调 [onEpisodeSelected]，参数为 (flag, episodeIndex)。
 *
 * 数据由外部传入 [VodInfo] 快照，弹窗内部不修改原始数据。
 */
class PlayEpisodeDialog(
    context: Context,
    private val vodInfo: VodInfo,
    private val currentFlag: String?,
    private val currentEpisodeIndex: Int
) : BaseDialog(context, CoreUiR.style.CustomDialogStyle) {

    /** 选集回调，参数：剧集索引 */
    private var episodeSelectedListener: ((flag: String, episodeIndex: Int) -> Unit)? = null

    /** 倒序回调，Activity 用于同步 VodInfo 状态 */
    private var reverseListener: (() -> Unit)? = null

    /** 主题色，由 Activity 注入 */
    private var themeColor: Int = Color.WHITE

    /** 当前展示的线路名 */
    private var displayFlag: String = currentFlag ?: ""

    /** 当前剧集列表（可被倒序） */
    private val displaySeries = ArrayList<VodInfo.VodSeries>()

    /** 当前选中的剧集索引（基于 displaySeries） */
    private var selectedEpisodeIndex: Int = currentEpisodeIndex

    /** 剧集索引到原 seriesMap 位置的映射，用于倒序时还原真实索引 */
    private val indexMapping = ArrayList<Int>()

    private lateinit var flagAdapter: FlagAdapter
    private lateinit var seriesAdapter: SeriesAdapter

    fun setThemeColor(color: Int): PlayEpisodeDialog {
        themeColor = color
        return this
    }

    fun onEpisodeSelected(listener: (flag: String, episodeIndex: Int) -> Unit): PlayEpisodeDialog {
        episodeSelectedListener = listener
        return this
    }

    fun onReverse(listener: () -> Unit): PlayEpisodeDialog {
        reverseListener = listener
        return this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_play_episode)

        findViewById<TextView>(R.id.tvTitle).apply {
            text = vodInfo.name ?: "选集"
            setTextColor(themeColor)
        }

        findViewById<View>(R.id.btnClose).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btnReverse).setOnClickListener { handleReverse() }

        flagAdapter = FlagAdapter().apply {
            setData(vodInfo.seriesFlags ?: emptyList(), displayFlag)
            onFlagClick = { flag -> switchFlag(flag) }
        }
        findViewById<TvRecyclerView>(R.id.rvFlag).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = flagAdapter
        }

        seriesAdapter = SeriesAdapter().apply {
            onEpisodeClick = { displayIndex -> handleEpisodeClick(displayIndex) }
        }
        findViewById<TvRecyclerView>(R.id.rvSeries).apply {
            layoutManager = V7GridLayoutManager(context, GRID_SPAN_COUNT)
            adapter = seriesAdapter
        }

        if (displayFlag.isEmpty()) {
            vodInfo.seriesFlags?.firstOrNull()?.name?.let { switchFlag(it) }
        } else {
            refreshSeriesList()
        }
    }

    /** 切换线路并刷新剧集列表 */
    private fun switchFlag(flag: String) {
        displayFlag = flag
        refreshSeriesList()
        flagAdapter.setSelected(flag)
    }

    /** 重新构建 displaySeries 与索引映射，保留原始顺序 */
    private fun refreshSeriesList() {
        displaySeries.clear()
        indexMapping.clear()
        val original = vodInfo.seriesMap?.get(displayFlag) ?: emptyList()
        original.forEachIndexed { index, series ->
            displaySeries.add(series)
            indexMapping.add(index)
        }
        selectedEpisodeIndex = if (displayFlag == currentFlag) currentEpisodeIndex else 0
        if (selectedEpisodeIndex >= displaySeries.size) selectedEpisodeIndex = 0
        seriesAdapter.setData(displaySeries, selectedEpisodeIndex, themeColor)
        // 滚动到当前选中位置
        if (selectedEpisodeIndex > 0) {
            findViewById<TvRecyclerView>(R.id.rvSeries).scrollToPosition(selectedEpisodeIndex)
        }
    }

    /** 倒序处理：交换 displaySeries 与 indexMapping */
    private fun handleReverse() {
        Collections.reverse(displaySeries)
        Collections.reverse(indexMapping)
        // 重新计算选中位置
        val originalIndex = if (displayFlag == currentFlag) currentEpisodeIndex else -1
        selectedEpisodeIndex = if (originalIndex >= 0) {
            indexMapping.indexOf(originalIndex).let { if (it >= 0) it else 0 }
        } else 0
        seriesAdapter.setData(displaySeries, selectedEpisodeIndex, themeColor)
        reverseListener?.invoke()
    }

    /** 选中剧集，回调真实索引 */
    private fun handleEpisodeClick(displayIndex: Int) {
        if (displayIndex < 0 || displayIndex >= indexMapping.size) return
        val realIndex = indexMapping[displayIndex]
        selectedEpisodeIndex = displayIndex
        seriesAdapter.setSelected(displayIndex)
        episodeSelectedListener?.invoke(displayFlag, realIndex)
        dismiss()
    }

    private companion object {
        const val GRID_SPAN_COUNT = 6
    }

    // ------------------------------------------------------------------
    // 线路适配器
    // ------------------------------------------------------------------

    private inner class FlagAdapter : RecyclerView.Adapter<FlagAdapter.FlagHolder>() {
        private val flags = ArrayList<VodInfo.VodSeriesFlag>()
        private var selected: String = ""

        fun setData(list: List<VodInfo.VodSeriesFlag>, selected: String) {
            flags.clear()
            flags.addAll(list)
            this.selected = selected
            notifyDataSetChanged()
        }

        fun setSelected(flag: String) {
            selected = flag
            notifyDataSetChanged()
        }

        var onFlagClick: ((String) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FlagHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_play_episode_flag, parent, false)
            return FlagHolder(view)
        }

        override fun onBindViewHolder(holder: FlagHolder, position: Int) {
            holder.bind(flags[position], selected == flags[position].name, themeColor)
        }

        override fun getItemCount(): Int = flags.size

        inner class FlagHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvFlag: TextView = itemView.findViewById(R.id.tvFlag)
            fun bind(flag: VodInfo.VodSeriesFlag, selected: Boolean, themeColor: Int) {
                tvFlag.text = flag.name
                // 聚焦时背景为主题色，文字统一白色保证对比；未聚焦时选中项用主题色
                val applyColor = { focused: Boolean ->
                    tvFlag.setTextColor(if (focused) Color.WHITE else if (selected) themeColor else Color.WHITE)
                }
                applyColor(itemView.isFocused)
                itemView.setOnFocusChangeListener { v, hasFocus ->
                    // 转发给宿主 TvRecyclerView，保持其焦点态（选中边框/onItemSelected）同步
                    (itemView.parent as? TvRecyclerView)?.onFocusChange(itemView, hasFocus)
                    applyColor(hasFocus)
                }
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos < 0 || pos >= flags.size) return@setOnClickListener
                    val name = flags[pos].name ?: return@setOnClickListener
                    onFlagClick?.invoke(name)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 剧集适配器
    // ------------------------------------------------------------------

    private inner class SeriesAdapter : RecyclerView.Adapter<SeriesAdapter.SeriesHolder>() {
        private val episodes = ArrayList<VodInfo.VodSeries>()
        private var selectedIndex: Int = -1
        private var themeColor: Int = Color.WHITE

        var onEpisodeClick: ((Int) -> Unit)? = null

        fun setData(list: List<VodInfo.VodSeries>, selectedIndex: Int, themeColor: Int) {
            episodes.clear()
            episodes.addAll(list)
            this.selectedIndex = selectedIndex
            this.themeColor = themeColor
            notifyDataSetChanged()
        }

        fun setSelected(index: Int) {
            val old = selectedIndex
            selectedIndex = index
            if (old >= 0) notifyItemChanged(old)
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeriesHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_play_episode, parent, false)
            return SeriesHolder(view)
        }

        override fun onBindViewHolder(holder: SeriesHolder, position: Int) {
            holder.bind(episodes[position], position == selectedIndex, themeColor)
        }

        override fun getItemCount(): Int = episodes.size

        inner class SeriesHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvEpisode: TextView = itemView.findViewById(R.id.tvEpisode)
            fun bind(series: VodInfo.VodSeries, selected: Boolean, themeColor: Int) {
                tvEpisode.text = series.name
                // 聚焦时背景为主题色，文字统一白色保证对比；未聚焦时选中项用主题色
                val applyColor = { focused: Boolean ->
                    tvEpisode.setTextColor(if (focused) Color.WHITE else if (selected) themeColor else Color.WHITE)
                }
                applyColor(itemView.isFocused)
                itemView.setOnFocusChangeListener { v, hasFocus ->
                    // 转发给宿主 TvRecyclerView，保持其焦点态（选中边框/onItemSelected）同步
                    (itemView.parent as? TvRecyclerView)?.onFocusChange(itemView, hasFocus)
                    applyColor(hasFocus)
                }
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos < 0 || pos >= episodes.size) return@setOnClickListener
                    onEpisodeClick?.invoke(pos)
                }
            }
        }
    }
}

package com.tvbox.kotlin.feature.search.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.pauseImagesOnScroll
import com.tvbox.kotlin.feature.search.databinding.ActivityFastSearchBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 聚合搜索页：多源并发搜索，按影片名聚合展示，支持按源过滤。
 *
 * 交互：
 * - 从外部传入关键字自动触发搜索；
 * - 分词列表点击以该分词重新搜索；
 * - 左侧源过滤列表获焦即切换右侧结果；
 * - 点击结果跳转详情，长按触发快速播放；
 * - 顶部状态栏实时展示搜索进度与结果数。
 */
@AndroidEntryPoint
class FastSearchActivity : BaseBindingActivity<ActivityFastSearchBinding>() {

    @Inject
    lateinit var appNavigator: AppNavigator

    @Inject
    lateinit var apiConfig: ApiConfig

    private val viewModel: SearchViewModel by viewModels()

    private lateinit var searchAdapter: FastSearchAdapter
    private lateinit var searchAdapterFilter: FastSearchAdapter
    private lateinit var siteAdapter: FastSearchSiteAdapter
    private lateinit var splitAdapter: SearchWordSplitAdapter

    /** 站点 key -> 站点名称（LinkedHashMap 保证迭代顺序稳定，供源列表增量追加比对） */
    private val sourceNames = LinkedHashMap<String, String>()

    /** 按源分组的结果，过滤时使用 */
    private val resultVods = LinkedHashMap<String, ArrayList<Movie.Video>>()

    /** 是否处于过滤模式（选中具体源） */
    private var filterMode = false

    /** 当前过滤的源 key */
    private var searchFilterKey = ""

    /** 是否已为源列表首项请求过默认焦点，避免后续刷新时抢焦点 */
    private var siteFirstFocusRequested = false

    override val layoutId: Int = com.tvbox.kotlin.feature.search.R.layout.activity_fast_search

    override fun initView(savedInstanceState: Bundle?) {
        initSourceNames()
        initSplitList()
        initSiteList()
        initResultLists()
        setupBackPress()
    }

    override fun initData() {
        observeViewModel()
        intent?.getStringExtra(EXTRA_WORD)?.takeIf { it.isNotBlank() }?.let { word ->
            binding.mSearchWord.text = word
            binding.mSearchWord.visibility = View.VISIBLE
            viewModel.search(word, fast = true)
        }
    }

    /** 构建 key->name 映射 */
    private fun initSourceNames() {
        sourceNames.clear()
        for (bean in apiConfig.getSourceBeanList()) {
            val key = bean.key ?: continue
            sourceNames[key] = bean.name ?: key
        }
    }

    /** 初始化分词列表 */
    private fun initSplitList() {
        splitAdapter = SearchWordSplitAdapter()
        splitAdapter.setOnItemClickListener { word, _ ->
            binding.mSearchWord.text = word
            binding.mSearchWord.visibility = View.VISIBLE
            viewModel.search(word, fast = true)
        }
        binding.mGridViewWordFenci.setHasFixedSize(true)
        binding.mGridViewWordFenci.layoutManager =
            V7LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.mGridViewWordFenci.adapter = splitAdapter
    }

    /** 初始化左侧源过滤列表 */
    private fun initSiteList() {
        siteAdapter = FastSearchSiteAdapter()
        siteAdapter.setOnItemSelectedListener { spName -> filterResult(spName) }
        siteAdapter.setOnItemClickListener { spName, _ -> filterResult(spName) }
        binding.mGridViewWord.setHasFixedSize(true)
        binding.mGridViewWord.layoutManager =
            V7LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.mGridViewWord.adapter = siteAdapter
        siteAdapter.setNewData(listOf(getString(uiR.string.fs_show_all)))
        binding.mGridViewWord.addOnChildAttachStateChangeListener(object :
            androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(child: View) {
                child.isFocusable = true
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    /** 初始化主结果列表与过滤结果列表，按设置的文字列表/缩略图模式渲染 */
    private fun initResultLists() {
        val viewType = viewModel.getSearchViewType()
        resultFocusScale = if (viewType == 0) FOCUS_LIST_SCALE else FOCUS_SCALE

        searchAdapter = FastSearchAdapter()
        searchAdapter.setViewType(viewType)
        searchAdapter.setSourceNameProvider { key -> sourceNames[key].orEmpty() }
        searchAdapter.setOnItemClickListener { video, _ -> goToDetail(video) }
        searchAdapter.setOnItemLongClickListener { video, _ -> goToDetail(video) }

        searchAdapterFilter = FastSearchAdapter()
        searchAdapterFilter.setViewType(viewType)
        searchAdapterFilter.setSourceNameProvider { key -> sourceNames[key].orEmpty() }
        searchAdapterFilter.setOnItemClickListener { video, _ -> goToDetail(video) }
        searchAdapterFilter.setOnItemLongClickListener { video, _ -> goToDetail(video) }

        // 注意：LayoutManager 实例不能跨 RecyclerView 共用（attach 第二个列表会抛异常），
        // 每个列表各自创建
        fun createLayoutManager() =
            if (viewType == 0) {
                V7LinearLayoutManager(this, 1, false)
            } else {
                V7GridLayoutManager(this, GRID_SPAN_COUNT)
            }
        binding.mGridView.layoutManager = createLayoutManager()
        binding.mGridView.adapter = searchAdapter
        // 快速滚动时暂停图片加载，停止后恢复
        binding.mGridView.pauseImagesOnScroll()
        binding.mGridView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                setPosterFocusScale(itemView, false)
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                setPosterFocusScale(itemView, true)
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {}
        })

        binding.mGridViewFilter.layoutManager = createLayoutManager()
        binding.mGridViewFilter.adapter = searchAdapterFilter
        // 快速滚动时暂停图片加载，停止后恢复
        binding.mGridViewFilter.pauseImagesOnScroll()
        binding.mGridViewFilter.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                setPosterFocusScale(itemView, false)
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                setPosterFocusScale(itemView, true)
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {}
        })
    }

    /** 焦点放大动画（文字列表用小比例，缩略图用大比例） */
    private var resultFocusScale = FOCUS_SCALE
    private fun setPosterFocusScale(itemView: View?, focused: Boolean) {
        if (itemView == null) return
        val scale = if (focused) resultFocusScale else 1.0f
        // 缩放值未变化时跳过，避免列表刷新触发的重复动画持续放大缩小
        if (kotlin.math.abs(itemView.scaleX - scale) < 0.01f) return
        itemView.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(FOCUS_ANIM_DURATION)
            .setInterpolator(BounceInterpolator())
            .start()
    }

    /** 按站点名过滤结果 */
    private fun filterResult(spName: String) {
        if (spName.isEmpty()) return
        siteAdapter.setSelectedName(spName)
        siteAdapter.refreshVisibleSelection(binding.mGridViewWord)

        if (spName == getString(uiR.string.fs_show_all)) {
            // 焦点在任一结果列表内时随可见性转移到主列表，避免丢失被分词/源列表抢占
            val hadResultFocus = binding.mGridView.hasFocus() || binding.mGridViewFilter.hasFocus()
            binding.mGridView.visibility = View.VISIBLE
            binding.mGridViewFilter.visibility = View.GONE
            filterMode = false
            if (hadResultFocus) {
                restoreResultFocus(binding.mGridView)
            }
            return
        }
        // 根据站点名反查 key
        val key = sourceNames.entries.firstOrNull { it.value == spName }?.key
        if (key.isNullOrEmpty()) return
        if (key == searchFilterKey) return
        searchFilterKey = key
        val list = resultVods[key] ?: ArrayList()
        // 焦点在任一结果列表内时切分类后转移到过滤列表，避免丢失被分词/源列表抢占
        val hadResultFocus = binding.mGridView.hasFocus() || binding.mGridViewFilter.hasFocus()
        searchAdapterFilter.setNewData(list)
        binding.mGridView.visibility = View.GONE
        binding.mGridViewFilter.visibility = View.VISIBLE
        filterMode = true
        if (hadResultFocus) {
            restoreResultFocus(binding.mGridViewFilter)
        }
    }

    /**
     * 列表数据刷新后恢复结果列表焦点。
     * 刷新期间禁止分词/源列表参与焦点搜索，避免焦点 item 重建丢失后系统默认焦点
     * 落到其它列表触发分类切换，造成卡片与分词来回闪动。
     */
    private fun restoreResultFocus(list: TvRecyclerView) {
        binding.mGridViewWordFenci.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.mGridViewWord.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        list.post {
            if (isFinishing || isDestroyed) return@post
            binding.mGridViewWordFenci.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            binding.mGridViewWord.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            list.requestFocus()
        }
    }

    /** 观察 ViewModel 状态 */
    private fun observeViewModel() {
        collectStarted(viewModel.fastResults) { results ->
            val oldCount = searchAdapter.itemCount
            rebuildResultVods(results)
            refreshSiteList()
            if (results.isEmpty()) {
                if (searchAdapter.itemCount > 0) searchAdapter.setNewData(emptyList())
            } else {
                // 搜索中增量：仅追加新到达的结果；排序完成的全量刷新由 resultVersion 驱动
                if (results.size > oldCount) {
                    searchAdapter.addData(results.subList(oldCount, results.size))
                }
                binding.mGridView.visibility = if (filterMode) View.GONE else View.VISIBLE
            }
        }
        // 排序完成信号：强制用最新结果全量刷新网格，确保排序后的顺序立即落屏
        collectStarted(viewModel.resultVersion) {
            val results = viewModel.fastResults.value
            rebuildResultVods(results)
            refreshSiteList()
            searchAdapter.setNewData(results)
        }
        collectStarted(viewModel.splitWords) { words ->
            splitAdapter.setNewData(words)
        }
        collectStarted(viewModel.searchState) { state ->
            when (state) {
                is SearchState.Empty -> {
                    Toast.makeText(this, getString(uiR.string.toast_no_result), Toast.LENGTH_SHORT).show()
                }
                is SearchState.Error -> {
                    Toast.makeText(this, state.msg, Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }
        collectStarted(viewModel.progress) { progress ->
            updateSearchStatus(progress)
        }
    }

    /** 重建按源分组的结果映射 */
    private fun rebuildResultVods(results: List<Movie.Video>) {
        resultVods.clear()
        for (video in results) {
            val key = video.sourceKey ?: continue
            resultVods.getOrPut(key) { ArrayList() }.add(video)
        }
        if (filterMode && searchFilterKey.isNotEmpty()) {
            val filtered = resultVods[searchFilterKey] ?: ArrayList()
            val oldCount = searchAdapterFilter.itemCount
            when {
                // 增量：仅追加新到达的结果，避免全量重建导致焦点/缩放动画反复
                filtered.size > oldCount ->
                    searchAdapterFilter.addData(filtered.subList(oldCount, filtered.size))
                // 收缩：结果减少（换词搜索），全量重建
                filtered.size < oldCount ->
                    searchAdapterFilter.setNewData(filtered)
                // 数量未变（排序顺序由 resultVersion 全量刷新驱动），跳过避免无意义重建
            }
        }
    }

    /** 刷新左侧源过滤列表，仅展示有结果的源 */
    private fun refreshSiteList() {
        val current = siteAdapter.getData()
        // 首次/清空：设置["全部"]
        if (current.isEmpty()) {
            siteAdapter.setNewData(listOf(getString(uiR.string.fs_show_all)))
            return
        }
        // 集合增量：搜索中结果只增不减，仅追加新源（不重建已有 item）。
        // 源结果并发返回顺序不固定，不能用前缀匹配判定增量，否则全量 setNewData 会
        // detach 当前焦点 item，焦点被系统恢复后表现为按钮抢焦点
        val currentSet = current.toHashSet()
        for ((key, name) in sourceNames) {
            if (resultVods.containsKey(key) && !currentSet.contains(name)) {
                siteAdapter.addData(name)
            }
        }
        // 源集合收缩（换词搜索新一轮）：全量重建（低频用户操作）
        val expectedSize = 1 + sourceNames.count { (key, _) -> resultVods.containsKey(key) }
        if (expectedSize < current.size) {
            val expected = ArrayList<String>().apply {
                add(getString(uiR.string.fs_show_all))
                for ((key, name) in sourceNames) {
                    if (resultVods.containsKey(key)) add(name)
                }
            }
            siteAdapter.setNewData(expected)
        }
    }

    /** 更新顶部状态栏 */
    private fun updateSearchStatus(progress: SearchProgress) {
        val total = progress.total
        val finished = progress.finished
        val resultCount = progress.resultCount
        val firstLine: String
        val secondLine: String
        if (progress.sorting) {
            // 排序中：提示用户，结果数展示当前已累计数量
            firstLine = getString(uiR.string.fast_search_sorting)
            secondLine = getString(uiR.string.fast_search_results, resultCount)
        } else if (total == 0) {
            firstLine = getString(uiR.string.fast_search_ready)
            secondLine = getString(uiR.string.fast_search_result_zero)
        } else if (finished < total) {
            firstLine = getString(uiR.string.fast_search_searching, finished, total)
            secondLine = getString(uiR.string.fast_search_results, resultCount)
        } else {
            firstLine = getString(uiR.string.fast_search_done, resultCount)
            secondLine = getString(uiR.string.fast_search_sources, total)
        }
        val text = "$firstLine\n$secondLine"
        val span = SpannableString(text)
        val split = firstLine.length
        span.setSpan(StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(RelativeSizeSpan(1.05f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(RelativeSizeSpan(0.78f), split + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(
            ForegroundColorSpan(0xCCFFFFFF.toInt()),
            split + 1,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.mSearchTitle.text = span
    }

    /** 跳转详情页 */
    private fun goToDetail(video: Movie.Video) {
        viewModel.cancelSearch()
        appNavigator.goDetail(this, video.id, video.sourceKey, Intent().putExtra("pic", video.pic))
    }

    /** 返回键直接退出 */
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                viewModel.cancelSearch()
                finish()
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 窗口焦点就绪时请求源列表首项为默认焦点，仅请求一次，不重试以免抢焦点
        if (hasFocus && !siteFirstFocusRequested) {
            requestWordListFirstFocus()
        }
    }

    /** 延迟请求源列表首项焦点（避开窗口焦点分配竞争） */
    private fun requestWordListFirstFocus() {
        if (siteFirstFocusRequested) return
        siteFirstFocusRequested = true
        binding.mGridViewWord.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (binding.mGridViewWord.getChildAt(0)?.requestFocus() != true) {
                siteFirstFocusRequested = false
                requestWordListFirstFocus()
            }
        }, SITE_FOCUS_REQUEST_DELAY_MS)
    }

    override fun onDestroy() {
        viewModel.cancelSearch()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_WORD = "word"
        private const val GRID_SPAN_COUNT = 4
        private const val FOCUS_ANIM_DURATION = 300L
        private const val FOCUS_SCALE = 1.1f

        /** 文字列表模式焦点缩放比例（缩略图用 FOCUS_SCALE）。
         *  文字列表 item 占满列表宽度，放大幅度过大（1.05）会超出可视区被外层裁剪两侧，
         *  故调小并配合 item 收窄（见 FastSearchAdapter）为放大动画预留空间。 */
        private const val FOCUS_LIST_SCALE = 1.03f

        /** 首帧聚焦请求前的延迟（避开窗口焦点分配竞争） */
        private const val SITE_FOCUS_REQUEST_DELAY_MS = 150L
    }
}

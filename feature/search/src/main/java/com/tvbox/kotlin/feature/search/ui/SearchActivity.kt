package com.tvbox.kotlin.feature.search.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.BounceInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.spider.SearchHelper
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.pauseImagesOnScroll
import com.tvbox.kotlin.core.ui.widget.SearchKeyboard
import com.tvbox.kotlin.feature.search.databinding.ActivitySearchBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 搜索页：输入关键字，多源并发搜索并展示结果。
 *
 * 交互：
 * - 搜索框输入关键字，支持系统软键盘与内置虚拟键盘双输入；
 * - 搜索历史从 [SearchPresenter] 加载，点击历史项重新搜索；
 * - 多源并发搜索，结果按源扁平展示，每条标注来源站点；
 * - 点击结果跳转详情页，长按结果触发快速播放；
 * - 返回键直接退出。
 */
@AndroidEntryPoint
class SearchActivity : BaseBindingActivity<ActivitySearchBinding>() {

    @Inject
    lateinit var appNavigator: AppNavigator

    @Inject
    lateinit var apiConfig: ApiConfig

    @Inject
    lateinit var searchHelper: SearchHelper

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var controlManager: ControlManager

    private val viewModel: SearchViewModel by viewModels()

    private lateinit var searchAdapter: SearchAdapter
    private lateinit var wordAdapter: SearchWordAdapter

    /** 站点 key -> 站点名称，用于结果展示来源标签 */
    private val sourceNames = HashMap<String, String>()

    /** 当前搜索关键字 */
    private var searchKey: String = ""

    /** 搜索状态标签的延迟隐藏任务，重复搜索时先取消旧的 */
    private var statusHideRunnable: Runnable? = null

    /** 已展示过的"搜索完成"批次标识（total|resultCount），页面重启动态 collect 重放完成态时不重复弹标签 */
    private var lastShownCompleteKey: String = ""

    private var keyword: String = ""

    /** 是否正在搜索，防止重复触发 */
    private var isSearching = false

    /** 从聚合搜索页返回时标记，恢复默认搜索界面 */
    private var returnFromFastSearch = false

    /** 搜索视图模式：0 文字列表，1 缩略图 */
    private var searchViewType = 0

    override val layoutId: Int = com.tvbox.kotlin.feature.search.R.layout.activity_search

    override fun initView(savedInstanceState: Bundle?) {
        // 禁用系统软键盘（TV 端用内置虚拟键盘输入），避免输入法窗口参与焦点竞争导致跳闪
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        initSourceNames()
        initSearchBox()
        initButtons()
        initKeyboard()
        initHotWordList()
        initResultList()
        initHistory()
        setupBackPress()
    }

    override fun initData() {
        viewModel.loadHistory()
        viewModel.loadHotWords()
        observeViewModel()
        // 从外部传入关键字时自动触发搜索（兼容 "word" 与 "title" 两种键，
        // 远端搜索推送历史使用 "title"，本应用导航使用 "word"）
        val externalWord = intent?.getStringExtra(EXTRA_WORD)?.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        externalWord?.let { word ->
            binding.etSearch.setText(word)
            search(word)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从聚合搜索页返回时恢复默认搜索界面
        if (returnFromFastSearch) {
            returnFromFastSearch = false
            isSearching = false
            keyword = ""
            binding.etSearch.setText("")
            viewModel.cancelSearch()
            viewModel.restoreHotWords()
            showHistoryArea(true)
            binding.mHotSearchText.text = getString(uiR.string.act_search_trend)
            // 聚搜页搜索的关键词由该页的 ViewModel 实例写入数据库，
            // 返回时重新加载历史列表，避免界面仍显示旧历史
            viewModel.loadHistory()
        }
    }

    /** 构建 key->name 映射，供适配器显示来源 */
    private fun initSourceNames() {
        sourceNames.clear()
        for (bean in apiConfig.getSourceBeanList()) {
            val key = bean.key ?: continue
            sourceNames[key] = bean.name ?: key
        }
    }

    /** 初始化搜索框输入监听 */
    private fun initSearchBox() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString()?.trim().orEmpty()
                if (keyword.isEmpty()) {
                    // 清空输入恢复热门词区，退出联想模式
                    viewModel.restoreHotWords()
                    showHistoryArea(true)
                } else {
                    // 输入关键字触发"猜你想搜"联想（覆盖系统软键盘与内置虚拟键盘两种输入）
                    viewModel.loadRec(keyword)
                }
            }
        })
        // 方向键在 ACTION_UP 处理移焦（与系统按键事件流一致），
        // 回车触发搜索并隐藏软键盘
        binding.etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        hideSystemKeyboard()
                        if (keyword.isNotEmpty()) search(keyword)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.etSearch.focusSearch(View.FOCUS_DOWN)?.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // 光标在末尾或文本为空时移出焦点（文本中间保留光标右移）
                        val len = binding.etSearch.text?.length ?: 0
                        if (len == 0 || binding.etSearch.selectionStart == len) {
                            binding.etSearch.focusSearch(View.FOCUS_RIGHT)?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        // 焦点移出编辑框时隐藏系统输入法，避免输入法弹出/收起引发窗口布局变化、焦点抖动
        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideSystemKeyboard()
        }
        binding.etSearch.setOnClickListener {
            showSystemKeyboard()
        }
    }

    /** 初始化搜索/清除按钮 */
    private fun initButtons() {
        binding.tvSearch.setOnClickListener {
            if (keyword.isNotEmpty()) {
                search(keyword)
            } else {
                Toast.makeText(this, uiR.string.search_input, Toast.LENGTH_SHORT).show()
            }
        }
        // 点击筛选图标弹出搜索源勾选弹窗
        binding.tvSearchCheckbox.setOnClickListener {
            val searchAbleSource = apiConfig.getSourceBeanList().filter { it.isSearchable() }
            SearchCheckboxDialog(
                context = this,
                sourceList = searchAbleSource,
                checkedSources = searchHelper.getSourcesForSearch(),
                apiConfig = apiConfig,
                searchHelper = searchHelper,
                appPreferences = appPreferences
            ).show()
        }
        binding.tvClear.setOnClickListener {
            binding.etSearch.setText("")
            keyword = ""
            viewModel.cancelSearch()
            viewModel.restoreHotWords()
            showHistoryArea(true)
        }
        binding.clearHistory.setOnClickListener {
            viewModel.clearHistory()
        }
    }

    /** 初始化内置虚拟键盘 */
    private fun initKeyboard() {
        binding.keyBoardRoot.setOnSearchKeyListener(object : SearchKeyboard.OnSearchKeyListener {
            override fun onSearchKey(pos: Int, key: String) {
                when (pos) {
                    0 -> {
                        // 远程搜索：拉起本地服务并展示二维码/地址
                        if (!controlManager.isServerRunning()) {
                            controlManager.startServer()
                        }
                        RemoteDialog(this@SearchActivity, controlManager).show()
                    }
                    1 -> {
                        // 退格
                        val text = binding.etSearch.text?.toString().orEmpty()
                        if (text.isNotEmpty()) {
                            binding.etSearch.setText(text.substring(0, text.length - 1))
                        }
                    }
                    else -> {
                        // 追加字符
                        val text = binding.etSearch.text?.toString().orEmpty() + key
                        binding.etSearch.setText(text)
                    }
                }
            }
        })
    }

    /** 初始化热门词列表 */
    private fun initHotWordList() {
        binding.mGridViewWord.setHasFixedSize(true)
        binding.mGridViewWord.layoutManager = V7LinearLayoutManager(this, 1, false)
        wordAdapter = SearchWordAdapter()
        wordAdapter.setOnItemClickListener { word, _ ->
            // 热门词可能携带 emoji 前缀，取最后一段作为关键字
            val realWord = word.split("\uFEFF").lastOrNull()?.trim().orEmpty()
            binding.etSearch.setText(realWord)
            search(realWord)
        }
        binding.mGridViewWord.adapter = wordAdapter
    }

    /** 初始化搜索结果列表 */
    private fun initResultList() {
        searchViewType = viewModel.getSearchViewType()
        searchAdapter = SearchAdapter()
        searchAdapter.setViewType(searchViewType)
        searchAdapter.setSourceNameProvider { key -> sourceNames[key].orEmpty() }
        searchAdapter.setOnItemClickListener { video, _ ->
            goToDetail(video)
        }
        searchAdapter.setOnItemLongClickListener { video, _ ->
            // 长按快速播放：直接跳转详情页进入播放流程
            goToDetail(video)
        }
        // 焦点缩放动画由 TvRecyclerView 的 OnItemListener 处理
        // （onItemPreSelected 缩回原尺寸 / onItemSelected 放大）
        binding.mGridView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                view?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(300)
                    ?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                val focusScale = if (searchViewType == 0) SEARCH_LIST_FOCUS_SCALE else SEARCH_POSTER_FOCUS_SCALE
                view?.animate()?.scaleX(focusScale)?.scaleY(focusScale)?.setDuration(300)
                    ?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemClick(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {}
        })
        binding.mGridView.setHasFixedSize(true)
        binding.mGridView.layoutManager = if (searchViewType == 0) {
            V7LinearLayoutManager(this, 1, false)
        } else {
            V7GridLayoutManager(this, GRID_SPAN_COUNT)
        }
        binding.mGridView.adapter = searchAdapter
        // 快速滚动时暂停图片加载，停止后恢复
        binding.mGridView.pauseImagesOnScroll()
    }

    /** 初始化搜索历史流式布局的焦点能力，点击监听在数据更新时通过 setViews 注入 */
    private fun initHistory() {
        // 历史项允许获取焦点，便于 TV 端方向键导航
        binding.tvHistory.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            for (i in 0 until binding.tvHistory.childCount) {
                binding.tvHistory.getChildAt(i)?.apply {
                    isFocusable = true
                    isFocusableInTouchMode = false
                }
            }
        }
    }

    /** 观察 ViewModel 状态 */
    private fun observeViewModel() {
        // 搜索进度：顶部状态标签展示"搜索中 x/y / 正在排序中 / 搜索完成 N"，完成后延迟隐藏
        collectStarted(viewModel.progress) { progress ->
            val tv = binding.tvSearchStatus
            statusHideRunnable?.let { tv.removeCallbacks(it) }
            statusHideRunnable = null
            val total = progress.total
            val finished = progress.finished
            if (total == 0) {
                tv.visibility = View.GONE
                lastShownCompleteKey = ""
                return@collectStarted
            }
            // 完成态去重：页面从后台恢复（如从聚搜返回）会重放残留的完成态，
            // 同一批结果已展示过就不再弹标签
            val completeKey = if (!progress.sorting && finished >= total) "$total|${progress.resultCount}" else ""
            if (completeKey.isNotEmpty() && completeKey == lastShownCompleteKey) {
                tv.visibility = View.GONE
                return@collectStarted
            }
            val text = if (progress.sorting) {
                getString(uiR.string.fast_search_sorting)
            } else if (finished < total) {
                getString(uiR.string.fast_search_searching, finished, total) + "  " +
                    getString(uiR.string.fast_search_results, progress.resultCount)
            } else {
                getString(uiR.string.fast_search_done, progress.resultCount)
            }
            tv.text = text
            tv.visibility = View.VISIBLE
            // 搜索完成（非排序态）后延迟隐藏标签，避免常驻遮挡结果区
            if (!progress.sorting && finished >= total) {
                lastShownCompleteKey = completeKey
                val r = Runnable { binding.tvSearchStatus.visibility = View.GONE }
                statusHideRunnable = r
                tv.postDelayed(r, SEARCH_STATUS_HIDE_DELAY_MS)
            }
        }
        collectStarted(viewModel.searchResults) { results ->
            // 搜索中按批次增量追加（首批 setNewData），避免每次批次到达都全量重建 item
            // （重建会丢焦点回退编辑框 = "搜索未结束时抢焦点"）；
            // 排序完成后的全量刷新由 resultVersion 事件驱动，此处不处理"size 不变的重排"
            val hadFocus = binding.mGridView.hasFocus()
            val oldCount = searchAdapter.itemCount
            when {
                // 注意：结果清空（含页面打开时 StateFlow 初始空值发射）不隐藏热搜/历史区，
                // 否则热搜区会在页面打开瞬间被永久关掉；隐藏热搜区由 search() 统一负责
                results.isEmpty() -> {
                    searchAdapter.setNewData(emptyList())
                }
                oldCount == 0 -> {
                    searchAdapter.setNewData(results)
                    showHistoryArea(false)
                }
                results.size > oldCount -> {
                    searchAdapter.addData(results.subList(oldCount, results.size))
                    showHistoryArea(false)
                }
            }
            // 数据重建过则恢复列表焦点（与热门词列表一致）
            if (hadFocus && binding.mGridView.childCount > 0) {
                binding.mGridView.requestFocus()
            }
        }
        // 排序完成信号：强制用最新结果全量刷新网格，确保排序后的顺序立即落屏
        collectStarted(viewModel.resultVersion) {
            val results = viewModel.searchResults.value
            val hadFocus = binding.mGridView.hasFocus()
            searchAdapter.setNewData(results)
            if (results.isNotEmpty()) {
                showHistoryArea(false)
            }
            if (hadFocus && binding.mGridView.childCount > 0) {
                binding.mGridView.requestFocus()
            }
        }
        collectStarted(viewModel.searchState) { state ->
            when (state) {
                is SearchState.Loading -> {
                    isSearching = true
                    binding.mGridView.visibility = View.GONE
                }
                is SearchState.Success -> {
                    isSearching = false
                    binding.mGridView.visibility = View.VISIBLE
                }
                is SearchState.Empty -> {
                    isSearching = false
                    Toast.makeText(this, getString(uiR.string.toast_no_result), Toast.LENGTH_SHORT).show()
                }
                is SearchState.Error -> {
                    isSearching = false
                    Toast.makeText(this, state.msg, Toast.LENGTH_SHORT).show()
                }
                is SearchState.Idle -> {
                    isSearching = false
                }
            }
        }
        collectStarted(viewModel.searchHistory) { history ->
            if (history.isEmpty()) {
                binding.tvHistory.setViews(emptyList()) { _ -> }
            } else {
                binding.tvHistory.setViews(history) { content ->
                    binding.etSearch.setText(content)
                    search(content)
                }
            }
        }
        collectStarted(viewModel.hotWords) { words ->
            // 联想/热搜列表数据异步刷新时保持列表焦点，避免 item 重建导致焦点回退到编辑框（来回跳闪）
            val hadFocus = binding.mGridViewWord.hasFocus()
            wordAdapter.setNewData(words)
            if (hadFocus && binding.mGridViewWord.childCount > 0) {
                binding.mGridViewWord.requestFocus()
            }
        }
        // 联想模式切换标题：猜你想搜 / 热门搜索
        collectStarted(viewModel.isRecMode) { recMode ->
            binding.mHotSearchText.text = if (recMode) getString(uiR.string.guess_you_want) else getString(uiR.string.act_search_trend)
        }
        // 搜索异常时给出提示并恢复历史/热门词区，避免界面空白
        collectStarted(viewModel.error) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            showHistoryArea(true)
        }
    }

    /** 执行搜索：聚合模式下转发到快速搜索页，否则本地搜索 */
    private fun search(title: String) {
        if (TextUtils.isEmpty(title)) {
            Toast.makeText(this, uiR.string.search_input, Toast.LENGTH_SHORT).show()
            return
        }
        if (viewModel.isFastSearchMode()) {
            viewModel.cancelSearch()
            returnFromFastSearch = true
            appNavigator.goFastSearch(this, title)
            return
        }
        showHistoryArea(false)
        viewModel.search(title, fast = false)
    }

    /** 切换历史区与结果区可见性 */
    private fun showHistoryArea(show: Boolean) {
        binding.searchTips.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvHistory.visibility = if (show) View.VISIBLE else View.GONE
        binding.llWord.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.mGridView.visibility = View.GONE
        }
    }

    /** 跳转详情页 */
    private fun goToDetail(video: Movie.Video) {
        viewModel.cancelSearch()
        appNavigator.goDetail(this, video.id, video.sourceKey, Intent().putExtra("pic", video.pic))
    }

    /** 显示系统软键盘 */
    private fun showSystemKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 隐藏系统软键盘 */
    private fun hideSystemKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(
            binding.etSearch.applicationWindowToken,
            InputMethodManager.RESULT_UNCHANGED_SHOWN
        )
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

    override fun onDestroy() {
        viewModel.cancelSearch()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_WORD = "word"
        private const val EXTRA_TITLE = "title"
        private const val GRID_SPAN_COUNT = 4
        /** 文字列表模式放大倍数：宽度撑满父容器，过大会被裁剪 */
        private const val SEARCH_LIST_FOCUS_SCALE = 1.05f
        /** 缩略图模式放大倍数 */
        private const val SEARCH_POSTER_FOCUS_SCALE = 1.1f

        /** 搜索状态标签完成后的停留时长（毫秒），随后自动隐藏 */
        private const val SEARCH_STATUS_HIDE_DELAY_MS = 2000L
    }
}

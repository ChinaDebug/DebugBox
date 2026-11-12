package com.tvbox.kotlin.feature.home.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kingja.loadsir.core.LoadService
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.common.bean.MovieSort
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.LoadSirHelper
import com.tvbox.kotlin.core.ui.callback.EmptyCallback
import com.tvbox.kotlin.core.ui.callback.LoadingCallback
import com.tvbox.kotlin.core.ui.enablePullToRefresh
import com.tvbox.kotlin.core.ui.pauseImagesOnScroll
import com.tvbox.kotlin.feature.home.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.jessyan.autosize.internal.CustomAdapt
import org.json.JSONObject
import java.util.Stack
import javax.inject.Inject

/**
 * 分类内容 Fragment，展示某个分类下的影片网格。
 * 支持懒加载、滚动分页、分类筛选、文件夹模式 UI 栈（点击 folder/cover 卡片进入子分类）、
 * action 卡片回调与长按快搜。
 */
@AndroidEntryPoint
class GridFragment : Fragment(), HomeTabFragment {

    @Inject
    lateinit var apiConfig: ApiConfig

    @Inject
    lateinit var appNavigator: AppNavigator

    @Inject
    lateinit var appPreferences: AppPreferences

    private var rootView: View? = null
    private var gridView: TvRecyclerView? = null
    private var gridAdapter = GridAdapter()

    private var sortData: MovieSort.SortData? = null

    /** 当前页码 */
    private var page = 1

    /** 总页数 */
    private var pageCount = 1

    /** 是否正在加载 */
    private var isLoading = false

    /** 是否正在预加载（独立于 isLoading，预加载不阻塞用户主动刷新/正式加载） */
    private var isPreloading = false

    /** 加载请求被 isLoading 阻塞时置位，协程结束后自动补发，避免快速切换分类丢失加载 */
    private var pendingLoad = false

    /** 是否已加载过首页数据 */
    private var hasLoaded = false

    /** 预加载完成但页面不可见时暂存的数据，切回当前页时一次性应用 */
    private var pendingPreloaded: List<Movie.Video>? = null

    /** 是否已加载到最后（不再触发加载更多） */
    private var loadMoreEnded = false

    /** 加载代际：强制重载（上键刷新）时 +1，使所有在途请求/暂存数据作废，
     *  避免 preload 旧数据、loadMore、旧刷新请求与本次重载结果互相覆盖 */
    private var loadGeneration = 0

    private var gridFilterDialog: GridFilterDialog? = null

    /** 加载/空态服务 */
    private var loadService: LoadService<*>? = null

    /** 覆盖式 loading 最小展示时长（ms）：至少完整转一圈且节奏不拖沓，与首页 loading 保持一致 */
    private val MIN_LOADING_MS = 2200L

    /** 本次 loading 展示起始时间戳，用于不足最小时长时延迟隐藏 */
    private var loadingShownAt = 0L

    /** 延迟隐藏 loading 的任务，新刷新开始/完成时取消，避免旧回调误隐藏新一轮 loading */
    private var hideLoadingRunnable: Runnable? = null

    /** 当前筛选弹窗对应的分类 ID，分类切换后需重新创建弹窗 */
    private var currentFilterSortId: String? = null

    /** 文件夹模式 UI 栈 */
    private val gridStack = Stack<GridInfo>()

    /** 解析过程中暂存的总页数 */
    private var parsedPageCount: Int = 1

    /** 延迟展示加载态的 Runnable，数据快速返回时避免 loading 闪烁/空白观感 */
    private var loadingRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sortData = arguments?.getSerializable(ARG_SORT) as? MovieSort.SortData
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        rootView = inflater.inflate(R.layout.fragment_grid, container, false)
        gridView = rootView?.findViewById(R.id.mGridView)
        return rootView!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        // 数据加载统一由 HomeActivity 显式触发（showFragment/rebuildFragments 调用 loadDataIfNotLoaded）
    }

    // ------------------------------------------------------------------
    // 布局与事件
    // ------------------------------------------------------------------

    private fun setupRecyclerView() {
        val rv = gridView ?: return
        // 用源站 style 初始化适配器：gridAdapter 默认构造不带 style，
        // 导致分类卡片始终为默认竖图（214×280）而列数却按 style 变化，两者不一致
        gridAdapter = createGridAdapter()
        // 网格尺寸固定，数据更新时跳过整树测量，降低回包触发的布局开销
        rv.setHasFixedSize(true)
        applyLayoutManager(rv)
        rv.adapter = gridAdapter
        loadService = LoadSirHelper.register(rv, listOf(LoadingCallback(), EmptyCallback()))
        rv.pauseImagesOnScroll()
        // 网格向上出界：焦点交还分类菜单，避免焦点丢失到顶栏 logo
        rv.setOnInBorderKeyEventListener(object : TvRecyclerView.OnInBorderKeyEventListener {
            override fun onInBorderKeyEvent(direction: Int, view: View?): Boolean {
                if (direction == View.FOCUS_UP) {
                    (activity as? HomeActivity)?.restoreCategoryFocus()
                    return true
                }
                return false
            }
        })
        // 焦点缩放动画，提供清晰焦点反馈
        rv.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(tvRecyclerView: TvRecyclerView, view: View?, position: Int) {
                view?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(300)
                    ?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemSelected(tvRecyclerView: TvRecyclerView, view: View?, position: Int) {
                view?.animate()?.scaleX(1.1f)?.scaleY(1.1f)?.setDuration(300)
                    ?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemClick(tvRecyclerView: TvRecyclerView, view: View?, position: Int) = Unit
        })
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isLoading || loadMoreEnded || dy <= 0) return
                val total = recyclerView.adapter?.itemCount ?: return
                val lastChild = recyclerView.getChildAt(recyclerView.childCount - 1) ?: return
                val lastVisible = recyclerView.getChildAdapterPosition(lastChild)
                if (lastVisible >= total - LOAD_MORE_THRESHOLD && page <= pageCount) {
                    page++
                    loadDataInternal(isRefresh = false)
                }
            }
        })
        gridAdapter.setOnItemClickListener { video, _ -> onItemClick(video) }
        gridAdapter.setOnItemLongClickListener { video, _ ->
            onItemLongClick(video)
            true
        }
        // 触摸设备下拉刷新：列表滚到顶部后继续下拉触发强制重载（与遥控器上键刷新同逻辑）
        rv.enablePullToRefresh { forceRefresh() }
    }

    /** 根据文件夹模式应用列表/网格布局，并按源站 style 调整列数 */
    private fun applyLayoutManager(rv: TvRecyclerView) {
        val context = requireContext()
        val style = VideoStyle.parseStyle(apiConfig.homeSourceBean.style)
        rv.layoutManager = if (isFolderMode() || style?.type == "list") {
            V7LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        } else {
            val spanCount = VideoStyle.spanCountByStyle(style, if (isBaseOnWidth()) 5 else 6)
            if (spanCount == 1) {
                V7LinearLayoutManager(context, spanCount, false)
            } else {
                V7GridLayoutManager(context, spanCount)
            }
        }
    }

    /** 是否以宽度为基准适配（屏幕比例 < 4.0 的盒子按宽度适配，5 列） */
    private fun isBaseOnWidth(): Boolean {
        return (activity as? CustomAdapt)?.isBaseOnWidth() ?: true
    }

    private fun showLoading() {
        // 覆盖式加载态：仅叠加显示转圈，不隐藏网格（隐藏网格会让卡片消失再显现，视觉闪屏）
        loadingShownAt = System.currentTimeMillis()
        hideLoadingRunnable?.let { rootView?.removeCallbacks(it) }
        hideLoadingRunnable = null
        rootView?.findViewById<View>(R.id.grid_loading)?.visibility = View.VISIBLE
    }

    /** 隐藏覆盖式 loading：距展示不足最小展示时长时延迟隐藏，保证刷新反馈可见 */
    private fun hideLoading() {
        val elapsed = System.currentTimeMillis() - loadingShownAt
        val delay = (MIN_LOADING_MS - elapsed).coerceAtLeast(0L)
        val r = Runnable {
            hideLoadingRunnable = null
            rootView?.findViewById<View>(R.id.grid_loading)?.visibility = View.GONE
        }
        hideLoadingRunnable = r
        rootView?.postDelayed(r, delay)
    }

    private fun showEmpty() {
        hideLoadingRunnable?.let { rootView?.removeCallbacks(it) }
        hideLoadingRunnable = null
        rootView?.findViewById<View>(R.id.grid_loading)?.visibility = View.GONE
        loadService?.showCallback(EmptyCallback::class.java)
    }

    private fun showSuccess() {
        hideLoading()
        loadService?.showSuccess()
    }

    // ------------------------------------------------------------------
    // 点击行为
    // ------------------------------------------------------------------

    private fun onItemClick(video: Movie.Video) {
        // 配置中心类卡片：action 字段非空时回调 jar 内 spider.action 并刷新列表
        val action = video.action
        if (action != null) {
            runAction(video.sourceKey.orEmpty(), action)
            return
        }
        if (video.tag == "folder" || video.tag == "cover") {
            changeView(video.id.orEmpty(), video.tag == "folder")
            return
        }
        val id = video.id
        if (id.isNullOrEmpty() || id.startsWith("msearch:")) {
            // id 为空或聚搜前缀时进入搜索
            if (appPreferences.getBoolean(AppConstants.FAST_SEARCH_MODE, false) && enableFastSearch()) {
                appNavigator.goFastSearch(requireContext(), video.name)
            } else {
                appNavigator.goSearch(requireContext(), video.name)
            }
        } else {
            appNavigator.goDetail(requireContext(), id, video.sourceKey ?: apiConfig.homeSourceBean.key, Intent().putExtra("pic", video.pic))
        }
    }

    private fun onItemLongClick(video: Movie.Video) {
        appNavigator.goFastSearch(requireContext(), video.name)
    }

    /** 调用 jar 内 action 并解析 msg 提示 */
    private fun runAction(sourceKey: String, action: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val json = try {
                apiConfig.action(sourceKey, action)
            } catch (t: Throwable) {
                ""
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val msg = try {
                    JSONObject(json).optString("msg")
                } catch (_: Throwable) {
                    ""
                }
                if (msg.isNotEmpty()) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    forceRefresh()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 文件夹模式 UI 栈
    // ------------------------------------------------------------------

    /** 创建网格适配器，携带源站 style（列表类型源强制单行布局） */
    private fun createGridAdapter(): GridAdapter {
        val style = VideoStyle.parseStyle(apiConfig.homeSourceBean.style)
        return GridAdapter(isFolderMode(), style)
    }

    /** 进入新分类（文件夹）时修改 flag 并重建列表 */
    private fun changeView(id: String, isFolder: Boolean) {
        saveCurrentView()
        val data = sortData ?: return
        data.flag = if (isFolder) "1" else "2"
        data.id = id
        // 进入新分类时清空旧筛选条件
        data.filterSelect.clear()
        currentFilterSortId = null
        rebuildList()
        loadDataInternal(isRefresh = true)
    }

    /** 保存当前页面状态到栈 */
    private fun saveCurrentView() {
        val info = GridInfo()
        info.sortId = sortData?.id ?: ""
        info.adapter = gridAdapter
        info.page = page
        info.pageCount = pageCount
        info.loaded = hasLoaded
        gridStack.push(info)
    }

    /** 重建 adapter 与布局（文件夹模式切换） */
    private fun rebuildList() {
        gridAdapter = createGridAdapter()
        page = 1
        pageCount = 1
        hasLoaded = false
        isLoading = false
        loadMoreEnded = false
        val rv = gridView ?: return
        applyLayoutManager(rv)
        rv.adapter = gridAdapter
        gridAdapter.setOnItemClickListener { video, _ -> onItemClick(video) }
        gridAdapter.setOnItemLongClickListener { video, _ ->
            onItemLongClick(video)
            true
        }
    }

    override fun restoreView(): Boolean {
        if (gridStack.isEmpty()) return false
        val rv = gridView ?: return false
        val info = gridStack.pop()
        sortData?.id = info.sortId
        sortData?.flag = "0"
        gridAdapter = info.adapter ?: createGridAdapter()
        page = info.page
        pageCount = info.pageCount
        hasLoaded = info.loaded
        loadMoreEnded = false
        applyLayoutManager(rv)
        rv.adapter = gridAdapter
        gridAdapter.setOnItemClickListener { video, _ -> onItemClick(video) }
        gridAdapter.setOnItemLongClickListener { video, _ ->
            onItemLongClick(video)
            true
        }
        showSuccess()
        rv.visibility = View.VISIBLE
        rv.requestFocus()
        return true
    }

    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    override fun loadDataIfNotLoaded() {
        // 未 attach 时 Hilt 注入与 viewLifecycleOwner 不可用，跳过（onViewCreated 会再次调用）
        if (!isAdded) return
        if (hasLoaded) {
            // 应用预加载暂存的数据，避免切回时空白等待
            pendingPreloaded?.let { data ->
                pendingPreloaded = null
                gridAdapter.setNewData(data)
            }
            if (gridView != null) showSuccess()
            return
        }
        page = 1
        if (isLoading) {
            // 加载中被阻塞：置位待加载，协程结束后自动补发
            pendingLoad = true
            return
        }
        loadDataInternal(isRefresh = true)
    }

    /**
     * 预加载相邻分类数据，提前准备供快速切换秒显。
     * 与 [loadDataIfNotLoaded] 的区别：spider 源不做 isResumed 放弃判断，且不展示 loading。
     */
    override fun preloadData() {
        val data = sortData ?: return
        if (hasLoaded || isPreloading || !isAdded || view == null) return
        isPreloading = true
        // 捕获代际：强制重载后旧 preload 结果作废
        val gen = loadGeneration
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isSpiderSource = apiConfig.homeSourceBean.type == 3
                val videos = if (isSpiderSource) {
                    LOAD_LOCK.withPermit { loadCategoryOnce(data) }
                } else {
                    loadCategoryOnce(data)
                }
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    // 代际不匹配（期间被强制重载）时作废
                    if (gen != loadGeneration) return@withContext
                    if (videos.isNotEmpty()) {
                        // 正式加载/刷新进行中时作废，避免旧数据覆盖新结果
                        if (isLoading) return@withContext
                        hasLoaded = true
                        if (view?.isShown == true) {
                            gridAdapter.setNewData(videos)
                            showSuccess()
                        } else {
                            // 页面不可见时暂存，切回时由 loadDataIfNotLoaded 应用
                            pendingPreloaded = videos
                        }
                    }
                }
            } finally {
                isPreloading = false
            }
        }
    }

    private fun loadDataInternal(isRefresh: Boolean) {
        val data = sortData ?: return
        if (isLoading) return
        // 视图已销毁时跳过，避免访问 viewLifecycleOwner 抛异常
        if (!isAdded || view == null) return
        isLoading = true
        if (isRefresh) showLoadingDelayed()
        // 捕获代际：请求期间被强制重载则结果作废
        val gen = loadGeneration
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // type 3 爬虫（QuickJS 非并发安全）串行排队执行，其余类型并行
                val isSpiderSource = apiConfig.homeSourceBean.type == 3
                val videos = if (isSpiderSource) {
                    LOAD_LOCK.withPermit {
                        // 排队期间用户已切走时放弃请求，避免过时分类长时间占锁
                        if (!isResumed) emptyList() else loadCategoryOnce(data)
                    }
                } else {
                    loadCategoryOnce(data)
                }
                withContext(Dispatchers.Main) {
                    cancelLoadingDelayed()
                    if (!isAdded) return@withContext
                    // 代际不匹配（被强制重载）时本次结果作废
                    if (gen != loadGeneration) return@withContext
                    if (videos.isNotEmpty()) {
                        if (isRefresh) {
                            // 刷新产生最新数据，作废 preload 暂存的旧数据
                            pendingPreloaded = null
                            gridAdapter.setNewData(videos)
                            hasLoaded = true
                        } else {
                            gridAdapter.addData(videos)
                        }
                        showSuccess()
                        // 刷新后滚动回顶部，避免首屏空白
                        if (isRefresh) gridView?.post { gridView?.scrollToPosition(0) }
                    } else {
                        if (isRefresh) {
                            showEmpty()
                        } else if (page > pageCount && pageCount != 0) {
                            Toast.makeText(requireContext(), "没有更多了", Toast.LENGTH_SHORT).show()
                        }
                    }
                    pageCount = parsedPageCount.coerceAtLeast(page)
                    loadMoreEnded = parsedPageCount != 0 && page >= parsedPageCount
                }
            } finally {
                // 无论协程完成/取消都必须复位 isLoading，避免标志卡死导致网格空白
                cancelLoadingDelayed()
                isLoading = false
                if (pendingLoad) {
                    pendingLoad = false
                    // 补发以刷新模式执行：pendingLoad 可能来自 forceRefresh，
                    // 该请求完成后 hasLoaded 已为 true，不能据此跳过补发
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (isAdded && view != null) loadDataInternal(isRefresh = true)
                    }
                }
            }
        }
    }

    /** 延迟展示加载态，数据快速返回时避免 loading 闪烁/空白观感 */
    private fun showLoadingDelayed() {
        loadingRunnable?.let { rootView?.removeCallbacks(it) }
        loadingRunnable = Runnable {
            loadingRunnable = null
            // 仍在加载时才显示：forceRefresh 已立即显示覆盖层，数据秒回后
            // 迟到的回调不得重新点亮 loading，否则会取消 hideLoading 的延迟隐藏
            if (isAdded && view != null && isLoading) showLoading()
        }
        rootView?.postDelayed(loadingRunnable, LOADING_DELAY_MS)
    }

    private fun cancelLoadingDelayed() {
        loadingRunnable?.let { rootView?.removeCallbacks(it) }
        loadingRunnable = null
    }

    /** 单次分类内容请求（含解析），在 IO 线程执行 */
    private fun loadCategoryOnce(data: MovieSort.SortData): List<Movie.Video> {
        val extend = HashMap(data.filterSelect)
        val json = try {
            apiConfig.categoryContent(data.id.orEmpty(), page.toString(), true, extend)
        } catch (t: Throwable) {
            ""
        }
        return parseCategoryContent(json)
    }

    /** 解析 categoryContent 返回的 JSON，提取影片列表 */
    private fun parseCategoryContent(json: String?): List<Movie.Video> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val obj = JSONObject(json)
            parsedPageCount = obj.optInt("pagecount", 1)
            if (!obj.has("list")) return emptyList()
            val listArr = obj.getJSONArray("list")
            val videos = ArrayList<Movie.Video>()
            for (i in 0 until listArr.length()) {
                videos.add(parseVideo(listArr.getJSONObject(i)))
            }
            videos
        } catch (_: Throwable) {
            parsedPageCount = 1
            emptyList()
        }
    }

    /** 解析单个影片 JSON 对象为 [Movie.Video] */
    private fun parseVideo(obj: JSONObject): Movie.Video {
        val video = Movie.Video()
        video.id = obj.optString("vod_id")
        video.name = obj.optString("vod_name")
        video.pic = obj.optString("vod_pic")
        video.lang = obj.optString("vod_lang")
        video.area = obj.optString("vod_area")
        video.year = obj.optInt("vod_year", 0)
        video.note = obj.optString("vod_remarks")
        video.actor = obj.optString("vod_actor")
        video.director = obj.optString("vod_director")
        video.type = obj.optString("type_name")
        video.last = obj.optString("vod_time")
        video.tag = obj.optString("vod_tag")
        // 配置中心类卡片 action：空串归一为 null，避免点击时误判进入 action 分支
        video.action = obj.optString("action").takeIf { it.isNotEmpty() }
        video.sourceKey = apiConfig.homeSourceBean.key
        return video
    }

    // ------------------------------------------------------------------
    // HomeTabFragment 接口
    // ------------------------------------------------------------------

    /** 分类 id（页面容器重建时用于匹配复用已有 Fragment，保留已加载数据） */
    fun getSortId(): String? = sortData?.id

    override fun isLoad(): Boolean = hasLoaded || gridStack.isNotEmpty()

    override fun scrollTop() {
        // post 延迟执行：确保视图完成布局后再滚动，避免数据/视图未就绪时
        // scrollToPosition(0) 无效导致内容停留在下方（切换后首屏空白）
        gridView?.post {
            val rv = gridView ?: return@post
            // 网格已在顶部时跳过 scrollToPosition，避免切换后触发不必要的滚动布局
            if (rv.canScrollVertically(-1)) rv.scrollToPosition(0)
        }
    }

    override fun isTop(): Boolean {
        val rv = gridView ?: return true
        return !rv.canScrollVertically(-1)
    }

    override fun resetFilterState() {
        sortData?.filterSelect?.clear()
        gridFilterDialog?.dismiss()
        gridFilterDialog = null
        currentFilterSortId = null
    }

    override fun showFilter() {
        val data = sortData ?: return
        if (data.filters.isEmpty()) return
        // 分类切换后释放旧弹窗并清空旧筛选条件
        if (gridFilterDialog != null && currentFilterSortId != data.id) {
            gridFilterDialog?.dismiss()
            gridFilterDialog = null
        }
        if (currentFilterSortId != data.id) {
            data.filterSelect?.clear()
            currentFilterSortId = data.id
        }
        if (gridFilterDialog == null) {
            gridFilterDialog = GridFilterDialog(requireContext())
            gridFilterDialog?.setData(data)
            gridFilterDialog?.onFilterChanged = { forceRefresh() }
        }
        gridFilterDialog?.show()
    }

    override fun forceRefresh() {
        // view 已销毁时忽略刷新请求，避免加载协程被取消后状态残留
        if (!isAdded || view == null) return
        // 强制重载：代际 +1 作废在途请求与暂存数据，彻底重置状态后重新拉取第一页
        loadGeneration++
        isLoading = false
        pendingLoad = false
        pendingPreloaded = null
        hasLoaded = false
        loadMoreEnded = false
        page = 1
        pageCount = 1
        parsedPageCount = 1
        gridStack.clear()
        // 立即展示 loading，提供明确的刷新反馈
        showLoading()
        loadDataInternal(isRefresh = true)
    }

    // ------------------------------------------------------------------
    // 模式判定
    // ------------------------------------------------------------------

    private fun isFolderMode(): Boolean = getUITag() == '1'

    /** 获取当前页面 UI 模式：'0' 正常 '1' 文件夹 '2' 带缩略图的文件夹 */
    private fun getUITag(): Char {
        val flag = sortData?.flag
        return if (flag.isNullOrEmpty()) '0' else flag[0]
    }

    /** 是否允许聚合搜索（flag 第二个字符为 1 时允许） */
    private fun enableFastSearch(): Boolean {
        val flag = sortData?.flag
        return flag.isNullOrEmpty() || flag.length < 2 || flag[1] == '1'
    }

    override fun onDestroyView() {
        gridFilterDialog?.dismiss()
        gridFilterDialog = null
        currentFilterSortId = null
        // 视图销毁仅复位进行中的加载标志（避免协程取消后标志卡死），
        // 保留已加载数据状态（hasLoaded/page/pageCount/loadMoreEnded/gridStack）：
        // FragmentPagerAdapter 跨页销毁本页视图后切回时直接复用已有数据秒显，
        // 避免每次切换分类都重新请求导致卡顿与空白网格。
        isLoading = false
        pendingLoad = false
        cancelLoadingDelayed()
        hideLoadingRunnable?.let { rootView?.removeCallbacks(it) }
        hideLoadingRunnable = null
        gridView?.adapter = null
        gridView = null
        rootView = null
        super.onDestroyView()
    }

    /** 文件夹 UI 栈节点 */
    private class GridInfo {
        var sortId = ""
        var adapter: GridAdapter? = null
        var page = 1
        var pageCount = 1
        var loaded = false
    }

    companion object {
        private const val ARG_SORT = "arg_sort"
        private const val LOAD_MORE_THRESHOLD = 5

        /** 延迟展示加载态的等待时长，数据快速返回时不显示 loading */
        private const val LOADING_DELAY_MS = 300L

        /** 分类内容加载串行锁：spider 非并发安全，所有分类请求排队执行 */
        private val LOAD_LOCK = Semaphore(1)

        fun newInstance(sortData: MovieSort.SortData): GridFragment {
            val fragment = GridFragment()
            val args = Bundle()
            args.putSerializable(ARG_SORT, sortData)
            fragment.arguments = args
            return fragment
        }
    }
}

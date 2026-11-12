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
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.common.event.RefreshEvent
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.data.VideoUpdateChecker
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.pauseImagesOnScroll
import com.tvbox.kotlin.core.ui.enablePullToRefresh
import com.tvbox.kotlin.feature.home.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 首页聚合页（my0），展示三种热播内容：0=豆瓣热播、1=站点推荐、2=历史记录。
 * 历史/直播/收藏/推送/网盘等功能入口已固定到首页顶部栏，由 HomeActivity 处理。
 */
@AndroidEntryPoint
class UserFragment : Fragment(), HomeTabFragment {

    @Inject
    lateinit var apiConfig: ApiConfig

    @Inject
    lateinit var appNavigator: AppNavigator

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var roomDataManger: RoomDataManger

    @Inject
    lateinit var videoUpdateChecker: VideoUpdateChecker

    @Inject
    lateinit var okHttpHelper: OkHttpHelper

    private val hotAdapter: HomeHotVodAdapter by lazy {
        // 初始不套源站 style：聚合页内容（豆瓣热播/历史/站点推荐）会按模式动态切换 style
        HomeHotVodAdapter()
    }

    private var gridView: TvRecyclerView? = null
    private var lineView: TvRecyclerView? = null

    /** 是否已加载过热播数据 */
    private var hasLoaded = false

    /** 历史模式删除状态 */
    private var deleteMode = false

    /** 站点推荐数据（来自 homeContent 的 videoList） */
    private var homeRecVideos: List<Movie.Video> = emptyList()

    /** 首页内容区是否加载中（数据未就绪时显示转圈，加载完成隐藏） */
    private var homeLoading = true

    /** loading 转圈最小展示时长（ms）：至少完整转一圈且节奏不拖沓，与分类页 loading 保持一致 */
    private val LOADING_SPIN_MS = 2200L

    /** 本次 loading 展示起始时间戳，用于不足最小时长时延迟隐藏 */
    private var loadingShownAt = 0L

    /** 延迟隐藏 loading 的任务，新一轮加载开始时取消，避免旧回调误隐藏新一轮转圈 */
    private var hideLoadingRunnable: Runnable? = null

    private val homeRec: Int
        get() = if (::appPreferences.isInitialized) appPreferences.getInt(AppConstants.HOME_REC, 0) else 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("UNCHECKED_CAST")
        homeRecVideos = arguments?.getSerializable(ARG_REC) as? List<Movie.Video> ?: emptyList()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_user, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupHotAdapters()
        applyRecStyle()
        observeEvents()
        // 初始加载中显示 loading，同时记录起始时间（保证初始转圈也完整转一圈）
        loadingShownAt = System.currentTimeMillis()
        updateLoadingView()
    }

    override fun onResume() {
        super.onResume()
        // 应用最新首页多行样式（单行/多行切换）
        applyRecStyle()
    }

    private fun bindViews(view: View) {
        gridView = view.findViewById(R.id.tvHotListForGrid)
        lineView = view.findViewById(R.id.tvHotListForLine)
    }

    /** 更新首页内容区加载loading显隐：加载中显示loading转圈，加载完成隐藏（网格由 applyRecStyle 控制显隐） */
    private fun updateLoadingView() {
        val view = view ?: return
        val loading = view.findViewById<View>(R.id.home_loading) ?: return
        loading.visibility = if (homeLoading) View.VISIBLE else View.GONE
    }

    /** 首页内容区加载状态（由 HomeActivity 在 ViewModel.loading 变化时同步） */
    fun setHomeLoading(loading: Boolean) {
        if (homeLoading == loading) return
        homeLoading = loading
        if (loading) {
            // 开始加载：记录起始时间，取消待执行的延迟隐藏
            loadingShownAt = System.currentTimeMillis()
            hideLoadingRunnable?.let { view?.removeCallbacks(it) }
            hideLoadingRunnable = null
            updateLoadingView()
        } else {
            // 加载完成：距展示不足完整一圈时延迟隐藏，保证转圈反馈完整可见
            val elapsed = System.currentTimeMillis() - loadingShownAt
            val delay = (LOADING_SPIN_MS - elapsed).coerceAtLeast(0L)
            val r = Runnable {
                hideLoadingRunnable = null
                homeLoading = false
                updateLoadingView()
            }
            hideLoadingRunnable = r
            view?.postDelayed(r, delay)
        }
    }

    /** 初始化热播网格/单行列表 */
    private fun setupHotAdapters() {
        val grid = gridView ?: return
        // 网格尺寸固定，数据更新时跳过整树测量，减少快速切换分类时的布局开销
        grid.setHasFixedSize(true)
        lineView?.setHasFixedSize(true)
        grid.layoutManager = V7GridLayoutManager(requireContext(), SPAN_COUNT)
        grid.adapter = hotAdapter
        lineView?.layoutManager = V7LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        lineView?.adapter = hotAdapter
        // 快速滚动时暂停图片加载，停止后恢复
        grid.pauseImagesOnScroll()
        lineView?.pauseImagesOnScroll()
        hotAdapter.hasUpdate = { video ->
            videoUpdateChecker.hasVideoUpdate(video.sourceKey, video.id)
        }
        hotAdapter.onItemClickListener = { vod, position ->
            onHotVodClick(vod, position)
        }
        hotAdapter.onItemLongClickListener = { vod, _ ->
            onHotVodLongClick(vod)
        }
        // 内容网格向上出界：焦点交还分类菜单，避免焦点丢失到顶栏 logo
        grid.setOnInBorderKeyEventListener(object : TvRecyclerView.OnInBorderKeyEventListener {
            override fun onInBorderKeyEvent(direction: Int, view: View?): Boolean {
                if (direction == View.FOCUS_UP) {
                    (activity as? HomeActivity)?.restoreCategoryFocus()
                    return true
                }
                return false
            }
        })
        lineView?.setOnInBorderKeyEventListener(object : TvRecyclerView.OnInBorderKeyEventListener {
            override fun onInBorderKeyEvent(direction: Int, view: View?): Boolean {
                if (direction == View.FOCUS_UP) {
                    (activity as? HomeActivity)?.restoreCategoryFocus()
                    return true
                }
                return false
            }
        })
        // 焦点获取/失去时对卡片做缩放动画（BounceInterpolator），滚动与停止时均播放
        grid.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                view?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(300)
                    ?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                if (position == -1) return
                view?.animate()?.scaleX(1.1f)?.scaleY(1.1f)?.setDuration(300)
                    ?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemClick(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) = Unit
        })
        // 单行模式同样提供焦点缩放动画
        lineView?.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                view?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(300)
                    ?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                view?.animate()?.scaleX(1.1f)?.scaleY(1.1f)?.setDuration(300)
                    ?.setInterpolator(BounceInterpolator())?.start()
            }

            override fun onItemClick(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) = Unit
        })
        // 触摸设备下拉刷新：网格/单行模式在顶部继续下拉即刷新首页聚合数据
        grid.enablePullToRefresh { forceRefresh() }
        lineView?.enablePullToRefresh { forceRefresh() }
    }

    /** 观察全局事件：历史变化/设置变化时刷新 */
    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            EventHub.events.collect { event ->
                if (event is RefreshEvent) {
                    when (event.type) {
                        RefreshEvent.TYPE_HISTORY -> {
                            view?.post {
                                if (homeRec == 2) refreshHotVod()
                            }
                        }
                        RefreshEvent.TYPE_SETTING -> {
                            view?.post { applyRecStyle() }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 热播数据加载
    // ------------------------------------------------------------------

    override fun loadDataIfNotLoaded() {
        if (hasLoaded) return
        // 未 attach 时 Fragment 尚未完成 Hilt 注入（appPreferences 等 lateinit 未初始化），
        // 直接跳过；Fragment 真正创建后 onViewCreated 会再次调用本方法加载。
        if (!isAdded) return
        hasLoaded = true
        refreshHotVod()
    }

    /** 首页聚合页数据由 ViewModel 统一管理，无需预加载 */
    override fun preloadData() = Unit

    private fun refreshHotVod(forceDouban: Boolean = false) {
        when (homeRec) {
            1 -> {
                // 站点推荐：有数据时显示站点推荐；无数据（源未提供 videoList）时降级豆瓣热播，
                // 避免移除功能按钮后首页内容区空白。数据稍后到达时由 setHomeRecInternal 切回站点推荐。
                hotAdapter.historyMode = false
                if (homeRecVideos.isNotEmpty()) {
                    // 站点推荐按源站 style 渲染卡片（横图源用 3 列网格，见 applyRecStyle）
                    hotAdapter.style = VideoStyle.parseStyle(apiConfig.homeSourceBean.style)
                    hotAdapter.title = REC_TITLE_SITE
                    hotAdapter.setNewData(homeRecVideos)
                } else {
                    // 降级豆瓣热播为默认竖图卡片（214×280），5 列网格，不套源站横图 style 避免卡片溢出
                    hotAdapter.style = null
                    hotAdapter.title = REC_TITLE_DOUBAN
                    loadDoubanHot(forceDouban)
                }
            }
            2 -> {
                hotAdapter.style = null
                hotAdapter.title = REC_TITLE_HISTORY
                hotAdapter.historyMode = true
                hotAdapter.deleteMode = deleteMode
                loadHistoryHotVod()
            }
            else -> {
                hotAdapter.style = null
                hotAdapter.title = REC_TITLE_DOUBAN
                hotAdapter.historyMode = false
                loadDoubanHot(forceDouban)
            }
        }
        applyRecStyle()
    }

    /** 历史记录模式：读取最近记录并按更新状态排序 */
    private fun loadHistoryHotVod() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val records = roomDataManger.getAllVodRecord(HISTORY_LIMIT)
            val list = records.mapNotNull { record ->
                if (record.id.isNullOrEmpty() || record.sourceKey.isNullOrEmpty()) return@mapNotNull null
                Movie.Video().apply {
                    id = record.id
                    sourceKey = record.sourceKey
                    name = record.name ?: ""
                    pic = record.pic
                    if (record.playNote.isNotEmpty()) note = record.playNote
                }
            }.sortedByDescending { video ->
                if (videoUpdateChecker.hasVideoUpdate(video.sourceKey, video.id)) 1 else 0
            }
            withContext(Dispatchers.Main) {
                if (isAdded) hotAdapter.setNewData(list)
                // 刷新/加载完成，隐藏 loading 转圈
                setHomeLoading(false)
            }
        }
    }

    /** 豆瓣热播模式：联网抓取豆瓣，按天缓存；forceRefresh 时强制重新请求（刷新入口用） */
    private fun loadDoubanHot(forceRefresh: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyyMd", Locale.getDefault()).format(Date())
            var json = ""
            if (!forceRefresh && appPreferences.getString("home_hot_day", "") == today) {
                json = appPreferences.getString("home_hot", "")
            }
            if (json.isEmpty()) {
                try {
                    val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
                    val url = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=$year,$year"
                    json = okHttpHelper.get(url, mapOf("User-Agent" to okHttpHelper.nextUserAgent()))
                    appPreferences.put("home_hot_day", today)
                    appPreferences.put("home_hot", json)
                } catch (_: Throwable) {
                    // 强制刷新请求失败时回退当天缓存，避免首页内容区空白
                    if (forceRefresh && json.isEmpty()) {
                        json = appPreferences.getString("home_hot", "")
                    }
                }
            }
            val list = parseDoubanHots(json)
            withContext(Dispatchers.Main) {
                if (isAdded) hotAdapter.setNewData(list)
                // 刷新/加载完成，隐藏 loading 转圈
                setHomeLoading(false)
            }
        }
    }

    private fun parseDoubanHots(json: String): List<Movie.Video> {
        if (json.isBlank()) return emptyList()
        return try {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("data")
            val list = ArrayList<Movie.Video>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val video = Movie.Video()
                video.name = item.optString("title")
                video.note = "评分：" + item.optString("rate")
                video.pic = item.optString("cover") +
                    "@User-Agent=${okHttpHelper.nextUserAgent()}@Referer=https://www.douban.com/"
                list.add(video)
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // 点击行为
    // ------------------------------------------------------------------

    private fun onHotVodClick(vod: Movie.Video, position: Int) {
        if (!isAdded) return
        if (apiConfig.getSourceBeanList().isEmpty()) return
        // 站点推荐模式下 action 卡片回调 jar 内 spider.action
        val action = vod.action
        if (homeRec == 1 && action != null) {
            // sourceKey 缺失时兜底为首页源（配置中心一般即当前首页源）
            runAction(vod.sourceKey ?: apiConfig.homeSourceBean.key, action)
            return
        }
        val id = vod.id
        if (!id.isNullOrEmpty()) {
            if (homeRec == 2 && deleteMode) {
                deleteHistory(vod, position)
            } else if (id.startsWith("msearch:")) {
                appNavigator.goFastSearch(requireContext(), vod.name)
            } else {
                // sourceKey 缺失时兜底为当前首页源，避免详情页因 sourceKey=null 显示"暂无播放数据"
                appNavigator.goDetail(requireContext(), id, vod.sourceKey ?: apiConfig.homeSourceBean.key, Intent().putExtra("pic", vod.pic))
            }
        } else {
            // id 为空时进入搜索
            if (appPreferences.getBoolean(AppConstants.FAST_SEARCH_MODE, false)) {
                appNavigator.goFastSearch(requireContext(), vod.name)
            } else {
                appNavigator.goSearch(requireContext(), vod.name)
            }
        }
    }

    /** 站点推荐 action 卡片回调：执行 jar 内 spider.action，Toast 提示返回的 msg */
    private fun runAction(sourceKey: String?, action: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val json = try {
                apiConfig.action(sourceKey.orEmpty(), action)
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
                }
            }
        }
    }

    private fun onHotVodLongClick(vod: Movie.Video): Boolean {
        if (!isAdded) return true
        if (apiConfig.getSourceBeanList().isEmpty()) return true
        // 历史模式长按切换删除模式，其余模式长按快速搜索
        if (homeRec == 2 && !vod.id.isNullOrEmpty()) {
            deleteMode = !deleteMode
            hotAdapter.deleteMode = deleteMode
        } else {
            appNavigator.goFastSearch(requireContext(), vod.name)
        }
        return true
    }

    private fun deleteHistory(vod: Movie.Video, position: Int) {
        val sourceKey = vod.sourceKey ?: return
        val vodId = vod.id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val info = roomDataManger.getVodInfo(sourceKey, vodId) ?: return@launch
            videoUpdateChecker.clearVideoUpdate(sourceKey, vodId)
            roomDataManger.deleteVodRecord(sourceKey, info)
            if (isAdded) {
                hotAdapter.removeAt(position)
                Toast.makeText(requireContext(), getString(uiR.string.hm_hist_del), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // HomeTabFragment 接口
    // ------------------------------------------------------------------

    override fun scrollTop() {
        if (!isAdded) return
        val rv = if (homeRecStyleIsGrid()) gridView else lineView
        rv?.scrollToPosition(0)
    }

    override fun isTop(): Boolean {
        if (!isAdded) return true
        val rv = if (homeRecStyleIsGrid()) gridView else lineView
        if (rv == null) return true
        // 无内容（热播加载失败/历史为空）或未绑定 adapter 时视为已在顶部：
        // findFirstCompletelyVisibleItemPosition 在空列表返回 -1，若此处返回 false，
        // 返回键会被 HomeActivity 的回顶分支无限吞掉（无提示、无法退出）
        if (rv.adapter == null || rv.adapter?.itemCount == 0) return true
        return !rv.canScrollVertically(-1)
    }

    override fun isLoad(): Boolean = hasLoaded

    override fun resetFilterState() = Unit

    override fun showFilter() = Unit

    override fun forceRefresh() {
        if (!isAdded) return
        hasLoaded = false
        // 刷新时显示 loading，数据到达后由 setHomeRecInternal / 各加载分支隐藏
        setHomeLoading(true)
        if (homeRec == 1) {
            // 站点推荐：真实重新拉取当前源 homeContent（分类与推荐数据一起刷新），
            // loading 由 ViewModel.loading 同步控制，数据到达后自动隐藏；
            // 源未就绪未启动刷新时立即隐藏，避免转圈卡住
            if ((activity as? HomeActivity)?.refreshHomeTab() != true) {
                setHomeLoading(false)
            }
        } else {
            // 豆瓣热播强制重新请求（绕过当天缓存），历史记录重读本地，均有 loading 反馈
            refreshHotVod(forceDouban = true)
        }
    }

    override fun restoreView(): Boolean = false

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun homeRecStyleIsGrid(): Boolean =
        ::appPreferences.isInitialized && appPreferences.getBoolean(AppConstants.HOME_REC_STYLE, false)

    /** 按 HOME_REC_STYLE 切换网格/单行显示；站点推荐模式再按源站 style 调整网格列数 */
    private fun applyRecStyle() {
        val gridStyle = homeRecStyleIsGrid()
        gridView?.visibility = if (gridStyle) View.VISIBLE else View.GONE
        lineView?.visibility = if (gridStyle) View.GONE else View.VISIBLE
        // 站点推荐按源站 style 调整列数（横图 3 列、4:3 用 4 列），与分类页 GridFragment 保持一致；
        // 豆瓣热播/历史记录固定默认列数
        val targetSpan = if (homeRec == 1 && homeRecVideos.isNotEmpty()) {
            VideoStyle.spanCountByStyle(VideoStyle.parseStyle(apiConfig.homeSourceBean.style), SPAN_COUNT)
        } else {
            SPAN_COUNT
        }
        val lm = gridView?.layoutManager
        if (lm is V7GridLayoutManager && lm.spanCount != targetSpan) {
            gridView?.layoutManager = V7GridLayoutManager(requireContext(), targetSpan)
        }
    }

    override fun onDestroyView() {
        hideLoadingRunnable?.let { view?.removeCallbacks(it) }
        hideLoadingRunnable = null
        gridView?.adapter = null
        lineView?.adapter = null
        gridView = null
        lineView = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_REC = "arg_rec"
        private const val SPAN_COUNT = 5
        private const val HISTORY_LIMIT = 20
        private const val REC_TITLE_DOUBAN = "豆瓣热播"
        private const val REC_TITLE_SITE = "站点推荐"
        private const val REC_TITLE_HISTORY = "历史记录"

        fun newInstance(recVideos: List<Movie.Video>? = null): UserFragment {
            val fragment = UserFragment()
            if (!recVideos.isNullOrEmpty()) {
                val args = Bundle()
                args.putSerializable(ARG_REC, ArrayList(recVideos))
                fragment.arguments = args
            }
            return fragment
        }

        /** 由 HomeActivity 在 homeVideos 更新时调用 */
        fun setHomeRecInternal(fragment: UserFragment, videos: List<Movie.Video>) {
            fragment.homeRecVideos = videos
            // 数据到达视为内容区加载完成，隐藏转圈（未 attach 时 view 为空，updateLoadingView 自动跳过；
            // Fragment 创建后 onViewCreated 会按最终字段值渲染），避免 loading 转圈一直不消失
            fragment.setHomeLoading(false)
            // 未 attach 的 Fragment 尚未完成 Hilt 注入（appPreferences 未初始化），
            // adapter 更新延迟到其创建后由 loadDataIfNotLoaded/onResume 应用
            if (!fragment.isAdded) return
            if (fragment.homeRec == 1) {
                if (videos.isNotEmpty()) {
                    // 站点推荐有数据时切回站点推荐（覆盖此前无数据时的豆瓣热播降级）：
                    // 恢复源站 style 渲染卡片并同步网格列数（横图源 3 列）
                    fragment.hotAdapter.style = VideoStyle.parseStyle(fragment.apiConfig.homeSourceBean.style)
                    fragment.hotAdapter.title = REC_TITLE_SITE
                    fragment.hotAdapter.setNewData(videos)
                    fragment.applyRecStyle()
                }
                // 无数据时保持当前降级显示（豆瓣热播），不覆盖为空白
            }
        }

        /** 由 HomeActivity 在 ViewModel.loading 变化时同步首页聚合页加载状态 */
        fun setHomeLoadingInternal(fragment: UserFragment, loading: Boolean) {
            // 未 attach 时也更新字段状态：view 为空时 updateLoadingView 自动跳过，
            // Fragment 创建后 onViewCreated 会按最新状态渲染，避免 loading 转圈卡住不消失
            fragment.setHomeLoading(loading)
        }

        /** 首页顶部样式按钮切换网格/单行后同步聚合页 */
        fun applyStyleTo(fragment: UserFragment) {
            if (fragment.isAdded) fragment.applyRecStyle()
        }
    }
}

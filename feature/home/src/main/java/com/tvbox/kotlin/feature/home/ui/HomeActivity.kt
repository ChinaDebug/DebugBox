package com.tvbox.kotlin.feature.home.ui

import android.content.Intent
import androidx.lifecycle.Lifecycle
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import com.github.catvod.crawler.pyLoader
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.bean.MovieSort
import com.tvbox.kotlin.core.common.event.RefreshEvent
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.common.util.StoragePermissionUtils
import com.tvbox.kotlin.core.data.VideoUpdateChecker
import com.tvbox.kotlin.core.data.VideoUpdateListener
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.ImageLoaderFactory
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.SelectDialog
import com.tvbox.kotlin.core.ui.dialog.TipDialog
import com.tvbox.kotlin.feature.home.R
import com.tvbox.kotlin.feature.home.databinding.ActivityHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 首页 Activity：顶部栏 + 横向分类菜单 + 内容 ViewPager。
 * 数据由 [HomeViewModel] 加载
 */
@AndroidEntryPoint
class HomeActivity : BaseBindingActivity<ActivityHomeBinding>() {

    @Inject
    lateinit var appNavigator: AppNavigator

    @Inject
    lateinit var apiConfig: ApiConfig

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var videoUpdateChecker: VideoUpdateChecker

    @Inject
    lateinit var controlManager: com.tvbox.kotlin.core.server.ControlManager

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var sortAdapter: SortAdapter
    private val fragments = mutableListOf<Fragment>()

    /** 按位置获取实现了统一契约的 Tab 页（GridFragment / UserFragment） */
    private fun tabAt(position: Int): HomeTabFragment? = fragments.getOrNull(position) as? HomeTabFragment

    /** 当前 ViewPager 选中的分类位置 */
    private var currentSelected = 0

    /** 恢复的分类位置（Activity 重建时使用） */
    private var savedCategoryPosition = 0

    /** 当前分类菜单焦点位置 */
    private var sortFocused = 0

    /** 标记分类焦点变化是否需要触发 ViewPager 切换 */
    private var sortChange = false

    /** 当前获得焦点的分类项 View */
    private var sortFocusView: View? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 退出二次确认的时间戳 */
    private var exitTime = 0L

    /** 内容区回顶防吞键标记：上次返回已尝试回顶但内容仍非顶部时，不再吞掉返回键直接进入退出流程 */
    private var backScrollTopPending = false

    /** 顶部栏 logo 上次单击时间（防抖） */
    private var lastLogoClickTime = 0L

    /** 是否正在清理缓存 */
    private var isClearingCache = false

    /** 启动更新检测标记，首页数据加载完成后仅触发一次 */
    private var startupCheckTriggered = false

    /** 启动即进直播标记，首页分类加载完成后仅触发一次 */
    private var defaultShowTriggered = false

    /** 分类菜单滚动中标志：快速移动焦点时跳过缩放动画，避免动画叠加掉帧 */
    private var categoryScrolling = false

    /** 快速滚动中标记待切换的分类：滚动停止后统一执行 ViewPager 切换，避免滚动中频繁切换卡顿 */
    private var categorySwitchPending = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var focusChangeListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    /** 时间刷新任务，每秒更新一次 */
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, TIME_REFRESH_INTERVAL)
        }
    }

    /** 更新检测监听：检测完成后刷新顶部栏历史按钮角标并停止转圈 */
    private val updateListener = object : VideoUpdateListener {
        override fun onCheckComplete(hasUpdate: Boolean, updates: Map<String, Boolean>) {
            runOnUiThread {
                stopHistoryCheckingAnimation()
                updateHistoryBadge(hasUpdate)
            }
        }

        override fun onCheckProgress(current: Int, total: Int) {
            // 检测进行中时启动历史按钮转圈提示
            runOnUiThread {
                if (videoUpdateChecker.isCheckingNow()) {
                    startHistoryCheckingAnimation()
                }
            }
        }

        override fun onCheckError(message: String) {
            runOnUiThread { stopHistoryCheckingAnimation() }
        }
    }

    /** 顶部栏历史按钮更新角标：有更新时显示 */
    private fun updateHistoryBadge(hasUpdate: Boolean) {
        binding.tvHistoryBadge?.visibility = if (hasUpdate) View.VISIBLE else View.GONE
    }

    /** 更新检测进行中：历史按钮图标转圈提示 */
    private fun startHistoryCheckingAnimation() {
        val icon = binding.hmHistoryIcon ?: return
        if (icon.animation == null) {
            icon.startAnimation(AnimationUtils.loadAnimation(this, uiR.anim.rotate_refresh_reverse))
        }
    }

    private fun stopHistoryCheckingAnimation() {
        binding.hmHistoryIcon?.clearAnimation()
    }

    /** 分类焦点变化后延迟切换 ViewPager，避免快速滑动时频繁重建 */
    private val dataRunnable = Runnable {
        if (categoryScrolling) {
            // 快速滚动中不执行切换，滚动停止后由 onScrollStateChanged 统一补发
            categorySwitchPending = true
            return@Runnable
        }
        if (sortChange) {
            sortChange = false
            if (sortFocused != currentSelected && sortFocused in fragments.indices) {
                // 切换前重置旧分类的筛选状态
                backScrollTopPending = false
                tabAt(currentSelected)?.resetFilterState()
                // 切换页面：Fragment 视图常驻，仅 hide 当前页 + show 目标页（无测量/布局开销）
                showFragment(sortFocused)
                sortAdapter.setActivatedPosition(currentSelected)
                val tab = tabAt(sortFocused)
                // scrollTop 延后执行，避免与页面切换同帧抢占主线程
                handler.post {
                    tab?.scrollTop()
                }
                // 预加载相邻分类延后执行，避免切换瞬间启动多个网络请求回包挤占主线程
                binding.mPageContainer.post {
                    preloadAdjacent(sortFocused)
                }
            }
        }
    }

    /**
     * 切换到指定分类页面。所有分类 Fragment 视图在 rebuildFragments 时一次性 add 常驻，
     * 切换仅通过 FragmentTransaction hide/show 改变可见性，不触发页面测量/布局，
     * 快速连续切换时主线程无布局积压，解决 ViewPager 切换卡顿。
     */
    private fun showFragment(position: Int) {
        if (position == currentSelected) return
        val fm = supportFragmentManager
        if (fm.isDestroyed) return
        val current = fragments.getOrNull(currentSelected)
        val target = fragments.getOrNull(position) ?: return
        val ft = fm.beginTransaction()
        current?.takeIf { it.isAdded }?.let { ft.hide(it) }
        if (target.isAdded) {
            ft.show(target)
        } else {
            ft.add(R.id.mPageContainer, target, "frag_$position")
        }
        ft.commitNowAllowingStateLoss()
        currentSelected = position
        // 页面容器移除 ViewPager 后，Fragment onResume 不再随切换触发，这里显式加载目标页数据
        (target as? HomeTabFragment)?.loadDataIfNotLoaded()
    }

    override val layoutId: Int = R.layout.activity_home

    override fun onCreate(savedInstanceState: Bundle?) {
        // 保留 Fragment 状态：Activity 重建（从详情/播放页返回被系统回收）时
        // FragmentManager 自动恢复各分类 Fragment 实例（含已加载数据），
        // rebuildFragments 按分类 id 匹配复用，避免返回后重新加载
        super.onCreate(savedInstanceState)
        // 恢复上次停留的分类位置，避免 Activity 重建（如从详情/播放页返回时被系统回收）后跳到首页
        savedCategoryPosition = savedInstanceState?.getInt(KEY_CURRENT_SELECTED, 0) ?: 0
        currentSelected = savedCategoryPosition
        sortFocused = savedCategoryPosition
    }

    override fun initView(savedInstanceState: Bundle?) {
        setupCategoryList()
        setupTopBar()
        setupBackPress()
        setupFocusListener()
        // 启动即抢分类菜单焦点：在系统初始焦点分配（首次布局）前主动 requestFocus，
        // 避免初始焦点落在站点 logo（tvName）上再移走造成闪烁/抢焦点观感
        binding.mGridViewCategory.requestFocus()
    }

    override fun initData() {
        observeViewModel()
        observeEvents()
        updateTime()
        // 冷启动禁用站点 logo 焦点：在系统初始焦点分配（首次布局）前使 tvName 不可聚焦，
        // 系统初始焦点落不到 logo 上，避免 logo 高亮后又被分类菜单抢走造成的明暗闪烁。
        // 首次布局完成后恢复可聚焦（不主动 requestFocus，不会抢走分类菜单焦点）
        binding.tvName.isFocusable = false
        binding.tvName.isFocusableInTouchMode = false
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                binding.tvName.isFocusable = true
                binding.tvName.isFocusableInTouchMode = false
            }
        })
        handler.postDelayed(timeRunnable, TIME_REFRESH_INTERVAL)
        registerNetworkCallback()
        updateWifiIcon()
        // 顶部栏历史按钮更新角标：注册检测监听并刷新初始状态
        videoUpdateChecker.addListener(updateListener)
        updateHistoryBadge(videoUpdateChecker.hasUpdate())
        if (videoUpdateChecker.isCheckingNow()) {
            startHistoryCheckingAnimation()
        }
        val useCache = intent?.getBooleanExtra(EXTRA_USE_CACHE, false) ?: false
        viewModel.loadHome(useCache)
        // 启动即聚焦分类菜单"首页"按钮：post 延迟等布局完成后抢占焦点，
        // 避免焦点先落在顶部站点名/站点推荐网格卡片上闪烁（固定首页按钮焦点）
        binding.mGridViewCategory.post { binding.mGridViewCategory.requestFocus() }
        // 非缓存启动时触发定时调度；启动检测移至首页数据加载完成后执行
        // （配置/jar 就绪时源列表才可用，否则检测抓取详情会因找不到源而失败）
        if (!useCache) {
            videoUpdateChecker.startScheduledCheck()
        }
        // 主界面常驻本地服务（承载推送/投屏/代理/直播源抓取），
        // 避免进入推送页时服务未启动导致"服务地址获取失败"。
        // 引用计数由 ControlManager 维护：播放页 PlayService 会 +1/-1，退出播放页后服务仍在。
        controlManager.startServer()
    }

    /** 初始化横向分类列表 */
    private fun setupCategoryList() {
        sortAdapter = SortAdapter()
        binding.mGridViewCategory.layoutManager =
            V7LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        // 分类项纵向间距 10dp
        binding.mGridViewCategory.setSpacingWithMargins(0, (10 * resources.displayMetrics.density).toInt())
        binding.mGridViewCategory.adapter = sortAdapter
        // 数据变化后保持当前分类位置（焦点与选中项同步到 currentSelected），
        // 避免分类数据重载（如从详情页返回触发重建/设置变更）时焦点被强制移回第一个分类，
        // 导致 ViewPager 跳回首页分类而不是停留在原分类
        sortAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                binding.mGridViewCategory.post {
                    if (sortAdapter.itemCount > 0) {
                        val target = currentSelected.coerceIn(0, sortAdapter.itemCount - 1)
                        binding.mGridViewCategory.setSelection(target)
                        // 仅当窗口尚无焦点时才恢复分类焦点；用户正在内容区操作时不打断
                        if (!binding.root.hasFocus()) {
                            binding.mGridViewCategory.layoutManager?.findViewByPosition(target)?.requestFocus()
                        }
                    }
                }
            }
        })
        // 快速移动焦点时跳过缩放动画（滚动中直接设置缩放值，停止后恢复动画），避免动画叠加掉帧
        binding.mGridViewCategory.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                categoryScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                if (newState == RecyclerView.SCROLL_STATE_IDLE && categorySwitchPending) {
                    // 快速滚动停止后统一执行待切换的分类，避免滚动过程中频繁 setCurrentItem 卡顿
                    categorySwitchPending = false
                    handler.post(dataRunnable)
                }
            }
        })
        binding.mGridViewCategory.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(tvRecyclerView: TvRecyclerView, view: View?, position: Int) {
                if (view == null) return
                // 分类滚动时播放失焦缩放动画，提供清晰焦点反馈
                view.animate().cancel()
                if (!view.isActivated) {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(125).start()
                }
                view.findViewById<View>(R.id.tvFilter)?.visibility = View.GONE
                updateSortItemTextStyle(view, view.isActivated)
            }

            override fun onItemSelected(tvRecyclerView: TvRecyclerView, view: View?, position: Int) {
                if (view == null) return
                sortChange = true
                // 快速滚动同样播放聚焦放大动画（视图常驻后无布局卡顿顾虑）
                view.animate().cancel()
                view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(CATEGORY_FOCUS_ANIM_DURATION).start()
                updateSortItemTextStyle(view, true)
                var targetPosition = position
                if (targetPosition == -1) {
                    targetPosition = 0
                    binding.mGridViewCategory.setSelection(0)
                }
                if (targetPosition !in 0 until sortAdapter.itemCount) return
                val sortData = sortAdapter.getItem(targetPosition)
                if (sortData.filters.isNotEmpty()) {
                    showFilterIcon(view, sortData.filterSelectCount())
                }
                sortFocusView = view
                sortFocused = targetPosition
                handler.removeCallbacks(dataRunnable)
                if (categoryScrolling) {
                    // 快速滚动中：仅标记待切换，滚动停止后统一执行，避免滚动中频繁切换 ViewPager
                    categorySwitchPending = true
                } else {
                    // 防抖延迟 60ms：兼顾切换跟手与避免频繁重建
                    handler.postDelayed(dataRunnable, CATEGORY_SWITCH_DELAY)
                }
            }

            override fun onItemClick(parent: TvRecyclerView, itemView: View?, position: Int) {
                if (itemView == null || currentSelected != position) return
                val fragment = tabAt(currentSelected) ?: return
                val sortData = sortAdapter.getItem(currentSelected)
                if (fragment is UserFragment) {
                    // 首页聚合页单击触发源切换
                    showSiteSwitch()
                } else if (sortData.filters.isNotEmpty()) {
                    // 当前分类有筛选项时弹出筛选
                    fragment.showFilter()
                }
            }
        })
        binding.mGridViewCategory.setOnInBorderKeyEventListener(object : TvRecyclerView.OnInBorderKeyEventListener {
            override fun onInBorderKeyEvent(direction: Int, view: View?): Boolean {
                if (direction == View.FOCUS_UP) {
                    // 上键行为（首页按钮上移 logo / 其他分类刷新）由 dispatchKeyEvent 统一处理，
                    // 此处拦截防止焦点逃逸到顶部栏按钮
                    return true
                }
                // 焦点下移到内容区时，若当前页尚未加载完成则拦截，避免焦点落入空白
                val fragment = tabAt(sortFocused) ?: return false
                return direction == View.FOCUS_DOWN && !fragment.isLoad()
            }
        })
    }

    /** 焦点分类项显示筛选图标，有已选条件时染主题色 */
    private fun showFilterIcon(view: View, count: Int) {
        view.findViewById<View>(R.id.tvFilter)?.visibility = View.VISIBLE
        view.findViewById<ImageView>(R.id.tvFilter)?.setColorFilter(
            if (count > 0) getThemeColor() else Color.WHITE
        )
    }

    /** 分类项文字样式：焦点/激活项白色加粗，否则 70% 白 */
    private fun updateSortItemTextStyle(view: View, highlight: Boolean) {
        val textView = view.findViewById<TextView>(R.id.tvTitle) ?: return
        textView.paint.isFakeBoldText = highlight
        textView.setTextColor(
            ContextCompat.getColor(
                this,
                if (highlight) uiR.color.color_FFFFFF else uiR.color.color_FFFFFF_70
            )
        )
        textView.invalidate()
    }

    /** 当前主题色，从主题 color_theme 属性解析 */
    private fun getThemeColor(): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(uiR.attr.color_theme, typedValue, true)
        return if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            Color.WHITE
        }
    }

    /** 初始化顶部栏点击事件 */
    private fun setupTopBar() {
        // tvName 单击清理缓存
        binding.tvName.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastLogoClickTime < LOGO_CLICK_INTERVAL || isClearingCache) {
                return@setOnClickListener
            }
            lastLogoClickTime = currentTime
            clearCacheAndReload()
        }
        // tvWifi：跳转系统 WiFi 设置
        binding.tvWifi.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "无法打开 WiFi 设置", Toast.LENGTH_SHORT).show()
            }
        }
        // tvFind：搜索
        binding.tvFind.setOnClickListener { appNavigator.goSearch(this) }
        // tvMenu：设置
        binding.tvMenu.setOnClickListener { appNavigator.goSetting(this) }
        binding.tvMenu.setOnLongClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.fromParts("package", packageName, null)
                startActivity(intent)
            } catch (_: Throwable) {
            }
            true
        }
        // 功能入口（固定显示在顶部栏）：历史/直播/收藏/推送/网盘
        binding.tvHistory.setOnClickListener { appNavigator.goHistory(this) }
        binding.tvLive.setOnClickListener { appNavigator.goLive(this) }
        binding.tvFavorite.setOnClickListener { appNavigator.goCollect(this) }
        binding.tvPush.setOnClickListener { appNavigator.goPush(this) }
        binding.tvDrive.setOnClickListener { appNavigator.goDrive(this) }
        // tvDate：跳转系统日期设置
        binding.tvDate.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }

    /** 返回键二次确认逻辑 */
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 加载状态卡住（如 PY 源引擎未就绪、源内容请求挂起）或分类数据未就绪时，
                // 分类菜单不可聚焦、页面无内容，此时直接走退出流程，
                // 避免返回键被加载态/空状态静默吞掉导致切源后无法退出应用
                if (viewModel.loading.value || fragments.isEmpty() || sortFocused !in fragments.indices) {
                    doExit()
                    return
                }
                val fragment = tabAt(sortFocused)
                // 文件夹模式优先恢复上一级视图
                if (fragment != null && fragment.restoreView()) {
                    return
                }
                // 内容未滚动到顶部时优先滚动到顶部；
                // 若上次返回已尝试回顶但内容仍非顶部（滚动异常/空态判定偏差），
                // 不再吞掉返回键，继续走退出流程，保证返回键始终可达退出
                if (fragment != null && !fragment.isTop()) {
                    if (backScrollTopPending) {
                        backScrollTopPending = false
                    } else {
                        backScrollTopPending = true
                        fragment.scrollTop()
                        // 回顶部后焦点归位分类菜单，避免内容网格滚出屏幕时焦点丢失到顶栏 logo
                        restoreCategoryFocus()
                        return
                    }
                } else {
                    backScrollTopPending = false
                }
                // 焦点不在分类菜单时顺带恢复分类焦点，但不再 return 拦截退出流程，
                // 非第一个分类时按一次返回即跳回第一个分类，
                // 已在第一个分类时直接走二次确认退出，避免需要连按多次才能退出
                val focusView = sortFocusView
                if (focusView != null && focusView.isAttachedToWindow && !focusView.isFocused) {
                    // 加载中分类菜单被禁用聚焦（isFocusable=false）时 requestFocus 会失败，
                    // 失败也继续走退出流程，不影响返回退出
                    focusView.requestFocus()
                }
                // 当前不是第一个分类时跳回第一个：手动同步焦点位置，
                // 避免 setSelection 不触发焦点回调导致 sortFocused 卡死无法退出
                if (sortFocused != 0) {
                    backToFirstCategory()
                    return
                }
                doExit()
            }
        })
    }

    /**
     * 预加载相邻分类数据：切换分类后提前加载左右各 [PRELOAD_RANGE] 个分类，
     * 用户连续快速切换时目标分类数据已就绪，无需等待网络请求即可秒显。
     */
    private fun preloadAdjacent(position: Int) {
        if (fragments.size <= 1) return
        for (offset in 1..PRELOAD_RANGE) {
            tabAt(position + offset)?.let { if (!it.isLoad()) it.preloadData() }
            tabAt(position - offset)?.let { if (!it.isLoad()) it.preloadData() }
        }
    }

    /**
     * 焦点归位分类菜单当前选中项。内容区网格向上出界/返回键回顶部时调用，
     * 避免网格滚出屏幕导致焦点丢失到顶栏 logo。
     */
    fun restoreCategoryFocus() {
        binding.mGridViewCategory.post {
            if (currentSelected in 0 until sortAdapter.itemCount) {
                binding.mGridViewCategory.setSelection(currentSelected)
                binding.mGridViewCategory.requestFocus()
            }
        }
    }

    /**
     * 首页聚合页真实刷新：站点推荐模式重新拉取当前源 homeContent（分类与推荐数据一起刷新）。
     * 返回是否已启动刷新；未就绪返回 false，调用方需自行隐藏 loading。
     * loading 由 ViewModel.loading 同步（HomeActivity → UserFragment.setHomeLoadingInternal），
     * 数据到达后由 setHomeRecInternal 隐藏。
     */
    fun refreshHomeTab(): Boolean {
        if (!viewModel.isHomeReady) return false
        viewModel.refreshHomeContent()
        return true
    }

    /** 返回键场景下跳回第一个分类，并同步分类菜单焦点与 ViewPager 位置 */
    private fun backToFirstCategory() {
        val previous = currentSelected
        sortFocused = 0
        sortChange = false
        backScrollTopPending = false
        handler.removeCallbacks(dataRunnable)
        binding.mGridViewCategory.setSelection(0)
        if (previous != 0) {
            tabAt(previous)?.resetFilterState()
            showFragment(0)
            sortAdapter.setActivatedPosition(0)
            tabAt(0)?.scrollTop()
            preloadAdjacent(0)
        }
    }

    /** 全局焦点监听：控制分类菜单 activated 高亮 */
    private fun setupFocusListener() {
        focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && isChildOf(newFocus, binding.topLayout)) {
                // 焦点进入顶部栏时取消分类高亮
                // post 延迟执行，避免焦点分发期间 RecyclerView 布局中调用 notifyItemChanged 崩溃
                binding.mGridViewCategory.post { sortAdapter.setActivatedPosition(-1) }
            } else if (currentSelected in 0 until sortAdapter.itemCount) {
                // 焦点回到分类菜单或内容区时恢复高亮
                binding.mGridViewCategory.post { sortAdapter.setActivatedPosition(currentSelected) }
            }
        }
        binding.contentLayout.viewTreeObserver.addOnGlobalFocusChangeListener(focusChangeListener)
    }

    /** 观察 ViewModel 数据变化 */
    private fun observeViewModel() {
        collectStarted(viewModel.sortList) { sorts ->
            rebuildFragments(sorts)
            // 首页分类加载完成后，开启"启动进直播"时跳转直播页
            if (!defaultShowTriggered && sorts.isNotEmpty()) {
                defaultShowTriggered = true
                if (appPreferences.getBoolean(AppConstants.HOME_DEFAULT_SHOW, false)) {
                    appNavigator.goLive(this)
                }
            }
        }
        // 首页推荐视频更新时同步到 UserFragment（站点推荐模式）
        collectStarted(viewModel.homeVideos) { videos ->
            for (fragment in fragments) {
                if (fragment is UserFragment) {
                    UserFragment.setHomeRecInternal(fragment, videos)
                }
            }
        }
        collectStarted(viewModel.loading) { loading ->
            // 分类菜单初始即含"首页"按钮可聚焦，加载中不禁止焦点（固定显示首页按钮）；
            // logo 闪烁提示加载中
            if (loading) {
                startTvNameAnimation()
            } else {
                binding.tvName.clearAnimation()
            }
            // 同步首页聚合页内容区加载转圈（数据未就绪时显示，加载完成隐藏）
            for (fragment in fragments) {
                if (fragment is UserFragment) {
                    UserFragment.setHomeLoadingInternal(fragment, loading)
                }
            }
            // 首页配置/jar 加载完成后触发启动更新检测（不依赖首页推荐视频是否为空，
            // 否则无 videoList 的源会导致检测永远不启动、历史按钮不转圈）
            if (!loading && !startupCheckTriggered) {
                startupCheckTriggered = true
                lifecycleScope.launch {
                    if (videoUpdateChecker.isStartupCheck() && videoUpdateChecker.canCheck()) {
                        videoUpdateChecker.startCheck(force = true, highConcurrency = true)
                    }
                }
            }
        }
        collectStarted(viewModel.sourceName) { name ->
            updateSourceName(name)
        }
        collectStarted(viewModel.error) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        // 配置加载失败：弹 TipDialog（标题为错误信息，可跳设置订阅地址）
        collectStarted(viewModel.configError) { msg ->
            if (msg != null) {
                showConfigErrorDialog(msg)
                viewModel.consumeConfigError()
            }
        }
        // 订阅地址为多仓（接口合集）：弹线路选择，不自动切换
        collectStarted(viewModel.apiLines) { lines ->
            if (lines.isNotEmpty()) showApiLinesDialog(lines)
        }
    }

    /** 多仓（接口合集）线路选择弹窗，选中后由 ViewModel 写入接口地址并重载 */
    private fun showApiLinesDialog(lines: List<Pair<String, String>>) {
        // 网格排列：线路少时 2 列，多于 14 条时 3 列，避免单竖排
        val spanCount = minOf(3, (lines.size / 5.0).toInt().coerceAtLeast(2))
        SelectDialog(
            context = this,
            tip = getString(uiR.string.dia_api_line),
            items = lines,
            display = { it.first },
            spanCount = spanCount,
            widthMm = 380 + 150 * (spanCount - 1),
            onSelect = { line, _ -> viewModel.chooseApiLine(line.second) }
        ).show()
    }

    /** 配置加载失败弹窗：提供跳转设置订阅地址 */
    private var configErrorDialog: TipDialog? = null

    private fun showConfigErrorDialog(msg: String) {
        configErrorDialog?.takeIf { it.isShowing }?.dismiss()
        configErrorDialog = TipDialog(
            this,
            msg,
            getString(uiR.string.hm_setting),
            getString(uiR.string.hm_cancel),
            object : TipDialog.OnListener {
                override fun left() {
                    configErrorDialog?.dismiss()
                    appNavigator.goSetting(this@HomeActivity)
                }

                override fun right() {
                    configErrorDialog?.dismiss()
                }

                override fun cancel() {
                    configErrorDialog?.dismiss()
                }
            }
        ).apply { show() }
    }

    /** 观察全局事件。
     *  注意：不绑定 STARTED 生命周期，保证首页处于后台（设置页/订阅设置对话框打开）时
     *  也能收到 web 订阅设置"提交全部并重载"事件并立即重载；
     *  由 lifecycleScope 管理，Activity 销毁时自动取消。
     *  后台执行 resetAndReload 仅更新 ViewModel 状态，UI（Fragment 重建）仍由
     *  observeViewModel 的 collectStarted 在回前台 STARTED 时应用，无 FragmentManager 后台操作风险。 */
    private fun observeEvents() {
        lifecycleScope.launch {
            EventHub.events.collect { event ->
                when (event) {
                    is TypedEvent -> {
                        when (event.type) {
                            // web 订阅设置"提交全部并重载"：统一应用配置并重载首页
                            // （各输入框"确定"仅由 ApiDialog 同步编辑框，不在此处理）
                            ControlManager.TYPE_SUBMIT_ALL -> handleSubmitAll()
                            // PY 引擎就绪：首页源为 PY 时恢复之前保持的加载态
                            pyLoader.TYPE_PY_LOADER_READY -> viewModel.refreshHomeContent()
                            // PY 引擎初始化失败提示
                            pyLoader.TYPE_PY_LOADER_ERROR -> Toast.makeText(
                                this@HomeActivity, "Python 引擎初始化失败", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    is RefreshEvent -> {
                        when (event.type) {
                            RefreshEvent.TYPE_SETTING -> {
                                // 设置变更后重新加载首页
                                viewModel.resetAndReload(true)
                            }
                            RefreshEvent.TYPE_HOME -> {
                                viewModel.refreshHomeContent()
                            }
                            RefreshEvent.TYPE_HOME_REC_CHANGE -> {
                                // 首页推荐类型变更后重建聚合页
                                viewModel.resetAndReload(true)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** web 订阅设置"提交全部并重载"：统一应用已保存的配置并重载首页 */
    private fun handleSubmitAll() {
        // 清除待重载标记，避免 onResume 重复触发
        appPreferences.putSync(AppConstants.RELOAD_PENDING, false)
        // 首页不在前台（如设置页/订阅设置对话框打开）时，清栈把首页带到前台，
        // 让用户立即看到重载效果（配置加载与 UI 应用由回前台后的 collectStarted 完成）
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Toast.makeText(this, "正在应用全部配置并重载", Toast.LENGTH_SHORT).show()
        viewModel.resetAndReload(false)
    }

    /**
     * 根据分类列表重建页面 Fragment。
     *
     * 复用 FragmentManager 中已存在且分类匹配的 Fragment（Activity 重建后 FragmentManager
     * 恢复的，或从其他界面返回时未变更的分类），保留已加载数据与滚动位置，避免返回后重新加载；
     * 仅对分类列表变化（如切换站点）导致的新增/变更分类创建新 Fragment。
     */
    private fun rebuildFragments(sorts: List<MovieSort.SortData>) {
        val existing = supportFragmentManager.fragments.filter { it is HomeTabFragment }

        // 构造目标分类列表（数据未就绪时保留 my0 首页项）
        val targetSorts = if (sorts.isEmpty()) {
            listOf(MovieSort.SortData("my0", getString(uiR.string.app_home)))
        } else {
            sorts
        }
        // 统一填充 my0 首页项名称：adjustSorts 注入的 my0 name 为空，
        // 需由 UI 层本地化填充，否则分类菜单"首页"按钮显示空白
        for (sort in targetSorts) {
            if (sort.id == "my0" && sort.name.isNullOrEmpty()) sort.name = getString(uiR.string.app_home)
        }

        // 按分类 id 匹配复用已有 Fragment，未匹配的创建新实例
        val newFragments = mutableListOf<Fragment>()
        for (sort in targetSorts) {
            val sortId = sort.id
            val reuse = if (sortId == "my0") {
                existing.filterIsInstance<UserFragment>().firstOrNull()
            } else {
                existing.filterIsInstance<GridFragment>().firstOrNull { it.getSortId() == sortId }
            }
            if (reuse != null) {
                newFragments.add(reuse)
            } else {
                if (sortId == "my0") {
                    // 首页聚合页，名称使用本地化资源
                    if (sort.name.isNullOrEmpty()) sort.name = getString(uiR.string.app_home)
                    newFragments.add(UserFragment.newInstance(viewModel.homeVideos.value))
                } else {
                    newFragments.add(GridFragment.newInstance(sort))
                }
            }
        }

        // 移除不再匹配的旧 Fragment（换源/分类列表变化）
        val toRemove = existing.filter { it !in newFragments }
        if (toRemove.isNotEmpty() && !supportFragmentManager.isDestroyed) {
            val rft = supportFragmentManager.beginTransaction()
            toRemove.forEach { rft.remove(it) }
            rft.commitNowAllowingStateLoss()
        }

        fragments.clear()
        fragments.addAll(newFragments)

        // 空列表时不重置分类位置：Activity 重建后首次 collect 到空列表时，
        // 若直接重置为 0 会导致从详情页返回时跳到首页分类。仅在非空且越界时纠正。
        if (fragments.isNotEmpty() && currentSelected >= fragments.size) {
            currentSelected = if (savedCategoryPosition in fragments.indices) savedCategoryPosition else fragments.lastIndex
        }

        sortAdapter.setNewData(targetSorts)

        // 一次性 add 未添加的 Fragment 视图并隐藏非当前页（视图常驻）：
        // 切换时仅 hide/show 改可见性，不触发页面测量/布局，快速切换无布局积压
        if (!supportFragmentManager.isDestroyed) {
            val ft = supportFragmentManager.beginTransaction()
            for ((index, fragment) in fragments.withIndex()) {
                if (!fragment.isAdded) {
                    ft.add(R.id.mPageContainer, fragment, "frag_$index")
                }
                if (index != currentSelected) ft.hide(fragment) else ft.show(fragment)
            }
            ft.commitNowAllowingStateLoss()
        }
        sortAdapter.setActivatedPosition(currentSelected)
        if (fragments.isNotEmpty()) {
            tabAt(currentSelected)?.loadDataIfNotLoaded()
        }
        // 数据就绪后请求分类菜单焦点（post 延迟确保布局完成，
        // 避免启动时焦点先落在顶部站点名或站点推荐网格卡片上闪烁）
        binding.mGridViewCategory.post { binding.mGridViewCategory.requestFocus() }
    }

    /** 加载期间 logo 闪烁动画 */
    private fun startTvNameAnimation() {
        val blink = AlphaAnimation(0.0f, 1.0f)
        blink.duration = 500
        blink.startOffset = 20
        blink.repeatMode = Animation.REVERSE
        blink.repeatCount = Animation.INFINITE
        binding.tvName.startAnimation(blink)
    }

    /** 更新顶部栏显示的源名称：开启"显示站点"时显示源名，否则显示应用名 */
    private fun updateSourceName(name: String) {
        if (appPreferences.getBoolean(AppConstants.HOME_SHOW_SOURCE, false) && name.isNotEmpty()) {
            binding.tvName.text = name
            binding.tvName.clearAnimation()
        } else {
            binding.tvName.text = getString(uiR.string.app_name)
        }
        // 站点名过长时以跑马灯方式滚动（无焦点时也滚动，仅文字超宽时生效）
        binding.tvName.isSelected = true
    }

    /** 更新时间显示 */
    private fun updateTime() {
        val pattern = "${getString(uiR.string.hm_date1)} | ${getString(uiR.string.hm_date2)}"
        val format = SimpleDateFormat(pattern, Locale.getDefault())
        binding.tvDate.text = format.format(Date())
    }

    /** 注册网络变化监听，实时更新 WiFi 图标 */
    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                runOnUiThread { updateWifiIcon(capabilities) }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    binding.tvWifi.setImageResource(uiR.drawable.hm_wifi_no)
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Throwable) {
        }
    }

    /** 注销网络监听 */
    private fun unregisterNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Throwable) {
            }
        }
        networkCallback = null
    }

    /** 根据当前网络状态更新 WiFi 图标 */
    private fun updateWifiIcon() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            binding.tvWifi.setImageResource(uiR.drawable.hm_wifi_no)
            return
        }
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            binding.tvWifi.setImageResource(uiR.drawable.hm_wifi_no)
            return
        }
        updateWifiIcon(capabilities)
    }

    /** 根据网络类型更新 WiFi 图标 */
    private fun updateWifiIcon(capabilities: NetworkCapabilities) {
        val resId = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> uiR.drawable.hm_wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> uiR.drawable.hm_mobile
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> uiR.drawable.hm_lan
            else -> uiR.drawable.hm_wifi_no
        }
        binding.tvWifi.setImageResource(resId)
    }

    /** 弹出源切换对话框，网格多列展示站点 */
    private fun showSiteSwitch() {
        val sites = apiConfig.getSourceBeanList().filter { it.hide == 0 }
        if (sites.isEmpty()) return
        val currentKey = apiConfig.homeSourceBean.key
        // 源数/20 决定额外列数与弹窗宽度（spanCount 0~2，列数 1~3，宽度 380+200*spanCount mm）
        val extraSpan = minOf(2, (sites.size / 20.0).toInt())
        val spanCount = extraSpan + 1
        // 仅存在已保存的多仓线路时才展示"多仓"入口，无线路时自动隐藏
        val hasApiLines = viewModel.readApiLines().isNotEmpty()
        SelectDialog(
            context = this,
            tip = getString(uiR.string.dia_source),
            items = sites,
            display = { it.name ?: it.key ?: "" },
            spanCount = spanCount,
            select = sites.indexOfFirst { it.key == currentKey }.coerceAtLeast(0),
            widthMm = 380 + 200 * extraSpan,
            footerText = if (hasApiLines) getString(uiR.string.btn_api_line) else null,
            onFooterClick = { showApiLinesFromFooter() },
            onSelect = { bean, _ ->
                apiConfig.setSourceBean(bean)
                reloadHome()
            }
        ).show()
    }

    /** 站点弹窗的"多仓"入口：读取已保存线路列表弹线路选择（复用自动弹出的线路弹窗样式） */
    private fun showApiLinesFromFooter() {
        val lines = viewModel.readApiLines()
        if (lines.isEmpty()) {
            Toast.makeText(this, getString(uiR.string.toast_no_api_line), Toast.LENGTH_SHORT).show()
            return
        }
        showApiLinesDialog(lines)
    }

    /** 重载首页：重启 HomeActivity 并使用缓存配置 */
    private fun reloadHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        intent.putExtra(EXTRA_USE_CACHE, true)
        startActivity(intent)
        finish()
    }

    /** 清理缓存并重载首页 */
    private fun clearCacheAndReload() {
        // 检查存储权限（无权限先弹权限提醒）
        if (!hasStoragePermission()) {
            showStoragePermissionDialog()
            return
        }
        isClearingCache = true
        FileUtils.recursiveDelete(cacheDir)
        FileUtils.recursiveDelete(externalCacheDir)
        apiConfig.clearAllCache()
        ImageLoaderFactory.clearMemoryCache()
        Toast.makeText(this, uiR.string.hm_cache_del, Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            isClearingCache = false
            reloadHome()
        }, 300)
    }

    /** 存储权限是否已授予：Android 11+ 检查 MANAGE_EXTERNAL_STORAGE，低版本检查写权限 */
    private fun hasStoragePermission(): Boolean = StoragePermissionUtils.hasManageExternalStorage(this)

    private fun storagePermissionList(): List<IPermission> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            listOf(PermissionLists.getManageExternalStoragePermission())
        } else {
            listOf(PermissionLists.getWriteExternalStoragePermission())
        }
    }

    /** 清理缓存前的权限提醒 */
    private fun showStoragePermissionDialog() {
        // Android 11+ 引导前往系统设置开启所有文件访问权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            AlertDialog.Builder(this)
                .setTitle("权限提醒")
                .setMessage("需要所有文件访问权限才能清理缓存。是否前往设置开启权限？")
                .setPositiveButton("去设置") { _, _ ->
                    XXPermissions.startPermissionActivity(
                        this,
                        XXPermissions.getDeniedPermissions(this, storagePermissionList())
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // Android 10 及以下直接申请传统存储权限，授予后继续清理缓存
        XXPermissions.with(this)
            .permissions(storagePermissionList())
            .request(object : OnPermissionCallback {
                override fun onPermissionResult(
                    granted: MutableList<IPermission>,
                    denied: MutableList<IPermission>
                ) {
                    if (denied.isEmpty()) {
                        clearCacheAndReload()
                        return
                    }
                    val neverAskAgain = denied.any {
                        it.isDoNotAskAgainPermission(this@HomeActivity)
                    }
                    val message = if (neverAskAgain) {
                        "存储权限已被永久拒绝，请前往应用设置手动开启，否则无法清理缓存。"
                    } else {
                        "存储权限被拒绝，无法清理缓存。是否前往设置开启权限？"
                    }
                    AlertDialog.Builder(this@HomeActivity)
                        .setTitle("权限提醒")
                        .setMessage(message)
                        .setPositiveButton("去设置") { _, _ ->
                            XXPermissions.startPermissionActivity(this@HomeActivity, denied)
                        }
                .setNegativeButton("取消", null)
                .show()
            }
        })
    }

    /** 二次确认退出 */
    private fun doExit() {
        if (System.currentTimeMillis() - exitTime < EXIT_INTERVAL) {
            videoUpdateChecker.shutdown()
            // 完全退出：停止后台播放服务与播放器。
            // 否则 PlayService 作为前台服务保持进程存活，后台音乐持续播放；
            // 重新启动后旧进程仍在播（表现为"旧进程依旧在播放"）
            com.tvbox.kotlin.core.server.PlayService.stopBackgroundAudio(this)
            com.tvbox.kotlin.core.server.PlayService.stop(this)
            finishAffinity()
        } else {
            exitTime = System.currentTimeMillis()
            Toast.makeText(this, uiR.string.hm_exit, Toast.LENGTH_SHORT).show()
        }
    }

    /** 判断 view 是否为指定 ViewGroup 的子项 */
    private fun isChildOf(view: View, parent: ViewGroup): Boolean {
        var current: android.view.ViewParent? = view.parent
        while (current != null) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    private fun removeFocusListener() {
        val listener = focusChangeListener ?: return
        try {
            binding.contentLayout.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
        } catch (_: Throwable) {
        }
        focusChangeListener = null
    }

    override fun onResume() {
        super.onResume()
        updateSourceName(viewModel.sourceName.value)
        // 从播放/详情/历史等页面返回时重新计算首页历史按钮角标
        updateHistoryBadge(videoUpdateChecker.hasUpdate())
        // 恢复每秒时间刷新任务
        handler.removeCallbacks(timeRunnable)
        handler.postDelayed(timeRunnable, TIME_REFRESH_INTERVAL)
        // web 订阅设置"提交全部并重载"时首页可能不在前台（如设置页订阅设置对话框打开），
        // EventHub 事件会丢；持久化标记兜底，返回首页时统一应用并重载
        if (appPreferences.getBoolean(AppConstants.RELOAD_PENDING, false)) {
            handleSubmitAll()
        }
        // 推荐类型变更后通知重建首页聚合页
        val currentHomeRec = appPreferences.getInt(AppConstants.HOME_REC, 0)
        if (lastHomeRec != -1 && lastHomeRec != currentHomeRec) {
            EventHub.post(RefreshEvent(RefreshEvent.TYPE_HOME_REC_CHANGE))
        }
        lastHomeRec = currentHomeRec
        // 仅当窗口尚无焦点时才把焦点交给分类菜单"首页"按钮：
        // 从详情/播放页返回时原焦点仍在该窗口内（root.hasFocus()=true），不打断用户当前操作
        if (sortAdapter.itemCount > 0 && !binding.root.hasFocus()) {
            binding.mGridViewCategory.requestFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 站点 logo 一直保持可聚焦（点击清缓存）；窗口获得焦点时仅在没有焦点的情况下
        // 交给分类菜单"首页"按钮，避免初始焦点落在 logo 上闪烁
        if (hasFocus && sortAdapter.itemCount > 0 && !binding.root.hasFocus()) {
            binding.mGridViewCategory.requestFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(timeRunnable)
    }

    /** MENU 键触发源切换；分类菜单上键：首页按钮上移焦点到 logo，其他分类刷新（焦点不动） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_MENU
        ) {
            showSiteSwitch()
        }
        // 焦点在分类菜单时按上键：仅首页按钮上移焦点到顶部 logo，其他分类执行刷新
        // （拦截事件，焦点不逃逸到顶部栏其他按钮）
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        ) {
            val focus = currentFocus
            if (focus != null && isChildOf(focus, binding.mGridViewCategory)) {
                if (sortFocused == 0) {
                    binding.tvName.requestFocus()
                } else {
                    tabAt(sortFocused)?.forceRefresh()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // 保存分类位置与焦点位置，Activity 重建后恢复，避免返回首页时停留在错误分类
        outState.putInt(KEY_CURRENT_SELECTED, currentSelected)
        outState.putInt(KEY_SORT_FOCUSED, sortFocused)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        removeFocusListener()
        videoUpdateChecker.removeListener(updateListener)
        controlManager.stopServer()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_USE_CACHE = "useCache"
        private const val TIME_REFRESH_INTERVAL = 1_000L
        private const val EXIT_INTERVAL = 2_000L
        private const val LOGO_CLICK_INTERVAL = 3_500L

        /** 分类切换防抖延迟：快速移动焦点时合并为一次切换，减少 ViewPager 频繁切换的卡顿 */
        private const val CATEGORY_SWITCH_DELAY = 120L

        /** 分类菜单焦点缩放动画时长，与失焦动画保持一致避免动画叠加拖尾 */
        private const val CATEGORY_FOCUS_ANIM_DURATION = 120L

        /** 切换分类后预加载相邻分类的个数（左右各 N 个） */
        private const val PRELOAD_RANGE = 2

        private const val KEY_CURRENT_SELECTED = "currentSelected"
        private const val KEY_SORT_FOCUSED = "sortFocused"

        /** 记录上次推荐类型，用于检测设置中切换 HOME_REC */
        private var lastHomeRec = -1
    }
}

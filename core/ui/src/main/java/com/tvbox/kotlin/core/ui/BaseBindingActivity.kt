package com.tvbox.kotlin.core.ui

import android.annotation.TargetApi
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.LocaleHelper
import com.tvbox.kotlin.core.common.util.WallpaperManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.jessyan.autosize.AutoSizeCompat
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.internal.CustomAdapt
import kotlin.math.max
import kotlin.math.min

/**
 * DataBinding Activity 基类，封装视图绑定与生命周期协程管理。
 * 实现 [CustomAdapt] 提供 mm 单位按 1280×720 设计稿适配。
 */
abstract class BaseBindingActivity<T : ViewDataBinding> : AppCompatActivity(), CustomAdapt {

    protected lateinit var binding: T
        private set

    protected abstract val layoutId: Int

    companion object {
        /** 已确认有效的屏幕宽高比，<0 表示尚未就绪/异常（冷启动方向协商中） */
        @Volatile
        private var screenRatio = -100.0f

        /** 屏幕比例合法范围：1.0~4.0，超宽带鱼屏（>=4.0）改按高度基准 */
        private const val MIN_SCREEN_RATIO = 1.0f
        private const val MAX_SCREEN_RATIO = 4.0f

        /** 窗口尺寸稳定后的二次校正延迟（HDMI 分辨率协商/系统栏变化） */
        private const val AUTO_SIZE_REFRESH_DELAY = 300L

        /** recreate 最小间隔（毫秒）：方向/分辨率协商期间窗口尺寸反复抖动时，防抖避免重建循环 */
        private const val RECREATE_DEBOUNCE_MS = 1000L

        /** PiP 过渡期保护时长（毫秒）：进入/退出 PiP 后窗口尺寸过渡期内不触发重建 */
        private const val PIP_TRANSITION_GUARD_MS = 1500L

        /** 进程内 recreate 次数上限：超限后放弃重建，防止协商异常设备卡在启动页无法进入 */
        private const val RECREATE_MAX_TIMES = 4

        /** 上次重建时间（跨实例共享，recreate 后新实例沿用防抖窗口） */
        private var lastRecreateAt = 0L

        /** 进程内累计重建次数（跨实例共享，用于上限兜底） */
        private var recreateCount = 0
    }

    /**
     * 重建防抖与上限保护。
     * 部分设备冷启动方向协商/HDMI 分辨率抖动会导致 onConfigurationChanged 与
     * onWindowFocusChanged 反复触发 recreate，若不加约束会陷入重建死循环，
     * 表现为"卡启动无法进入界面"。
     */
    private fun requestRecreate() {
        if (isFinishing || isDestroyed) return
        if (recreateCount >= RECREATE_MAX_TIMES) {
            densityFixPending = false
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastRecreateAt < RECREATE_DEBOUNCE_MS) return
        lastRecreateAt = now
        recreateCount++
        densityFixPending = false
        binding.root.post {
            if (!isFinishing && !isDestroyed) recreate()
        }
    }

    /** 上次适配的物理屏幕长边/短边，用于检测 HDMI 分辨率切换（方向切换不变化） */
    private var lastMaxSide = -1
    private var lastMinSide = -1

    /** 内容视图是否已 inflate，用于判断是否需要重建兜底 */
    private var contentInflated = false

    /** 画中画模式标志：PiP 中窗口尺寸变化属系统窗口管理（进入/缩放/退出），不触发配置变化重建 */
    private var pipActive = false

    /** PiP 过渡期（进入/退出）结束时间戳：期间窗口尺寸变化不触发重建 */
    private var pipTransitionUntil = 0L

    /** 布局可能基于异常密度 inflate，需在窗口尺寸稳定后重建一次 */
    private var densityFixPending = false

    /** 已同步到 AutoSize 的屏幕尺寸缓存，避免每次资源解析重复读取窗口 */
    private var syncedScreenWidth = -1
    private var syncedScreenHeight = -1

    /**
     * AutoSize 同步递归守卫：部分 ROM 的 WindowManagerImpl.getCurrentBounds 内部会回调
     * getResources()，嵌套调用时跳过窗口查询，防止 getResources→窗口查询→getResources 无限递归。
     * getResources 仅在主线程被调用（重写内已按 Looper 过滤），普通成员变量即安全。
     */
    private var inAutoSizeSync = false

    /** 延迟二次校正任务：窗口尺寸可能滞后于方向/系统栏变化 */
    private val refreshAutoSizeRunnable = Runnable { refreshAutoSize() }

    /**
     * 获取当前窗口物理尺寸（px）。应用全屏沉浸式绘制，取整块屏幕尺寸
     * （含刘海/虚拟按键区域）作为适配基准；异常时返回 null。
     */
    private fun currentWindowSize(): Pair<Int, Int>? {
        return try {
            val w: Int
            val h: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                w = bounds.width()
                h = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val point = Point().also { windowManager.defaultDisplay.getRealSize(it) }
                w = point.x
                h = point.y
            }
            if (w <= 0 || h <= 0) null else w to h
        } catch (_: Throwable) {
            // 窗口未就绪/ROM 差异导致查询失败时静默降级，避免异常刷屏阻塞主线程
            null
        }
    }

    /**
     * 计算屏幕宽高比（横屏宽>高、比例 1.0~4.0）。
     * 未就绪/异常（宽高为 0、竖屏临时尺寸、比例非法）返回 -1，
     * 调用方据此跳过 AutoSize 换算，避免基于错误尺寸污染全局密度。
     */
    private fun computeScreenRatio(): Float {
        val size = currentWindowSize() ?: return -1f
        val (w, h) = size
        // TV 横屏设计：竖屏临时尺寸视为未就绪，跳过换算避免污染
        if (w < h) return -1f
        val ratio = w.toFloat() / h
        return if (ratio in MIN_SCREEN_RATIO..MAX_SCREEN_RATIO) ratio else -1f
    }

    /** 刷新缓存的有效屏幕比例（仅在计算有效时写入），返回当前比例，-1 表示未就绪 */
    private fun refreshScreenRatio(): Float {
        val ratio = computeScreenRatio()
        if (ratio > 0) screenRatio = ratio
        return ratio
    }

    override fun getSizeInDp(): Float = if (isBaseOnWidth()) 1280f else 720f

    override fun isBaseOnWidth(): Boolean = screenRatio < MAX_SCREEN_RATIO

    /**
     * 将 AutoSize 的屏幕基准同步为当前窗口实际尺寸。
     * AutoSize 在进程启动时缓存屏幕尺寸（手机冷启动为竖屏值），横屏场景若不主动覆盖，
     * 换算会沿用陈旧竖屏宽度导致整体缩放偏小；此处显式覆盖后每次换算均基于当前窗口。
     * @return 是否成功同步（窗口查询失败返回 false，调用方应跳过换算避免基于过期基准改写 density）
     */
    private fun syncAutoSizeBase(): Boolean {
        val size = currentWindowSize() ?: return false
        if (size.first != syncedScreenWidth || size.second != syncedScreenHeight) {
            syncedScreenWidth = size.first
            syncedScreenHeight = size.second
            try {
                AutoSizeConfig.getInstance()
                    .setScreenWidth(size.first)
                    .setScreenHeight(size.second)
            } catch (e: Throwable) {
                LOG.e(e)
                return false
            }
        }
        return true
    }

    /** 使 AutoSize 基准缓存失效，下次资源解析重新同步当前窗口尺寸 */
    private fun invalidateAutoSizeBase() {
        syncedScreenWidth = -1
        syncedScreenHeight = -1
    }

    /**
     * 按当前窗口同步 AutoSize 基准并执行密度换算，随后触发重布局。
     * 在 onResume/窗口焦点/配置变化时调用，保证方向或分辨率切换后 mm 布局即时校正。
     */
    private fun refreshAutoSize() {
        if (isFinishing || isDestroyed) return
        if (refreshScreenRatio() !in MIN_SCREEN_RATIO..MAX_SCREEN_RATIO) return
        // 窗口尺寸同步失败（ROM 查询异常）时跳过换算，避免基于过期基准改写全局 density
        if (!syncAutoSizeBase()) return
        try {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
            window.decorView.requestLayout()
        } catch (e: Throwable) {
            LOG.e(e)
        }
    }

    /** 延迟二次校正，覆盖窗口尺寸滞后稳定（HDMI 协商、虚拟按键出现/隐藏）的场景 */
    private fun scheduleAutoSizeRefresh() {
        if (isFinishing || isDestroyed) return
        val decorView = window.decorView
        decorView.removeCallbacks(refreshAutoSizeRunnable)
        decorView.postDelayed(refreshAutoSizeRunnable, AUTO_SIZE_REFRESH_DELAY)
    }

    override fun getResources(): Resources {
        // 每次都必须重新换算：AutoSize 的 density 会被 cancelAdapt 还原，
        // 仅首次换算后 inflate 的 Dialog 等视图会使用原始 density，导致 mm 尺寸错乱。
        // 仅当屏幕尺寸有效时才换算：冷启动方向协商期间的异常尺寸（0/竖屏/比例非法）
        // 跳过换算，避免 AutoSize 基于错误尺寸全局改写 density/densityDpi 导致 mm 布局错乱；
        // 跳过期间若内容已 inflate，标记待首帧焦点时重建兜底。
        // 窗口尺寸每次同步（非标设备/方向切换后首帧即正确）；嵌套调用（ROM 的
        // getCurrentBounds 回调 getResources）由 inAutoSizeSync 守卫跳过，防止无限递归。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (screenRatio in MIN_SCREEN_RATIO..MAX_SCREEN_RATIO) {
                var synced = true
                if (!inAutoSizeSync) {
                    inAutoSizeSync = true
                    synced = try {
                        syncAutoSizeBase()
                    } finally {
                        inAutoSizeSync = false
                    }
                }
                // 窗口尺寸同步成功才换算，失败时保持当前 density，避免基于过期基准全局改写
                if (synced) {
                    try {
                        AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
                    } catch (_: Throwable) {
                    }
                }
            } else if (contentInflated) {
                densityFixPending = true
            }
        }
        return super.getResources()
    }

    /**
     * 按 HOME_LOCALE 偏好应用语言环境，保证设置页切换语言后重建 Activity 即生效。
     * 英文必须用 "en"：空语言标签 Locale.forLanguageTag("") 在中文系统上会被忽略，
     * 导致资源仍按 values-zh 解析，切换英文无效果。
     */
    override fun attachBaseContext(newBase: Context) {
        val lang = try {
            if (AppPreferences.getShared(newBase).getInt(AppConstants.HOME_LOCALE, 0) == 0) "zh" else "en"
        } catch (_: Throwable) {
            "zh"
        }
        super.attachBaseContext(LocaleHelper.onAttach(newBase, lang))
    }

    /**
     * 隐藏系统状态栏（主题已全屏，Android 11+ 需关闭系统窗口适配延伸内容到刘海区）；
     * 应用壁纸/默认背景到窗口，避免透明 windowBackground 在页面切换时闪屏花屏。
     */
    override fun onResume() {
        super.onResume()
        hideSystemUI()
        changeWallpaper(force = false)
        invalidateAutoSizeBase()
        refreshAutoSize()
        scheduleAutoSizeRefresh()
    }

    /** 窗口创建时即允许内容延伸到刘海/挖孔区域（沉浸式） */
    private fun applyCutoutMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                // 允许窗口越界绘制到系统栏区域，配合 setDecorFitsSystemWindows(false) 覆盖到刘海区
                lp.flags = lp.flags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                window.attributes = lp
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            }
        } catch (_: Throwable) {
        }
    }

    /** 隐藏状态栏，保留导航栏；刘海延伸由 [applyCutoutMode] 在窗口创建时处理 */
    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                controller?.hide(WindowInsets.Type.statusBars())
                controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        } catch (_: Throwable) {
        }
    }

    /** 应用壁纸到窗口，无壁纸时使用默认背景；对外公开以便设置页更换壁纸后立即生效 */
    fun changeWallpaper(force: Boolean) {
        WallpaperManager.getInstance().loadWallpaper(this, force) { bitmap ->
            if (isFinishing || isDestroyed) return@loadWallpaper
            if (bitmap != null && !bitmap.isRecycled) {
                window.setBackgroundDrawable(BitmapDrawable(resources, bitmap))
            } else {
                window.setBackgroundDrawableResource(R.drawable.app_bg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 每次均尝试刷新有效比例：异常尺寸不写入缓存，避免首个 Activity 缓存错误比例
        refreshScreenRatio()
        val size = currentWindowSize()
        val maxSide = size?.let { max(it.first, it.second) } ?: -1
        val minSide = size?.let { min(it.first, it.second) } ?: -1
        if (maxSide > 0 && minSide > 0) {
            lastMaxSide = maxSide
            lastMinSide = minSide
        }
        applyThemePreference()
        super.onCreate(savedInstanceState)
        // 窗口创建即允许内容延伸到刘海/挖孔区域，早于内容布局，避免部分机型布局完成后不重排
        applyCutoutMode()
        binding = DataBindingUtil.setContentView(this, layoutId)
        contentInflated = true
        binding.lifecycleOwner = this
        binding.executePendingBindings()
        initView(savedInstanceState)
        initData()
    }

    /**
     * 窗口获得焦点时窗口尺寸已稳定：
     * 刷新屏幕比例并确保 AutoSize 换算生效；
     * 若此前布局曾在异常密度下 inflate（getResources 换算被跳过），重建一次修正。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || isFinishing || isDestroyed) return
        // PiP 中窗口尺寸变化属系统窗口管理（进入/缩放/退出），不触发重建
        if (pipActive || isInPipMode()) return
        invalidateAutoSizeBase()
        refreshAutoSize()
        scheduleAutoSizeRefresh()
        if (densityFixPending && screenRatio in MIN_SCREEN_RATIO..MAX_SCREEN_RATIO) {
            requestRecreate()
        }
    }

    /**
     * 手机冷启动（sensorLandscape）会经历"竖屏→横屏"方向转换，竖屏阶段换算被跳过、
     * 布局已按系统密度 inflate；转横屏后物理长/短边不变（同一块屏），无法用尺寸变化感知，
     * 故在此检查 [densityFixPending]：方向转正后重建一次修正布局。
     * HDMI 分辨率切换（720p↔4k 等）由物理长/短边变化触发重建。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val now = SystemClock.uptimeMillis()
        if (pipActive || isInPipMode() || now < pipTransitionUntil) {
            // PiP 进入/退出期间窗口尺寸变化属系统窗口管理：
            // 跳过重建并同步基准尺寸，避免 PiP 退出后窗口恢复全屏被误判为物理尺寸变化而重建
            val size = currentWindowSize()
            size?.let {
                lastMaxSide = max(it.first, it.second)
                lastMinSide = min(it.first, it.second)
            }
            refreshAutoSize()
            return
        }
        refreshScreenRatio()
        invalidateAutoSizeBase()
        val size = currentWindowSize()
        val maxSide = size?.let { max(it.first, it.second) } ?: -1
        val minSide = size?.let { min(it.first, it.second) } ?: -1
        val physicalChanged = maxSide > 0 && minSide > 0 &&
            (maxSide != lastMaxSide || minSide != lastMinSide)
        if (physicalChanged) {
            lastMaxSide = maxSide
            lastMinSide = minSide
        }
        if ((physicalChanged || densityFixPending) &&
            screenRatio in MIN_SCREEN_RATIO..MAX_SCREEN_RATIO && contentInflated && !isFinishing && !isDestroyed
        ) {
            requestRecreate()
        }
        refreshAutoSize()
    }

    /** 画中画模式变化：更新标志并设置过渡期，期间窗口尺寸变化由 [onConfigurationChanged] 跳过重建 */
    @TargetApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipActive = isInPictureInPictureMode
        // 进入/退出 PiP 后窗口尺寸过渡期内不触发重建（避免界面/播放器被重建重载）
        pipTransitionUntil = SystemClock.uptimeMillis() + PIP_TRANSITION_GUARD_MS
    }

    /** 是否处于画中画模式（API 26+，低版本恒 false） */
    private fun isInPipMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode

    /**
     * 按用户主题偏好应用主题，缺省使用默认主题。
     * 必须在 super.onCreate() 之前调用，保证窗口与控件使用正确主题；
     * 各主题均继承 AppTheme.NoActionBar 并提供 color_theme，避免 colorAccent 引用链断裂。
     */
    private fun applyThemePreference() {
        try {
            val themeStyles = intArrayOf(
                R.style.NetfxTheme,
                R.style.DoraeTheme,
                R.style.PepsiTheme,
                R.style.NarutoTheme,
                R.style.MinionTheme,
                R.style.YagamiTheme,
                R.style.SakuraTheme
            )
            val index = AppPreferences.getShared(this).getInt(AppConstants.THEME_SELECT, 0)
            setTheme(themeStyles[index.coerceIn(0, themeStyles.lastIndex)])
        } catch (e: Throwable) {
            LOG.e("applyThemePreference", e)
        }
    }

    protected abstract fun initView(savedInstanceState: Bundle?)

    protected open fun initData() {}

    /** 在 STARTED 状态下收集 Flow，避免在 onStop 后仍处理事件 */
    protected fun <U> collectStarted(flow: Flow<U>, action: suspend (U) -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect(action) }
        }
    }

    override fun onStop() {
        super.onStop()
        // 页面停止时恢复全局图片暂停标志：滚动中的页面切走（仅 onStop 未触发
        // RecyclerView detach）会残留 paused=true，导致新页面/返回后的图片请求全部
        // 挂起在 PauseInterceptor、只显示占位图，表现为"海报变成占位图且不滚动不恢复"
        ImageLoaderFactory.setPaused(false)
    }

    override fun onPause() {
        super.onPause()
        // 后台时取消延迟校正任务，避免页面不可见期间触发换算
        window.decorView.removeCallbacks(refreshAutoSizeRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) binding.unbind()
    }
}

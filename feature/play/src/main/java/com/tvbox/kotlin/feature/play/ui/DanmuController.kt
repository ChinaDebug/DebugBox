package com.tvbox.kotlin.feature.play.ui

import android.os.SystemClock
import android.view.View
import android.widget.Toast
import com.tvbox.kotlin.core.common.util.HawkUtils
import com.tvbox.kotlin.core.ui.R as uiR
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import master.flame.danmaku.danmaku.model.android.DanmakuContext

/**
 * 弹幕加载与管理控制器，屏蔽 PlayFragment/PlayActivity 的宿主差异。
 *
 * 流程：
 * - [checkDanmu]：注入弹幕数据，更新按钮与视图可见性，开关开启且正在播放时主动加载；
 * - [loadDanmuWhenPlaying]：播放状态回调触发加载；
 * - [setDanmuViewSettings]：设置参数应用到共享 [danmakuContext]（单例，
 *   速度/大小/行数/透明度即时生效）；reload=true 时执行加载任务，reload=false 时仅显示并同步进度。
 *
 * 差异收敛点：Toast 用 [PlayHost.context]，协程用 [PlayHost.scope]，
 * 视图用 [PlayHost.danmakuView]，控制栏用 [PlayHost.controller]，
 * 播放器用 [PlayHost.videoView]，数据用 [PlayHost.viewModel]。
 */
class DanmuController(private val host: PlayHost) {

    /** 弹幕是否已加载（避免播放回调/开关回调重复加载） */
    var danmuLoaded = false

    /** 当前弹幕源（注入/开始加载时记录）：checkDanmu 幂等判定依据，源未变化时不打断/不重复拉取 */
    private var currentDanmuSource = ""

    private var danmuLoadJob: Job? = null

    /** 弹幕上下文（创建一次，设置变更对已加载弹幕即时生效） */
    private val danmakuContext: DanmakuContext by lazy { DanmakuContext.create() }

    /**
     * 加载代际：每次强制重载递增。旧任务阻塞在 IO 中无法被协程取消，
     * 结束后凭代际判断自己已被取代，直接丢弃结果（不提示、不缓存、不操作视图），
     * 避免被取消的旧任务失败时弹出"加载失败"而新任务已成功显示。
     */
    private var danmuLoadGeneration = 0L

    /**
     * 加载中收到的重载请求（如加载途中用户改弹幕速度）：登记而非立即取消重发，
     * 等当前任务结束（内容已写入缓存，重载走缓存解析）再执行，避免源 jar 被重复调用。
     */
    private var pendingReload = false

    /** 待重载请求的提示口径（沿用请求方的 showToast） */
    private var pendingReloadShowToast = true

    /** 最近一次失败提示的弹幕源与时间：同源短时间内的重复失败不再提示 */
    private var lastFailureSource = ""
    private var lastFailureToastAt = 0L

    /**
     * 强制取消当前弹幕加载（换集/退出界面时调用）：
     * 立即取消加载任务、隐藏弹幕视图并重置加载状态。
     * 阻塞在 HTTP 网络中的旧任务无法被协程取消即时中断，
     * 其结束后凭代际校验（isLatest）自动丢弃结果，不操作视图。
     */
    fun cancelLoad() {
        danmuLoadJob?.cancel()
        danmuLoadJob = null
        danmuLoaded = false
        currentDanmuSource = ""
        pendingReload = false
        danmuLoadGeneration++
        host.danmakuView?.let { view ->
            runCatching { view.hide() }
            view.visibility = View.GONE
        }
    }

    /** 注入弹幕数据后检查：更新按钮与视图可见性，弹幕加载由播放状态回调触发。
     * 幂等基准取 ViewModel 内 resolve 后的最终源（宿主传入的原始串可能因端口占位替换而与之不同，
     * 会造成同源被误判为新源重复加载）；同源重复注入时不打断已加载/加载中的弹幕，
     * 避免同源被重复 fetch（每次真实 fetch 都会触发源 jar 的"加载成功"提示）。 */
    fun checkDanmu() {
        val view = host.danmakuView ?: return
        val source = host.viewModel.getDanmu()
        host.controller.setHasDanmu(source.isNotEmpty())
        if (source == currentDanmuSource) {
            // 同源且已渲染：保持现状
            if (danmuLoaded) return
            // 同源但尚未加载（首次注入/直链播放入口）：播放中且开关开启时补一次加载
            if (source.isNotEmpty() && HawkUtils.getDanmuOpen() &&
                host.viewModel.playState.value == PlayState.PLAYING
            ) {
                loadDanmuWhenPlaying(showToast = false)
            }
            return
        }
        // 新弹幕源：完整重置旧加载与渲染后等待播放回调加载
        currentDanmuSource = source
        danmuLoaded = false
        view.hide()
        view.visibility = View.GONE
        danmuLoadJob?.cancel()
        danmuLoadJob = null
        danmuLoadGeneration++
        if (source.isNotEmpty()) {
            runCatching { view.release() }
        }
        // 兜底：弹幕数据注入时视频已处于播放状态（如直链播放/弹幕晚于 onPlaying 注入），
        // 立即加载，避免漏加载；用应用层播放状态严格判定（onError 会同步为 ERROR），
        // 避免 videoView.playState 在错误被 Surface 切换抑制/状态回调滞后时残留 PLAYING 而误加载
        if (source.isNotEmpty() && HawkUtils.getDanmuOpen() &&
            host.viewModel.playState.value == PlayState.PLAYING
        ) {
            loadDanmuWhenPlaying(showToast = false)
        }
    }

    /** 视频正常播放后加载弹幕（playing 状态回调与开关打开时调用）。
     * 弹幕完全由开关驱动：关闭时不下发任何弹幕任务（不下载不装载，
     * 避免调 jar 获取弹幕内容触发源 jar 的"弹幕加载成功"提示），开启时才下载+解析+渲染。 */
    fun loadDanmuWhenPlaying(showToast: Boolean = true) {
        // 开关关闭：完全跳过弹幕，不预下载
        if (!HawkUtils.getDanmuOpen()) return
        if (!danmuLoaded && host.danmakuView != null && host.viewModel.getDanmu().isNotEmpty()) {
            danmuLoaded = true
            setDanmuViewSettings(reload = true, showToast = showToast)
        }
    }

    /**
     * 应用弹幕设置并可选择重载：
     * - 设置参数始终应用到共享 [danmakuContext]；
     * - 大小/行数/透明度渲染期生效即时可见，速度在 prepare 时固化需重载；
     * - reload=true 时执行加载任务（含"请稍等"提示）；
     * - reload=false 时仅显示并同步到当前播放进度。
     */
    private fun setDanmuViewSettings(reload: Boolean, showToast: Boolean = true) {
        DanmuLoader.applyDanmakuSettings(danmakuContext)
        if (reload) {
            // 失败提示仅跟随用户主动操作（开启弹幕/修改设置），自动加载失败保持静默：
            // 弹幕服务异步生成期间拉取常见空响应/超时，与源端“加载成功”提示同时出现会互相矛盾
            loadDanmuIfNeeded(showToast = showToast, notifyFailure = showToast)
        } else {
            val view = host.danmakuView ?: return
            if (HawkUtils.getDanmuOpen()) {
                view.show()
                view.seekTo(host.videoView.currentPosition)
            }
        }
    }

    /** 异步加载弹幕，成功后注入到 DanmakuView。
     *  @param notifyFailure 拉取失败时是否提示；自动加载为 false，仅用户主动操作提示 */
    private fun loadDanmuIfNeeded(showToast: Boolean = true, notifyFailure: Boolean = false) {
        val view = host.danmakuView ?: return
        // 登记本次加载的弹幕源：播放回调自动加载后，checkDanmu 才能按同源跳过，
        // 否则会被误判为新源而 release+prepare 重来一遍（表现为弹幕加载两次）
        currentDanmuSource = host.viewModel.getDanmu()
        // 优先使用已缓存的弹幕内容（首次下载成功后缓存，
        // 后续开关/设置重载直接解析缓存内容，不重复网络请求，避免"加载成功又失败"）
        val cached = host.viewModel.getCachedDanmuContent()
        val danmu = if (!cached.isNullOrEmpty()) cached else host.viewModel.getDanmu()
        if (danmu.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        // 旧任务仍在进行：取消后立即重发会让源 jar 被重复调用（重复抓取与重复提示），
        // 登记待重载，等旧任务结束（内容已入缓存，重载走缓存解析）再执行
        val running = danmuLoadJob
        if (running != null && running.isActive) {
            pendingReload = true
            pendingReloadShowToast = showToast
            return
        }
        danmuLoadJob?.cancel()
        danmuLoadJob = null
        danmuLoadGeneration++
        val generation = danmuLoadGeneration
        // 本方法仅在弹幕开关开启时被调用（loadDanmuWhenPlaying 已按开关过滤），
        // 显示视图等待加载，不遮挡视频
        view.visibility = View.VISIBLE
        if (showToast) {
            Toast.makeText(host.context, host.context.getString(uiR.string.danmu_loading_hint), Toast.LENGTH_SHORT).show()
        }
        danmuLoadJob = host.scope.launch {
            try {
                val result = DanmuLoader.loadInto(
                    danmu,
                    view,
                    context = danmakuContext,
                    startPosition = { host.videoView.danmuStartPosition() },
                    videoPlaying = { host.videoView.isPlaying },
                    // 每帧弹幕时钟校准源：线程安全的播放位置推算（倍速同步），
                    // 不直接访问内核，避免弹幕线程触发 Media3 线程校验
                    clockPosition = { host.videoView.danmuClockPosition() },
                    // 无缓存内容时才走网络拉取；已缓存直接解析内容
                    remoteFetcher = if (cached.isNullOrEmpty()) host.viewModel::fetchDanmu else null,
                    onContent = { content ->
                        if (cached.isNullOrEmpty()) host.viewModel.cacheDanmuContent(content)
                    },
                    isLatest = { generation == danmuLoadGeneration }
                )

                if (generation != danmuLoadGeneration) return@launch
                when (result) {
                    // 失败时隐藏弹幕视图：避免加载失败后残留空白视图/旧帧（详情页预览窗切集场景）
                    is DanmuLoader.DanmuLoadResult.InvalidPort -> {
                        view.hide()
                        view.visibility = View.GONE
                        // 复位加载标记：播放状态再次变化时允许重新尝试，避免一次失败后不再加载
                        danmuLoaded = false
                        if (notifyFailure) showFailureToast(uiR.string.danmu_init_failed, Toast.LENGTH_LONG)
                    }
                    is DanmuLoader.DanmuLoadResult.Failed -> {
                        view.hide()
                        view.visibility = View.GONE
                        danmuLoaded = false
                        if (notifyFailure) showFailureToast(uiR.string.danmu_load_failed, Toast.LENGTH_SHORT)
                    }
                    else -> Unit
                }
            } catch (_: Throwable) {
            } finally {
                // 仅处理当前任务，避免被取消的旧任务影响新任务
                if (danmuLoadJob === coroutineContext[Job]) {
                    // 加载中登记的重载请求：当前任务已结束（内容已入缓存），
                    // 此刻重载走缓存解析、不再请求网络，避免重复调用源 jar
                    if (pendingReload) {
                        pendingReload = false
                        val toast = pendingReloadShowToast
                        host.postOnUi {
                            if (HawkUtils.getDanmuOpen()) {
                                loadDanmuIfNeeded(showToast = toast)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 弹幕失败提示：同一弹幕源在阈值内只提示一次。
     * 换集/设置重载/自动重试会多次触发失败，逐次提示会连续弹出“加载失败”。
     */
    private fun showFailureToast(resId: Int, duration: Int) {
        if (!HawkUtils.getDanmuOpen()) return
        val source = currentDanmuSource
        val now = SystemClock.elapsedRealtime()
        if (source == lastFailureSource && now - lastFailureToastAt < FAILURE_TOAST_INTERVAL_MS) return
        lastFailureSource = source
        lastFailureToastAt = now
        Toast.makeText(host.context, host.context.getString(resId), duration).show()
    }

    /** 打开弹幕设置弹窗（原 onDanmuClick 内容） */
    fun showDanmuSettingDialog() {
        DanmuSettingDialog(host.context) { open, needReload -> handleDanmuSetting(open, needReload) }.show()
    }

    /**
     * 弹幕开关/设置变更处理：
     * - 开启且已加载：needReload=true 重载，needReload=false 仅应用设置并同步进度；
     * - 开启且未加载：立即加载；
     * - 关闭：隐藏视图、取消进行中的加载并重置加载状态，无任何提示。
     */
    fun handleDanmuSetting(open: Boolean, needReload: Boolean) {
        val view = host.danmakuView ?: return@handleDanmuSetting
        if (open) {
            view.visibility = View.VISIBLE
            if (danmuLoaded) {
                // 设置变更（颜色/速度等）重载：弹幕已加载渲染，不弹"加载需要时间"提示
                setDanmuViewSettings(reload = needReload, showToast = false)
            } else {
                // 开关从关到开首次加载：弹"加载需要时间"提示
                loadDanmuWhenPlaying()
            }
        } else {
            // 关闭状态：隐藏视图、取消进行中的加载并重置加载状态（含已登记的重载），无任何提示
            view.hide()
            view.visibility = View.GONE
            danmuLoadJob?.cancel()
            danmuLoadJob = null
            danmuLoaded = false
            pendingReload = false
        }
    }

    private companion object {
        /** 同一弹幕源失败提示的最小间隔（毫秒） */
        const val FAILURE_TOAST_INTERVAL_MS = 5_000L
    }
}

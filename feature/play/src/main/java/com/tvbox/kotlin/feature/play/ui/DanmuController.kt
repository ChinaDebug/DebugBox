package com.tvbox.kotlin.feature.play.ui

import android.os.SystemClock
import android.view.View
import android.widget.Toast
import com.tvbox.kotlin.core.common.util.HawkUtils
import com.tvbox.kotlin.core.ui.dialog.DanmuSettingDialog
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

    /** 在线弹幕覆盖前的源弹幕（首次覆盖时备份，用于"使用源弹幕"恢复） */
    private var sourceDanmuBackup: String? = null

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
        sourceDanmuBackup = null
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

    /** 视频正常播放后加载弹幕。弹幕完全由开关驱动：关闭时不下发任何任务，不预下载。 */
    fun loadDanmuWhenPlaying(showToast: Boolean = true) {
        if (!HawkUtils.getDanmuOpen()) return
        if (!danmuLoaded && host.danmakuView != null && host.viewModel.getDanmu().isNotEmpty()) {
            danmuLoaded = true
            setDanmuViewSettings(reload = true, showToast = showToast)
        }
    }

    /**
     * 应用弹幕设置并可选重载。
     * 大小/行数/透明度渲染期生效，速度在 prepare 时固化需重载。
     */
    private fun setDanmuViewSettings(reload: Boolean, showToast: Boolean = true) {
        DanmuLoader.applyDanmakuSettings(danmakuContext)
        if (reload) {
            // 失败提示仅跟随用户主动操作；自动加载失败保持静默，避免与源端提示矛盾
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
        // 登记本次加载的弹幕源，供 checkDanmu 按同源跳过，避免重复 release+prepare
        currentDanmuSource = host.viewModel.getDanmu()
        // 优先解析缓存内容，避免重复网络请求
        val cached = host.viewModel.getCachedDanmuContent()
        val danmu = if (!cached.isNullOrEmpty()) cached else host.viewModel.getDanmu()
        if (danmu.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        // 旧任务仍活跃时登记待重载，等其结束再执行；递增代际使旧任务结果失效，
        // 避免旧源内容写入缓存后重载仍显示旧弹幕
        val running = danmuLoadJob
        if (running != null && running.isActive) {
            danmuLoadGeneration++
            pendingReload = true
            pendingReloadShowToast = showToast
            return
        }
        danmuLoadJob?.cancel()
        danmuLoadJob = null
        danmuLoadGeneration++
        val generation = danmuLoadGeneration
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
                        // 仅当前代际可写入缓存，防止被取代的旧任务污染新弹幕源
                        if (cached.isNullOrEmpty() && generation == danmuLoadGeneration) {
                            host.viewModel.cacheDanmuContent(content)
                        }
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

    /** 打开弹幕设置弹窗；在线搜索使用内置弹幕库，不依赖源 jar */
    fun showDanmuSettingDialog() {
        DanmuSettingDialog(
            host.context,
            { open, needReload -> handleDanmuSetting(open, needReload) }
        ) { openOnlineDanmuSearch() }.show()
    }

    /** 内置在线弹幕搜索：以剧名 + 当前集数作为关键字，选择后直接加载 */
    private fun openOnlineDanmuSearch() {
        DanmuSearchDialog(
            host.context,
            host.viewModel,
            buildDanmuKeyword(),
            onSelected = { content -> applyOnlineDanmu(content) },
            onRestoreSource = { restoreSourceDanmu() }
        ).show()
    }

    /** 组合初始搜索词：剧名沿用标题，集数取自当前集名中的数字，便于直接命中该集弹幕 */
    private fun buildDanmuKeyword(): String {
        val title = host.viewModel.title.value.ifEmpty {
            host.viewModel.vodInfo.value?.name.orEmpty()
        }.trim()
        val episode = host.viewModel.currentEpisode.value?.name.orEmpty()
        val number = Regex("\\d+").find(episode)?.value
        return if (title.isNotEmpty() && number != null && !title.contains(number)) {
            "$title $number"
        } else {
            title
        }
    }

    /** 应用在线选择的弹幕内容：写入弹幕源并强制重新加载 */
    private fun applyOnlineDanmu(content: String) {
        if (content.isEmpty()) return
        // 首次被在线弹幕覆盖前备份源弹幕，便于随时恢复
        if (sourceDanmuBackup == null) sourceDanmuBackup = host.viewModel.getDanmu()
        host.viewModel.setDanmu(content)
        currentDanmuSource = content
        danmuLoaded = false
        HawkUtils.setDanmuOpen(true)
        host.controller.setHasDanmu(true)
        host.danmakuView?.visibility = View.VISIBLE
        loadDanmuIfNeeded(showToast = true, notifyFailure = true)
    }

    /** 恢复源弹幕：撤销在线弹幕覆盖，回到源 jar 提供的弹幕 */
    private fun restoreSourceDanmu() {
        val backup = sourceDanmuBackup ?: return
        sourceDanmuBackup = null
        host.viewModel.setDanmu(backup)
        currentDanmuSource = ""
        danmuLoaded = false
        host.controller.setHasDanmu(backup.isNotEmpty())
        if (backup.isEmpty()) {
            host.danmakuView?.let {
                it.hide()
                it.visibility = View.GONE
            }
            Toast.makeText(
                host.context,
                host.context.getString(uiR.string.danmu_no_source),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        host.danmakuView?.visibility = View.VISIBLE
        loadDanmuIfNeeded(showToast = true, notifyFailure = true)
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
